"""Stable hashing, ids and split buckets (deterministic across machines/runs)."""
from __future__ import annotations

import hashlib
import random


def h(*parts) -> str:
    return hashlib.sha1("\x1f".join(str(p) for p in parts).encode("utf-8")).hexdigest()


def stable_id(prefix: str, *parts) -> str:
    return f"{prefix}-{h(*parts)[:12]}"


def unit(*parts) -> float:
    """Deterministic float in [0, 1) from the parts."""
    return int(h(*parts)[:13], 16) / float(16 ** 13)


def bucket_split(key: str, dev: float = 0.03, test: float = 0.03, salt: str = "murmur-split-v1") -> str:
    """Split a *group key* (never a row) into train/dev/test."""
    u = unit(salt, key)
    if u < test:
        return "test"
    if u < test + dev:
        return "dev"
    return "train"


def rng(*parts) -> random.Random:
    return random.Random(int(h(*parts)[:16], 16))
