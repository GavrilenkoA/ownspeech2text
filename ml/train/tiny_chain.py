#!/usr/bin/env python3
"""Plumbing check of the whole chain for a model family WITHOUT its real weights: build a tiny
randomly initialised model from the family's real config + tokenizer, then train (any method) ->
merge -> GGUF -> Q4_0 -> llama-server eval. Proves the code path (module names, PEFT targets, saving,
conversion, llama.cpp loading, template) for LFM3 / Qwen3.5-2B on a CPU in minutes. Output quality
is meaningless (random weights).

  python -m ml.train.tiny_chain --family lfm3 --tok-dir .cache/ml/train/models/LFM3-1.7B-tok \
      --method dora --work .cache/ml/train/tiny
"""
from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
import time
from pathlib import Path

from ml.eval import contract

PY = sys.executable


def tiny_model(family: str, tok_dir: Path, out: Path) -> Path:
    import torch
    from transformers import AutoConfig, AutoModelForCausalLM, AutoTokenizer
    torch.manual_seed(0)
    cfg = AutoConfig.from_pretrained(tok_dir)
    if family == "qwen3.5":
        cfg = cfg.text_config
        cfg.num_hidden_layers, cfg.layer_types = 4, cfg.layer_types[:4]
        cfg.hidden_size, cfg.intermediate_size = 256, 512
        cfg.num_attention_heads, cfg.num_key_value_heads, cfg.head_dim = 4, 2, 64
        cfg.linear_num_key_heads, cfg.linear_num_value_heads = 4, 4
        cfg.linear_key_head_dim = cfg.linear_value_head_dim = 64
        cfg.mtp_num_hidden_layers = 0
    else:
        cfg.num_hidden_layers = 4
        cfg.layer_types = ["conv", "conv", "full_attention", "sliding_attention"]
        cfg.hidden_size, cfg.intermediate_size = 256, 512
        cfg.num_attention_heads, cfg.num_key_value_heads, cfg.head_dim = 4, 2, 64
    m = AutoModelForCausalLM.from_config(cfg)
    if out.exists():
        shutil.rmtree(out)
    m.save_pretrained(out)
    AutoTokenizer.from_pretrained(tok_dir).save_pretrained(out)
    for f in ("chat_template.jinja", "LICENSE"):
        if (tok_dir / f).exists():
            shutil.copy2(tok_dir / f, out / f)
    return out


def sh(cmd: list[str], log: Path) -> float:
    t = time.time()
    with open(log, "a") as f:
        f.write("$ " + " ".join(cmd) + "\n")
        f.flush()
        r = subprocess.run(cmd, stdout=f, stderr=subprocess.STDOUT)
    if r.returncode:
        raise SystemExit(f"failed: {' '.join(cmd)} (see {log})")
    return round(time.time() - t, 1)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--family", choices=["qwen3.5", "lfm3"], required=True)
    ap.add_argument("--tok-dir", required=True, help="dir with the real config.json + tokenizer files")
    ap.add_argument("--method", default="lora", choices=["lora", "dora", "full"])
    ap.add_argument("--work", default=str(contract.REPO / ".cache/ml/train/tiny"))
    ap.add_argument("--data", default=str(contract.REPO / ".cache/ml/train/data/smoke.train.jsonl"))
    ap.add_argument("--dev", default=str(contract.REPO / ".cache/ml/train/data/smoke.dev.jsonl"))
    ap.add_argument("--test", default=str(contract.REPO / ".cache/ml/train/data/smoke.test.jsonl"))
    a = ap.parse_args(argv)
    work = Path(a.work) / f"{a.family}-{a.method}"
    work.mkdir(parents=True, exist_ok=True)
    log = work / "chain.log"
    log.write_text("")
    times = {}
    t = time.time()
    base = tiny_model(a.family, Path(a.tok_dir), work / "base")
    times["build_tiny"] = round(time.time() - t, 1)
    run = work / "run"
    if run.exists():
        shutil.rmtree(run)
    cfg = contract.REPO / "ml/train/configs/dryrun-cpu.yaml"
    times["train"] = sh([PY, "-m", "ml.train.sft", "--config", str(cfg), "--set", f"base_model={base}",
                         f"method={a.method}", f"output_dir={run}", "train.max_steps=2", "train.eval_steps=2",
                         "train.save_steps=2", f"data.train=[{{path: {a.data}}}]", f"data.dev=[{{path: {a.dev}}}]",
                         "lora.r=8", "lora.alpha=16"], log)
    times["merge"] = sh([PY, "-m", "ml.train.merge", "--run", str(run)], log)
    name = f"tiny-{a.family}-{a.method}"
    times["export"] = sh([PY, "-m", "ml.train.export", "--run", str(run), "--name", name, "--keep-bf16"], log)
    for q in ("BF16", "Q4_0"):
        times[f"eval_{q}"] = sh([PY, "-m", "ml.eval.run_eval", "--model", str(run / "gguf" / f"{name}-{q}.gguf"),
                                 "--data", a.test, "--limit", "4", "--out", str(work / f"eval-{q}")], log)
    rep = json.loads((work / "eval-Q4_0/report.json").read_text())
    man = json.loads((run / "gguf/manifest.json").read_text())
    res = {"family": a.family, "method": a.method, "times_s": times, "template_detected": rep["info"]["template"],
           "files": [(f["path"], f["bytes"]) for f in man["files"]], "eval_n": rep["info"]["n"],
           "train_info": {k: json.loads((run / "train_info.json").read_text())[k]
                          for k in ("params_total", "params_trainable", "steps", "train_loss", "eval_loss")}}
    (work / "result.json").write_text(json.dumps(res, indent=1))
    print(json.dumps(res, indent=1))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
