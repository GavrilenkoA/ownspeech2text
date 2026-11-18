"""Numbers as spoken words: move the spoken numbers of ``tts_text`` into another rendering of the sentence.

``text`` writes numbers in digits ("через 20 минут", "99.9% uptime"); ``tts_text`` carries them as words,
declined as said ("через двадцать минут"). A TTS voice that reads Latin terms natively but cannot read
digits (Supertonic under ``lang=ru``: "80%" and "11:30" are dropped, measured 2026-11-19) needs a third
rendering: ``text`` with only its numbers taken from ``tts_text``. ``transfer_spoken_numbers`` builds it
by aligning the two token sequences; digit tokens it cannot align are spelled by ``spell_digits`` (no
declension: nominative cardinals, "%" read as "процентов"/"percent").

Stdlib only (plus the miner's number grammar).
"""
from __future__ import annotations

import re
from difflib import SequenceMatcher

from ml.roundtrip.mine.norm import spell_numeric_token

_EDGE = re.compile(r"^([\"«(\[]*)(.*?)([.,!?;:…\"»)\]]*)$")
_NUMERIC = re.compile(r"^[+\-]?[$€₽]?\d+(?:[.,:/\-]\d+)*%?$")
_HAS_DIGIT = re.compile(r"\d")


def _core(tok: str) -> tuple[str, str, str]:
    m = _EDGE.match(tok)
    assert m is not None
    return m.group(1), m.group(2), m.group(3)


def _key(tok: str) -> str:
    return _core(tok)[1].casefold().replace("ё", "е")


def is_number_token(tok: str) -> bool:
    """A whitespace token that is a written number ("20", "10:30", "99,9%", "2:00,")."""
    return bool(_NUMERIC.match(_core(tok)[1]))


def has_number_token(s: str) -> bool:
    return any(is_number_token(t) for t in s.split())


_LATIN = re.compile(r"[A-Za-z]")


def _part_of_name(prev: str, spoken_lang: str) -> bool:
    """Is a number after ``prev`` part of a product or model name? In Russian speech any Latin word before
    it ("RTX 5090"); in English a capitalised word ("Pixel 9", "Llama 4", not "at 9:30")."""
    core = _core(prev)[1]
    if spoken_lang == "ru":
        return bool(_LATIN.search(core))
    return bool(core) and core[0].isupper() and core.lower() not in _EN_PLAIN


_EN_PLAIN = {"i", "at", "by", "on", "in", "to", "from", "until", "before", "after", "about", "under", "over"}


def spell_digits(s: str, lang: str) -> str:
    """Replace every written-number token of ``s`` by nominative words; other tokens are kept.

    A number right after a Latin word is part of a product or model name ("RTX 5090", "Llama 4") and is
    left as written: its reading ("пятьдесят девяносто") is not the cardinal.
    """
    spoken_lang = "en" if lang == "en" else "ru"
    toks = s.split()
    out = []
    for k, tok in enumerate(toks):
        lead, core, trail = _core(tok)
        if not _NUMERIC.match(core) or (k and _part_of_name(toks[k - 1], spoken_lang)):
            out.append(tok)
            continue
        pct = core.endswith("%")
        num = core[:-1] if pct else core
        nxt = _core(toks[k + 1])[1] if k + 1 < len(toks) else ""
        # "2.4" is said "два точка четыре", "99,9" "девяносто девять и девять"
        words = spell_numeric_token(num, spoken_lang, nxt, variant=1 if "." in num and spoken_lang == "ru" else 0) or num
        if pct:
            words += " percent" if spoken_lang == "en" else " процентов"
        out.append(lead + words + trail)
    return " ".join(out)


def transfer_spoken_numbers(dst: str, spoken: str, lang: str) -> str:
    """``dst`` with each written number replaced by the words ``spoken`` uses at the same place.

    Alignment is by tokens (case and edge punctuation ignored). A replaced block must consist of number
    tokens only on the ``dst`` side and of digit-free tokens on the ``spoken`` side; any number left over
    is spelled by :func:`spell_digits`. Edge punctuation of ``dst`` tokens is kept.
    """
    a, b = dst.split(), spoken.split()
    sm = SequenceMatcher(None, [_key(t) for t in a], [_key(t) for t in b], autojunk=False)
    out: list[str] = []
    for op, i0, i1, j0, j1 in sm.get_opcodes():
        block = a[i0:i1]
        if op == "replace" and all(is_number_token(t) for t in block) \
                and not any(_HAS_DIGIT.search(t) for t in b[j0:j1]):
            words = [_core(t)[1] for t in b[j0:j1]]
            lead, _, _ = _core(block[0])
            _, _, trail = _core(block[-1])
            out.append(lead + " ".join(w for w in words if w) + trail)
        else:
            out.extend(block)
    return spell_digits(" ".join(out), lang)
