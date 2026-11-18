"""Validate contract JSONL files: python -m ml.common.validate [--strict] FILE [FILE ...]  (exit 1 on any error)."""
from __future__ import annotations

import argparse
import json
import sys

from ml.common.schema import validate_file


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("files", nargs="+")
    ap.add_argument("--strict", action="store_true", help="also require categories from the taxonomy")
    ap.add_argument("--no-render", action="store_true", help="skip rendering through prompts/render.py")
    a = ap.parse_args(argv)
    bad = 0
    for f in a.files:
        s = validate_file(f, strict=a.strict, check_render=not a.no_render)
        print(json.dumps({k: s[k] for k in ("path", "records", "invalid")}, ensure_ascii=False))
        for line, rid, errs in s["errors"][:10]:
            print(f"  line {line} id={rid}: {errs}", file=sys.stderr)
        bad += s["invalid"]
    return 1 if bad else 0


if __name__ == "__main__":
    raise SystemExit(main())
