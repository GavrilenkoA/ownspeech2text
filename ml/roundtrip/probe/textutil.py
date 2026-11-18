"""Text helpers shared by the probe tooling. Stdlib only, no I/O."""
from __future__ import annotations

import re
import unicodedata

CYR_LETTER = r"А-Яа-яЁё"
LAT_LETTER = r"A-Za-z"

_CYR_RE = re.compile(f"[{CYR_LETTER}]")
_LAT_RE = re.compile(f"[{LAT_LETTER}]")
# A "word" for length bounds: a whitespace token that contains a letter or digit.
_ALNUM_RE = re.compile(f"[{CYR_LETTER}{LAT_LETTER}0-9]")
# Tokens for dedup / similarity: letters and digits, inner . _ - + # kept.
_TOKEN_RE = re.compile(f"[{CYR_LETTER}{LAT_LETTER}0-9]+(?:[._\\-+#/][{CYR_LETTER}{LAT_LETTER}0-9]+)*")
_WS_RE = re.compile(r"\s+")


def nfc(s: str) -> str:
    return unicodedata.normalize("NFC", s)


def has_cyrillic(s: str) -> bool:
    return bool(_CYR_RE.search(s))


def has_latin(s: str) -> bool:
    return bool(_LAT_RE.search(s))


def is_cyrillic_form(s: str) -> bool:
    """True when the string has Cyrillic letters and no Latin letters."""
    return has_cyrillic(s) and not has_latin(s)


def is_latin_form(s: str) -> bool:
    """True when the string has no Cyrillic letters (Latin, digits, symbols)."""
    return not has_cyrillic(s)


def word_count(s: str) -> int:
    """Number of whitespace-separated tokens that contain a letter or digit."""
    return sum(1 for tok in s.split() if _ALNUM_RE.search(tok))


def script_words(s: str) -> tuple[int, int]:
    """(words containing Cyrillic, words containing Latin) over whitespace tokens.

    A token with both scripts counts for both.
    """
    cyr = lat = 0
    for tok in s.split():
        if _CYR_RE.search(tok):
            cyr += 1
        if _LAT_RE.search(tok):
            lat += 1
    return cyr, lat


def detect_lang(s: str) -> str | None:
    """Probe-set language label implied by the script mix.

    ru     Cyrillic only (digits/symbols allowed, no Latin letter anywhere)
    en     no Cyrillic letter anywhere
    mixed  both scripts, Russian matrix: Cyrillic words >= Latin words
    None   both scripts with a Latin majority (not a valid probe sentence)
    """
    cyr, lat = script_words(s)
    if cyr and not lat:
        return "ru"
    if lat and not cyr:
        return "en"
    if cyr and lat:
        return "mixed" if cyr >= lat else None
    return None


def tokens(s: str) -> list[str]:
    return _TOKEN_RE.findall(s)


def norm_key(s: str) -> str:
    """Normalised form for exact-duplicate detection: NFKC, casefold, ё->е,
    punctuation dropped, whitespace collapsed."""
    s = unicodedata.normalize("NFKC", s).casefold().replace("ё", "е")
    return " ".join(tokens(s))


def norm_tokens(s: str) -> list[str]:
    return norm_key(s).split()


def collapse_ws(s: str) -> str:
    return _WS_RE.sub(" ", s).strip()
