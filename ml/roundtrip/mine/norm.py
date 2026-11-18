"""Tokenisation, style folding and number handling for ru / en / mixed text.

Everything here separates *style* (casing, punctuation, ё/е, digits vs number words) from content:

  tokenize("Созвон в 10:30, ок?") -> tokens with core "Созвон", "в", "10:30", "ок" and their punctuation
  fold("Ёлка")   -> "елка"      (casefold + ё->е + typographic apostrophe)
  squash("CI/CD") -> "cicd"     (fold + drop separators; '%', '#', '+' are kept: "15%", "C#", "C++")
  number_groups(["десять", "тридцать"]) == number_groups(["10:30"]) == ["10", "30"]

Standard library only.
"""
from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass
from typing import Iterable, Sequence

# ----------------------------------------------------------------------------- tokens

_KEEP_LEAD = set("#$€₽№@+")
_KEEP_TRAIL = set("%#+")
_SQUASH_KEEP = set("%#+")
_HARD_SPLIT = re.compile(r"[—–…]+")  # em/en dash and ellipsis glued to words split them
_TERMINAL = set(".!?…")
PUNCT_CHARS = set(".,!?;:…—–-\"«»„“”'()[]{}")


@dataclass(frozen=True)
class Token:
    """One whitespace token. `core` is the word without edge punctuation; offsets refer to `core`."""

    core: str
    start: int
    end: int
    lead: str
    trail: str


def nfc(s: str) -> str:
    return unicodedata.normalize("NFC", s)


def _is_core_char(ch: str) -> bool:
    return ch.isalnum()


def tokenize(text: str) -> list[Token]:
    """Split on whitespace (and glued dashes/ellipses); strip edge punctuation into lead/trail.

    Tokens whose core is empty (a lone "—" or "...") are dropped: they carry no word.
    Their punctuation is still visible through `punctuation_of(text)`.
    """
    text = nfc(text)
    out: list[Token] = []
    for m in re.finditer(r"\S+", text):
        chunk_start = m.start()
        chunk = m.group(0)
        pos = 0
        for part in _HARD_SPLIT.split(chunk):
            idx = chunk.find(part, pos) if part else pos
            pos = idx + len(part)
            if not part:
                continue
            lo, hi = 0, len(part)
            while lo < hi and not (_is_core_char(part[lo]) or part[lo] in _KEEP_LEAD):
                lo += 1
            while hi > lo and not (_is_core_char(part[hi - 1]) or part[hi - 1] in _KEEP_TRAIL):
                hi -= 1
            core = part[lo:hi]
            # a lone kept symbol ("+", "#") with no alphanumerics is punctuation, not a word
            if not core or not any(_is_core_char(c) for c in core):
                continue
            out.append(Token(core=core, start=chunk_start + idx + lo, end=chunk_start + idx + hi,
                             lead=part[:lo], trail=part[hi:]))
    return out


def words(text: str) -> list[str]:
    return [t.core for t in tokenize(text)]


def fold(s: str) -> str:
    """Casefold + ё->е + unify apostrophes. Keeps internal punctuation."""
    s = nfc(s).casefold().replace("ё", "е").replace("’", "'").replace("ʼ", "'")
    return s


def squash(s: str) -> str:
    """fold() and drop every non-alphanumeric character except % # +."""
    return "".join(c for c in fold(s) if c.isalnum() or c in _SQUASH_KEEP)


def punctuation_of(text: str) -> list[str]:
    """All punctuation characters of a text in order (used for style statistics)."""
    return [c for c in nfc(text) if c in PUNCT_CHARS]


def ends_with_terminal(text: str) -> bool:
    t = nfc(text).rstrip(" \"»”')")
    return bool(t) and t[-1] in _TERMINAL


# ----------------------------------------------------------------------------- scripts

def script_of(s: str) -> str:
    """'lat', 'cyr', 'digit', 'mixed' (Latin and Cyrillic letters) or 'other'."""
    lat = cyr = dig = 0
    for c in s:
        if "a" <= c.lower() <= "z" or "À" <= c <= "ɏ":
            lat += 1
        elif "Ѐ" <= c <= "ӿ":
            cyr += 1
        elif c.isdigit():
            dig += 1
    if lat and cyr:
        return "mixed"
    if lat:
        return "lat"
    if cyr:
        return "cyr"
    if dig:
        return "digit"
    return "other"


