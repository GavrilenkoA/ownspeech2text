"""Import prompts/render.py (the executable prompt spec) without packaging it,
and turn a data-contract record into training/eval text.

The prompt is never stored in the data: it is rendered here at load time.
Record fields used: ctx, transcript, target, meta.preferences (optional str),
meta.style_override (optional str).
"""
from __future__ import annotations

import importlib.util
from functools import lru_cache
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
RENDER_PY = REPO / "prompts" / "render.py"


@lru_cache(maxsize=1)
def render():
    spec = importlib.util.spec_from_file_location("murmur_prompt_render", RENDER_PY)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def user_message(rec: dict) -> str:
    meta = rec.get("meta") or {}
    return render().user_message(rec.get("ctx") or {}, rec.get("transcript", ""),
                                 style_override=meta.get("style_override"))


def system_prompt(rec: dict, kind: str = "finetuned") -> str:
    return render().system_prompt(kind, (rec.get("meta") or {}).get("preferences"))


def to_prompt(rec: dict, system: str = "finetuned", template: str = "qwen3.5") -> dict:
    """{'prompt': raw chat string ending in the assistant header (thinking off),
        'completion': target, 'messages': [...] } for SFT / eval."""
    r = render()
    sys_txt = system_prompt(rec, system)
    usr = user_message(rec)
    return {"id": rec["id"], "prompt": r.chat(sys_txt, usr, template), "completion": rec["target"],
            "messages": [{"role": "system", "content": sys_txt}, {"role": "user", "content": usr},
                         {"role": "assistant", "content": rec["target"]}]}


def rendered_context(ctx: dict) -> str:
    return render().render_context(ctx or {})
