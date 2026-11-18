"""Synthetic phone screens (CONTEXT_V2): render(spec) -> {"png", "size", "visible", "exclude_rects"}."""


def render(spec: dict) -> dict:
    from .render import render as _render
    return _render(spec)