def has_upper(s: str) -> bool:
    return any(c.isupper() for c in s)


def has_digit(s: str) -> bool:
    return any(c.isdigit() for c in s)


# ----------------------------------------------------------------------------- number words

# kinds: u = unit 0-9, t = teen 10-19, d = tens 20-90, h = hundreds, s = scale, o = ordinal (terminates)
_RU_UNITS = {
    0: "ноль нуль ноля нуля нолю нулю нолем нулем",
    1: "один одна одно одного одной одному одним одном одну одною одни одних",
    2: "два две двух двум двумя",
    3: "три трех трем тремя",
    4: "четыре четырех четырем четырьмя",
    5: "пять пяти пятью",
    6: "шесть шести шестью",
    7: "семь семи семью",
    8: "восемь восьми восемью восьмью",
    9: "девять девяти девятью",
}
_RU_TEENS = {10: "десят", 11: "одиннадцат", 12: "двенадцат", 13: "тринадцат", 14: "четырнадцат",
             15: "пятнадцат", 16: "шестнадцат", 17: "семнадцат", 18: "восемнадцат", 19: "девятнадцат"}
_RU_TENS = {
    20: "двадцать двадцати двадцатью", 30: "тридцать тридцати тридцатью", 40: "сорок сорока",
    50: "пятьдесят пятидесяти пятьюдесятью", 60: "шестьдесят шестидесяти шестьюдесятью",
    70: "семьдесят семидесяти семьюдесятью", 80: "восемьдесят восьмидесяти восемьюдесятью восьмьюдесятью",
    90: "девяносто девяноста",
}
_RU_HUNDREDS = {
    100: "сто ста сотни сотня",
    200: "двести двухсот двумстам двумястами двухстах",
    300: "триста трехсот тремстам тремястами трехстах",
    400: "четыреста четырехсот четыремстам четырьмястами четырехстах",
}
_RU_H_STEMS = {500: ("пять", "пяти", "пятью"), 600: ("шесть", "шести", "шестью"), 700: ("семь", "семи", "семью"),
               800: ("восемь", "восьми", "восемью"), 900: ("девять", "девяти", "девятью")}
_RU_SCALES = {
    1000: "тысяча тысячи тысяч тысячу тысячей тысяче тысячам тысячами тысячах",
    10 ** 6: "миллион миллиона миллионов миллиону миллионом миллионе миллионам миллионами миллионах",
    10 ** 9: "миллиард миллиарда миллиардов миллиарду миллиардом миллиарде миллиардам миллиардами миллиардах",
}
_RU_ORD_STEMS = {
    0: "нулев", 1: "перв", 2: "втор", 4: "четверт", 5: "пят", 6: "шест", 7: "седьм", 8: "восьм", 9: "девят",
    10: "десят", 11: "одиннадцат", 12: "двенадцат", 13: "тринадцат", 14: "четырнадцат", 15: "пятнадцат",
    16: "шестнадцат", 17: "семнадцат", 18: "восемнадцат", 19: "девятнадцат", 20: "двадцат", 30: "тридцат",
    40: "сороков", 50: "пятидесят", 60: "шестидесят", 70: "семидесят", 80: "восьмидесят", 90: "девяност",
    100: "сот", 200: "двухсот", 300: "трехсот", 1000: "тысячн",
}
_RU_ORD_END = "ый ой ий ая ое ого ому ым ом ую ые ых ыми".split()
_RU_TRET = "третий третья третье третьего третьему третьим третьем третью третьи третьих".split()

_EN_UNITS = {"zero": 0, "oh": 0, "one": 1, "two": 2, "three": 3, "four": 4, "five": 5, "six": 6, "seven": 7,
             "eight": 8, "nine": 9}
_EN_TEENS = {"ten": 10, "eleven": 11, "twelve": 12, "thirteen": 13, "fourteen": 14, "fifteen": 15,
             "sixteen": 16, "seventeen": 17, "eighteen": 18, "nineteen": 19}
