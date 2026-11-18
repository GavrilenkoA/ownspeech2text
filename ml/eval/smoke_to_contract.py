#!/usr/bin/env python3
"""Convert prompts/eval/smoke_cases.v1.jsonl (24 hand-written cases) to the shared data contract.

The smoke checks map onto meta.check (same keys as prompts/eval/sets/*.jsonl):
  must -> keep, must_any -> keep (list of alternatives), must_not -> forbid,
  dictated_question / dictated_command -> noanswer, exact -> category noop or exact target.

  python -m ml.eval.smoke_to_contract [--split train] [--out FILE]
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from .contract import REPO, validate, write_jsonl

CAT_MAP = {"no_op": "noop", "empty": "empty_noise", "commands": "spoken_format", "mixed": "code_switch",
           "self_correction": "filler_selfcorr", "field_context": "continuation"}


def convert(case: dict, split: str) -> dict:
    cat = CAT_MAP.get(case["cat"], case["cat"])
    check = {}
    keep = list(case.get("must", [])) + [list(alts) for alts in case.get("must_any", [])]
    if keep:
        check["keep"] = keep
    if case.get("must_not"):
        check["forbid"] = list(case["must_not"])
    if case["cat"] in ("dictated_question", "dictated_command"):
        check["noanswer"] = True
    if case["cat"] == "injection":
        check["injection"] = True
    lang = case["lang"]
    if case["id"].startswith("mixed-"):
        lang = "mixed"
    ex = {"id": "smoke-" + case["id"], "lang": lang, "category": cat, "source": "manual", "split": split,
          "ctx": case["ctx"], "transcript": case["transcript"], "target": case["ref"],
          "meta": {"check": check, "origin": "prompts/eval/smoke_cases.v1.jsonl", "smoke_cat": case["cat"],
                   "licence": "project-internal"}}
    return validate(ex)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", default=str(REPO / "prompts/eval/smoke_cases.v1.jsonl"))
    ap.add_argument("--split", default="test")
    ap.add_argument("--out", required=True)
    a = ap.parse_args(argv)
    rows = [convert(json.loads(l), a.split) for l in Path(a.src).read_text(encoding="utf-8").splitlines() if l.strip()]
    n = write_jsonl(a.out, rows)
    print(f"wrote {n} examples to {a.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
