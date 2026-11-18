"""Latin <-> Cyrillic transliteration and a script-independent phonetic key.

The key lets the aligner see that "Kubernetes" and "кубернетис", "ClickHouse" and "клик хаус",
"Jira" and "джира" are the same term mangled, not unrelated words:

  phonetic_key("Kubernetes") == phonetic_key("кубернетис") == "KaPaRNaTaS"

Rules: Cyrillic is romanised; English digraphs are reduced (ph->f, th->t, ck->k, dzh/dj/j->J, sh/sch/zh->Ш ...);
voicing pairs are merged (b/p, d/t, g/k/c/q, v/f/w, z/s); every vowel run becomes "a"; doubles collapse.
`lat2cyr` is the reverse direction used by the noise sampler: an English-reading transliteration
("ClickHouse" -> "кликхаус", "Jira" -> "джира") plus spelled-acronym readings ("CI/CD" -> "си ай си ди").
"""
from __future__ import annotations

import json
import re
from functools import lru_cache
from pathlib import Path
from typing import Sequence

from .norm import fold

_CYR2LAT = {
    "а": "a", "б": "b", "в": "v", "г": "g", "д": "d", "е": "e", "ё": "e", "ж": "zh", "з": "z", "и": "i",
    "й": "y", "к": "k", "л": "l", "м": "m", "н": "n", "о": "o", "п": "p", "р": "r", "с": "s", "т": "t",
    "у": "u", "ф": "f", "х": "h", "ц": "ts", "ч": "ch", "ш": "sh", "щ": "sch", "ъ": "", "ы": "y", "ь": "",
    "э": "e", "ю": "yu", "я": "ya", "і": "i", "ї": "yi", "є": "ye", "ґ": "g",
}


def cyr2lat(s: str) -> str:
    return "".join(_CYR2LAT.get(c, c) for c in fold(s))


# ordered digraph reductions applied to romanised lowercase text; capital letters are key classes
_DIGRAPHS: list[tuple[str, str]] = [
    ("tion", "Шan"), ("ssue", "Шu"), ("sch", "Ш"), ("tch", "Ч"), ("dzh", "J"), ("dj", "J"), ("zh", "Ш"), ("sh", "Ш"),
    ("ch", "Ч"), ("ph", "F"), ("th", "T"), ("ck", "K"), ("gh", "G"), ("qu", "KF"), ("ts", "S"),
    ("tz", "S"), ("x", "KS"), ("ge", "Je"), ("gi", "Ji"), ("j", "J"),
]
_SINGLE = {
    "b": "P", "p": "P", "d": "T", "t": "T", "g": "K", "k": "K", "q": "K", "c": "K", "v": "F", "f": "F",
    "w": "F", "z": "S", "s": "S", "h": "H", "l": "L", "r": "R", "m": "M", "n": "N",
}
_VOWELS = set("aeiouy")


@lru_cache(maxsize=65536)
def phonetic_key(s: str) -> str:
    """Script-independent consonant skeleton; vowel runs -> 'a'. Digits are kept as is."""
    t = cyr2lat(s)
    t = "".join(c for c in t if c.isalnum())
    # soft c before e/i/y reads as s in English ("Celery", "CI")
    t = re.sub(r"c(?=[eiy])", "s", t)
    t = re.sub(r"w(?![aeiouy])", "u", t)  # "flow", "Airflow": w closing a syllable is a vowel
    for a, b in _DIGRAPHS:
        t = t.replace(a, b)
    out: list[str] = []
    for c in t:
        if c in _VOWELS:
            k = "a"
        elif c.isupper() or c in "ШЧ":
            k = c
        elif c in _SINGLE:
            k = _SINGLE[c]
        elif c.isdigit():
            k = c
        else:
            continue
        if out and out[-1] == k:
            continue
        out.append(k)
    # edge vowels are unstable across languages (silent English "e", Russian case endings,
    # the "э" Russian speakers put before a consonant cluster), so they do not count
    if len(out) > 3 and out[-1] == "a":
        out.pop()
    if len(out) > 4 and out[0] == "a":
        out.pop(0)
    return "".join(out)


def levenshtein(a: str, b: str) -> int:
    if a == b:
        return 0
    if len(a) < len(b):
        a, b = b, a
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def ratio(a: str, b: str) -> float:
    """1 - normalised edit distance, in [0, 1]."""
    if not a and not b:
        return 1.0
    return 1.0 - levenshtein(a, b) / max(len(a), len(b))


