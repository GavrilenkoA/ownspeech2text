#!/usr/bin/env python3
"""Hand-authored eval cases with a REAL ctx v2: screen spec -> ml.screens.build_ctx (render -> OCR) ->
shared-contract JSONL, with the CONTEXT_V2 category rules checked on the OCR lines.

Input JSONL, one case per line:
  {"id", "lang", "category", "split", "spec": <ml/screens render spec>, "transcript", "target", "meta"}
Output: contract JSONL (ctx = build_ctx(spec); meta.screen_spec keeps the spec so the case can be
re-rendered when the OCR pipeline changes; source "manual"). Cases that break a rule are not written;
they are listed with the reason (re-render with another theme / font_scale / seed, or fix the case):
  context_resolution, context_names, near_miss: every gold entity (meta.check.keep / meta.entities) is in
      screen.lines as spelled (ml.eval.metrics.on_screen)
  context_counterpart (and meta.must_not_show): every meta.distractors term and its close variants
      (case-folded, edit distance <= 2 over same-length word windows) is absent from screen.lines

Needs the OCR/screens venv (onnxruntime, playwright): run through tools/heavy, e.g.
  tools/heavy env PYTHONPATH=. .cache/ml/ocr/venv/bin/python -m ml.eval.v2_cases CASES.jsonl OUT.jsonl
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from ml.eval import contract, ctxview, metrics

SUPPORT = metrics.CTX_SUPPORT_CATS


def _lev(a: str, b: str) -> int:
    return metrics.edit_distance(list(a), list(b))


def close_variant_on_screen(ctx: dict, term: str, max_dist: int = 2) -> str | None:
    """The screen word window closest to `term` if within max_dist edits (case-folded), else None."""
    t = metrics.fold(term).casefold()
    n = len(t.split())
    for line in ctxview.screen_lines(ctx):
        ws = metrics.fold(line).casefold().split()
        for i in range(len(ws) - n + 1):
            w = " ".join(ws[i:i + n]).strip(".,:;!?()«»\"'")
            if _lev(w, t) <= max_dist:
                return line
    return None


def check_case(ex: dict) -> list[str]:
    """CONTEXT_V2 category rules on the OCR output. Empty list = the case is valid."""
    errs, ctx, meta = [], ex["ctx"], ex.get("meta") or {}
    if ex["category"] in SUPPORT:
        for alts in metrics.gold_entities(ex):
            if not any(metrics.on_screen(ctx, a) for a in alts):
                errs.append(f"entity {alts[0]!r} not in OCR lines")
    if ex["category"] == "context_counterpart" or meta.get("must_not_show"):
        for d in list(meta.get("distractors") or []) + list(meta.get("must_not_show") or []):
            hit = close_variant_on_screen(ctx, d)
            if hit:
                errs.append(f"unsupported term {d!r} (or a close variant) is on screen: {hit!r}")
    return errs


def build(cases: list[dict], build_ctx) -> tuple[list[dict], list[dict]]:
    ok, bad = [], []
    for c in cases:
        ctx = build_ctx(c["spec"])
        meta = dict(c.get("meta") or {}) | {"screen_spec": c["spec"]}
        ex = contract.validate({"id": c["id"], "lang": c["lang"], "category": c["category"], "source": "manual",
                                "split": c.get("split", "test"), "ctx": ctx, "transcript": c["transcript"],
                                "target": c["target"], "meta": meta})
        errs = check_case(ex)
        (bad if errs else ok).append(ex if not errs else {"id": c["id"], "errors": errs,
                                                           "ocr_lines": ctxview.screen_lines(ctx)})
    return ok, bad


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("cases")
    ap.add_argument("out")
    a = ap.parse_args(argv)
    from ml.screens.build_ctx import build_ctx  # only here: needs the OCR/screens venv
    cases = [json.loads(l) for l in Path(a.cases).read_text(encoding="utf-8").splitlines() if l.strip()]
    ok, bad = build(cases, build_ctx)
    contract.write_jsonl(a.out, ok)
    for b in bad:
        print(json.dumps(b, ensure_ascii=False), file=sys.stderr)
    print(json.dumps({"written": len(ok), "rejected": len(bad), "out": a.out}))
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
