"""The only place in ml/train and ml/eval that looks inside `ctx` (docs/ml/CONTEXT_V2.md).

Everything else treats ctx as opaque and hands it to prompts/render.py. Metrics need two facts:
  * the screen text the model could have read (term-support checks, "allowed" words), and
  * where the context came from (report breakdown).

ctx v2 ({"v": 2, "screen": {"lines": [{"text", "conf", "box"}]}, ...}) is read directly: the
screen text is the OCR pipeline's final lines, *before* the renderer's budget trimming, because the
data rules in CONTEXT_V2 ("the term must appear in screen.lines") are stated on those lines.
Any other ctx (v1 structured context, minimal {} objects) is not parsed: its screen text is the
rendered <context> block from prompts/render.py, so no v1 field name is assumed here.
"""
from __future__ import annotations

from ml.eval import contract

SOURCES = ("screen", "no_screen", "legacy")


def is_v2(ctx: dict | None) -> bool:
    return isinstance(ctx, dict) and ctx.get("v") == 2


def screen_lines(ctx: dict | None) -> list[str]:
    """OCR lines for v2 ([] when there is no screenshot); rendered context lines otherwise."""
    if is_v2(ctx):
        scr = ctx.get("screen") or {}
        return [str(l.get("text", "")) for l in scr.get("lines") or [] if isinstance(l, dict) and l.get("text")]
    block = contract.render.render_context(ctx or {})
    return [l for l in block.splitlines() if l and not l.startswith(("<context>", "</context>"))]


def screen_text(ctx: dict | None) -> str:
    return "\n".join(screen_lines(ctx))


def source(ctx: dict | None) -> str:
    """'screen' (v2 with OCR lines), 'no_screen' (v2 without a screenshot / OCR found nothing),
    'legacy' (any non-v2 ctx: structured v1 context the phone never produces)."""
    if not is_v2(ctx):
        return "legacy"
    return "screen" if screen_lines(ctx) else "no_screen"
