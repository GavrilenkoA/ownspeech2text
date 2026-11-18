#!/usr/bin/env python3
"""Stand-in for ml.screens.build_ctx (render spec -> ctx v2) until ml/screens/READY exists.

NOT the real pipeline: no image is rendered and no OCR model runs. It lays the spec out the way the real
OCR output of a phone screenshot looks (measured on .cache/spikes/asr-ocr/ocr/pages/gt.json: one line per
wrapped text line, ~17.5 px per character and 52 px per line on the 1080 px cover screen, bubble text
wrapped at ~38 characters, clock lines under every bubble, own messages right-aligned, a header with the
chat name and a status line, the field hint at the bottom) and adds the noise the real pipeline leaves:
icon glyphs read as letters with low confidence, rare character confusions and Latin/Cyrillic homoglyphs
(residual after repair). Status bar and keyboard are excluded, as the real pipeline does with the
render's exclude_rects. Strings in spec["protect"] are never corrupted (CONTEXT_V2: a case whose term OCR
broke is re-rendered or dropped). Deterministic in spec["seed"].

Spec = the CONTEXT_V2 render spec plus "app" (package/label/category), "field.kind", "language",
"privacy", "protect" (fixture-only), "subtitle", "screen": false (no screenshot).
"""
from __future__ import annotations

import hashlib
import random

DEVICES = {"fold7_cover": (1080, 2520), "fold7_inner": (2184, 1968)}
CHAR_W, LINE_H, LINE_GAP = 17.5, 48, 4
HINTS = {"message": {"ru": "Сообщение", "en": "Message"}, "email_body": {"ru": "Текст письма", "en": "Compose email"},
         "search": {"ru": "Поиск или URL", "en": "Search or type URL"}, "note": {"ru": "", "en": ""},
         "code": {"ru": "", "en": ""}, "generic": {"ru": "", "en": ""}}
STATUS = {"ru": ["был(а) недавно", "в сети", "печатает…"], "en": ["last seen recently", "online", "typing…"]}
JUNK = ["Q", "©", "@", "Il", "ДО", "<", "IIl", "∞", "Ⓜ", "8"]
CONFUSE = {"ш": "щ", "щ": "ш", "й": "и", "ё": "е", "l": "I", "I": "l", "O": "0", "rn": "m", "з": "э", "ы": "ь1"}
HOMO = {"a": "а", "c": "с", "e": "е", "o": "о", "p": "р", "x": "х", "y": "у"}


def _rng(seed) -> random.Random:
    return random.Random(int(hashlib.sha256(str(seed).encode()).hexdigest()[:12], 16))


def wrap(text: str, width: int) -> list[str]:
    out, cur = [], ""
    for w in text.split():
        if cur and len(cur) + 1 + len(w) > width:
            out.append(cur)
            cur = w
        else:
            cur = (cur + " " + w) if cur else w
    if cur:
        out.append(cur)
    return out


def _noise(text: str, r: random.Random, protect: list[str], cyr_ctx: bool) -> str:
    spans = []
    low = text.casefold()
    for p in protect:
        i = low.find(p.casefold())
        while p and i >= 0:
            spans.append((i, i + len(p)))
            i = low.find(p.casefold(), i + 1)
    words, pos, out = text.split(" "), 0, []
    for w in words:
        a, b = pos, pos + len(w)
        pos = b + 1
        if any(a < e and s < b for s, e in spans) or len(w) < 4:
            out.append(w)
            continue
        x = r.random()
        if x < 0.025:
            k = next((k for k in CONFUSE if k in w), None)
            if k:
                w = w.replace(k, CONFUSE[k], 1)
        elif x < 0.035:
            j = r.randrange(1, len(w) - 1)
            w = w[:j] + w[j + 1:]
        elif cyr_ctx and x < 0.08 and any("a" <= ch <= "z" for ch in w):
            j = next((j for j, ch in enumerate(w) if ch in HOMO), None)
            if j is not None:
                w = w[:j] + HOMO[w[j]] + w[j + 1:]
        out.append(w)
    return " ".join(out)


class _Layout:
    def __init__(self, spec: dict):
        self.w, self.h = DEVICES[spec.get("device", "fold7_cover")]
        fs = float(spec.get("font_scale", 1.0))
        self.cw, self.lh = CHAR_W * fs, int(LINE_H * fs)
        self.lines: list[dict] = []
        self.r = _rng(spec.get("seed", 0))
        self.protect = list(spec.get("protect") or [])
        self.cyr = (spec.get("locale") or "ru") == "ru"

    def add(self, text: str, x0: int, y: int, conf: float | None = None, noisy: bool = True, scale: float = 1.0) -> int:
        t = _noise(text, self.r, self.protect, self.cyr) if noisy else text
        lh = int(self.lh * scale)
        x1 = min(self.w - 8, int(x0 + len(text) * self.cw * scale))
        c = conf if conf is not None else round(0.86 + 0.13 * self.r.random(), 3)
        self.lines.append({"text": t, "conf": c, "box": [int(x0), int(y), x1, int(y + lh)]})
        return y + lh + LINE_GAP

    def block(self, text: str, x0: int, y: int, width_chars: int) -> int:
        for part in wrap(text, width_chars):
            y = self.add(part, x0, y)
        return y