_EN_TENS = {"twenty": 20, "thirty": 30, "forty": 40, "fifty": 50, "sixty": 60, "seventy": 70, "eighty": 80,
            "ninety": 90}
_EN_SCALES = {"thousand": 1000, "million": 10 ** 6, "billion": 10 ** 9}
_EN_ORD = {"zeroth": 0, "first": 1, "second": 2, "third": 3, "fourth": 4, "fifth": 5, "sixth": 6, "seventh": 7,
           "eighth": 8, "ninth": 9, "tenth": 10, "eleventh": 11, "twelfth": 12, "thirteenth": 13,
           "fourteenth": 14, "fifteenth": 15, "sixteenth": 16, "seventeenth": 17, "eighteenth": 18,
           "nineteenth": 19, "twentieth": 20, "thirtieth": 30, "fortieth": 40, "fiftieth": 50, "sixtieth": 60,
           "seventieth": 70, "eightieth": 80, "ninetieth": 90, "hundredth": 100, "thousandth": 1000}

# connectors that may sit between number words without breaking the number span
CONNECTORS = {"и", "and", "point", "dot", "точка", "запятая", "целых", "целая", "a"}
_PERCENT_WORDS = {"процент", "процента", "процентов", "проценту", "процентами", "процентах", "percent",
                  "percents", "per-cent"}


def _build_number_lexicon() -> dict[str, tuple[int, str]]:
    lex: dict[str, tuple[int, str]] = {}

    def add(forms: Iterable[str], value: int, kind: str) -> None:
        for f in forms:
            lex.setdefault(f, (value, kind))

    for v, forms in _RU_UNITS.items():
        add(forms.split(), v, "u")
    for v, stem in _RU_TEENS.items():
        add((stem + "ь", stem + "и", stem + "ью"), v, "t")
    for v, forms in _RU_TENS.items():
        add(forms.split(), v, "d")
    for v, forms in _RU_HUNDREDS.items():
        add(forms.split(), v, "h")
    for v, (nom, gen, ins) in _RU_H_STEMS.items():
        add((nom + "сот", gen + "сот", gen + "стам", ins + "стами", gen + "стах"), v, "h")
    for v, forms in _RU_SCALES.items():
        add(forms.split(), v, "s")
    for v, stem in _RU_ORD_STEMS.items():
        add((stem + e for e in _RU_ORD_END), v, "o")
    add(_RU_TRET, 3, "o")
    for table, kind in ((_EN_UNITS, "u"), (_EN_TEENS, "t"), (_EN_TENS, "d"), (_EN_SCALES, "s"), (_EN_ORD, "o")):
        for w, v in table.items():
            add((w,), v, kind)
    add(("hundred", "hundreds"), 100, "H")  # English multiplier hundred
    return lex


NUMBER_LEXICON: dict[str, tuple[int, str]] = _build_number_lexicon()

_NUMERIC_RE = re.compile(
    r"^[+\-]?[$€₽]?\d+(?:[.,:/\-]\d+)*(?:%|st|nd|rd|th|-?(?:й|я|е|го|му|м|х|ми|ой|ый|ая|ое|ые|ых|ий))?[$€₽]?$"
)


def is_numeric_token(core: str) -> bool:
    return bool(_NUMERIC_RE.match(fold(core)))


def is_number_word(w: str) -> bool:
    return fold(w) in NUMBER_LEXICON or fold(w) in _PERCENT_WORDS


def _split_hyphen_words(ws: Sequence[str]) -> list[str]:
    out: list[str] = []
    for w in ws:
        f = fold(w)
        if "-" in f and not any(c.isdigit() for c in f):
            out.extend(p for p in f.split("-") if p)
        else:
            out.append(f)
    return out


