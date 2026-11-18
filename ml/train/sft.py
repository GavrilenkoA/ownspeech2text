#!/usr/bin/env python3
"""Supervised fine-tuning for Murmur post-processing models: LoRA, DoRA or full fine-tuning behind
one `method:` switch, on shared-contract JSONL rendered with prompts/render.py at load time.

  python -m ml.train.sft --config ml/train/configs/q08-lora.yaml [--set train.max_steps=5 ...]

Writes OUTPUT_DIR/{checkpoint-*/, final/, data_stats.json, train_info.json}. Re-running the same
command resumes from the newest checkpoint (train.resume: true). final/ is either a PEFT adapter
(lora/dora; merge with ml.train.merge) or a full HF model in bf16, with tokenizer and the
official chat template, ready for ml.train.export.
"""
from __future__ import annotations

import argparse
import json
import math
import os
import platform
import shutil
import time
from pathlib import Path

import torch
from torch.utils.data import Dataset

from ml.eval import contract
from ml.train import config as cfglib
from ml.train.render_data import IGNORE, PromptMix, build

COPY_FILES = ("chat_template.jinja", "LICENSE", "NOTICE")


class FeatDataset(Dataset):
    def __init__(self, feats):
        self.f = feats

    def __len__(self):
        return len(self.f)

    def __getitem__(self, i):
        return {"input_ids": self.f[i]["input_ids"], "labels": self.f[i]["labels"]}


class Collator:
    """Right padding: safe for the causal recurrent layers (Gated DeltaNet, short conv) because
    padding comes after every real token."""

    def __init__(self, pad_id: int, multiple_of: int = 8):
        self.pad, self.mult = pad_id, multiple_of

    def __call__(self, batch):
        n = max(len(b["input_ids"]) for b in batch)
        n = int(math.ceil(n / self.mult) * self.mult)
        ids = torch.full((len(batch), n), self.pad, dtype=torch.long)
        lab = torch.full((len(batch), n), IGNORE, dtype=torch.long)
        att = torch.zeros((len(batch), n), dtype=torch.long)
        for i, b in enumerate(batch):
            k = len(b["input_ids"])
            ids[i, :k] = torch.tensor(b["input_ids"])
            lab[i, :k] = torch.tensor(b["labels"])
            att[i, :k] = 1
        return {"input_ids": ids, "labels": lab, "attention_mask": att}


def completion_loss(model, inputs: dict, num_items_in_batch=None):
    """Causal-LM loss computed only where it is needed: logits are projected for the positions whose
    NEXT token is supervised (boolean `logits_to_keep` mask, supported by transformers' Qwen3.5 and LFM3
    heads), not for the whole sequence. Same value as the stock shifted cross-entropy (test), but the
    logits tensor shrinks from batch x seq_len x vocab to completion_tokens x vocab: for Qwen3.5
    (248K vocab) at batch 16 x ~1.2K tokens that is ~19 GB in fp32 vs ~1 GB, the difference between
    an OOM and a fit on a 32 GB card.
    Returns (loss, outputs, normalised_by_num_items)."""
    labels = inputs["labels"]
    rest = {k: v for k, v in inputs.items() if k != "labels"}
    keep = torch.zeros_like(labels, dtype=torch.bool)
    keep[:, :-1] = labels[:, 1:] != IGNORE
    out = model(**rest, logits_to_keep=keep)
    logits = out.logits.reshape(-1, out.logits.shape[-1]).float()
    target = labels[:, 1:][keep[:, :-1]]
    loss = torch.nn.functional.cross_entropy(logits, target, reduction="sum")
    if num_items_in_batch is not None:
        return loss / num_items_in_batch, out, True
    return loss / max(1, target.numel()), out, False


def detect_template(base: str) -> str:
    from transformers import AutoConfig
    mt = AutoConfig.from_pretrained(base).model_type
    return contract.MODEL_TYPE_TO_TEMPLATE[mt]


def precision(cfg: dict) -> tuple[torch.dtype, bool]:
    """(dtype to load weights in, use bf16 autocast). Full FT keeps fp32 master weights: bf16
    weights cannot represent 1e-5-sized updates (8-bit mantissa)."""
    p = cfg["train"].get("precision", "auto")
    cuda = torch.cuda.is_available()
    if p == "auto":
        if not cuda:
            return torch.float32, False
        p = "bf16-mixed" if cfg["method"] == "full" else "bf16-base"
    return {"fp32": (torch.float32, False), "bf16-mixed": (torch.float32, True),
            "bf16-base": (torch.bfloat16, True), "bf16-pure": (torch.bfloat16, True)}[p]


