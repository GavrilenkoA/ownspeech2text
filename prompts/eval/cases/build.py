#!/usr/bin/env python3
"""Validate the hand-written cases and write prompts/eval/sets/{dev,test}.v1.jsonl.

  python3 -m prompts.eval.cases.build          (from the repo root)
"""
from __future__ import annotations

import collections
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "prompts"))
import render  # noqa: E402

from . import dev_v1, shots_v2, shots_v3, test_v1  # noqa: E402

SETS = ROOT / "prompts" / "eval" / "sets"
REQUIRED = ("id", "lang", "category", "source", "split", "ctx", "transcript", "target", "meta")


def validate(cases: list[dict], split: str) -> list[str]:
    errs = []
    ids = collections.Counter(c["id"] for c in cases)
    errs += [f"duplicate id {i}" for i, n in ids.items() if n > 1]
    for c in cases:
        for k in REQUIRED:
            if k not in c:
                errs.append(f"{c.get('id')}: missing {k}")
        if c["split"] != split:
            errs.append(f"{c['id']}: split {c['split']} != {split}")
        if c["category"] == "noop" and c["target"] != c["transcript"]:
            errs.append(f"{c['id']}: noop target differs from transcript")
        if c["category"] == "empty_noise" and c["target"] != "":
            errs.append(f"{c['id']}: empty_noise target must be ''")
        if c["category"] == "long" and not 150 <= len(c["transcript"].split()) <= 300:
            errs.append(f"{c['id']}: long transcript has {len(c['transcript'].split())} words")
        try:
            render.user_message(c["ctx"], c["transcript"])
        except Exception as e:  # noqa: BLE001
            errs.append(f"{c['id']}: render failed: {e}")
        for k in c["meta"]["check"].get("keep", []):
            alts = k if isinstance(k, list) else [k]
            if c["target"] and not any(a in c["target"] for a in alts) and not any(
                    any(a in t for a in alts) for t in c["meta"]["check"].get("alts", [])):
                errs.append(f"{c['id']}: keep {alts} not in target")
    return errs


def main() -> int:
    dev, test = dev_v1.CASES, test_v1.CASES
    errs = validate(dev, "dev") + validate(test, "test")
    overlap = {c["transcript"] for c in dev} & {c["transcript"] for c in test}
    errs += [f"transcript in both dev and test: {t!r}" for t in overlap]
    errs += [f"id in both: {i}" for i in {c['id'] for c in dev} & {c['id'] for c in test}]
    shot_sets = {"default.v2": shots_v2.CASES, "default.v3": shots_v3.CASES,
                 "default.v3s": [c for c in shots_v3.CASES if c["id"] in shots_v3.SHORT]}
    shots = shots_v2.CASES + shots_v3.CASES
    errs += validate(shots_v2.CASES, "train") + validate(shots_v3.CASES, "train")
    errs += [f"shot transcript also in eval: {t!r}" for t in {c["transcript"] for c in shots} & ({c["transcript"] for c in dev} | {c["transcript"] for c in test})]
    for c in dev + test + shots:
        for f in c["meta"]["check"].get("forbid", []):
            if f.casefold() in c["target"].casefold() and c["target"]:
                import re as _re
                if _re.search(r"(?<!\w)" + _re.escape(f.casefold()) + r"(?!\w)", c["target"].casefold()):
                    errs.append(f"{c['id']}: forbid {f!r} occurs in target")
    if errs:
        print("\n".join(errs), file=sys.stderr)
        return 1
    SETS.mkdir(parents=True, exist_ok=True)
    for name, cases in (("dev", dev), ("test", test)):
        p = SETS / f"{name}.v1.jsonl"
        p.write_text("".join(json.dumps(c, ensure_ascii=False) + "\n" for c in cases), encoding="utf-8")
        langs = collections.Counter(c["lang"] for c in cases)
        cats = collections.Counter(c["category"] for c in cases)
        print(f"{p.relative_to(ROOT)}: {len(cases)} cases; lang {dict(langs)}; {len(cats)} categories {dict(sorted(cats.items()))}")
    for name, rows in shot_sets.items():
        sp = ROOT / "prompts" / "shots" / f"{name}.jsonl"
        sp.parent.mkdir(parents=True, exist_ok=True)
        sp.write_text("".join(json.dumps(c, ensure_ascii=False) + "\n" for c in rows), encoding="utf-8")
        print(f"{sp.relative_to(ROOT)}: {len(rows)} few-shot turns")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
