"""Number style of probe ``text``: digits where the app's default prompt asks for digits.

``prompts/system/default.v5.md`` rule 4 tells the cleanup LLM to write digits for times, dates, money,
percentages, measurements, versions, phone numbers and numbers above ten. The probe ``text`` is the
intended final text and becomes the stage-C target, so it must follow the same rule; the spoken words
belong in ``tts_text`` (seed source: ``<20=двадцать>``).

Flagged in ``text``:
  - a run of number words whose value is above ten ("двадцать минут", "three hundred examples");
    a bare scale word on its own ("на миллион сообщений", "a thousand times") is allowed;
  - a number word followed by a percent word ("семь процентов", "eighty percent");
  - a number word followed by a unit of data size, frequency, bits or milliseconds ("сорок мегабайт").

Small counts stay words ("два бага", "three times"). Clock times said as small numbers ("at nine") are
not detectable without context and are left to review.
"""
from __future__ import annotations

import re

from ml.roundtrip.mine.norm import is_number_word, parse_number_words, tokenize

_PERCENT_RE = re.compile(r"^(процент\w*|percent|per-cent)$")
_UNIT_RE = re.compile(
    r"^((кило|мега|гига|тера)?байт\w*|бит|бита|битах|битами|миллисекунд\w*|(кило|мега|гига)?герц\w*|"
    r"(kilo|mega|giga|tera)?bytes?|bits?|milliseconds?|(kilo|mega|giga)?hertz|[kmgt]b|ms|khz)$")
_SCALES = {1000, 10 ** 6, 10 ** 9}
_MARKUP_CHARS = "`"
_CURLY = "‘’‚‛′"


def number_style_problems(text: str) -> list[str]:
    """Problems with how numbers are written in ``text`` (empty list = follows rule 4)."""
    toks = [t.core for t in tokenize(text)]
    out: list[str] = []
    i = 0
    while i < len(toks):
        if not is_number_word(toks[i]) or _PERCENT_RE.match(toks[i].lower()):
            i += 1
            continue
        j = i
        while j < len(toks) and is_number_word(toks[j]) and not _PERCENT_RE.match(toks[j].lower()):
            j += 1
        run = toks[i:j]
        values = parse_number_words(run) or []
        nxt = toks[j].lower() if j < len(toks) else ""
        phrase = " ".join(run)
        if _PERCENT_RE.match(nxt):
            out.append(f"percentage in words {phrase + ' ' + toks[j]!r}: write digits and % (default.v5 rule 4)")
        elif _UNIT_RE.match(nxt):
            out.append(f"measurement in words {phrase + ' ' + toks[j]!r}: write digits (default.v5 rule 4)")
        elif any(v > 10 for v in values) and not (len(run) == 1 and values and values[0] in _SCALES):
            out.append(f"number above ten in words {phrase!r}: write digits (default.v5 rule 4)")
        i = j
    return out


def markup_problems(s: str) -> list[str]:
    """Characters dictated text never contains: Markdown backticks and typographic apostrophes."""
    out = []
    if any(c in s for c in _MARKUP_CHARS):
        out.append("contains Markdown backticks")
    if any(c in s for c in _CURLY):
        out.append("contains a typographic apostrophe (use ')")
    return out


def normalise_apostrophes(s: str) -> str:
    return "".join("'" if c in _CURLY else c for c in s)