def char_sim(a: str, b: str) -> float:
    """Spelling similarity in the same script (after fold, ignoring spaces and separators)."""
    fa = "".join(c for c in fold(a) if c.isalnum())
    fb = "".join(c for c in fold(b) if c.isalnum())
    return ratio(fa, fb)


@lru_cache(maxsize=65536)
def phon_sim(a: str, b: str) -> float:
    """Phonetic-key similarity across scripts. Very short keys must match exactly."""
    ka, kb = phonetic_key(a), phonetic_key(b)
    if not ka or not kb:
        return 0.0
    if min(len(ka), len(kb)) <= 2:
        return 1.0 if ka == kb else 0.0
    return ratio(ka, kb)


# ----------------------------------------------------------------------------- acronyms

_EN_LETTERS_CYR = {
    "a": "эй", "b": "би", "c": "си", "d": "ди", "e": "и", "f": "эф", "g": "джи", "h": "эйч", "i": "ай",
    "j": "джей", "k": "кей", "l": "эл", "m": "эм", "n": "эн", "o": "оу", "p": "пи", "q": "кью", "r": "ар",
    "s": "эс", "t": "ти", "u": "ю", "v": "ви", "w": "дабл ю", "x": "экс", "y": "уай", "z": "зет",
}
_RU_LETTERS_CYR = {
    "a": "а", "b": "бэ", "c": "цэ", "d": "дэ", "e": "е", "f": "эф", "g": "гэ", "h": "аш", "i": "и",
    "j": "жи", "k": "ка", "l": "эль", "m": "эм", "n": "эн", "o": "о", "p": "пэ", "q": "ку", "r": "эр",
    "s": "эс", "t": "тэ", "u": "у", "v": "вэ", "w": "дабл ю", "x": "икс", "y": "игрек", "z": "зэт",
}
_EN_LETTERS_LAT = {
    "a": "ay", "b": "bee", "c": "see", "d": "dee", "e": "ee", "f": "ef", "g": "gee", "h": "aitch", "i": "eye",
    "j": "jay", "k": "kay", "l": "el", "m": "em", "n": "en", "o": "oh", "p": "pee", "q": "cue", "r": "are",
    "s": "es", "t": "tee", "u": "you", "v": "vee", "w": "double you", "x": "ex", "y": "why", "z": "zee",
}
_DIGITS_RU = ["ноль", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять"]
_DIGITS_EN = ["zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine"]


def is_acronym(term: str) -> bool:
    """CI/CD, API, GPT-4o, AWS, LLM, k8s-style tokens that are usually spelled letter by letter."""
    letters = [c for c in term if c.isalpha()]
    if not letters or len(letters) > 7:
        return False
    if not all("a" <= c.lower() <= "z" for c in letters):
        return False
    uppers = sum(c.isupper() for c in letters)
    return uppers >= 2 and uppers >= len(letters) - 1


def _spell(term: str, letters: dict[str, str], digits: list[str]) -> str:
    out: list[str] = []
    for c in term:
        lc = c.lower()
        if lc in letters:
            if c.isupper() or term.isupper() or not out:
                out.append(letters[lc])
            else:
                out.append(letters[lc])
        elif c.isdigit():
            out.append(digits[int(c)])
    return " ".join(out)


def _number_reading(digits: str, lang: str) -> list[str]:
    from .norm import en_cardinal, ru_cardinal

    n = int(digits)
    card = ru_cardinal(n) if lang == "ru" else en_cardinal(n)
    table = _DIGITS_RU if lang == "ru" else _DIGITS_EN
    by_digit = " ".join(table[int(d)] for d in digits)
    return [card] if len(digits) == 1 else [card, by_digit]


def acronym_readings(term: str) -> list[str]:
    """Spoken forms of acronyms and letter+digit names, in Cyrillic and Latin.

    "CI/CD" -> "си ай си ди", "цэ и цэ дэ", "see eye see dee"; "H100" -> "эйч сто", "аш сто", ...;
    "Qwen3" -> "квен три", ...; "GPT-4o" -> "джи пи ти четыре оу", ...
    """
    has_digits = any(c.isdigit() for c in term)
    if not is_acronym(term) and not (has_digits and any(c.isalpha() for c in term)):
        return []
    if is_acronym(term) and not has_digits:
        return [_spell(term, _EN_LETTERS_CYR, _DIGITS_RU), _spell(term, _RU_LETTERS_CYR, _DIGITS_RU),
                _spell(term, _EN_LETTERS_LAT, _DIGITS_EN)]
    runs = re.findall(r"[A-Za-z]+|\d+", term)
    out: list[str] = []
    for letters_cyr, letters_lat in ((_EN_LETTERS_CYR, _EN_LETTERS_LAT), (_RU_LETTERS_CYR, _EN_LETTERS_LAT)):
        for lang, letters in (("ru", letters_cyr), ("en", letters_lat)):
            variants = [""]
            for run in runs:
                if run.isdigit():
                    opts = _number_reading(run, lang)
                elif run.isupper() or len(run) <= 2:
                    opts = [_spell(run, letters, _DIGITS_RU)]
                else:
                    opts = [_lat_word_to_cyr(run) if lang == "ru" else run.lower()]
                variants = [f"{v} {o}".strip() for v in variants for o in opts][:8]
            out.extend(variants)
    # letters spelled, digits kept: "p99" -> "пи 99"
    out.append(" ".join(run if run.isdigit() else _spell(run, _EN_LETTERS_CYR, _DIGITS_RU) for run in runs))
    # letters left as written, digits spoken: "H100" -> "H сто"
    for lang in ("ru", "en"):
        variants = [""]
        for run in runs:
            opts = _number_reading(run, lang) if run.isdigit() else [run]
            variants = [f"{v} {o}".strip() for v in variants for o in opts][:8]
        out.extend(variants)
    seen: list[str] = []
    for r in out:
        if r not in seen:
            seen.append(r)
    return seen


def term_sim(ref: str, hyp: str) -> float:
    """Best of: phonetic similarity of the spans, or of an acronym reading vs the hypothesis."""
    best = phon_sim(ref, hyp)
    for reading in acronym_readings(ref):
        best = max(best, phon_sim(reading, hyp))
    return best


# ----------------------------------------------------------------------------- Latin -> Cyrillic

_L2C_MULTI: list[tuple[str, str]] = [
    ("tion", "шн"), ("ssue", "шью"), ("sch", "ш"), ("tch", "ч"), ("sh", "ш"), ("ch", "ч"), ("ph", "ф"), ("th", "т"),
    ("ck", "к"), ("qu", "кв"), ("oo", "у"), ("ee", "и"), ("ea", "и"), ("ou", "ау"), ("ow", "оу"),
    ("ai", "ей"), ("ay", "ей"), ("ey", "ей"), ("oi", "ой"), ("oy", "ой"), ("au", "о"), ("aw", "о"),
    ("ew", "ью"), ("ui", "уи"), ("kn", "н"), ("wh", "в"), ("wr", "р"), ("ng", "нг"), ("x", "кс"),
    ("ya", "я"), ("yo", "ё"), ("yu", "ю"), ("ye", "е"),
]
_L2C_SINGLE = {
    "a": "а", "b": "б", "c": "к", "d": "д", "e": "е", "f": "ф", "g": "г", "h": "х", "i": "и", "j": "дж",
    "k": "к", "l": "л", "m": "м", "n": "н", "o": "о", "p": "п", "q": "к", "r": "р", "s": "с", "t": "т",
    "u": "у", "v": "в", "w": "в", "y": "и", "z": "з",
}


# common IT words whose English reading the rules below get wrong
_L2C_LEXICON = {
    "git": "гит", "github": "гитхаб", "gitlab": "гитлаб", "get": "гет", "pull": "пул", "push": "пуш", "put": "пут",
    "full": "фул", "up": "ап", "hub": "хаб", "batch": "батч", "stack": "стек", "cache": "кэш", "rate": "рейт",
    "learning": "лёрнинг", "size": "сайз", "chart": "чарт", "compose": "компоуз", "action": "экшн",
    "actions": "экшнс", "data": "дата", "machine": "машин", "chat": "чат", "google": "гугл", "apple": "эппл",
    "python": "пайтон", "java": "джава", "type": "тайп", "file": "файл", "linux": "линукс", "user": "юзер",
    "commit": "коммит", "merge": "мёрж", "branch": "бранч", "release": "релиз", "feature": "фича",
    "token": "токен", "cloud": "клауд", "studio": "студио", "visual": "вижуал", "code": "код", "node": "нода",
    "open": "опен", "issue": "ишью", "trace": "трейс", "helm": "хелм", "face": "фейс", "lambda": "лямбда",
    "query": "квери", "queue": "кью", "key": "ки", "pipeline": "пайплайн", "runner": "раннер", "build": "билд",
}


def _lat_word_to_cyr(w: str) -> str:
    w = w.lower()
    if not w:
        return ""
    if w in _L2C_LEXICON:
        return _L2C_LEXICON[w]
    # soft c / g before e, i, y (Celery -> селери, Docker keeps к)
    w = re.sub(r"c(?=[eiy])", "s", w)
    w = re.sub(r"(?<!g)(?<!^)g(?=[iy])|(?<!g)g(?=e)", "дж", w)
    # magic e at the end of a word part: Face -> фейс, Node -> ноуд, Line -> лайн, Cube -> кьюб
    w = re.sub(r"(?<![aeiou])([aiou])([bdfgklmnprstvz])e(s?)$",
               lambda m: {"a": "ей", "i": "ай", "o": "оу", "u": "ью"}[m.group(1)] + m.group(2) + m.group(3), w)
    # silent final e after a consonant (Pipeline -> ...лайн, Confluence -> конфлуенс)
    if len(w) > 3 and w.endswith("e") and w[-2] not in "aeiouy":
        w = w[:-1]
    # short u before a consonant cluster reads as "а": Hugging -> хаггинг, function -> фанкшн
    w = re.sub(r"u(?=([bdfgklmnprstvz])\1|ck|nc|nk|ng|mp|bs|sh|st)", "а", w)
    # y in a short first syllable or between consonants reads as "ай": Py -> пай, byte -> байт
    w = re.sub(r"^([bcdfghklmnprstvz])y$", r"\1ай", w)
    w = re.sub(r"(?<=[bcdfghklmnprstvz])y(?=[bcdfghklmnprstvz])", "ай", w)
    w = re.sub(r"^y(?=[aeiou])", "й", w)
    w = re.sub(r"(?<=[aeiou])y$", "й", w)
    w = re.sub(r"(?<=[^aeiou])y$", "и", w)
    w = re.sub(r"^y", "и", w)
    for a, b in _L2C_MULTI:
        w = w.replace(a, b)
    out = "".join(_L2C_SINGLE.get(c, c) for c in w)
    out = re.sub(r"(.)\1+", r"\1", out) if len(out) > 4 else out
    if out.startswith("е"):  # Airflow -> эйрфлоу, Excel -> эксел
        out = "э" + out[1:]
    return out


_CAMEL = re.compile(r"[A-Z]+(?![a-z])|[A-Z]?[a-z]+|\d+")


def camel_parts(term: str) -> list[str]:
    """"ClickHouse" -> ["Click", "House"]; "PyTorch" -> ["Py", "Torch"]; "gRPC" -> ["g", "RPC"]."""
    return _CAMEL.findall(term)


def lat2cyr(term: str, split_camel: bool = False, acronym_style: str = "en") -> str:
    """English-reading transliteration of a Latin term into Cyrillic.

    acronym_style: 'en' (си ай), 'ru' (цэ и) for acronyms. split_camel inserts spaces between CamelCase parts.
    Non-Latin characters are kept.
    """
    if is_acronym(term):
        table = _EN_LETTERS_CYR if acronym_style == "en" else _RU_LETTERS_CYR
        return _spell(term, table, _DIGITS_RU)
    pieces: list[str] = []
    for chunk in re.split(r"([^A-Za-z0-9]+)", term):
        if not chunk:
            continue
        if not re.match(r"[A-Za-z0-9]", chunk):
            pieces.append(" ")
            continue
        parts = camel_parts(chunk) or [chunk]
        conv = []
        for p in parts:
            if p.isdigit():
                conv.append(" ".join(_DIGITS_RU[int(d)] for d in p) if len(p) > 2 else p)
            elif p.isupper() and len(p) >= 2:
                conv.append(_spell(p, _EN_LETTERS_CYR, _DIGITS_RU))
            else:
                conv.append(_lat_word_to_cyr(p))
        pieces.append((" " if split_camel else "").join(conv))
    return re.sub(r"\s+", " ", "".join(pieces)).strip()


# ----------------------------------------------------------------------------- faithful transliteration

_LETTERWISE = {
    "a": "а", "b": "б", "c": "к", "d": "д", "e": "е", "f": "ф", "g": "г", "h": "х", "i": "и", "j": "дж",
    "k": "к", "l": "л", "m": "м", "n": "н", "o": "о", "p": "п", "q": "к", "r": "р", "s": "с", "t": "т",
    "u": "у", "v": "в", "w": "в", "x": "кс", "y": "и", "z": "з",
}
_CYR_V = set("аеиоуыюя")
# Russian case endings an ASR model may put on a transliterated term ("в кубернетисе", "с Лорой")
_ENDINGS = ("ами", "ями", "ого", "его", "ому", "ему", "ой", "ей", "ом", "ем", "ам", "ям", "ах", "ях", "ов", "ев",
            "ую", "ою", "у", "ю", "е", "а", "я", "ы", "и")
LEXICON_PATH = Path(__file__).resolve().parents[1] / "probe" / "terms.jsonl"
_lexicon_override: dict[str, tuple[str, ...]] | None = None


def letterwise(term: str) -> str:
    """Letter-by-letter Cyrillic rendering ("Kafka" -> "кафка", "Laura" -> "лаура", "TTL" -> "ттл")."""
    out = []
    for c in term:
        lc = c.lower()
        if lc in _LETTERWISE:
            out.append(_LETTERWISE[lc])
        elif c.isdigit():
            out.append(c)
        elif not c.isalnum():
            out.append(" ")
    return re.sub(r"\s+", " ", "".join(out)).strip()


def norm_cyr(s: str) -> str:
    """Spelling-variant-free Cyrillic skeleton: lowercase, ё/э -> е, й -> и, no ь/ъ, letters and digits only."""
    s = fold(s).replace("э", "е").replace("й", "и")
    return "".join(c for c in s if (c.isalpha() and c not in "ьъ") or c.isdigit())


def _first_vowel(s: str) -> int:
    for i, c in enumerate(s):
        if c in _CYR_V or c == "е":
            return i
    return -1


def translit_cost(cand: str, hyp: str) -> float:
    """Weighted edit distance from a faithful reading ``cand`` to ``hyp`` (both ``norm_cyr``-normalised).

    Vowel substitutions cost 0.5 (unstressed vowels are spelled loosely), except the first vowel, which
    is usually the stressed one in an English loan ("Kafka" -> "кофка" is a mishearing, cost 1).
    Consonant substitutions cost 1, so voicing changes ("Kafka" -> "кавка", "Slack" -> "слаг") are errors.
    Inserting or deleting a vowel costs 0.5, a doubled letter 0.25 ("хероку" / "херроку"), a consonant 1.25
    (an extra or missing sound: "пиджи" / "пиджин").
    """
    fv = _first_vowel(cand)
    n, m = len(cand), len(hyp)

    def is_v(c: str) -> bool:
        return c in _CYR_V or c == "е"

    def indel(s: str, i: int) -> float:
        c = s[i]
        if (i > 0 and s[i - 1] == c) or (i + 1 < len(s) and s[i + 1] == c):
            return 0.25
        return 0.5 if is_v(c) else 1.25

    prev = [0.0] * (m + 1)
    for j in range(1, m + 1):
        prev[j] = prev[j - 1] + indel(hyp, j - 1)
    for i in range(1, n + 1):
        cur = [prev[0] + indel(cand, i - 1)] + [0.0] * m
        a = cand[i - 1]
        for j in range(1, m + 1):
            b = hyp[j - 1]
            if a == b:
                sub = 0.0
            elif is_v(a) and is_v(b):
                sub = 1.0 if i - 1 == fv else 0.5
            else:
                sub = 1.0
            cur[j] = min(prev[j - 1] + sub, prev[j] + indel(cand, i - 1), cur[j - 1] + indel(hyp, j - 1))
        prev = cur
    return prev[m]


def set_term_lexicon(readings: dict[str, Sequence[str]] | None) -> None:
    """Override the term -> Cyrillic readings table (tests); None restores the probe lexicon."""
    global _lexicon_override
    _lexicon_override = None if readings is None else {k: tuple(v) for k, v in readings.items()}
    lexicon_readings.cache_clear()
    spoken_readings.cache_clear()
    faithful_translit.cache_clear()


@lru_cache(maxsize=1)
def _probe_lexicon_full() -> tuple[dict[str, tuple[str, ...]], dict[str, tuple[str, ...]]]:
    ru: dict[str, tuple[str, ...]] = {}
    en: dict[str, tuple[str, ...]] = {}
    if not LEXICON_PATH.exists():
        return ru, en
    with open(LEXICON_PATH, encoding="utf-8") as fh:
        for line in fh:
            if not line.strip():
                continue
            e = json.loads(line)
            for f in e.get("written_forms", []):
                ru.setdefault(f, tuple(e.get("ru_spoken", [])))
                en.setdefault(f, tuple(e.get("en_spoken", [])))
    return ru, en


def _probe_lexicon() -> dict[str, tuple[str, ...]]:
    return _probe_lexicon_full()[0]


@lru_cache(maxsize=65536)
def spoken_readings(term: str) -> tuple[str, ...]:
    """Every reading of ``term`` in the probe lexicon, Russian and English ("journalctl" -> "journal
    control"). A hypothesis that writes exactly a reading wrote down what was said: style, not an error."""
    if _lexicon_override is not None:
        return _lexicon_override.get(term, ())
    ru, en = _probe_lexicon_full()
    return ru.get(term, ()) + en.get(term, ())


@lru_cache(maxsize=65536)
def lexicon_readings(term: str) -> tuple[str, ...]:
    """How Russian engineers say ``term`` (``ru_spoken`` of the probe lexicon), empty when unknown."""
    table = _lexicon_override if _lexicon_override is not None else _probe_lexicon()
    return table.get(term) or table.get(term.strip()) or ()


def _spelled(ref: str) -> bool:
    """Every segment is spelled letter by letter: acronyms and upper-case codes ("SSD", "Q4_K_M", "H100")."""
    segs = [w for w in re.split(r"[\s/_.\-]+", ref) if w]
    return bool(segs) and all(is_acronym(w) or (w.upper() == w and any(c.isalpha() for c in w)) for w in segs)


def _letterwise_ok(ref: str) -> bool:
    """Letter-by-letter Cyrillic is a real writing convention for plain words ("Kafka" -> "кафка") and for
    all-caps acronyms ("TTL" -> "ттл"), not for names mixing an acronym with a word ("SGLang", "pgBouncer")."""
    for w in re.split(r"[\s/_.\-]+", ref):
        if w and not w.isupper() and (re.search(r"[A-Z]{2}", w) or re.match(r"^[a-z]{1,3}[A-Z]", w)):
            return False
    return True


def _tolerance(ref: str, cand_len: int) -> float:
    if _spelled(ref):
        return 0.0  # spelled letters: every letter must be there
    if cand_len <= 6:
        return 0.25
    if cand_len <= 10:
        return 0.5
    return 1.0


_NORM_ENDINGS = tuple(sorted({norm_cyr(e) for e in _ENDINGS}, key=lambda e: (-len(e), e)))


@lru_cache(maxsize=262144)
def faithful_translit(ref: str, hyp: str) -> bool:
    """Is Cyrillic ``hyp`` a faithful rendering of Latin ``ref`` (style), not a mishearing (error)?

    Candidates: the term's lexicon readings, an English-style and a letter-by-letter transliteration and
    spelled-acronym readings. ``hyp`` may add a Russian case ending ("лору", "кубернетисе", "Марком").
    It is faithful when it is within a small weighted edit distance (``translit_cost``) of a candidate:
    0 for acronyms, 0.25 for readings of up to 6 letters, 0.5 up to 10, 1.0 beyond.
    """
    cands = set(lexicon_readings(ref))
    cands.update((lat2cyr(ref), lat2cyr(ref, acronym_style="ru")))
    if _letterwise_ok(ref):
        cands.add(letterwise(ref))
    cands.update(r for r in acronym_readings(ref) if not re.search(r"[A-Za-z]", r))
    h = norm_cyr(hyp)
    if not h:
        return False
    hyps = [(h, False)] + [(h[: -len(e)], True) for e in _NORM_ENDINGS if h.endswith(e) and len(h) - len(e) >= 3]
    for c in cands:
        cn = norm_cyr(c)
        if not cn:
            continue
        tol = _tolerance(ref, len(cn))
        stems = [cn] + ([cn[:-1]] if cn[-1] in _CYR_V and len(cn) > 3 else [])
        for hv, stripped in hyps:
            for k, cv in enumerate(stems):
                if k and not stripped:
                    continue  # the reading's own final vowel may only give way to a case ending
                if translit_cost(cv, hv) <= tol:
                    return True
    return False