def parse_number_words(ws: Sequence[str]) -> list[int] | None:
    """Parse a run of number words into a list of numbers ("twenty twenty five" -> [20, 25]).

    Returns None when any word is not a number word or connector. A new number starts whenever the next
    word cannot extend the current one ("десять тридцать" -> [10, 30]; "сто двадцать" -> [120]).
    """
    nums: list[int] = []
    total = 0      # completed scale segments of the current number
    small = 0      # current segment below the next scale
    active = False
    last_kind = ""
    last_scale = 10 ** 12

    def flush() -> None:
        nonlocal total, small, active, last_kind, last_scale
        if active:
            nums.append(total + small)
        total, small, active, last_kind, last_scale = 0, 0, False, "", 10 ** 12

    for w in _split_hyphen_words(ws):
        if w in CONNECTORS:
            if w == "a":  # only "a hundred / a thousand"
                continue
            if w == "and" and active and last_kind in ("h", "s"):  # "one hundred and five"
                continue
            flush()
            continue
        if w not in NUMBER_LEXICON:
            return None
        v, kind = NUMBER_LEXICON[w]
        if w == "oh" and not active and not nums:
            return None  # "oh" is a number only after a number
        if kind == "H":  # English "hundred" multiplies a preceding unit/teen
            if active and last_kind in ("u", "t") and small < 100:
                small *= 100
            else:
                flush()
                small = 100
            active, last_kind = True, "h"
            continue
        if kind == "s":
            if active and v >= last_scale:
                flush()
            total += (small if small else 1) * v
            small = 0
            last_scale = v
            active, last_kind = True, "s"
            continue
        value_kind = kind if kind != "o" else ("u" if v < 10 else "t" if v < 20 else "d" if v < 100 else "h")
        if active and not _extends(value_kind, small, last_kind):
            flush()
        small += v
        active, last_kind = True, kind
        if kind == "o":  # an ordinal ends the number
            flush()
    flush()
    return nums


def _extends(kind: str, small: int, last_kind: str) -> bool:
    """Can a word of `kind` continue a number whose current sub-scale segment is `small`?"""
    if kind == "u":
        return last_kind in ("d", "h", "s") and small % 10 == 0 and not 10 <= small % 100 < 20
    if kind in ("t", "d"):
        return last_kind in ("h", "s") and small % 100 == 0
    if kind == "h":
        return last_kind == "s" and small == 0
    return False


def number_groups(ws: Sequence[str]) -> list[str] | None:
    """Canonical digit groups of a span made only of numbers, number words, connectors and percent words.

    "10:30" -> ["10", "30"]; "десять тридцать" -> ["10", "30"]; "15%" -> ["15", "%"];
    "пятнадцать процентов" -> ["15", "%"]. Returns None if the span contains any other word or no number.
    """
    groups: list[str] = []
    run: list[str] = []
    ws = _fold_scaled_numbers([fold(w) for w in ws])

    def flush_run() -> bool:
        if not run:
            return True
        parsed = parse_number_words(run)
        run.clear()
        if parsed is None:
            return False
        groups.extend(str(n) for n in parsed)
        return True

    for w in ws:
        f = fold(w)
        if is_numeric_token(f):
            if not flush_run():
                return None
            body = re.sub(r"(st|nd|rd|th|-?[а-я]+)$", "", f)
            for part in re.split(r"[.,:/\-]", body):
                digits = "".join(c for c in part if c.isdigit())
                if digits:
                    groups.append(str(int(digits)))
            if "%" in f:
                groups.append("%")
        elif f in _PERCENT_WORDS:
            if not flush_run():
                return None
            groups.append("%")
        elif f in NUMBER_LEXICON or f in CONNECTORS or ("-" in f and all(
                p in NUMBER_LEXICON for p in f.split("-") if p)):
            run.append(f)
        else:
            return None
    if not flush_run():
        return None
    if not any(g != "%" for g in groups):
        return None
    return groups


def cardinal_word_run(ws: Sequence[str]) -> str | None:
    """Digit form of a run of number words that a digit-writing recogniser would emit as one number.

    "двадцать пять" -> "25", "пятнадцать процентов" -> "15%", "two hundred" -> "200". None when the run
    contains anything but cardinal number words (ordinals, connectors and other words keep their spelling
    because their digit form needs a suffix or a separator), parses to more than one number, or is a lone
    unit word below ten ("один из", "два раза" are written as words in prose). Used by both the miner (to
    measure how often a model writes such runs as digits) and the noise sampler (to apply it).
    """
    if not ws:
        return None
    percent = fold(ws[-1]) in _PERCENT_WORDS
    body = [fold(w) for w in (ws[:-1] if percent else ws)]
    if not body or any(w not in NUMBER_LEXICON or NUMBER_LEXICON[w][1] == "o" for w in body):
        return None
    parsed = parse_number_words(body)
    if not parsed or len(parsed) != 1:
        return None
    if len(body) == 1 and parsed[0] < 10:
        return None
    return f"{parsed[0]}%" if percent else str(parsed[0])