def build_ctx(spec: dict) -> dict:
    """spec -> ctx v2 (same contract as ml.screens.build_ctx)."""
    loc = spec.get("locale") or "ru"
    f = spec.get("field") or {}
    ctx: dict = {"v": 2, "app": dict(spec["app"])}
    if f.get("kind"):
        field = {"kind": f["kind"]}
        hint = f.get("hint", HINTS.get(f["kind"], {}).get(loc, ""))
        if hint:
            field["hint"] = hint
        if f.get("before_cursor"):
            field["before_cursor"] = f["before_cursor"]
        ctx["field"] = field
    L = _Layout(spec)
    W, H = L.w, L.h
    tpl = spec.get("template", "chat")
    kb_top = int(H * 0.58) if spec.get("keyboard", True) and W < 2000 else int(H * 0.62) if spec.get("keyboard", True) else H
    # field geometry: chat/mail/notes at the bottom above the keyboard; browser at the top
    if tpl == "browser":
        fbox = [40, 150, W - 40, 150 + 110]
    elif tpl == "terminal":
        fbox = [0, kb_top - 70, W, kb_top - 8]
    else:
        fbox = [40, kb_top - 130, W - 150, kb_top - 20]
    y = 100
    if tpl in ("chat", "mail", "notes", "feed"):
        if spec.get("title"):
            y = L.add(spec["title"], 178 if tpl == "chat" else 52, y, scale=1.1)
        if tpl == "chat" and spec.get("subtitle", True):
            y = L.add(spec["subtitle"] if isinstance(spec.get("subtitle"), str) else L.r.choice(STATUS[loc]), 178, y + 8,
                      scale=0.8)
        if L.r.random() < 0.6:
            L.add(L.r.choice(JUNK), W - 110, 108, conf=round(0.3 + 0.25 * L.r.random(), 3), noisy=False)
        y += 60
    elif tpl == "browser":
        y = fbox[3] + 60
    for s in spec.get("snippets") or []:
        y = L.block(s, 52, y, int((W - 104) / L.cw)) + 40
    if tpl == "chat" and spec.get("messages"):
        y = L.add("Сегодня" if loc == "ru" else "Today", W // 2 - 60, y + 10, noisy=False) + 30
    # messages: lay out all, then scroll so the last one sits right above the field (top ones clipped)
    msg_lines: list[tuple[str, int, int]] = []  # (text, x0, rel_y); "\x00" = clock line, "\x01" = author line
    ry = 0
    bw = int(W * 0.72 / L.cw)
    group = tpl == "chat" and len({m.get("author") for m in spec.get("messages") or [] if not m.get("is_me")}) > 1
    for m in spec.get("messages") or []:
        parts = wrap(m.get("text", ""), bw)
        width = int(max((len(p) for p in parts), default=1) * L.cw)
        x0 = W - 70 - width if m.get("is_me") and tpl == "chat" else 52
        if (group or tpl == "mail") and not m.get("is_me") and m.get("author"):
            msg_lines.append(("\x01" + m["author"], x0, ry))
            ry += int(L.lh * 0.85) + LINE_GAP
        for p in parts:
            msg_lines.append((p, x0, ry))
            ry += L.lh + LINE_GAP
        if m.get("time"):
            msg_lines.append(("\x00" + m["time"], x0 + width - 72, ry))
            ry += 36
        ry += 60
    top_limit, bottom = y, fbox[1] - 30
    shift = bottom - ry if y + ry > bottom else y
    for text, x0, rel in msg_lines:
        yy = rel + shift
        if yy < top_limit:
            continue  # scrolled off
        if text.startswith("\x00"):
            L.add(text[1:], x0, yy, noisy=False, scale=0.7)
        elif text.startswith("\x01"):
            L.add(text[1:], x0, yy, scale=0.85)
        else:
            L.add(text, x0, yy)
    # the field itself: hint or the text already typed
    ftxt = f.get("before_cursor") or ctx.get("field", {}).get("hint") or ""
    if tpl == "terminal":
        ftxt = "~ $ " + (f.get("before_cursor") or "")
    if ftxt:
        L.add(ftxt[-int((fbox[2] - fbox[0]) / L.cw):], fbox[0] + 20, fbox[1] + 20, noisy=False)
    if "field" in ctx:
        ctx["field"]["box"] = fbox
    if spec.get("screen", True) is not False:
        lines = sorted(L.lines, key=lambda d: (d["box"][1], d["box"][0]))
        if lines:
            ctx["screen"] = {"size": [W, H], "lines": lines}
    ctx["language"] = dict(spec.get("language") or {"asr": loc})
    ctx["privacy"] = dict(spec.get("privacy") or {"level": "full", "reason": None})
    return ctx


if __name__ == "__main__":
    import json
    import sys
    print(json.dumps(build_ctx(json.load(open(sys.argv[1], encoding="utf-8"))), ensure_ascii=False, indent=1))
