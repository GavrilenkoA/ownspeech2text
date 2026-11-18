#!/usr/bin/env python3
"""Reference renderer for Murmur post-processing prompts (v1).

This file is the executable specification of the prompt layout. The Android
(Kotlin) implementation must produce byte-identical strings for the golden
cases in prompts/examples/ (see prompts/README.md). The synthetic-data and
eval pipelines import this module so that training, evaluation and the app
all see exactly the same text.

No third-party dependencies. Python >= 3.9.

Usage:
  python3 render.py CONTEXT.json TRANSCRIPT.txt [--template qwen3.5|lfm3|messages]
                    [--system default|finetuned] [--preferences PREFS.txt]
"""
from __future__ import annotations

import json
import re
import sys
import unicodedata
from pathlib import Path

HERE = Path(__file__).resolve().parent
PROMPT_VERSION = "v1"

# ---------------------------------------------------------------- budgets
# Character budgets (not tokens) so that every tokenizer sees the same text.
# Russian costs ~2.5-2.9 chars/token on Qwen3.5 / LFM3 tokenizers, English ~4.2
# (measured in docs/ml/CONTEXT_AND_PROMPT.md), so 1400 chars <= ~560 tokens.
MAX_CONTEXT_CHARS = 1400
MAX_BEFORE_CURSOR = 300
MAX_AFTER_CURSOR = 100
MAX_MESSAGES = 3
MAX_MESSAGE_CHARS = 160
MAX_TERMS = 40
MAX_TERMS_CHARS = 400
MAX_DICTIONARY = 20
MAX_SNIPPETS = 3
MAX_SNIPPET_CHARS = 120
MAX_TITLE_CHARS = 60

LANG_NAMES = {"ru": "Russian", "en": "English", "uk": "Ukrainian", "de": "German",
              "fr": "French", "es": "Spanish"}

FIELD_PHRASES = {
    "message": "message field",
    "email_body": "email body",
    "email_subject": "email subject",
    "search": "search field",
    "address": "address field",
    "note": "note",
    "code": "code or terminal input",
    "generic": "text field",
    "none": "no text field focused",
}


# ---------------------------------------------------------------- sanitising
_CTRL = re.compile(r"[\u0000-\u0008\u000b-\u001f\u007f-\u009f​-‏‪-‮⁦-⁩﻿]")
_WS = re.compile(r"[ \t ]+")


def clean(s: str | None, keep_newlines: bool = False) -> str:
    """Normalise untrusted text: NFC, strip control/bidi chars, neutralise
    angle brackets so screen text can never open or close our tags."""
    if not s:
        return ""
    s = unicodedata.normalize("NFC", str(s))
    s = _CTRL.sub("", s)
    s = s.replace("<", "‹").replace(">", "›")  # ‹ ›
    if keep_newlines:
        s = "\n".join(_WS.sub(" ", line).strip() for line in s.splitlines())
        s = re.sub(r"\n{3,}", "\n\n", s).strip()
    else:
        s = _WS.sub(" ", s.replace("\r", " ").replace("\n", " ")).strip()
    return s


def clip_end(s: str, n: int) -> str:
    """Keep the first n chars, cut at a word boundary, mark with an ellipsis."""
    if len(s) <= n:
        return s
    cut = s[:n]
    sp = cut.rfind(" ")
    if sp > n * 0.6:
        cut = cut[:sp]
    return cut.rstrip() + "…"


def clip_start(s: str, n: int) -> str:
    """Keep the last n chars (text right before the cursor matters most)."""
    if len(s) <= n:
        return s
    cut = s[-n:]
    sp = cut.find(" ")
    if 0 <= sp < n * 0.4:
        cut = cut[sp + 1:]
    return "…" + cut.lstrip()