_POINT_WORDS = {"point", "dot", "точка", "запятая", "целых", "целая", "и"}
_DEC_RE = re.compile(r"^\d+(?:[.,]\d+)?$")


def _scale_of(w: str) -> int | None:
    e = NUMBER_LEXICON.get(w)
    return e[0] if e and e[1] == "s" else None


def _fold_scaled_numbers(ws: list[str]) -> list[str]:
    """Rewrite digit/word mixtures with a scale into one digit string, so both sides compare by value.

    "128 тысяч" -> "128000"; "1.2 million" -> "1200000"; "one point two million" -> "1200000";
    "128 000" -> "128000" (space-grouped thousands).
    """
    out: list[str] = []
    i = 0
    while i < len(ws):
        w = ws[i]
        # space-grouped thousands: 128 000 (000 ...)
        if re.fullmatch(r"\d{1,3}", w):
            j, val = i + 1, w
            while j < len(ws) and re.fullmatch(r"\d{3}", ws[j]):
                val += ws[j]
                j += 1
            if j > i + 1:
                out.append(val)
                i = j
                continue
        # digits + scale word
        if _DEC_RE.match(w) and i + 1 < len(ws) and _scale_of(ws[i + 1]):
            out.append(str(int(round(float(w.replace(",", ".")) * _scale_of(ws[i + 1])))))  # type: ignore[operator]
            i += 2
            continue
        # number words, point word, single unit digits, scale word
        if w in NUMBER_LEXICON and NUMBER_LEXICON[w][1] in ("u", "t", "d"):
            j = i
            while j < len(ws) and ws[j] in NUMBER_LEXICON and NUMBER_LEXICON[ws[j]][1] in ("u", "t", "d", "h", "H"):
                j += 1
            if j < len(ws) and ws[j] in _POINT_WORDS:
                k = j + 1
                digits = ""
                while k < len(ws) and ws[k] in NUMBER_LEXICON and NUMBER_LEXICON[ws[k]][1] == "u":
                    digits += str(NUMBER_LEXICON[ws[k]][0])
                    k += 1
                if digits and k < len(ws) and _scale_of(ws[k]):
                    whole = parse_number_words(ws[i:j])
                    if whole and len(whole) == 1:
                        out.append(str(int(round(float(f"{whole[0]}.{digits}") * _scale_of(ws[k])))))  # type: ignore[operator]
                        i = k + 1
                        continue
        out.append(w)
        i += 1
    return out


def numbers_equivalent(a: Sequence[str], b: Sequence[str]) -> bool:
    """Same number written differently ("2025" vs "20 25", "09:05" vs "девять ноль пять")."""
    if list(a) == list(b) or "".join(a) == "".join(b):
        return True
    a2 = [g for g in a if g != "0"]
    b2 = [g for g in b if g != "0"]
    return bool(a2) and (a2 == b2 or "".join(a2) == "".join(b2))


# ----------------------------------------------------------------------------- numbers -> words

_RU_U_M = ["ноль", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять", "десять",
           "одиннадцать", "двенадцать", "тринадцать", "четырнадцать", "пятнадцать", "шестнадцать", "семнадцать",
           "восемнадцать", "девятнадцать"]
_RU_T = ["", "", "двадцать", "тридцать", "сорок", "пятьдесят", "шестьдесят", "семьдесят", "восемьдесят",
         "девяносто"]
_RU_H = ["", "сто", "двести", "триста", "четыреста", "пятьсот", "шестьсот", "семьсот", "восемьсот", "девятьсот"]
_EN_U = ["zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven",
         "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen"]
