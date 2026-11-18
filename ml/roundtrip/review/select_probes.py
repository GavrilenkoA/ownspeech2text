"""Pick a balanced, seeded sample of seed probes for the adversarial review run.

Excludes the probes the runner's local proof already used (``--exclude-rows``), so the review run is
fresh text for the noise-sampler held-out check.

  python -m ml.roundtrip.review.select_probes --n 80 --seed 7 \
      --exclude-rows ml/roundtrip/samples/roundtrip/local_proof.jsonl --out ml/roundtrip/review/probe80.jsonl
"""
from __future__ import annotations

import argparse
import json
import random
import sys
from collections import defaultdict
from pathlib import Path
from typing import Dict, List, Optional, Sequence, Set

LANGS = ("ru", "en", "mixed")


def main(argv: Optional[Sequence[str]] = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--probe", type=Path, default=Path("ml/roundtrip/probe/seed.jsonl"))
    ap.add_argument("--n", type=int, default=80)
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--exclude-rows", type=Path, nargs="*", default=[])
    ap.add_argument("--out", type=Path, required=True)
    a = ap.parse_args(argv)
    excl: Set[str] = set()
    for p in a.exclude_rows:
        with open(p, encoding="utf-8") as fh:
            excl.update(json.loads(line)["probe_id"] for line in fh if line.strip())
    by_lang: Dict[str, List[dict]] = defaultdict(list)
    with open(a.probe, encoding="utf-8") as fh:
        for line in fh:
            r = json.loads(line)
            if r["id"] not in excl:
                by_lang[r["lang"]].append(r)
    rng = random.Random(a.seed)
    per = [a.n // 3 + (1 if i < a.n % 3 else 0) for i in range(3)]
    out: List[dict] = []
    for lang, k in zip(LANGS, per):
        out += rng.sample(by_lang[lang], k)
    a.out.parent.mkdir(parents=True, exist_ok=True)
    with open(a.out, "w", encoding="utf-8") as fh:
        for r in out:
            fh.write(json.dumps(r, ensure_ascii=False) + "\n")
    print(f"{len(out)} probes ({dict(zip(LANGS, per))}), excluded {len(excl)} -> {a.out}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
