"""Synthetic phone screens (CONTEXT_V2): render(spec) -> {"png", "size", "visible", "exclude_rects", "field_box"}.

RENDERER_VERSION is bumped by hand on every change that can change a PNG, `visible` or `field_box`;
VERSION adds a hash of the renderer's sources so that a forgotten bump is still detected
(ml.screens.build_ctx.stale(meta) compares both, plus the OCR version and the Chrome build).
"""
from functools import lru_cache
from pathlib import Path

# History: screens.v1 = 2026-11-18 (no field.box). screens.v2 = 2026-11-19 (field_box from [data-focus];
# browser_search no longer draws two focused fields).
RENDERER_VERSION = "screens.v2"


def source_files() -> list[str]:
    here = Path(__file__).resolve().parent
    fs = [p.relative_to(here).as_posix() for p in here.rglob("*") if p.suffix in (".py", ".js", ".json")
          and p.is_file() and not ({"tests", "__pycache__", "node_modules"} & set(p.relative_to(here).parts))]
    return sorted(f for f in fs if f not in ("samples.py", "sheet.py", "specs.py"))   # do not shape spec -> render


@lru_cache(maxsize=1)
def source_hash() -> str:
    from ml.ocr.config import src_hash
    return src_hash(Path(__file__).resolve().parent, source_files())


def __getattr__(name):   # VERSION is computed on first access (module-level __getattr__, PEP 562)
    if name == "VERSION":
        return f"{RENDERER_VERSION}+src:{source_hash()}"
    raise AttributeError(name)


def render(spec: dict) -> dict:
    from .render import render as _render
    return _render(spec)