_EN_T = ["", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"]


def ru_plural(n: int, one: str, few: str, many: str) -> str:
    n = abs(n) % 100
    if 11 <= n <= 14:
        return many
    if n % 10 == 1:
        return one
    if 2 <= n % 10 <= 4:
        return few
    return many


def _ru_below_1000(n: int, feminine: bool) -> list[str]:
    out: list[str] = []
    if n >= 100:
        out.append(_RU_H[n // 100])
        n %= 100
    if n >= 20:
        out.append(_RU_T[n // 10])
        n %= 10
        if n == 0:
            return out
    if n or not out:
        w = _RU_U_M[n]
        if feminine and n == 1:
            w = "одна"
        elif feminine and n == 2:
            w = "две"
        out.append(w)
    return out


def ru_cardinal(n: int, feminine: bool = False) -> str:
    if n < 0:
        return "минус " + ru_cardinal(-n, feminine)
    if n == 0:
        return "ноль"
    parts: list[str] = []
    for scale, forms, fem in ((10 ** 9, ("миллиард", "миллиарда", "миллиардов"), False),
                              (10 ** 6, ("миллион", "миллиона", "миллионов"), False),
                              (1000, ("тысяча", "тысячи", "тысяч"), True)):
        q = n // scale
        if q:
            parts.extend(_ru_below_1000(q, fem))
            parts.append(ru_plural(q, *forms))
            n %= scale
    if n:
        parts.extend(_ru_below_1000(n, feminine))
    return " ".join(parts)


def _en_below_1000(n: int) -> list[str]:
    out: list[str] = []
    if n >= 100:
        out.extend((_EN_U[n // 100], "hundred"))
        n %= 100
    if n >= 20:
        out.append(_EN_T[n // 10])
        n %= 10
        if n == 0:
            return out
    if n or not out:
        out.append(_EN_U[n])
    return out


def en_cardinal(n: int) -> str:
    if n < 0:
        return "minus " + en_cardinal(-n)
    if n == 0:
        return "zero"
    parts: list[str] = []
    for scale, name in ((10 ** 9, "billion"), (10 ** 6, "million"), (1000, "thousand")):
        q = n // scale
        if q:
            parts.extend(_en_below_1000(q))
            parts.append(name)
            n %= scale
    if n:
        parts.extend(_en_below_1000(n))
    return " ".join(parts)


_RU_ORD_NOM = {0: "нулевой", 1: "первый", 2: "второй", 3: "третий", 4: "четвертый", 5: "пятый", 6: "шестой",
               7: "седьмой", 8: "восьмой", 9: "девятый", 10: "десятый", 11: "одиннадцатый", 12: "двенадцатый",
               13: "тринадцатый", 14: "четырнадцатый", 15: "пятнадцатый", 16: "шестнадцатый",
               17: "семнадцатый", 18: "восемнадцатый", 19: "девятнадцатый", 20: "двадцатый", 30: "тридцатый",
               40: "сороковой", 50: "пятидесятый", 60: "шестидесятый", 70: "семидесятый", 80: "восьмидесятый",
               90: "девяностый"}
# suffix after the hyphen ("5-го") -> ending replacing the nominative "-ый/-ой/-ий"
_RU_ORD_SUFFIX = {"й": None, "ый": None, "ой": None, "ий": None, "го": "ого", "ого": "ого", "му": "ому",
                  "е": "ое", "я": "ая", "м": "ом", "х": "ых", "ми": "ыми", "ю": "ую", "ая": "ая", "ое": "ое"}


def _ru_ordinal_word(n: int, suffix: str) -> str:
    base = _RU_ORD_NOM[n]
    end = _RU_ORD_SUFFIX.get(suffix)
    if end is None:
        return base
    if n == 3:
        return {"ого": "третьего", "ому": "третьему", "ое": "третье", "ая": "третья", "ом": "третьем",
                "ых": "третьих", "ыми": "третьими", "ую": "третью"}[end]
    stem = base[:-2]
    if end in ("ого", "ому", "ом") and base.endswith("ий"):
        end = "е" + end[1:]
    return stem + end


def ru_ordinal(n: int, suffix: str = "й") -> str:
    if n < 0 or n >= 1000:
        return ru_cardinal(n)
    head = n - n % 100 if n % 100 else 0
    rest = n % 100
    words_: list[str] = []
    if head and rest:
        words_.append(_RU_H[head // 100])
    elif head and not rest:
        return ru_cardinal(n)  # "сотый" etc. are rare in dictation; keep cardinal form
    if rest in _RU_ORD_NOM:
        words_.append(_ru_ordinal_word(rest, suffix))
    else:
        words_.append(_RU_T[rest // 10])
        words_.append(_ru_ordinal_word(rest % 10, suffix))
    return " ".join(words_)


_EN_ORD_BY_VALUE = {v: w for w, v in _EN_ORD.items() if v < 100}


def en_ordinal(n: int) -> str:
    if n % 100 in _EN_ORD_BY_VALUE and n >= 100:
        return en_cardinal(n - n % 100) + " " + _EN_ORD_BY_VALUE[n % 100]
    if n in _EN_ORD_BY_VALUE:
        return _EN_ORD_BY_VALUE[n]
    if n < 100:
        return _EN_T[n // 10] + " " + _EN_ORD_BY_VALUE[n % 10]
    return en_cardinal(n) + "th"


def spell_numeric_token(core: str, lang: str, next_word: str = "", variant: int = 0) -> str | None:
    """Speak a numeric token the way an ASR without inverse text normalisation writes it.

    lang: 'ru' or 'en'. `next_word` drives Russian gender for 1/2 ("одна ошибка", "две минуты").
    `variant` picks among spoken forms of decimals ("3,5" -> "три и пять" | "три точка пять" | "три пять").
    Returns None for tokens it cannot speak (then the caller keeps the digits).
    """
    f = fold(core)
    if not is_numeric_token(f):
        return None
    ru = lang != "en"
    percent = f.endswith("%")
    f = f.rstrip("%").lstrip("+").strip("$€₽")
    m = re.match(r"^(\d+)(?:-?(st|nd|rd|th|й|я|е|го|му|м|х|ми|ой|ый|ая|ое|ые|ых|ий))$", f)
    if m and m.group(2):
        n = int(m.group(1))
        return ru_ordinal(n, m.group(2)) if ru else en_ordinal(n)
    if re.match(r"^\d{1,2}:\d{2}$", f):
        h, mi = (int(x) for x in f.split(":"))
        card = ru_cardinal if ru else en_cardinal
        if mi == 0:
            return card(h) + ("" if ru else " o'clock")
        if mi < 10:
            return f"{card(h)} {'ноль' if ru else 'oh'} {card(mi)}"
        return f"{card(h)} {card(mi)}"
    if re.match(r"^\d+[.,]\d+$", f):
        a, b = re.split(r"[.,]", f)
        card = ru_cardinal if ru else en_cardinal
        frac = " ".join(card(int(c)) for c in b) if b.startswith("0") else card(int(b))
        forms = ([f"{card(int(a))} и {frac}", f"{card(int(a))} точка {frac}", f"{card(int(a))} {frac}"] if ru
                 else [f"{card(int(a))} point {frac}", f"{card(int(a))} {frac}"])
        return forms[variant % len(forms)]
    if re.match(r"^\d+([\-/]\d+)+$", f):
        card = ru_cardinal if ru else en_cardinal
        return " ".join(card(int(p)) for p in re.split(r"[\-/]", f))
    if re.match(r"^\d+$", f):
        n = int(f)
        if ru:
            # after 1 the noun is nominative (feminine ends in а/я: "одна ошибка"); after 2 it is genitive
            # singular (feminine ends in ы/и: "две минуты", masculine in а/я: "два раза")
            end = fold(next_word)[-1:] if next_word else ""
            fem = n % 100 not in (11, 12) and ((n % 10 == 1 and end in ("а", "я")) or
                                                (n % 10 == 2 and end in ("ы", "и")))
            text = ru_cardinal(n, feminine=fem)
            if percent:
                text += " " + ru_plural(n, "процент", "процента", "процентов")
            return text
        if 1100 <= n <= 2099 and n % 100 != 0 and not (2000 <= n <= 2009) and variant % 2 == 1:
            text = f"{en_cardinal(n // 100)} {en_cardinal(n % 100) if n % 100 >= 10 else 'oh ' + en_cardinal(n % 100)}"
        else:
            text = en_cardinal(n)
        return text + (" percent" if percent else "")
    return None
