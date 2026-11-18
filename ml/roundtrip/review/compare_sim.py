"""Compare real round-trip rows with simulated rows of the same (probe, model): per-language error-only WER and
term error rate, and print side-by-side examples for a blind look.

  python -m ml.roundtrip.review.compare_sim --real REAL.jsonl --sim SIM.jsonl [--examples 12 --models t-one whisper]
"""
from __future__ import annotations

import argparse
import json
import random
import sys
from collections import defaultdict
from typing import Optional, Sequence

from ml.roundtrip.mine.align import align, term_hits


def _rows(p: str) -> list[dict]:
    with open(p, encoding="utf-8") as fh:
        return [json.loads(line) for line in fh if line.strip()]


def _agg(rows: list[dict]) -> dict:
    acc: dict = defaultdict(lambda: [0, 0, 0, 0])  # err, nref, term_err, n_terms
    for r in rows:
        al = align(r["text"], r["hypothesis"])
        a = acc[(r["asr_model"], r["lang"])]
        a[0] += al.errors
        a[1] += al.n_ref
        for h in term_hits(al, r["terms"]):
            a[2] += int(h.is_error)
            a[3] += 1
    return acc


def main(argv: Optional[Sequence[str]] = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--real", required=True)
    ap.add_argument("--sim", required=True)
    ap.add_argument("--examples", type=int, default=0)
    ap.add_argument("--models", nargs="*", default=[])
    ap.add_argument("--seed", type=int, default=5)
    a = ap.parse_args(argv)
    real, sim = _rows(a.real), _rows(a.sim)
    ra, sa = _agg(real), _agg(sim)
    print(f"{'model':48s} {'lang':5s} {'real ewer':>9s} {'sim ewer':>9s} {'real term_err':>13s} {'sim term_err':>12s}")
    for k in sorted(ra):
        r, s = ra[k], sa.get(k)
        if not s:
            continue
        print(f"{k[0]:48s} {k[1]:5s} {r[0] / max(1, r[1]):9.3f} {s[0] / max(1, s[1]):9.3f} "
              f"{r[2] / max(1, r[3]):13.3f} {s[2] / max(1, s[3]):12.3f}")
    if a.examples:
        sim_by = defaultdict(list)
        for r in sim:
            sim_by[(r["probe_id"], r["asr_model"])].append(r)
        rng = random.Random(a.seed)
        cands = [r for r in real if (r["probe_id"], r["asr_model"]) in sim_by
                 and (not a.models or any(m in r["asr_model"] for m in a.models))]
        for r in rng.sample(cands, min(a.examples, len(cands))):
            s = sim_by[(r["probe_id"], r["asr_model"])][0]
            pair = [("REAL", r["hypothesis"]), ("SIM", s["hypothesis"])]
            rng.shuffle(pair)
            print(f"\n--- {r['asr_model']} {r['lang']}\nREF: {r['text']}\n A : {pair[0][1]}\n B : {pair[1][1]}"
                  f"\n   (A={pair[0][0]})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
