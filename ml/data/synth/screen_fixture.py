"""Layout-only stand-in for ml.screens.build_ctx, used ONLY while ml/screens/READY or ml/ocr/READY is missing
and in unit tests. It lays the spec out like a phone screen (status bar and keyboard excluded, app chrome lines,
line wrapping by device width and font scale, messages scrolled off the top when they do not fit) and returns
the visible lines with conf 1.0. There is no OCR and no recognition noise, so records built with it carry
meta.screen.backend = "fixture" and are not valid training data under CONTEXT_V2 (generate.py refuses to write a
full-size run with it unless --allow-fixture is given).
"""
from __future__ import annotations

VERSION = "fixture.v1"
_CHROME = {
    "ru-RU": {"chat": ["в сети"], "email": ["Входящие"], "notes": ["Заметки"], "browser": ["Поиск или адрес"],
              "terminal": ["~ $"], "generic": []},
    "en-US": {"chat": ["online"], "email": ["Inbox"], "notes": ["Notes"], "browser": ["Search or type URL"],
              "terminal": ["~ $"], "generic": []},
}
_SIZES = {"fold7_cover": (1080, 2520), "fold7_inner": (1968, 2184)}


def _wrap(text: str, width: int) -> list[str]:
    out, cur = [], ""
    for w in text.split():
        if cur and len(cur) + 1 + len(w) > width:
            out.append(cur)
            cur = w
        else:
            cur = (cur + " " + w).strip()
    if cur:
        out.append(cur)
    return out


def build_ctx(spec: dict) -> dict:
    W, H = _SIZES.get(spec.get("device"), (1080, 2520))
    fs = float(spec.get("font_scale") or 1.0)
    lh = int(46 * fs) + 18                       # line height in px
    cpl = max(12, int((W * 0.72) / (24 * fs)))    # characters per bubble line
    top = 110                                     # status bar (excluded)
    bottom = H - (int(H * 0.38) if spec.get("keyboard") else 0)
    lines: list[dict] = []

    def add(text, x, y, w_chars):
        lines.append({"text": text, "conf": 1.0, "box": [x, y, min(W - 20, x + int(w_chars * 24 * fs)), y + lh - 14]})

    y = top + 20
    locale = spec.get("locale") or "ru-RU"
    tmpl = spec.get("template") or "generic"
    if spec.get("title"):
        for t in _wrap(spec["title"], cpl)[:2]:
            add(t, 150, y, len(t))
            y += lh
    for c in _CHROME.get(locale, _CHROME["ru-RU"]).get(tmpl, []):
        add(c, 150, y, len(c))
        y += lh
    for s in spec.get("snippets") or []:
        for t in _wrap(s, cpl + 8)[:3]:
            add(t, 60, y, len(t))
            y += lh
    head_end = y
    # field at the bottom (above the keyboard), messages stacked upwards from it
    fld = spec.get("field")
    foot = []
    if fld is not None:
        txt = fld.get("before_cursor") or fld.get("hint") or ""
        foot = _wrap(txt, cpl + 6)[-3:] if txt else []
    y_field = bottom - lh * max(1, len(foot)) - 30
    blocks = []
    for m in spec.get("messages") or []:
        b = []
        if not m.get("is_me") and m.get("author") and m["author"] != spec.get("title"):
            b.append((m["author"], 90 if not m.get("is_me") else W // 3))
        x = W // 3 if m.get("is_me") else 90
        b += [(t, x) for t in _wrap(m["text"], cpl)]
        if m.get("time"):
            b.append((m["time"], x + int(cpl * 18 * fs)))
        blocks.append(b)
    msg_lines: list[tuple[str, int]] = []
    room = (y_field - head_end) // lh
    for b in reversed(blocks):                     # newest at the bottom; older ones scroll off the top
        if len(msg_lines) + len(b) > room:
            break
        msg_lines = b + msg_lines
    y = y_field - lh * len(msg_lines)
    for t, x in msg_lines:
        add(t, x, y, len(t))
        y += lh
    y = y_field
    for t in foot:
        add(t, 60, y, len(t))
        y += lh
    out = {"v": 2, "app": dict(spec.get("app") or {}), "screen": {"size": [W, H], "lines": lines}}
    if fld is not None:
        out["field"] = {k: v for k, v in fld.items() if k in ("kind", "hint", "before_cursor")}
        out["field"]["box"] = [40, y_field - 10, W - 40, y_field + lh * max(1, len(foot)) + 10]
    return out
