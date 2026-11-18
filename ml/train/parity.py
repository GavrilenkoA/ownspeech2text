#!/usr/bin/env python3
"""Export parity check: the merged HF checkpoint and its BF16 GGUF must produce the same greedy text.

LoRA targets all-linear, which on Qwen3.5 includes the Gated DeltaNet in_proj_qkv / in_proj_z /
in_proj_a / in_proj_b tensors that convert_hf_to_gguf.py reorders or transforms; a silent merge or
conversion error would otherwise only show up as "the recipe does not work". This runs N dev items
greedily through both (same rendered prompt from ml.eval.contract, same max_new_tokens rule) and
fails when fewer than --min-identical of them give identical text. BF16 arithmetic differs between
torch and llama.cpp, so a long output may legitimately diverge late; a broken export diverges on
almost every item (and usually in the first tokens), which is what this catches.

  python -m ml.train.parity --run RUN_DIR --gguf RUN_DIR/gguf/NAME-BF16.gguf --data dev.jsonl [-n 24]

Writes RUN_DIR/eval/parity.json; exit 0 = PASS, 3 = FAIL (diverged), 2 = usage error.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
from pathlib import Path

from ml.eval import contract
from ml.eval.backends import HFBackend, LlamaServer
from ml.eval.run_eval import detect_template, postprocess


def pick(data: list[dict], n: int) -> list[dict]:
    """Deterministic, category-spread subset: round-robin over categories in id order."""
    by: dict[str, list[dict]] = {}
    for ex in sorted(data, key=lambda e: e["id"]):
        by.setdefault(ex["category"], []).append(ex)
    out, i = [], 0
    while len(out) < n and any(len(v) > i for v in by.values()):
        out += [v[i] for v in by.values() if len(v) > i]
        i += 1
    return out[:n]


def common_prefix(a: str, b: str) -> int:
    k = 0
    for x, y in zip(a, b):
        if x != y:
            break
        k += 1
    return k


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--run", required=True, help="run dir (uses merged/ for LoRA/DoRA, final/ for full FT)")
    ap.add_argument("--hf", help="override the HF checkpoint dir")
    ap.add_argument("--gguf", required=True, help="the BF16 GGUF exported from that checkpoint")
    ap.add_argument("--data", nargs="+", required=True)
    ap.add_argument("-n", type=int, default=24)
    ap.add_argument("--system", default="finetuned")
    ap.add_argument("--min-identical", type=float, default=0.8)
    ap.add_argument("--llama-bin", default=os.environ.get("MURMUR_LLAMA_BIN",
                                                          str(contract.REPO / ".cache/ml/llm/bin/llama-b12503")))
    ap.add_argument("--ngl", type=int, default=int(os.environ.get("MURMUR_LLAMA_NGL", "0")))
    ap.add_argument("--threads", type=int, default=int(os.environ.get("MURMUR_THREADS", "6")))
    ap.add_argument("--out", help="default RUN/eval/parity.json")
    a = ap.parse_args(argv)
    run = Path(a.run)
    hf = Path(a.hf) if a.hf else (run / "merged" if (run / "merged/config.json").exists() else run / "final")
    if not (hf / "config.json").exists() or not Path(a.gguf).exists():
        print(f"parity: missing {hf}/config.json or {a.gguf}", file=sys.stderr)
        return 2
    template = detect_template(str(hf))
    items = pick(contract.load(a.data), a.n)
    prompts = [(ex, contract.render_prompt(ex, a.system, template)) for ex in items]
    t0 = time.time()
    outs = {}
    for name, mk in (("hf", lambda: HFBackend(str(hf), threads=a.threads)),
                     ("gguf", lambda: LlamaServer(a.gguf, a.llama_bin, threads=a.threads, ctx=8192,
                                                  extra_args=["-ngl", str(a.ngl)]))):
        be = mk()
        try:
            outs[name] = [postprocess(be.generate(p, ex["transcript"]).text) for ex, p in prompts]
        finally:
            be.close()
    rows = []
    for (ex, _), h, g in zip(prompts, outs["hf"], outs["gguf"]):
        rows.append({"id": ex["id"], "identical": h == g, "prefix_chars": common_prefix(h, g),
                     "hf": h, "gguf": g})
    ident = sum(r["identical"] for r in rows) / max(1, len(rows))
    early = sum(1 for r in rows if not r["identical"] and r["prefix_chars"] < 8)
    ok = ident >= a.min_identical
    res = {"status": "PASS" if ok else "FAIL", "identical_share": round(ident, 3), "n": len(rows),
           "diverged_in_first_8_chars": early, "min_identical": a.min_identical, "hf_dir": str(hf),
           "gguf": a.gguf, "template": template, "system": a.system, "seconds": round(time.time() - t0, 1),
           "rows": rows}
    out = Path(a.out or run / "eval/parity.json")
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(res, ensure_ascii=False, indent=1), encoding="utf-8")
    print(json.dumps({k: v for k, v in res.items() if k != "rows"}, ensure_ascii=False))
    return 0 if ok else 3


if __name__ == "__main__":
    raise SystemExit(main())
