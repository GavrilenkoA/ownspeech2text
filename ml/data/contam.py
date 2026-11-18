"""Eval contamination check: synthetic records and the teacher prompt vs every held-out eval set.

Eval texts = transcript and target/ref of prompts/eval/sets/*.jsonl (not *.specs.jsonl) and
prompts/eval/smoke_cases*.jsonl. Two rules, both on character 5-grams (filters.shingles):
  * record: Jaccard >= 0.8 between a record's target (or transcript) and an eval target/ref (or transcript),
    the near-duplicate rule of filters.dedup -> the record is dropped ("dedup:eval_contamination").
  * prompt phrase: a quoted example (>= 12 characters) in the teacher prompt whose 5-grams are >= 80 % contained
    in one eval text -> the prompt nudges the teacher towards a held-out case (unit test fails).

  python -m ml.data.contam --data .cache/ml/data/synth_v2   # report contaminated records of a run
  python -m ml.data.contam --prompt                          # check the teacher prompt
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from functools import lru_cache
from pathlib import Path

from ml.common.render_bridge import REPO
from ml.data.synth.filters import jaccard, shingles

EVAL_GLOBS = ("prompts/eval/sets/*.jsonl", "prompts/eval/smoke_cases*.jsonl")
THR = 0.8


@lru_cache(maxsize=1)
def eval_texts() -> tuple[tuple[str, str, str], ...]:
    """((eval id, field, text), ...) over every eval set."""
    out = []
    for g in EVAL_GLOBS:
        for p in sorted(REPO.glob(g)):
            if p.name.endswith(".specs.jsonl"):
                continue
            for line in p.read_text(encoding="utf-8").splitlines():
                if not line.strip():
                    continue
                d = json.loads(line)
                for f in ("transcript", "target", "ref"):
                    if isinstance(d.get(f), str) and d[f].strip():
                        out.append((f"{p.name}:{d.get('id')}", f, d[f]))
    return tuple(out)


@lru_cache(maxsize=1)
def _index() -> tuple:
    return tuple((i, f, shingles(t)) for i, f, t in eval_texts() if len(t) >= 12)


def contaminated(rec: dict, thr: float = THR) -> str | None:
    """Eval id that this record near-duplicates (target vs target/ref, transcript vs transcript), else None."""
    pairs = [(rec.get("target") or "", ("target", "ref")), (rec.get("transcript") or "", ("transcript",))]
    for text, fields in pairs:
        if len(text) < 12:
            continue
        sh = shingles(text)
        for eid, f, esh in _index():
            if f in fields and jaccard(sh, esh) >= thr:
                return eid
    return None


def _containment(a: frozenset, b: frozenset) -> float:
    return len(a & b) / len(a) if a else 0.0


def prompt_phrases() -> list[str]:
    """Quoted example phrases of the teacher prompt (system text + category hints)."""
    from ml.data.synth import teacher_prompts as TP
    text = TP.GEN_SYSTEM + "\n" + "\n".join(TP.CATEGORY_HINTS.values()) + "\n" + "\n".join(TP.MIX_HINTS.values())
    return [m for m in re.findall(r"['\"“«]([^'\"”»]{12,200})['\"”»]", text)]


def prompt_hits(thr: float = THR) -> list[tuple[str, str]]:
    out = []
    for ph in prompt_phrases():
        sh = shingles(ph)
        for eid, _f, esh in _index():
            if _containment(sh, esh) >= thr:
                out.append((ph, eid))
                break
    return out


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--data", help="run directory with train/dev/test.jsonl")
    ap.add_argument("--prompt", action="store_true", help="check the teacher prompt's quoted examples")
    a = ap.parse_args(argv)
    bad = 0
    if a.prompt:
        hits = prompt_hits()
        for ph, eid in hits:
            print(f"prompt phrase {ph!r} ~ {eid}")
        print(f"teacher prompt: {len(prompt_phrases())} quoted phrases, {len(hits)} near eval items")
        bad += len(hits)
    if a.data:
        n, nb = 0, 0
        for split in ("train", "dev", "test"):
            p = Path(a.data) / f"{split}.jsonl"
            if not p.exists():
                continue
            for line in p.read_text(encoding="utf-8").splitlines():
                rec = json.loads(line)
                n += 1
                hit = contaminated(rec)
                if hit:
                    nb += 1
                    print(f"{rec['id']} ({split}) ~ {hit}")
        print(f"records: {n}, near eval items: {nb}")
        bad += nb
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