def load_model(cfg: dict):
    from transformers import AutoModelForCausalLM
    dtype, _ = precision(cfg)
    kw = {"dtype": dtype}
    attn = cfg["train"].get("attn_implementation")
    if attn:
        kw["attn_implementation"] = attn
    model = AutoModelForCausalLM.from_pretrained(cfg["base_model"], **kw)
    model.config.use_cache = False
    method = cfg["method"]
    if method in ("lora", "dora"):
        from peft import LoraConfig, get_peft_model
        lc = cfg.get("lora", {})
        tm = lc.get("target_modules", "all-linear")
        peft_cfg = LoraConfig(r=int(lc.get("r", 64)), lora_alpha=int(lc.get("alpha", 128)),
                              lora_dropout=float(lc.get("dropout", 0.0)), target_modules=tm,
                              use_dora=(method == "dora"), use_rslora=bool(lc.get("rslora", False)),
                              modules_to_save=lc.get("modules_to_save"), task_type="CAUSAL_LM")
        model = get_peft_model(model, peft_cfg)
    elif method == "full":
        if cfg["train"].get("freeze_embeddings"):
            model.get_input_embeddings().weight.requires_grad_(False)
    else:
        raise ValueError(f"method must be lora|dora|full, got {method!r}")
    n_all = sum(p.numel() for p in model.parameters())
    n_tr = sum(p.numel() for p in model.parameters() if p.requires_grad)
    return model, {"params_total": n_all, "params_trainable": n_tr}


def last_checkpoint(out: Path) -> str | None:
    cks = sorted(out.glob("checkpoint-*"), key=lambda p: int(p.name.split("-")[-1]))
    for ck in reversed(cks):
        if (ck / "trainer_state.json").exists():
            return str(ck)
    return None


