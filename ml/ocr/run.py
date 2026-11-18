"""CLI: python -m ml.ocr.run IMG [--exclude x0,y0,x1,y1 ...] [--detail] -> JSON {"size", "lines"} on stdout."""
from __future__ import annotations

import argparse
import json
import sys

from .config import DEFAULT
from .pipeline import run, run_detailed


def dumps(obj) -> str:
    """The canonical serialisation (golden files, determinism checks)."""
    return json.dumps(obj, ensure_ascii=False, separators=(",", ":"), sort_keys=False)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="python -m ml.ocr.run")
    ap.add_argument("image")
    ap.add_argument("--exclude", action="append", default=[], help="x0,y0,x1,y1 (repeatable)")
    ap.add_argument("--threads", type=int, default=DEFAULT.threads)
    ap.add_argument("--det-limit", type=int, default=DEFAULT.det_limit)
    ap.add_argument("--detail", action="store_true", help="also print raw lines and timings")
    a = ap.parse_args(argv)
    rects = [tuple(int(v) for v in s.split(",")) for s in a.exclude]
    cfg = DEFAULT.with_(threads=a.threads, det_limit=a.det_limit)
    if a.detail:
        d = run_detailed(a.image, rects, cfg)
        print(json.dumps(d, ensure_ascii=False, indent=1))
    else:
        sys.stdout.write(dumps(run(a.image, rects, cfg)) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
