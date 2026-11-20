#!/usr/bin/env python3
"""Run a model over a shared-contract JSONL and write predictions, per-example scores, a summary
(per language and category) and the ship-gate report.

  python -m ml.eval.run_eval --model X.gguf --data set.jsonl --out DIR [--system finetuned|default|default.v2]
         [--template auto|qwen3.5|lfm3] [--backend auto|llama|hf|teacher] [--split test] [--limit N] [--judge]
  python -m ml.eval.run_eval --rescore DIR [--data ...] [--data-root LOCAL_DATA_DIR]

--system takes a system prompt name or a prompt profile (prompts/profiles/<name>.json, e.g. the
shipping default with few-shot turns). Outputs in DIR: predictions.jsonl (resumable: finished ids
are skipped on re-run), scores.jsonl, report.json, report.md. --rescore recomputes scores and
report from existing predictions (and DIR/judge.jsonl if present) without loading any model: used
on the dev server after syncing pod results and running `ml.eval.judge`. Exit code 0 even when
gates fail (the verdict is in report.json).

Every report carries two measurements of the same predictions: `summary`/`gates` on the raw model
output (what select.py ranks), and `guarded` = the same after prompts/guard.py (precheck skip ->
empty, post-LLM repair/fallback), i.e. what the app inserts. The guard is deterministic, so it is
recomputed on --rescore; scores_guarded.jsonl holds the per-example rows.
Both carry `pass` (prompts/eval/score.py, the prompt-eval scorer) and a per-file breakdown
(`by_set`: dev.v2, test.v2, the dataset's dev/test, ...); `baseline` compares the prompt eval sets
with the stock models' numbers (ml/eval/baselines/, docs/ml/PROMPT_EVAL.md), raw vs raw and guarded
vs guarded (ml/eval/scoring.py, ml/eval/baseline.py).
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import re
import sys
import time
from pathlib import Path

from . import baseline, contract, gates, metrics, scoring
from .backends import HFBackend, LlamaServer, TeacherBackend

DEFAULT_LLAMA_BIN = os.environ.get("MURMUR_LLAMA_BIN", str(contract.REPO / ".cache/ml/llm/bin/llama-b12503"))
_THINK = re.compile(r"^\s*<think>.*?</think>\s*", re.S)


def sha256(path: str | Path, chunk: int = 1 << 22) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while b := f.read(chunk):
            h.update(b)
    return h.hexdigest()


def detect_template(model: str) -> str:
    p = Path(model)
    if p.suffix == ".gguf":
        from gguf import GGUFReader  # llama.cpp gguf-py
        arch = GGUFReader(str(p)).fields["general.architecture"].contents()
        return contract.MODEL_TYPE_TO_TEMPLATE[arch]
    cfg = json.loads((p / "config.json").read_text())
    return contract.MODEL_TYPE_TO_TEMPLATE[cfg.get("model_type")]


def postprocess(text: str) -> str:
    """What the app does before validation: drop a leaked think block and surrounding whitespace."""
    return _THINK.sub("", text or "").strip()


def build_backend(a):
    kind = a.backend
    if kind == "auto":
        kind = "llama" if a.model.endswith(".gguf") else "hf"
    if kind == "llama":
        return LlamaServer(a.model, a.llama_bin, threads=a.threads, ctx=a.ctx, extra_args=["-ngl", str(a.ngl)],
                           log_path=str(Path(a.out) / "llama-server.log")), "gguf"
    if kind == "hf":
        return HFBackend(a.model, threads=a.threads, dtype=a.dtype), "hf"
    from .judge import JudgeConfig
    return TeacherBackend(JudgeConfig(a.judge_url, a.model if a.model != "teacher" else None)), "teacher"


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--model", help=".gguf file, HF dir, or (backend teacher) the teacher model name / 'teacher'")
    ap.add_argument("--data", nargs="+")
    ap.add_argument("--out")
    ap.add_argument("--rescore", metavar="DIR", help="recompute DIR/report.json from DIR/predictions.jsonl")
    ap.add_argument("--data-root", help="with --rescore: local dir replacing the pod data dir of report.json info.data")
    ap.add_argument("--split", default=None, help="keep only this split (default: all rows)")
    ap.add_argument("--system", default="finetuned", help="system prompt name or prompts/profiles/<name>")
    ap.add_argument("--template", default="auto")
    ap.add_argument("--backend", default="auto", choices=["auto", "llama", "hf", "teacher"])
    ap.add_argument("--llama-bin", default=DEFAULT_LLAMA_BIN)
    ap.add_argument("--threads", type=int, default=int(os.environ.get("MURMUR_THREADS", "6")))
    ap.add_argument("--ctx", type=int, default=8192)  # >= longest prompt (train max_len auto cap 4096) + 1024 new tokens
    ap.add_argument("--ngl", type=int, default=int(os.environ.get("MURMUR_LLAMA_NGL", "0")),
                    help="llama.cpp GPU layers (0 = CPU, the ship-gate setting; 99 = all on GPU)")
    ap.add_argument("--dtype", default="bfloat16")
    ap.add_argument("--limit", type=int, default=None)
    ap.add_argument("--label", default=None, help="free-form run label stored in the report")
    ap.add_argument("--judge", action="store_true", help="also run ml.eval.judge (teacher env, dev server only)")
    ap.add_argument("--judge-url", default=None, help="judge/teacher base URL (default: MURMUR_TEACHER_BASE_URL)")
    a = ap.parse_args(argv)

    old_info = {}
    if a.rescore:
        a.out = a.rescore
        old_info = json.loads((Path(a.out) / "report.json").read_text())["info"]
        from .judge import remap
        a.data = a.data or [remap(p, a.data_root) for p in old_info["data"]]
        a.split = a.split or old_info.get("split")
        a.model, a.system, a.label = old_info["model"], old_info["system"], a.label or old_info.get("label")
    if not (a.model and a.data and a.out):
        ap.error("--model, --data and --out are required (or --rescore DIR)")
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    data = contract.load(a.data, split=a.split)[: a.limit]
    if a.rescore:
        template = old_info["template"]
    elif a.backend == "teacher":
        template = "messages"
    else:
        template = detect_template(a.model) if a.template == "auto" else a.template
    pred_path = out / "predictions.jsonl"
    done = {}
    if pred_path.exists():
        for line in pred_path.read_text(encoding="utf-8").splitlines():
            if line.strip():
                r = json.loads(line)
                done[r["id"]] = r
    todo = [ex for ex in data if ex["id"] not in done]
    if a.rescore and todo:
        raise SystemExit(f"--rescore: {len(todo)} examples have no prediction in {pred_path}")
    if not a.rescore:
        run_key = {"model": str(Path(a.model).resolve()) if Path(a.model).exists() else a.model,
                   "system": a.system, "template": template}
        key_path = out / "run_key.json"
        if key_path.exists() and json.loads(key_path.read_text()) != run_key:
            raise SystemExit(f"{out} holds predictions of a different run {key_path.read_text()}; use a new --out")
        key_path.write_text(json.dumps(run_key))
    backend, kind = None, old_info.get("backend")
    t_start = time.time()
    if todo:
        backend, kind = build_backend(a)
    elif not kind:
        kind = "teacher" if a.backend == "teacher" else ("gguf" if a.model.endswith(".gguf") else "hf")
    try:
        with open(pred_path, "a", encoding="utf-8") as f:
            for i, ex in enumerate(todo, 1):
                if getattr(backend, "wants_messages", False):
                    g = backend.generate_messages(*contract.render_messages(ex, a.system))
                else:
                    g = backend.generate(contract.render_prompt(ex, a.system, template), ex["transcript"])
                rec = {"id": ex["id"], "out": postprocess(g.text), "raw_out": g.text, "prompt_tokens": g.prompt_tokens,
                       "completion_tokens": g.completion_tokens, "prefill_ms": g.prefill_ms, "decode_ms": g.decode_ms,
                       "latency_ms": g.latency_ms, **({"extra": g.extra} if g.extra else {})}
                f.write(json.dumps(rec, ensure_ascii=False) + "\n")
                f.flush()
                done[ex["id"]] = rec
                print(f"[{i}/{len(todo)}] {ex['id']} {g.latency_ms}ms -> {rec['out'][:80]!r}", file=sys.stderr, flush=True)
    finally:
        if backend:
            backend.close()
    gen_s = time.time() - t_start

    from .judge import JudgeConfig, judge_all, load_judged
    judged = load_judged(out)
    if a.judge:
        judged = judge_all(data, done, out, JudgeConfig(a.judge_url))
    rows, grows, reasons = scoring.score_all(data, done, a.system, scoring.set_map(a.data), judged)
    contract.write_jsonl(out / "scores.jsonl", rows)
    contract.write_jsonl(out / "scores_guarded.jsonl", grows)
    summ = metrics.summarize(rows)
    gsumm = metrics.summarize(grows)
    preds = [done[ex["id"]] for ex in data]
    pref = [p["prefill_ms"] / p["prompt_tokens"] for p in preds if p.get("prefill_ms") and p.get("prompt_tokens")]
    dec = [p["completion_tokens"] / (p["decode_ms"] / 1000) for p in preds
           if p.get("decode_ms") and p.get("completion_tokens")]
    model_p = Path(a.model)
    info = {"model": str(model_p), "label": a.label, "backend": kind, "template": template, "system": a.system,
            "data": a.data, "split": a.split, "n": len(data), "threads": a.threads, "ngl": a.ngl,
            "llama_bin": a.llama_bin if kind == "gguf" else None,
            "model_sha256": sha256(model_p) if model_p.is_file() else old_info.get("model_sha256"),
            "model_bytes": model_p.stat().st_size if model_p.is_file() else old_info.get("model_bytes"),
            "host": platform.node(), "gen_wall_s": round(gen_s, 1) if todo else old_info.get("gen_wall_s"),
            "judge": sorted({j.get("judge_model", "?") for j in judged.values()}) or None,
            "judged_n": sum(1 for ex in data if ex["id"] in judged),
            "prefill_ms_per_token_mean": round(sum(pref) / len(pref), 3) if pref else None,
            "decode_tok_per_s_mean": round(sum(dec) / len(dec), 2) if dec else None,
            "note": "latency is host hardware, not the phone; use the app Benchmark for device numbers"}
    if a.rescore:
        for k in ("threads", "ngl", "llama_bin", "host"):
            info[k] = old_info.get(k)
        info["rescored_on"] = platform.node()
    g = gates.evaluate(summ, backend=kind)
    guarded = {"summary": gsumm, "gates": gates.evaluate(gsumm, backend=kind),
               "fired": sum(n for k, n in reasons.items() if k not in ("ok", "repaired")), "reasons": reasons,
               "note": "judge scores are copied only where the guard left the raw output unchanged"}
    report = {"info": info, "summary": summ, "gates": g, "guarded": guarded}
    report["baseline"] = baseline.compare(report)
    (out / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=1), encoding="utf-8")
    (out / "report.md").write_text(to_markdown(report), encoding="utf-8")
    print(to_markdown(report))
    return 0


COLS = [("n", "n"), ("pass", "pass"), ("exact", "exact"), ("em", "EM"), ("nwer", "N-WER"), ("nwer_raw", "raw"),
        ("entity_recall", "ent"), ("ctx_resolution_rate", "resolve"), ("distractor_insertion", "distr"),
        ("noop_exact", "noop"),
        ("answer_rate", "answer"), ("injection_success", "inject"), ("empty_ok", "empty"),
        ("false_empty", "f-empty"), ("wrong_language", "lang"), ("drift_proxy", "drift~"),
        ("len_ratio_mean", "len"), ("latency_ms_p50", "p50ms"), ("gen_tokens_mean", "gen")]


def table(summ: dict) -> str:
    def cell(v):
        return "" if v is None else (str(v) if isinstance(v, int) else f"{v:.3g}")
    lines = ["| group | " + " | ".join(s for _, s in COLS) + " |", "|---|" + "---:|" * len(COLS)]
    lines.append("| **all** | " + " | ".join(cell(summ["overall"][k]) for k, _ in COLS) + " |")
    for name in ("by_set", "by_lang", "by_ctx_source", "by_category"):
        for k, v in summ.get(name, {}).items():
            tag = {"by_ctx_source": f"ctx:{k}", "by_set": f"set:{k}"}.get(name, k)
            lines.append(f"| {tag} | " + " | ".join(cell(v.get(c)) for c, _ in COLS) + " |")
    return "\n".join(lines)


def to_markdown(rep: dict) -> str:
    i = rep["info"]
    head = (f"# Eval: {Path(i['model']).name}" + (f" ({i['label']})" if i.get("label") else "") + "\n\n"
            f"backend={i['backend']} template={i['template']} system={i['system']} n={i['n']} "
            f"sha256={(i['model_sha256'] or '')[:12]} prefill_ms/tok={i['prefill_ms_per_token_mean']} "
            f"decode_tok/s={i['decode_tok_per_s_mean']} (host CPU/GPU, not phone)\n")
    body = head + "\n" + table(rep["summary"]) + "\n\n" + gates.to_markdown(rep["gates"]) + "\n"
    gd = rep.get("guarded")
    if gd:
        body += (f"\n## After prompts/guard.py (what the app inserts; guard fired {gd['fired']}x: "
                 + ", ".join(f"{k} {v}" for k, v in sorted(gd["reasons"].items())) + ")\n\n"
                 + table(gd["summary"]) + "\n\n" + gates.to_markdown(gd["gates"]) + "\n")
    if rep.get("baseline"):
        body += "\n" + baseline.to_markdown(rep["baseline"]) + "\n"
    return body


if __name__ == "__main__":
    raise SystemExit(main())
