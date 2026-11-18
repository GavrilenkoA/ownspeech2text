"""Strict filters for public (input, output) cleanup pairs.

Policy: an example that teaches the model to answer, paraphrase, summarise,
restructure, translate or invent is worse than no example. Each filter returns
a reason code; a pair is kept only with zero reasons. Thresholds are
deliberately tight; the stats report shows what each one removed.
"""
from __future__ import annotations

import re

from ml.common import text as T

FORMAT_CMD_EN = ["period", "full stop", "comma", "new line", "new paragraph", "question mark", "colon",
                 "exclamation mark", "exclamation point", "open quote", "close quote", "semicolon", "hyphen", "dash"]
FORMAT_CMD_RU = ["точка", "запятая", "новая строка", "новый абзац", "вопросительный знак", "двоеточие",
                 "восклицательный знак", "открыть кавычки", "закрыть кавычки", "тире", "с новой строки"]
ENUM_WORDS = ["first", "second", "third", "firstly", "secondly", "number one", "number two", "во-первых",
              "во-вторых", "в-третьих", "первое", "второе", "третье", "пункт", "one", "two", "three"]
TIME_MONEY_UNITS = r"(am|pm|a\.m\.|p\.m\.|o'clock|dollars?|bucks|pounds?|euros?|percent|cents?|rubles?|рубл\w*|" \
                   r"процент\w*|евро|доллар\w*|час\w*|минут\w*|утра|вечера|дня|ночи)"
NUM_WORDS_RE = None  # built lazily


EXTRA_SC_EN = ["never mind", "nevermind", "i meant", "no i meant", "forget that", "my mistake", "make it",
               "change that to", "hold on", "not that", "wait", "hang on", "that's wrong", "or rather", "rather",
               "i meant to say", "let me correct"]
SOFT_FILLERS = {"like", "basically", "okay", "ok", "actually", "literally", "so", "well", "right", "yeah", "anyway",
                "honestly", "just", "really", "kinda", "sorta", "oh", "alright", "see", "ну", "вот", "короче", "типа",
                "значит", "слушай", "это", "самое", "вообще", "просто", "кстати"}
LEFT_IN_RE = re.compile(r"(?i)(no wait|scratch that|or no|no,? actually|sorry,? i mean|wait,? no|"
                        r"ой,? нет|точнее говоря|нет,? стоп)")
SPLICE_RE = (re.compile(r"[a-z][.] [A-Z][a-z]"), re.compile(r"[a-z][.] [a-z]"))


def _sc_marker(inp_norm: str, lang: str) -> bool:
    marks = T.SC_MARKERS_RU if lang == "ru" else T.SC_MARKERS_EN + EXTRA_SC_EN
    toks = inp_norm.split()
    return any(T.contains_phrase(toks, m) for m in marks if m not in ("actually", "sorry", "ой", "нет"))


def _drop_phrases(nw: list[str], phrases: list[str]) -> list[str]:
    ps = sorted((p.split() for p in phrases), key=len, reverse=True)
    out, i = [], 0
    while i < len(nw):
        for p in ps:
            if nw[i:i + len(p)] == p:
                i += len(p)
                break
        else:
            out.append(nw[i])
            i += 1
    return out


SYMBOL_WORDS = {"$": ["dollar", "dollars", "bucks", "cents"], "€": ["euro", "euros", "cents"], "£": ["pound", "pounds", "pence", "quid"],
                "₹": ["rupee", "rupees"], "%": ["percent", "per", "cent", "процент", "процента", "процентов"],
                "₽": ["рубль", "рубля", "рублей", "копеек"], "°": ["degree", "degrees", "градус", "градуса", "градусов"],
                "@": ["at", "собака"], "#": ["hash", "hashtag", "number"], "&": ["and"], "+": ["plus"]}


def _corrections_by_no(nw: list[str], lang: str) -> bool:
    """'thirty dollars no forty dollars', 'в три нет в четыре': a bare no/нет that
    separates two parallel phrases is a self-correction, not a negation."""
    neg = "нет" if lang == "ru" else "no"
    for i, w in enumerate(nw):
        if w != neg or i == 0 or i == len(nw) - 1:
            continue
        before = [x for x in nw[max(0, i - 6):i] if x not in T.HESITATION]
        after = [x for x in nw[i + 1:i + 7] if x not in T.HESITATION]
        if not before or not after:
            continue
        if (_is_number_word(after[0]) or after[0].isdigit()) and any(_is_number_word(b) or b.isdigit() for b in before):
            return True
        shared = set(before) & set(after)
        if shared - T.FUNCTION_WORDS or len(shared) >= 2:
            return True
    return False


def _is_number_word(w: str) -> bool:
    return w in T.RU_NUM or T._en_value(w) is not None or w in ("point", "and", "запятая", "целых")