def save_final(model, tok, cfg: dict, out: Path) -> Path:
    from huggingface_hub import snapshot_download
    final = out / "final"
    if final.exists():
        shutil.rmtree(final)
    final.mkdir(parents=True)
    if cfg["method"] in ("lora", "dora"):
        model.save_pretrained(final / "adapter")
    else:
        model.to(torch.bfloat16).save_pretrained(final, safe_serialization=True)
    tok.save_pretrained(final)
    base = Path(cfg["base_model"])
    src = base if base.is_dir() else Path(snapshot_download(cfg["base_model"], allow_patterns=list(COPY_FILES)))
    for name in COPY_FILES:  # official template wins over whatever the tokenizer serialised
        if (src / name).exists():
            shutil.copy2(src / name, final / name)
    return final


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--config", required=True)
    ap.add_argument("--set", nargs="*", default=[], help="dotted overrides, e.g. train.max_steps=5")
    ap.add_argument("--prepare-only", action="store_true", help="render+tokenise data, write stats, exit")
    a = ap.parse_args(argv)
    cfg = cfglib.resolve(a.config, a.set)
    from transformers import AutoTokenizer, Trainer, TrainingArguments, set_seed
    from transformers.trainer_pt_utils import LengthGroupedSampler

    out = Path(cfg["output_dir"])
    out.mkdir(parents=True, exist_ok=True)
    tr = cfg["train"]
    set_seed(int(tr.get("seed", 42)))
    if tr.get("threads"):
        torch.set_num_threads(int(tr["threads"]))
    tok = AutoTokenizer.from_pretrained(cfg["base_model"])
    template = cfg.get("template") or detect_template(cfg["base_model"])
    mix = PromptMix(**cfg.get("prompt", {}))
    t0 = time.time()
    train_f, tinfo = build(cfg["data"]["train"], "train", tok, template, mix, tr.get("max_len", "auto"),
                           cfg["data"].get("upsample"))
    max_len = tinfo["max_len"]  # resolved on the training split; dev uses the same limit
    dev_f, dinfo = (build(cfg["data"]["dev"], "dev", tok, template, mix, max_len) if cfg["data"].get("dev")
                    else ([], {}))
    stats = {"template": template, "train": tinfo, "dev": dinfo, "render_s": round(time.time() - t0, 1)}
    (out / "data_stats.json").write_text(json.dumps(stats, indent=1, ensure_ascii=False))
    (out / "config.resolved.json").write_text(json.dumps(cfg, indent=1, ensure_ascii=False))
    print(json.dumps(stats, ensure_ascii=False))
    if a.prepare_only:
        return 0
    if not train_f:
        raise SystemExit("no training examples after filtering")

    model, pinfo = load_model(cfg)
    print(json.dumps(pinfo))
    _, autocast_bf16 = precision(cfg)
    bs, ga = int(tr.get("batch_size", 8)), int(tr.get("grad_accum", 1))
    args = TrainingArguments(
        output_dir=str(out), per_device_train_batch_size=bs, per_device_eval_batch_size=bs,
        gradient_accumulation_steps=ga, learning_rate=float(tr["lr"]),
        num_train_epochs=float(tr.get("epochs", 1)), max_steps=int(tr.get("max_steps", -1)),
        lr_scheduler_type=tr.get("scheduler", "cosine"), warmup_steps=float(tr.get("warmup", 0.03)),
        weight_decay=float(tr.get("weight_decay", 0.0)), adam_beta2=float(tr.get("adam_beta2", 0.95)),
        max_grad_norm=float(tr.get("max_grad_norm", 1.0)), optim=tr.get("optim", "adamw_torch"),
        bf16=autocast_bf16, gradient_checkpointing=bool(tr.get("gradient_checkpointing", False)),
        gradient_checkpointing_kwargs={"use_reentrant": False},
        logging_steps=int(tr.get("logging_steps", 10)), save_strategy="steps",
        save_steps=int(tr.get("save_steps", 500)), save_total_limit=int(tr.get("save_total_limit", 2)),
        eval_strategy="steps" if dev_f else "no", eval_steps=int(tr.get("eval_steps", 500)) if dev_f else None,
        report_to=tr.get("report_to", "none"), seed=int(tr.get("seed", 42)), remove_unused_columns=False,
        dataloader_num_workers=int(tr.get("workers", 0)), use_cpu=not torch.cuda.is_available(),
        torch_compile=bool(tr.get("torch_compile", False)), save_only_model=bool(tr.get("save_only_model", False)),
        use_liger_kernel=bool(tr.get("liger", False)), prediction_loss_only=True)
    lengths = [len(f["input_ids"]) for f in train_f]

    class MurmurTrainer(Trainer):
        def compute_loss(self, model, inputs, return_outputs=False, num_items_in_batch=None):
            loss, out, scaled = completion_loss(model, inputs, num_items_in_batch)
            self.loss_is_scaled_for_ga = scaled  # read by training_step right after this call
            return (loss, out) if return_outputs else loss

        def _get_train_sampler(self, train_dataset=None):
            g = torch.Generator()
            g.manual_seed(int(tr.get("seed", 42)))
            return LengthGroupedSampler(bs * ga, lengths=lengths, generator=g)

    pad = tok.pad_token_id if tok.pad_token_id is not None else tok.convert_tokens_to_ids(contract.TEMPLATES[template])
    trainer = MurmurTrainer(model=model, args=args, data_collator=Collator(pad), train_dataset=FeatDataset(train_f),
                            eval_dataset=FeatDataset(dev_f) if dev_f else None)
    resume = last_checkpoint(out) if tr.get("resume", True) else None
    if resume:
        print(f"resuming from {resume}")
    if torch.cuda.is_available():
        torch.cuda.reset_peak_memory_stats()
    t1 = time.time()
    res = trainer.train(resume_from_checkpoint=resume)
    train_s = time.time() - t1
    final = save_final(trainer.model, tok, cfg, out)
    steps = trainer.state.global_step
    seqs = steps * bs * ga * max(1, args.world_size)
    mean_len = sum(lengths) / len(lengths)
    info = {
        "run_name": cfg.get("run_name"), "method": cfg["method"], "base_model": cfg["base_model"], "template": template,
        **pinfo, "steps": steps, "train_runtime_s": round(train_s, 1), "train_loss": res.training_loss,
        "approx_tokens_per_s": round(seqs * mean_len / train_s, 1) if train_s else None,
        "eval_loss": next((h["eval_loss"] for h in reversed(trainer.state.log_history) if "eval_loss" in h), None),
        "peak_cuda_mem_gb": round(torch.cuda.max_memory_allocated() / 1e9, 2) if torch.cuda.is_available() else None,
        "device": torch.cuda.get_device_name(0) if torch.cuda.is_available() else platform.processor() or "cpu",
        "versions": {m: __import__(m).__version__ for m in ("torch", "transformers", "peft", "accelerate")},
        "final_dir": str(final), "resumed_from": resume, "data": stats,
    }
    (out / "train_info.json").write_text(json.dumps(info, indent=1, ensure_ascii=False))
    # Trainer always writes a checkpoint at the last step (a full-FT one with optimizer state is
    # 8.5 GB measured for Qwen3.5-0.8B, ~25 GB for LFM3-1.7B [A]). Once final/ and
    # train_info.json exist the run is complete and resume is never needed again: drop them.
    if not tr.get("keep_checkpoints", False):
        for ck in out.glob("checkpoint-*"):
            shutil.rmtree(ck, ignore_errors=True)
    print(json.dumps({k: v for k, v in info.items() if k != "data"}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    os.environ.setdefault("TOKENIZERS_PARALLELISM", "false")
    raise SystemExit(main())
