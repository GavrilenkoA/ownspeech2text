"""YAML run configs with `extends:` inheritance, ${ENV} expansion and dotted CLI overrides."""
from __future__ import annotations

import copy
import os
import re
from pathlib import Path

import yaml

_ENV = re.compile(r"\$\{(\w+)(?::-([^}]*))?\}")


def _expand(v):
    if isinstance(v, str):
        return _ENV.sub(lambda m: os.environ.get(m.group(1), m.group(2) if m.group(2) is not None else ""), v)
    if isinstance(v, dict):
        return {k: _expand(x) for k, x in v.items()}
    if isinstance(v, list):
        return [_expand(x) for x in v]
    return v


def merge(base: dict, over: dict) -> dict:
    out = copy.deepcopy(base)
    for k, v in over.items():
        if isinstance(v, dict) and isinstance(out.get(k), dict):
            out[k] = merge(out[k], v)
        else:
            out[k] = copy.deepcopy(v)
    return out


def load(path: str | Path) -> dict:
    path = Path(path)
    cfg = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    parent = cfg.pop("extends", None)
    if parent:
        cfg = merge(load(path.parent / parent), cfg)
    return cfg


def apply_overrides(cfg: dict, items: list[str]) -> dict:
    """`train.max_steps=5 method=lora lora.r=8` -> nested keys; values parsed as YAML scalars."""
    cfg = copy.deepcopy(cfg)
    for it in items or []:
        key, _, val = it.partition("=")
        node = cfg
        parts = key.split(".")
        for p in parts[:-1]:
            node = node.setdefault(p, {})
        node[parts[-1]] = yaml.safe_load(val)
    return cfg


def resolve(path: str | Path, overrides: list[str] | None = None) -> dict:
    return _expand(apply_overrides(load(path), overrides or []))
