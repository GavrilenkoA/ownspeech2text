"""Reviewer sample from a finished generator run: up to --n lines, stratified by category and language.

  python -m ml.data.make_sample --run .cache/ml/data/synth_runs/proof_v6 --out ml/data/samples/synth_v2.sample.jsonl \
      [--drop context_counterpart:en]   # cells the current planner no longer produces
"""
from __future__ import annotations

import argparse
from collections import defaultdict
from pathlib import Path

from ml.common.schema import read_jsonl, write_jsonl


def pick(recs: list[dict], n: int) -> list[dict]:
    cells = defaultdict(list)
    for r in recs:
        cells[(r["category"], r["lang"])].append(r)
    out, i = [], 0
    keys = sorted(cells)
    while len(out) < n and any(cells.values()):
        k = keys[i % len(keys)]
        if cells[k]:
            out.append(cells[k].pop(0))
        i += 1
    return sorted(out, key=lambda r: (r["category"], r["lang"], r["id"]))


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--run", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--n", type=int, default=200)
    ap.add_argument("--drop", action="append", default=[], help="CATEGORY:LANG cells to leave out")
    a = ap.parse_args(argv)
    drop = {tuple(d.split(":", 1)) for d in a.drop}
    recs = [r for s in ("train", "dev", "test") if (Path(a.run) / f"{s}.jsonl").exists()
            for r in read_jsonl(Path(a.run) / f"{s}.jsonl") if (r["category"], r["lang"]) not in drop]
    n = write_jsonl(a.out, pick(recs, min(a.n, 200)))
    print(f"{n} lines -> {a.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
