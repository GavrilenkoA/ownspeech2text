#!/usr/bin/env python3
"""Assemble generator / public-data shards (shared-contract JSONL) into train/dev/test files,
validating every row, rejecting duplicate ids and reporting the mix per split/lang/category/source.

  python -m ml.train.assemble --out DATA_DIR SHARD.jsonl [SHARD2.jsonl ...]

Writes DATA_DIR/{train,dev,test}.jsonl and DATA_DIR/stats.json. Eval-set leakage check: a train row
whose (transcript, target) pair also appears in dev/test is dropped and counted.
"""
from __future__ import annotations

import argparse
import glob
import json
from collections import Counter, defaultdict
from pathlib import Path

from ml.eval import contract


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("shards", nargs="+")
    a = ap.parse_args(argv)
    paths = sorted({p for s in a.shards for p in glob.glob(s)})
    if not paths:
        raise SystemExit("no shards matched")
    rows = contract.load(paths)
    by = defaultdict(list)
    for r in rows:
        by[r["split"]].append(r)
    held = {(r["transcript"].strip(), r["target"].strip()) for s in ("dev", "test") for r in by[s]}
    leaked = [r for r in by["train"] if (r["transcript"].strip(), r["target"].strip()) in held]
    by["train"] = [r for r in by["train"] if (r["transcript"].strip(), r["target"].strip()) not in held]
    out = Path(a.out)
    stats = {"shards": paths, "dropped_train_leaks": len(leaked)}
    for split in ("train", "dev", "test"):
        contract.write_jsonl(out / f"{split}.jsonl", by[split])
        rs = by[split]
        stats[split] = {"n": len(rs), "lang": dict(Counter(r["lang"] for r in rs)),
                        "category": dict(Counter(r["category"] for r in rs).most_common()),
                        "source": dict(Counter(r["source"] for r in rs)),
                        "empty_target": sum(1 for r in rs if not r["target"].strip())}
    (out / "stats.json").write_text(json.dumps(stats, indent=1, ensure_ascii=False))
    print(json.dumps(stats, indent=1, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