# ---------------------------------------------------------------- context
def _context_lines(ctx: dict) -> list[tuple[int, str]]:
    """Return (drop_priority, line). Higher priority number = dropped first
    when the block is over budget. Priority 0 lines are never dropped."""
    out: list[tuple[int, str]] = []
    privacy = (ctx.get("privacy") or {}).get("level", "full")

    app = ctx.get("app") or {}
    field = ctx.get("field") or {}
    label = clean(app.get("label")) or "unknown app"
    kind = field.get("kind") or "generic"
    if privacy == "none":
        label = "unknown app"
    out.append((0, f"App: {label} — {FIELD_PHRASES.get(kind, 'text field')}"))

    lang = (ctx.get("language") or {}).get("asr")
    if lang:
        out.append((0, f"Dictation language: {LANG_NAMES.get(lang, lang)}"))

    if privacy in ("app_only", "none"):
        return out

    conv = ctx.get("conversation") or {}
    title = clip_end(clean(conv.get("title")), MAX_TITLE_CHARS)
    if title and privacy == "full":
        label = {"chat": "Conversation with", "email_thread": "Email thread", "document": "Document",
                 "page": "Page"}.get(conv.get("kind") or "chat", "Conversation with")
        out.append((1, f"{label}: {title}"))

    if privacy == "full":
        msgs = [m for m in (conv.get("messages") or []) if clean(m.get("text"))][-MAX_MESSAGES:]
        if msgs:
            out.append((3, "Recent messages (oldest first):"))
            for i, m in enumerate(msgs):
                who = "Me" if m.get("is_me") else (clip_end(clean(m.get("author")), 40) or "Them")
                # older messages get a higher drop priority
                out.append((3 + (len(msgs) - 1 - i), f"- {who}: {clip_end(clean(m.get('text')), MAX_MESSAGE_CHARS)}"))

        before = clip_start(clean(field.get("before_cursor"), keep_newlines=False), MAX_BEFORE_CURSOR)
        after = clip_end(clean(field.get("after_cursor"), keep_newlines=False), MAX_AFTER_CURSOR)
        if before:
            out.append((2, f"Text already in the field before the cursor: «{before}»"))
        if after:
            out.append((5, f"Text in the field after the cursor: «{after}»"))

    user = ctx.get("user") or {}
    dic = [clean(t) for t in (user.get("dictionary") or []) if clean(t)][:MAX_DICTIONARY]
    if dic:
        out.append((1, "User's dictionary: " + "; ".join(dic)))

    terms, seen, used = [], {t.casefold() for t in dic}, 0
    for t in sorted(ctx.get("terms") or [], key=lambda x: -float(x.get("score", 0))):
        txt = clip_end(clean(t.get("text")), 60)
        if not txt or txt.casefold() in seen:
            continue
        if len(terms) >= MAX_TERMS or used + len(txt) + 2 > MAX_TERMS_CHARS:
            break
        seen.add(txt.casefold())
        terms.append(txt)
        used += len(txt) + 2
    if terms:
        out.append((2, "Names and terms on screen: " + "; ".join(terms)))

    if privacy == "full":
        snips = [clip_end(clean(s), MAX_SNIPPET_CHARS) for s in (ctx.get("screen_snippets") or [])]
        snips = [s for s in snips if s][:MAX_SNIPPETS]
        if snips:
            out.append((6, "Other text on screen: " + " | ".join(snips)))
    return out


def render_context(ctx: dict | None) -> str:
    lines = _context_lines(ctx or {})
    # Drop lowest-value lines until the block fits the budget.
    while sum(len(l) + 1 for _, l in lines) > MAX_CONTEXT_CHARS:
        worst = max(range(len(lines)), key=lambda i: (lines[i][0], i))
        if lines[worst][0] == 0:
            break
        lines.pop(worst)
    # A "Recent messages" header without messages is useless.
    body = [l for _, l in lines]
    if "Recent messages (oldest first):" in body:
        i = body.index("Recent messages (oldest first):")
        if i + 1 >= len(body) or not body[i + 1].startswith("- "):
            body.pop(i)
    return "<context>\n" + "\n".join(body) + "\n</context>"


