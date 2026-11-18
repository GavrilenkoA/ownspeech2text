"""Build ``tts_text``: a pronunciation-friendly variant of a probe sentence.

mixed  every Latin-script lexicon term is replaced by a ``ru_spoken`` reading
       that covers all its words (``pick_reading``), Latin person names by a supplied Cyrillic spelling, so a
       Russian-only voice reads the sentence the way an engineer says it.
en     terms whose English reading is not their spelling ("k8s", "Qdrant",
       "nginx") are replaced by an ``en_spoken`` reading (same rule).
ru     nothing to do (already Cyrillic) -> None.
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from difflib import SequenceMatcher

from ml.roundtrip.probe.lexicon import TermMatcher
from ml.roundtrip.probe.textutil import collapse_ws, has_cyrillic, has_latin

_LAT_TOKEN_RE = re.compile(r"[A-Za-z][A-Za-z0-9.+#_\-/]*")
_ALNUM = re.compile(r"[^a-z0-9]")

# Rough Latin -> Cyrillic reading for leftovers (used only as a last resort).
_DIGRAPHS = (
    ("you", "ю"), ("sch", "ш"), ("tch", "ч"), ("sh", "ш"), ("ch", "ч"), ("zh", "ж"), ("th", "т"),
    ("ph", "ф"), ("kh", "х"), ("ck", "к"), ("oo", "у"), ("ee", "и"), ("ea", "и"),
    ("ou", "ау"), ("qu", "кв"), ("ya", "я"), ("yu", "ю"), ("ts", "ц"),
)
_LETTERS = {
    "a": "а", "b": "б", "c": "к", "d": "д", "e": "е", "f": "ф", "g": "г", "h": "х",
    "i": "и", "j": "дж", "k": "к", "l": "л", "m": "м", "n": "н", "o": "о", "p": "п",
    "q": "к", "r": "р", "s": "с", "t": "т", "u": "у", "v": "в", "w": "в", "x": "кс",
    "y": "и", "z": "з",
}


@dataclass(frozen=True)
class Respelling:
    tts_text: str | None
    leftovers: tuple[str, ...]  # Latin tokens nothing could respell (mixed only)


# How Latin letters are named when an acronym is spelled out in Russian speech.
_LETTER_NAMES = {
    "A": "эй", "B": "би", "C": "си", "D": "ди", "E": "и", "F": "эф", "G": "джи", "H": "эйч",
    "I": "ай", "J": "джей", "K": "кей", "L": "эл", "M": "эм", "N": "эн", "O": "оу", "P": "пи",
    "Q": "кью", "R": "ар", "S": "эс", "T": "ти", "U": "ю", "V": "ви", "W": "дабл ю", "X": "икс",
    "Y": "уай", "Z": "зед",
}
_ACRONYM_RE = re.compile(r"\b[A-Z][A-Za-z]?[A-Z]+\b|\b[A-Z]{2,}\b")


def spell_acronyms(s: str) -> str:
    """Spell short upper-case tokens letter by letter: "HF" -> "эйч эф", "CoT" -> "си оу ти"."""
    def one(m: re.Match[str]) -> str:
        tok = m.group(0)
        if len(tok) > 4:
            return tok
        return " ".join(_LETTER_NAMES.get(ch.upper(), ch) for ch in tok)
    return _ACRONYM_RE.sub(one, s)


def translit_latin(word: str) -> str:
    """Very rough phonetic transliteration of a Latin token into Cyrillic."""
    w = word.lower()
    out: list[str] = []
    i = 0
    while i < len(w):
        for src, dst in _DIGRAPHS:
            if w.startswith(src, i):
                out.append(dst)
                i += len(src)
                break
        else:
            ch = w[i]
            out.append(_LETTERS.get(ch, ch if not ch.isascii() or ch.isdigit() else " "))
            i += 1
    return collapse_ws("".join(out))


_PIECE_RE = re.compile(r"[A-Za-z]+|[0-9]+")
SIMILARITY_SLACK = 0.1
MIN_SIMILARITY = 0.3  # below this nothing sounds like the surface: choose by length
_CYR_TAIL_RE = re.compile(r"[-\s]([А-Яа-яЁё]+)$")


def pick_reading(readings: tuple[str, ...], surface: str, canonical: str | None = None) -> str:
    """The reading to speak for a matched surface, so TTS neither drops nor adds words.

    ``need`` is the number of Latin/digit pieces in the surface; a reading
    "covers" the surface when it has at least ``need`` words. For the canonical
    spelling the author's first covering reading wins ("Parakeet TDT" ->
    "паракит ти ди ти", not "паракит"). An alternate form ("Fold7" of
    "Galaxy Z Fold7", "Material You" of "Material 3") takes the covering
    reading that sounds most like the surface (character similarity of the
    letters to a rough transliteration, acronyms spelled out); readings within
    ``SIMILARITY_SLACK`` of the best count as tied and the one whose word count
    is closest to ``need`` wins, then the author's order. Falls back to the
    longest reading when none covers the surface.
    """
    need = len(_PIECE_RE.findall(surface))
    covering = [r for r in readings if len(r.split()) >= need]
    if not covering:
        return max(readings, key=lambda r: len(r.split()))
    if canonical is not None and surface.casefold() == canonical.casefold():
        return covering[0]
    cyrillic = any(has_cyrillic(r) for r in covering)
    probes = {_sound_key(surface, cyrillic)}
    if cyrillic:
        probes.add(_sound_key(spell_acronyms(surface), cyrillic))

    def score(r: str) -> float:
        key = _sound_key(r, False)
        return max(SequenceMatcher(None, p, key).ratio() if p or key else 1.0 for p in probes)

    scores = [score(r) for r in covering]
    best = max(scores)
    floor = best - SIMILARITY_SLACK if best >= MIN_SIMILARITY else -1.0
    tied = [(abs(len(r.split()) - need), i, r) for i, (r, sc) in enumerate(zip(covering, scores)) if sc >= floor]
    return min(tied)[2]


_NUMBER_WORD_RE = re.compile(
    r"^(ноль|нол|один|одна|два|две|три|четыр|пят|шест|сем|восем|девят|десят|сорок|сто|сот|двест|"
    r"тысяч|миллион|полтор|zero|one|two|three|four|five|six|seven|eight|nine|ten|twenty|thirty|forty|"
    r"fifty|sixty|seventy|eighty|ninety|hundred|thousand)")


def _sound_key(s: str, cyrillic: bool) -> str:
    """Letters only: digits and number words are dropped, since readings spell
    numbers out ("5090" vs "пятьдесят девяносто") and that is not a signal."""
    s = translit_latin(s) if cyrillic else s.lower()
    words = re.findall(r"[a-zа-яё]+", s.lower())
    return " ".join(w for w in words if not _NUMBER_WORD_RE.match(w))


def keep_inflection(reading: str, surface: str) -> str:
    """Carry a Russian case ending of the surface into the reading:
    "A/B-теста" read "аб тест" becomes "аб теста"."""
    m = _CYR_TAIL_RE.search(surface)
    if not m:
        return reading
    words = reading.split()
    tail = m.group(1)
    if words and tail.lower() != words[-1].lower() and tail.lower().startswith(words[-1].lower()):
        words[-1] = tail.lower()
    return " ".join(words)


def _cap_like(original_start: bool, s: str) -> str:
    return s[:1].upper() + s[1:] if original_start and s else s


def respell(text: str, lang: str, matcher: TermMatcher, persons: dict[str, str] | None = None,
            translit_leftovers: bool = False) -> Respelling:
    persons = persons or {}
    if lang == "ru":
        return Respelling(None, ())
    pieces: list[str] = []
    pos = 0
    changed = False
    for m in matcher.find(text):
        if lang == "mixed":
            if not has_latin(m.surface):
                continue
            repl = keep_inflection(pick_reading(m.entry.ru_spoken, m.surface, m.entry.term), m.surface)
        else:
            if not m.entry.en_spoken:
                continue
            repl = pick_reading(m.entry.en_spoken, m.surface, m.entry.term)
            if _ALNUM.sub("", repl.lower()) == _ALNUM.sub("", m.surface.lower()):
                continue
        at_start = text[: m.start].strip() == ""
        pieces.append(text[pos: m.start])
        pieces.append(_cap_like(at_start, repl))
        pos = m.end
        changed = True
    pieces.append(text[pos:])
    out = "".join(pieces)
    for latin, cyr in sorted(persons.items(), key=lambda kv: -len(kv[0])):
        if latin in out:
            out = re.sub(rf"(?<![\w]){re.escape(latin)}(?![\w])", cyr, out)
            changed = True
    leftovers: tuple[str, ...] = ()
    if lang == "mixed":
        left = tuple(_LAT_TOKEN_RE.findall(out))
        if left and translit_leftovers:
            out = _LAT_TOKEN_RE.sub(lambda mm: translit_latin(mm.group(0)), out)
            changed = True
            left = ()
        leftovers = left
    if lang == "en" and has_cyrillic(out):
        leftovers = ("<cyrillic>",)
    out = collapse_ws(out)
    return Respelling(out if changed and out != text else None, leftovers)