def _strip_markers(nw: list[str], lang: str) -> list[str]:
    """Remove self-correction marker phrases so 'no wait' does not count as a negation."""
    marks = sorted(T.SC_MARKERS_RU if lang == "ru" else T.SC_MARKERS_EN, key=lambda m: -len(m.split()))
    out, i = [], 0
    while i < len(nw):
        for m in marks:
            p = m.split()
            if nw[i:i + len(p)] == p and len(p) > 1:
                i += len(p)
                break
        else:
            out.append(nw[i])
            i += 1
    return out


def _novel_content(inp: str, out: str) -> list[str]:
    """Output content words with no close match among input words (spelling
    fixes up to similarity 0.75 are tolerated, numbers handled elsewhere)."""
    iw = set(T.norm_words(inp))
    ikeys = {T.phonetic_key(w) for w in iw}
    joined = T.phonetic_key(inp)
    novel = []
    for w in T.content_words(out):
        if w in iw:
            continue
        parts = [p for p in re.split(r"[-']", w) if p]
        if parts and all(p in iw or p in T.FUNCTION_WORDS for p in parts):
            continue
        k = T.phonetic_key(w)
        if k in ikeys or (len(k) >= 5 and k in joined):
            continue
        if any(T.similarity(k, ik) >= 0.75 for ik in ikeys):
            continue
        novel.append(w)
    return novel


def _stray_comma(inp: str, out: str) -> bool:
    """'be, uh, more' -> 'be, more': a comma left where a filler was removed."""
    toks = re.findall(r"[\w'’-]+|[,.;!?]", inp.lower())
    lo = out.lower()
    for i, t in enumerate(toks):
        if t not in T.HESITATION:
            continue
        j = i - 1
        while j >= 0 and toks[j] == ",":
            j -= 1
        k = i + 1
        while k < len(toks) and (toks[k] == "," or toks[k] in T.HESITATION):
            k += 1
        if j < 0 or k >= len(toks) or not toks[j][0].isalnum() or not toks[k][0].isalnum():
            continue
        p, n = toks[j], toks[k]
        if toks[i - 1] != ",":
            continue  # comma before the filler in the input is what gets left behind
        if f"{p}, {n}" in lo and f"{p}, {n}" not in re.sub(r"\s+", " ", inp.lower()):
            return True
    return bool(re.search(r",\s*,|\s,\s|,\s*[.!?]", out))