# ---------------------------------------------------------------- prompt
def load_tones() -> dict:
    return json.loads((HERE / "tones" / "tones.v1.json").read_text(encoding="utf-8"))


def style_line(ctx: dict | None, tones: dict | None = None, override: str | None = None) -> str:
    if override:
        return "Style: " + clean(override)
    tones = tones or load_tones()
    cat = ((ctx or {}).get("app") or {}).get("category") or "default"
    variation = ((ctx or {}).get("field") or {}).get("input_type_variation")
    cat = tones.get("field_overrides", {}).get(variation, cat) if variation else cat
    entry = tones["categories"].get(cat) or tones["categories"]["default"]
    return "Style: " + entry["text"]


def system_prompt(kind: str = "default", preferences: str | None = None) -> str:
    base = (HERE / "system" / f"{kind}.{PROMPT_VERSION}.md").read_text(encoding="utf-8").rstrip("\n")
    prefs = clean(preferences, keep_newlines=True) if preferences else ""
    if prefs:
        block = (HERE / "system" / f"preferences_block.{PROMPT_VERSION}.md").read_text(encoding="utf-8").rstrip("\n")
        base += block.replace("{{PREFERENCES}}", prefs[:1000])
    return base


def user_message(ctx: dict | None, transcript: str, style_override: str | None = None) -> str:
    return (style_line(ctx, override=style_override) + "\n"
            + render_context(ctx) + "\n"
            + "<transcript>\n" + clean(transcript) + "\n</transcript>")


def chat(system: str, user: str, template: str = "qwen3.5") -> str:
    """Raw prompt strings, thinking disabled. Verified against the official
    chat_template.jinja of Qwen/Qwen3.5-0.8B and LiquidAI/LFM3-1.7B
    (enable_thinking=False, add_generation_prompt=True) on 2026-11-18."""
    if template == "qwen3.5":
        return (f"<|im_start|>system\n{system}<|im_end|>\n<|im_start|>user\n{user}<|im_end|>\n"
                f"<|im_start|>assistant\n<think>\n\n</think>\n\n")
    if template == "lfm3":
        return (f"<|startoftext|><|im_start|>system\n{system}<|im_end|>\n<|im_start|>user\n{user}<|im_end|>\n"
                f"<|im_start|>assistant\n<think></think>")
    if template == "messages":
        return json.dumps([{"role": "system", "content": system}, {"role": "user", "content": user}],
                          ensure_ascii=False, indent=2)
    raise ValueError(template)


def regen_goldens() -> None:
    """Rewrite prompts/examples/golden_*.txt from golden_*.input.json."""
    for inp in sorted((HERE / "examples").glob("golden_*.input.json")):
        g = json.loads(inp.read_text(encoding="utf-8"))
        stem = inp.name[: -len(".input.json")]
        for kind in ("default", "finetuned"):
            for tpl in ("qwen3.5", "lfm3"):
                text = chat(system_prompt(kind, g.get("preferences")), user_message(g["context"], g["transcript"]), tpl)
                (HERE / "examples" / f"{stem}.{tpl}.{kind}.txt").write_text(text, encoding="utf-8")


def main(argv: list[str]) -> int:
    import argparse
    if argv[:1] == ["--regen-goldens"]:
        regen_goldens()
        return 0
    ap = argparse.ArgumentParser()
    ap.add_argument("context")
    ap.add_argument("transcript")
    ap.add_argument("--template", default="qwen3.5")
    ap.add_argument("--system", default="default")
    ap.add_argument("--preferences")
    a = ap.parse_args(argv)
    ctx = json.loads(Path(a.context).read_text(encoding="utf-8"))
    tr = Path(a.transcript).read_text(encoding="utf-8")
    prefs = Path(a.preferences).read_text(encoding="utf-8") if a.preferences else None
    sys.stdout.write(chat(system_prompt(a.system, prefs), user_message(ctx, tr), a.template))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
