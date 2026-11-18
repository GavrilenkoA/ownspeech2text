"""Review helpers over round-trip rows (interface 2), read-only on the miner's code.

  python -m ml.roundtrip.review.inspect_align leak ROWS.jsonl         # term variants that absorbed neighbours
  python -m ml.roundtrip.review.inspect_align pairs ROWS.jsonl --n 40 --seed 3   # alignments for hand review
"""
from __future__ import annotations

import argparse
import json
import random
import sys
from collections import Counter
from typing import Optional, Sequence

from ml.roundtrip.mine.align import align, describe, term_hits


def _rows(path: str) -> list[dict]:
    with open(path, encoding="utf-8") as fh:
        return [json.loads(line) for line in fh if line.strip()]


def leak(path: str) -> int:
    n = bad = 0
    ex: Counter = Counter()
    by_model: Counter = Counter()
    by_model_n: Counter = Counter()
    for r in _rows(path):
        al = align(r["text"], r["hypothesis"])
        for h in term_hits(al, r["terms"]):
            n += 1
            by_model_n[r["asr_model"]] += 1
            ops = [op for op in al.ops if op.r0 < h.r1 and op.r1 > h.r0]
            lo = min(op.r0 for op in ops)
            hi = max(op.r1 for op in ops)
            if lo < h.r0 or hi > h.r1:
                bad += 1
                by_model[r["asr_model"]] += 1
                ref_span = " ".join(t.core for t in al.ref[lo:hi])
                ex[(h.term, ref_span, h.hyp, r["asr_model"].split("@")[0])] += 1
    print(f"term hits {n}; variants whose aligned op also covers neighbouring reference words: {bad} "
          f"({bad / max(1, n):.1%})")
    for m in sorted(by_model_n):
        print(f"  {m:50s} {by_model[m]:4d}/{by_model_n[m]:4d}")
    for (t, span, hyp, m), c in ex.most_common(25):
        print(f"  term={t!r:22} ref_span={span!r:34} variant={hyp!r:28} {m}")
    return 0


def pairs(path: str, n: int, seed: int, models: Optional[Sequence[str]]) -> int:
    rows = _rows(path)
    if models:
        rows = [r for r in rows if any(m in r["asr_model"] for m in models)]
    rng = random.Random(seed)
    for r in rng.sample(rows, min(n, len(rows))):
        al = align(r["text"], r["hypothesis"])
        print(f"=== {r['probe_id']} {r['asr_model']} {r['tts_system']}/{r['tts_voice']}  wer_style={al.wer_style:.2f}")
        print(f"REF: {r['text']}\nHYP: {r['hypothesis']}")
        print(describe(al))
        for h in term_hits(al, r["terms"]):
            print(f"  TERM {h.term!r} -> {h.hyp!r} kinds={h.kinds}")
    return 0


def main(argv: Optional[Sequence[str]] = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    a = sub.add_parser("leak")
    a.add_argument("rows")
    b = sub.add_parser("pairs")
    b.add_argument("rows")
    b.add_argument("--n", type=int, default=40)
    b.add_argument("--seed", type=int, default=3)
    b.add_argument("--models", nargs="*")
    args = ap.parse_args(argv)
    if args.cmd == "leak":
        return leak(args.rows)
    return pairs(args.rows, args.n, args.seed, args.models)


if __name__ == "__main__":
    sys.exit(main())
