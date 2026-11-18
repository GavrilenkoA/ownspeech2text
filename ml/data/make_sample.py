"""Reviewer sample from a finished generator run: up to --n lines, stratified by category and language.

  python -m ml.data.make_sample --run .cache/ml/data/synth_runs/real216 --out ml/data/samples/synth_v2.sample.jsonl
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
    a = ap.parse_args(argv)
    recs = [r for s in ("train", "dev", "test") if (Path(a.run) / f"{s}.jsonl").exists()
            for r in read_jsonl(Path(a.run) / f"{s}.jsonl")]
    n = write_jsonl(a.out, pick(recs, min(a.n, 200)))
    print(f"{n} lines -> {a.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