def pair_reasons(inp: str, out: str, lang: str, *, allow_lists: bool = True, max_words: int = 300) -> list[str]:
    r: list[str] = []
    inp_s, out_s = inp.strip(), out.strip()
    if not inp_s:
        return ["empty_input"]
    in_nw = T.norm_words(inp_s)
    out_nw = T.norm_words(out_s)
    if not out_s:
        return ["empty_output"] if T.content_words(inp_s) else []
    if len(in_nw) > max_words:
        r.append("too_long")
    if "\n" in inp_s or "—" in inp_s or "–" in inp_s or ";" in inp_s:
        r.append("input_not_asr_like")
    if SPLICE_RE[0].search(inp_s) and SPLICE_RE[1].search(inp_s):
        r.append("spliced_input")
    # identity
    if inp_s == out_s:
        formatted = bool(re.search(r"[A-ZА-ЯЁ]", inp_s)) and bool(re.search(r"[.!?,]", inp_s))
        if not formatted:
            r.append("identity_unformatted")
        return r
    # structure / markdown
    lines = out_s.splitlines()
    if any(re.match(r"\s*(#|\*\*|- \[|\[ \]|\||>)", l) for l in lines) or "**" in out_s:
        r.append("markdown")
    if any(re.match(r"\s*[-*•]\s", l) for l in lines):
        r.append("bullets")
    numbered = [l for l in lines if re.match(r"\s*\d+[.)]\s", l)]
    if numbered:
        enum = any(T.contains_phrase(in_nw, w) for w in ENUM_WORDS)
        if not (allow_lists and enum):
            r.append("restructured_list")
    if re.search(r"(?i)\b(action items?|attendees|summary|key points|takeaways)\s*:", out_s) and \
            not re.search(r"(?i)\b(action items?|attendees|summary|key points|takeaways)\b", inp_s):
        r.append("invented_section")
    # language
    if T.guess_lang(out_s) and T.guess_lang(inp_s) and T.guess_lang(out_s) != T.guess_lang(inp_s):
        r.append("language_changed")
    if T.has_preamble(out_s) and not T.has_preamble(inp_s):
        r.append("preamble")
    # length & retention (spoken commands/enumerators become punctuation, number words become digits)
    cmds = (FORMAT_CMD_RU if lang == "ru" else FORMAT_CMD_EN) + ["bullet", "bullet point", "open parenthesis",
                                                                  "close parenthesis", "quote", "unquote"]
    cmds = [c for c in cmds if not T.contains_phrase(out_nw, c)]
    in_core = [w for w in _drop_phrases(in_nw, cmds) if w not in T.HESITATION]
    sc = _sc_marker(" ".join(in_nw), lang) or _corrections_by_no(in_nw, lang)
    n_in = sum(1 for w in in_core if not _is_number_word(w)) + len(T.numbers_in(" ".join(in_core), lang))
    n_out = sum(1 for w in out_nw if not w.isdigit()) + len(T.numbers_in(out_s, lang))
    ratio = n_out / max(1, n_in)
    if ratio > 1.25:
        r.append("output_longer")
    if ratio < (0.3 if sc else 0.55):
        r.append("over_deletion")
    in_cw = [w for w in T.content_words(" ".join(in_core)) if not _is_number_word(w) and w not in SOFT_FILLERS]
    out_cw = set(T.content_words(out_s))
    for sym, ws in SYMBOL_WORDS.items():
        if sym in out_s:
            out_cw.update(ws)
    out_keys = {T.phonetic_key(w) for w in out_cw}
    if in_cw:
        okj = "".join(sorted(out_keys))
        out_key_str = T.phonetic_key(out_s)

        def _kept(w):
            if w in out_cw:
                return True
            k = T.phonetic_key(w)
            return k in out_keys or (len(k) >= 3 and k in out_key_str) or any(
                T.similarity(k, ok) >= 0.75 for ok in out_keys)
        kept = sum(1 for w in in_cw if _kept(w))
        ret = kept / len(in_cw)
        if ret < (0.5 if sc else 0.75):
            r.append("low_retention")
    novel = _novel_content(inp_s, out_s)
    if len(novel) > max(1, len(out_cw) // 20):
        r.append("novel_content")
    # meaning guards
    neg = T.NEGATIONS_RU if lang == "ru" else T.NEGATIONS_EN
    in_neg = sum(1 for w in _strip_markers(in_nw, lang) if w in neg)
    out_neg = sum(1 for w in _strip_markers(out_nw, lang) if w in neg)
    if out_neg < in_neg and not sc:
        r.append("negation_lost")
    if sum(1 for w in out_nw if w in neg) > sum(1 for w in in_nw if w in neg):
        r.append("negation_added")
    if LEFT_IN_RE.search(out_s):
        r.append("correction_left_in")
    for hdg in (T.HEDGES_RU if lang == "ru" else T.HEDGES_EN):
        if T.contains_phrase(in_nw, hdg) > T.contains_phrase(out_nw, hdg) and not sc:
            r.append("hedge_lost")
            break
    # numbers: style and consistency
    if T.spelled_numbers_over(out_s, 10, lang):
        r.append("number_words_in_target")
    elif re.search(r"(?i)\b(" + "|".join(list(T._EN_UNITS) + list(T._EN_TENS) + T._RU_U[1:]) + r")\s+" + TIME_MONEY_UNITS + r"\b",
                   out_s):
        r.append("number_words_in_target")
    if not sc and T.numbers_in(out_s, lang) and not set(T.numbers_in(out_s, lang)) <= set(T.numbers_in(inp_s, lang)):
        r.append("number_changed")
    # sloppiness
    if _stray_comma(inp_s, out_s):
        r.append("stray_comma")
    if re.search(r"[.!?]\s+[a-zа-яё]", out_s) or re.match(r"[a-zа-яё]", out_s):
        r.append("lowercase_sentence_start")
    if re.search(r"\b[A-ZА-Я]{3,}\s+[A-ZА-Я]{3,}\b", out_s):
        r.append("allcaps_words")
    if re.search(r"(?<![\w.'’])[A-Z] [A-Z](?![\w'’])", out_s):
        r.append("spaced_letters")
    if re.search(r"(?i)\b(lets|weve|im|dont|didnt|cant|wont|isnt|thats|youre|theyre|ive|doesnt)\b", out_s):
        r.append("bad_contraction")
    last = [l for l in out_s.splitlines() if l.strip()][-1]
    if not re.search(r"[.!?…:)\"»]\s*$", last) and len(T.words(last)) >= 6:
        r.append("truncated_or_unterminated")
    return r


def category_of(inp: str, out: str, lang: str, hint: str | None = None) -> str:
    in_nw = T.norm_words(inp)
    if inp.strip() == out.strip():
        return "no_op"
    if _sc_marker(" ".join(in_nw), lang):
        return "self_correction"
    cmds = FORMAT_CMD_RU if lang == "ru" else FORMAT_CMD_EN
    out_nw = T.norm_words(out)
    if any(T.contains_phrase(in_nw, c) and not T.contains_phrase(out_nw, c) for c in cmds):
        return "formatting_commands"
    if re.search(r"(?m)^\s*\d+[.)]\s", out):
        return "list"
    if re.search(r"\d", out) and T.spelled_numbers_over(inp, 0, lang):
        return "numbers"
    if out.rstrip().endswith("?"):
        return "dictated_question"
    return hint or "plain"
