"""Fingerprint of the ctx v2 producer (ml/ocr + ml/screens source and constants).

The v2 eval sets and the few-shot files store it in meta.render.producer when they are built. ml/ocr's
PIPELINE_VERSION is not always bumped when constants change, so run_eval compares this hash instead and
refuses to run on stale contexts (rebuild with `make -C prompts sets`).
"""
from __future__ import annotations

import hashlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
# files whose content decides what build_ctx returns (render + OCR + post-processing); tests/docs excluded
PATTERNS = ("ml/ocr/*.py", "ml/screens/*.py", "ml/screens/families/*.py", "ml/screens/driver.js")


def producer_fingerprint(root: Path = ROOT) -> str:
    h = hashlib.sha256()
    for pat in PATTERNS:
        for p in sorted(root.glob(pat)):
            h.update(str(p.relative_to(root)).encode() + b"\0" + p.read_bytes() + b"\0")
    return h.hexdigest()[:12]


def stale(cases: list[dict], root: Path = ROOT) -> tuple[int, str]:
    """-> (number of cases with a recorded producer different from the current one, current fingerprint)."""
    cur = producer_fingerprint(root)
    n = sum(1 for c in cases if ((c.get("meta") or {}).get("render") or {}).get("producer") not in (None, cur))
    return n, cur


if __name__ == "__main__":
    print(producer_fingerprint())
