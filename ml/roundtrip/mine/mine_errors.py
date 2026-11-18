"""Mine round-trip results into per-ASR-model style profiles, confusion tables and a term-mangling catalogue.

Input: JSONL rows of interface 2 (ml/roundtrip/out/roundtrip/*.jsonl, or fixtures / adapters):
  {"probe_id", "lang", "text", "terms", "tts_system", "tts_voice", "channel", "audio", "audio_s",
   "asr_model", "hypothesis", "asr_wall_s"}  (+ optional "hyp_normalised": true when the hypothesis was
   already normalised by a dataset, or "style_comparable": false when the reference itself is unstyled
   lowercase text; either excludes the row from casing/punctuation/digit statistics)

Output directory:
  profiles.json         {asr_model: {...}}  style profile, WER/CER split by lang/channel and term vs non-term,
                         hallucination tails, and the rates the noise sampler draws from ("sampler")
  confusions.jsonl      {"asr_model","lang","ref","hyp","count","kind","in_term"}
  term_manglings.jsonl  {"term","category","lang_context","asr_model","variants":[{"hyp","count"}],"n",
                         "exact_rate","folded_rate","translit_rate","error_rate"}
  REPORT.md             human-readable summary

Deterministic: the same input rows in the same order give byte-identical outputs.

  python -m ml.roundtrip.mine.mine_errors ml/roundtrip/samples/mined/fixture_roundtrip.jsonl \\
      --out ml/roundtrip/samples/mined/fixture
"""
from __future__ import annotations

import argparse
import json
import sys
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from multiprocessing import Pool
from pathlib import Path
from typing import Any, Iterable, Sequence

from .align import (ERROR_KINDS, Alignment, EditCounts, TermHit, align, cer_counts, raw_wer_counts, term_hits)
from .norm import (cardinal_word_run, ends_with_terminal, fold, has_digit, has_upper, is_number_word,
                   is_numeric_token, punctuation_of, script_of)

TAIL_MIN_WORDS = 2         # trailing insertion runs at least this long are hallucination tails
CONFUSION_KINDS = ("sub", "sub_near", "del", "ins", "translit", "number", "punct")
REQUIRED = ("probe_id", "text", "asr_model", "hypothesis")
_OTHER_PUNCT = set(":;—–-\"«»„“”()[]")


# ----------------------------------------------------------------------------- IO

def read_jsonl(paths: Iterable[str | Path]) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for p in paths:
        with open(p, encoding="utf-8") as fh:
            for ln, line in enumerate(fh, 1):
                line = line.strip()
                if not line:
                    continue
                try:
                    row = json.loads(line)
                except json.JSONDecodeError as e:
                    raise SystemExit(f"{p}:{ln}: invalid JSON: {e}") from e
                missing = [k for k in REQUIRED if k not in row]
                if missing:
                    raise SystemExit(f"{p}:{ln}: missing fields {missing}")
                rows.append(row)
    return rows


def guess_lang(text: str) -> str:
    lat = sum(1 for c in text if "a" <= c.lower() <= "z")
    cyr = sum(1 for c in text if "Ѐ" <= c <= "ӿ")
    if cyr and lat:
        return "mixed"
    return "ru" if cyr else "en"


def _r(x: float) -> float:
    return round(float(x), 4)


def _rate(a: float, b: float) -> float | None:
    return _r(a / b) if b else None


def _top(counter: Counter, k: int) -> list[list[Any]]:
    return [[key, n] for key, n in sorted(counter.items(), key=lambda kv: (-kv[1], str(kv[0])))[:k]]


# ----------------------------------------------------------------------------- per-row analysis

@dataclass
class RowAnalysis:
    row: dict[str, Any]
    lang: str
    al: Alignment
    hits: list[TermHit]
    term_op_idx: set[int]
    raw: EditCounts
    cer: EditCounts
    tail: tuple[int, int]
    lead: tuple[int, int]


def analyse_row(row: dict[str, Any]) -> RowAnalysis:
    text = row.get("text") or ""
    hyp = row.get("hypothesis") or ""
    lang = row.get("lang") or guess_lang(text)
    al = align(text, hyp)
    terms = row.get("terms") or []
    hits = term_hits(al, terms)
    term_op_idx: set[int] = set()
    for h in hits:
        sel = [k for k, op in enumerate(al.ops) if op.r0 < h.r1 and op.r1 > h.r0]
        if sel:
            term_op_idx.update(range(sel[0], sel[-1] + 1))
    return RowAnalysis(row=row, lang=lang, al=al, hits=hits, term_op_idx=term_op_idx,
                       raw=raw_wer_counts(text, hyp), cer=cer_counts(text, hyp),
                       tail=al.trailing_insertions(), lead=al.leading_insertions())


# ----------------------------------------------------------------------------- aggregation

@dataclass
class _Bucket:
    raw: EditCounts = field(default_factory=EditCounts)
    cer: EditCounts = field(default_factory=EditCounts)
    errors: int = 0
    words: int = 0
    n: int = 0

    def add(self, a: RowAnalysis) -> None:
        self.raw.add(a.raw)
        self.cer.add(a.cer)
        self.errors += a.al.errors
        self.words += a.al.n_ref
        self.n += 1

    def as_dict(self) -> dict[str, Any]:
        return {"n": self.n, "words": self.words, "wer": _r(self.raw.rate), "wer_style_aware": _rate(self.errors, self.words),
                "cer": _r(self.cer.rate)}


@dataclass
class ModelStats:
    model: str
    n: int = 0
    n_empty: int = 0
    n_style_rows: int = 0
    langs: Counter = field(default_factory=Counter)
    tts: Counter = field(default_factory=Counter)
    channels: Counter = field(default_factory=Counter)
    total: _Bucket = field(default_factory=_Bucket)
    by_lang: dict[str, _Bucket] = field(default_factory=lambda: defaultdict(_Bucket))
    by_channel: dict[str, _Bucket] = field(default_factory=lambda: defaultdict(_Bucket))
    kinds: Counter = field(default_factory=Counter)
    term_words: int = 0
    term_errors: int = 0
    nonterm_words: int = 0
    nonterm_errors: int = 0
    # sampler rates over non-term reference words
    nt_sub: int = 0
    nt_near: int = 0
    nt_del: int = 0
    nt_ins: int = 0
    nonterm_words_nonempty: int = 0
    # casing
    has_upper: int = 0
    first_upper: int = 0
    all_lower: int = 0
    nonempty_style: int = 0
    proper_ref: int = 0
    proper_kept: int = 0
    # punctuation
    any_punct: int = 0
    ref_terminal: int = 0
    hyp_terminal_when_ref: int = 0
    ref_question: int = 0
    hyp_question_when_ref: int = 0
    ref_commas: int = 0
    hyp_commas: int = 0
    ref_other: int = 0
    hyp_other: int = 0
    # digits
    ref_numbers: int = 0
    num_digits: int = 0
    num_words: int = 0
    num_error: int = 0
    hyp_digit_rows: int = 0
    num_examples: Counter = field(default_factory=Counter)
    wnum_digits: int = 0   # runs of cardinal number words written as digits ("двадцать пять" -> "25")
    wnum_words: int = 0    # ... kept as words
    # latin
    ref_latin: int = 0
    lat_kept: int = 0
    lat_cyr: int = 0
    lat_error: int = 0
    latin_by_lang: dict[str, Counter] = field(default_factory=lambda: defaultdict(Counter))
    hyp_latin_rows: int = 0
    # yo
    ref_yo: int = 0
    yo_kept: int = 0
    # hallucinations
    tails: Counter = field(default_factory=Counter)
    leads: Counter = field(default_factory=Counter)
    n_tail: int = 0
    n_lead: int = 0
    overlong: int = 0
    # terms
    terms_n: int = 0
    terms_exact: int = 0
    terms_folded: int = 0
    terms_translit: int = 0
    terms_error: int = 0
    terms_by_lang: dict[str, Counter] = field(default_factory=lambda: defaultdict(Counter))
    # tables
    ref_word_counts: Counter = field(default_factory=Counter)
    rtf_sum: float = 0.0
    rtf_n: int = 0
    style_excluded_rows: int = 0


def _accumulate(st: ModelStats, a: RowAnalysis, conf: Counter, terms: dict, tail_min: int) -> None:
    row, al, lang = a.row, a.al, a.lang
    hyp = row.get("hypothesis") or ""
    channel = row.get("channel") or "clean"
    style_ok = not row.get("hyp_normalised", False) and row.get("style_comparable", True) is not False
    st.n += 1
    st.langs[lang] += 1
    st.tts[f"{row.get('tts_system', '?')}/{row.get('tts_voice', '?')}"] += 1
    st.channels[channel] += 1
    st.total.add(a)
    st.by_lang[lang].add(a)
    st.by_channel[channel].add(a)
    if not al.hyp:
        st.n_empty += 1
    if not style_ok:
        st.style_excluded_rows += 1
    audio_s, wall = row.get("audio_s"), row.get("asr_wall_s")
    if isinstance(audio_s, (int, float)) and isinstance(wall, (int, float)) and audio_s > 0 and wall > 0:
        st.rtf_sum += wall / audio_s
        st.rtf_n += 1

    tail_lo, tail_hi = a.tail
    is_tail = tail_hi - tail_lo >= tail_min
    lead_lo, lead_hi = a.lead
    is_lead = lead_hi - lead_lo >= tail_min
    if is_tail:
        st.n_tail += 1
        st.tails[al.hyp_text[al.hyp[tail_lo].start:].strip()] += 1  # keep its closing punctuation
    if is_lead:
        st.n_lead += 1
        st.leads[al.hyp_surface(lead_lo, lead_hi)] += 1
    if len(al.hyp) >= max(4, 2 * len(al.ref)):
        st.overlong += 1

    term_tokens: set[int] = set()
    for h in a.hits:
        term_tokens.update(range(h.r0, h.r1))
    for i, t in enumerate(al.ref):
        if i not in term_tokens and al.hyp:
            st.ref_word_counts[fold(t.core)] += 1

    empty = not al.hyp  # an empty hypothesis is one event (empty_rate), not N word deletions
    for k, op in enumerate(al.ops):
        st.kinds[op.kind] += max(op.n_ref, op.n_hyp)
        in_term = k in a.term_op_idx
        if in_term:
            st.term_errors += op.errors
        else:
            st.nonterm_errors += op.errors
            if empty:
                continue
            in_tail = op.kind == "ins" and ((is_tail and op.h0 >= tail_lo) or (is_lead and op.h1 <= lead_hi))
            if op.kind == "sub":
                st.nt_sub += max(op.n_ref, op.n_hyp)
            elif op.kind == "sub_near":
                st.nt_sub += max(op.n_ref, op.n_hyp)
                st.nt_near += max(op.n_ref, op.n_hyp)
            elif op.kind == "del":
                st.nt_del += op.n_ref
            elif op.kind == "ins" and not in_tail:
                st.nt_ins += op.n_hyp
            if in_tail:
                continue
        if op.kind in CONFUSION_KINDS and not empty:
            conf[(st.model, lang, fold(al.ref_span(op)), fold(al.hyp_span(op)), op.kind, in_term)] += 1

    st.term_words += len(term_tokens)
    st.nonterm_words += len(al.ref) - len(term_tokens)
    if not empty:
        st.nonterm_words_nonempty += len(al.ref) - len(term_tokens)

    # token-level style facts
    tok_op: dict[int, int] = {}
    for k, op in enumerate(al.ops):
        for i in range(op.r0, op.r1):
            tok_op[i] = k
    for i, t in enumerate(al.ref):
        op = al.ops[tok_op[i]]
        hspan = al.hyp_span(op)
        if style_ok and is_numeric_token(t.core):
            st.ref_numbers += 1
            if op.kind in ERROR_KINDS:
                st.num_error += 1
            elif has_digit(hspan):
                st.num_digits += 1
            else:
                st.num_words += 1
            if op.kind == "number" and i == op.r0:
                st.num_examples[(al.ref_span(op), hspan)] += 1
        if script_of(t.core) in ("lat", "mixed") and not is_numeric_token(t.core):
            st.ref_latin += 1
            if op.kind in ERROR_KINDS:
                st.lat_error += 1
                st.latin_by_lang[lang]["error"] += 1
            elif script_of(hspan) in ("lat", "mixed"):
                st.lat_kept += 1
                st.latin_by_lang[lang]["latin"] += 1
            else:
                st.lat_cyr += 1
                st.latin_by_lang[lang]["cyrillic"] += 1
            st.latin_by_lang[lang]["n"] += 1
        if style_ok and "ё" in t.core.lower() and op.kind not in ERROR_KINDS:
            st.ref_yo += 1
            st.yo_kept += "ё" in hspan.lower()
        if style_ok and i > 0 and t.core[:1].isupper() and op.kind in ("match", "case", "yo", "punct"):
            st.proper_ref += 1
            st.proper_kept += hspan[:1].isupper()

    if style_ok and al.hyp:
        _count_word_numbers(st, al, tok_op, term_tokens)
    if style_ok and al.hyp:
        st.nonempty_style += 1
        st.has_upper += has_upper(hyp)
        st.first_upper += al.hyp[0].core[:1].isupper()
        st.all_lower += not has_upper(hyp)
        st.any_punct += bool(punctuation_of(hyp))
        st.hyp_digit_rows += has_digit(hyp)
    if style_ok:
        st.n_style_rows += 1
        text = row.get("text") or ""
        rp, hp = punctuation_of(text), punctuation_of(hyp)
        if ends_with_terminal(text):
            st.ref_terminal += 1
            st.hyp_terminal_when_ref += ends_with_terminal(hyp)
        if text.rstrip().endswith("?"):
            st.ref_question += 1
            st.hyp_question_when_ref += hyp.rstrip().endswith("?")
        st.ref_commas += rp.count(",")
        st.hyp_commas += hp.count(",")
        st.ref_other += sum(1 for c in rp if c in _OTHER_PUNCT)
        st.hyp_other += sum(1 for c in hp if c in _OTHER_PUNCT)
    st.hyp_latin_rows += any(script_of(t.core) in ("lat", "mixed") for t in al.hyp)

    for h in a.hits if not empty else []:
        key = (h.term, h.category, lang, st.model)
        rec = terms.setdefault(key, {"variants": Counter(), "n": 0, "exact": 0, "folded": 0, "translit": 0,
                                     "error": 0})
        rec["variants"][h.hyp] += 1
        rec["n"] += 1
        rec["exact"] += h.exact
        rec["folded"] += h.folded_equal
        rec["translit"] += (not h.is_error) and "translit" in h.kinds
        rec["error"] += h.is_error
        st.terms_n += 1
        st.terms_exact += h.exact
        st.terms_folded += h.folded_equal
        st.terms_translit += (not h.is_error) and "translit" in h.kinds
        st.terms_error += h.is_error
        tl = st.terms_by_lang[lang]
        tl["n"] += 1
        tl["exact"] += h.exact
        tl["folded"] += h.folded_equal
        tl["error"] += h.is_error
        tl["latin_kept"] += script_of(h.hyp) in ("lat", "mixed")


def _count_word_numbers(st: ModelStats, al: Alignment, tok_op: dict[int, int], term_tokens: set[int]) -> None:
    """How the model writes reference numbers that were spelled out in words (eligible runs only)."""
    i, n = 0, len(al.ref)
    while i < n:
        if i in term_tokens or not is_number_word(al.ref[i].core):
            i += 1
            continue
        j = i
        while j < n and j not in term_tokens and is_number_word(al.ref[j].core):
            j += 1
        if cardinal_word_run([t.core for t in al.ref[i:j]]) is not None:
            ops = [al.ops[k] for k in sorted({tok_op[x] for x in range(i, j)})]
            if not any(op.kind in ERROR_KINDS for op in ops):
                if any(op.kind == "number" and has_digit(al.hyp_span(op)) for op in ops):
                    st.wnum_digits += 1
                else:
                    st.wnum_words += 1
        i = j


def _label(rate: float | None, hi: float, lo: float, names: tuple[str, str, str]) -> str:
    if rate is None:
        return "unknown"
    if rate >= hi:
        return names[0]
    if rate <= lo:
        return names[2]
    return names[1]


def _profile(st: ModelStats, conf: Counter) -> dict[str, Any]:
    ne = st.nonempty_style
    casing = {
        "has_upper_rate": _rate(st.has_upper, ne), "first_upper_rate": _rate(st.first_upper, ne),
        "all_lower_rate": _rate(st.all_lower, ne), "proper_case_kept_rate": _rate(st.proper_kept, st.proper_ref),
        "n_rows": ne,
    }
    casing["label"] = ("lowercase" if (casing["all_lower_rate"] or 0) >= 0.9 else
                       "sentence" if (casing["first_upper_rate"] or 0) >= 0.7 else "mixed") if ne else "unknown"
    punct = {
        "any_rate": _rate(st.any_punct, ne), "terminal_kept_rate": _rate(st.hyp_terminal_when_ref, st.ref_terminal),
        "question_kept_rate": _rate(st.hyp_question_when_ref, st.ref_question),
        "comma_ratio": _rate(st.hyp_commas, st.ref_commas), "other_ratio": _rate(st.hyp_other, st.ref_other),
        "n_rows": st.n_style_rows,
    }
    punct["label"] = ("unknown" if not ne else "none" if (punct["any_rate"] or 0) < 0.1 else
                      "full" if (punct["terminal_kept_rate"] or 0) >= 0.7 else "partial")
    style_num = st.num_digits + st.num_words
    digits = {
        "ref_numbers": st.ref_numbers, "kept_digits_rate": _rate(st.num_digits, style_num),
        "spelled_rate": _rate(st.num_words, style_num), "error_rate": _rate(st.num_error, st.ref_numbers),
        "hyp_digit_rate": _rate(st.hyp_digit_rows, ne),
        "word_numbers": st.wnum_digits + st.wnum_words,
        "words_to_digits_rate": _rate(st.wnum_digits, st.wnum_digits + st.wnum_words),
        "examples": [[k[0], k[1], n] for k, n in sorted(st.num_examples.items(), key=lambda kv: (-kv[1], kv[0]))[:8]],
    }
    digits["label"] = _label(digits["kept_digits_rate"], 0.7, 0.3, ("digits", "mixed", "words"))
    style_lat = st.lat_kept + st.lat_cyr
    latin = {
        "ref_latin_words": st.ref_latin, "kept_latin_rate": _rate(st.lat_kept, style_lat),
        "cyrillic_rate": _rate(st.lat_cyr, style_lat), "error_rate": _rate(st.lat_error, st.ref_latin),
        "hyp_latin_rate": _rate(st.hyp_latin_rows, st.n),
        "by_lang": {lg: {"n": c["n"], "kept_latin_rate": _rate(c["latin"], c["latin"] + c["cyrillic"]),
                         "error_rate": _rate(c["error"], c["n"])} for lg, c in sorted(st.latin_by_lang.items())},
    }
    latin["label"] = _label(latin["kept_latin_rate"], 0.7, 0.3, ("latin", "mixed", "cyrillic"))
    hall = {
        "tail_rate": _rate(st.n_tail, st.n), "lead_rate": _rate(st.n_lead, st.n), "empty_rate": _rate(st.n_empty, st.n),
        "overlong_rate": _rate(st.overlong, st.n), "tails": _top(st.tails, 10), "leads": _top(st.leads, 5),
    }
    terms = {
        "n": st.terms_n, "exact_rate": _rate(st.terms_exact, st.terms_n),
        "folded_rate": _rate(st.terms_folded, st.terms_n), "translit_rate": _rate(st.terms_translit, st.terms_n),
        "error_rate": _rate(st.terms_error, st.terms_n),
        "by_lang": {lg: {"n": c["n"], "exact_rate": _rate(c["exact"], c["n"]), "folded_rate": _rate(c["folded"], c["n"]),
                         "error_rate": _rate(c["error"], c["n"]), "latin_kept_rate": _rate(c["latin_kept"], c["n"])}
                    for lg, c in sorted(st.terms_by_lang.items())},
    }
    ins_words: Counter = Counter()
    del_words: Counter = Counter()
    sub_targets: set[str] = set()
    for (m, _lg, ref, hyp, kind, in_term), n in conf.items():
        if m != st.model or in_term:
            continue
        if kind == "ins":
            ins_words[hyp] += n
        elif kind == "del":
            del_words[ref] += n
            sub_targets.add(ref)
        elif kind in ("sub", "sub_near"):
            sub_targets.add(ref)
    sampler = {
        "p_sub": _rate(st.nt_sub, st.nonterm_words_nonempty) or 0.0,
        "p_del": _rate(st.nt_del, st.nonterm_words_nonempty) or 0.0,
        "p_ins": _rate(st.nt_ins, st.nonterm_words_nonempty) or 0.0, "near_share": _rate(st.nt_near, st.nt_sub) or 0.0,
        "nonterm_words": st.nonterm_words_nonempty,
        "confusable_words": {w: st.ref_word_counts[w] for w in sorted(sub_targets) if st.ref_word_counts[w]},
        "ins_words": _top(ins_words, 30), "del_words": _top(del_words, 30),
    }
    return {
        "n": st.n, "n_ref_words": st.total.words, "langs": dict(sorted(st.langs.items())),
        "tts": dict(sorted(st.tts.items())), "channels": dict(sorted(st.channels.items())),
        "wer": _r(st.total.raw.rate), "wer_style_aware": _rate(st.total.errors, st.total.words),
        "cer": _r(st.total.cer.rate),
        "wer_by_lang": {k: v.as_dict() for k, v in sorted(st.by_lang.items())},
        "wer_by_channel": {k: v.as_dict() for k, v in sorted(st.by_channel.items())},
        "term_wer": _rate(st.term_errors, st.term_words), "nonterm_wer": _rate(st.nonterm_errors, st.nonterm_words),
        "term_words": st.term_words, "nonterm_words": st.nonterm_words,
        "kinds": {k: st.kinds[k] for k in sorted(st.kinds)},
        "casing": casing, "punctuation": punct, "digits": digits, "latin": latin,
        "yo": {"ref_yo_words": st.ref_yo, "kept_rate": _rate(st.yo_kept, st.ref_yo)},
        "hallucination": hall, "terms": terms, "sampler": sampler,
        "rtf": _rate(st.rtf_sum, st.rtf_n), "style_excluded_rows": st.style_excluded_rows,
    }


@dataclass
class MinedTables:
    profiles: dict[str, dict[str, Any]]
    confusions: list[dict[str, Any]]
    term_manglings: list[dict[str, Any]]
    n_rows: int
    n_skipped: int


def mine_rows(rows: Sequence[dict[str, Any]], jobs: int = 1, tail_min: int = TAIL_MIN_WORDS) -> MinedTables:
    """Align every row and aggregate. Rows with a null hypothesis (failed recognition) are skipped."""
    usable = [r for r in rows if r.get("hypothesis") is not None]
    if jobs > 1 and len(usable) > 50:
        with Pool(jobs) as pool:
            analyses = pool.map(analyse_row, usable, chunksize=32)
    else:
        analyses = [analyse_row(r) for r in usable]
    stats: dict[str, ModelStats] = {}
    conf: Counter = Counter()
    terms: dict[tuple, dict] = {}
    for a in analyses:
        m = a.row["asr_model"]
        st = stats.setdefault(m, ModelStats(model=m))
        _accumulate(st, a, conf, terms, tail_min)
    profiles = {m: _profile(stats[m], conf) for m in sorted(stats)}
    confusions = [
        {"asr_model": m, "lang": lg, "ref": ref, "hyp": hyp, "count": n, "kind": kind, "in_term": in_term}
        for (m, lg, ref, hyp, kind, in_term), n in sorted(conf.items(), key=lambda kv: (kv[0][0], -kv[1], kv[0][1:]))
    ]
    manglings = []
    for (term, cat, lg, m), rec in sorted(terms.items(), key=lambda kv: (kv[0][3], kv[0][0], kv[0][2], kv[0][1])):
        n = rec["n"]
        manglings.append({
            "term": term, "category": cat, "lang_context": lg, "asr_model": m,
            "variants": [{"hyp": h, "count": c} for h, c in sorted(rec["variants"].items(), key=lambda kv: (-kv[1], kv[0]))],
            "n": n, "exact_rate": _r(rec["exact"] / n), "folded_rate": _r(rec["folded"] / n),
            "translit_rate": _r(rec["translit"] / n), "error_rate": _r(rec["error"] / n),
        })
    return MinedTables(profiles, confusions, manglings, len(usable), len(rows) - len(usable))


# ----------------------------------------------------------------------------- report

def _pct(x: float | None) -> str:
    return "–" if x is None else f"{100 * x:.1f}%"


def _undo_list(p: dict[str, Any]) -> list[str]:
    out = []
    c, pu, d, la, h, t = p["casing"], p["punctuation"], p["digits"], p["latin"], p["hallucination"], p["terms"]
    if c["label"] == "lowercase":
        out.append("restore all casing (sentence starts, names, product names)")
    elif (c.get("proper_case_kept_rate") or 1) < 0.8:
        out.append(f"restore capitalisation of names/terms (kept only {_pct(c['proper_case_kept_rate'])})")
    if pu["label"] == "none":
        out.append("restore all punctuation")
    elif pu["label"] == "partial" or (pu.get("comma_ratio") or 1) < 0.6:
        out.append(f"restore missing punctuation (commas at {_pct(pu['comma_ratio'])} of reference)")
    if d["label"] in ("words", "mixed"):
        out.append(f"convert spoken numbers, times and dates to digits ({_pct(d['spelled_rate'])} spelled out)")
    if (d.get("words_to_digits_rate") or 0) > 0.3:
        out.append(f"spell out again numbers the writer wanted in words ({_pct(d['words_to_digits_rate'])} of "
                   "spelled-out numbers come back as digits)")
    if la["ref_latin_words"] and la["label"] in ("cyrillic", "mixed"):
        out.append(f"turn Cyrillic transliterations back into Latin terms ({_pct(la['cyrillic_rate'])} of Latin words)")
    if (t.get("error_rate") or 0) > 0.15:
        out.append(f"repair garbled terms ({_pct(t['error_rate'])} of terms are recognition errors) — from context only")
    if (h.get("tail_rate") or 0) > 0.02:
        out.append(f"drop hallucinated tails ({_pct(h['tail_rate'])} of utterances)")
    if (p["sampler"]["p_del"] or 0) > 0.03:
        out.append(f"tolerate dropped words ({_pct(p['sampler']['p_del'])} of non-term words) without inventing content")
    return out or ["little beyond light punctuation fixes"]


def render_report(tables: MinedTables, title: str, sources: Sequence[str], top: int = 8) -> str:
    P = tables.profiles
    L: list[str] = [f"# {title}", ""]
    L.append(f"Rows mined: {tables.n_rows} (skipped with null hypothesis: {tables.n_skipped}). "
             f"Sources: {', '.join(sources)}.")
    L += ["", "WER is the conventional normalised WER (case, punctuation and ё folded; nothing else forgiven). "
          "Style-aware WER additionally forgives digits-vs-words and faithful Latin<->Cyrillic transliteration, "
          "so it counts only real recognition errors. Term WER is style-aware WER over the annotated hard terms.", ""]
    L += ["## Per model", "",
          "| ASR model | rows | WER | style-aware WER | CER | term WER | non-term WER | term exact | casing | punct | numbers | Latin terms | tails |",
          "|---|---:|---:|---:|---:|---:|---:|---:|---|---|---|---|---:|"]
    for m, p in P.items():
        L.append(f"| `{m}` | {p['n']} | {_pct(p['wer'])} | {_pct(p['wer_style_aware'])} | {_pct(p['cer'])} | "
                 f"{_pct(p['term_wer'])} | {_pct(p['nonterm_wer'])} | {_pct(p['terms']['exact_rate'])} | "
                 f"{p['casing']['label']} | {p['punctuation']['label']} | {p['digits']['label']} | "
                 f"{p['latin']['label']} | {_pct(p['hallucination']['tail_rate'])} |")
    L += ["", "## WER by language", "", "| ASR model | lang | rows | WER | style-aware WER | CER |", "|---|---|---:|---:|---:|---:|"]
    for m, p in P.items():
        for lg, b in p["wer_by_lang"].items():
            L.append(f"| `{m}` | {lg} | {b['n']} | {_pct(b['wer'])} | {_pct(b['wer_style_aware'])} | {_pct(b['cer'])} |")
    if any(len(p["wer_by_channel"]) > 1 for p in P.values()):
        L += ["", "## WER by channel", "", "| ASR model | channel | rows | WER | style-aware WER |", "|---|---|---:|---:|---:|"]
        for m, p in P.items():
            for ch, b in p["wer_by_channel"].items():
                L.append(f"| `{m}` | {ch} | {b['n']} | {_pct(b['wer'])} | {_pct(b['wer_style_aware'])} |")
    L += ["", "## Output style", "",
          "| ASR model | upper-case anywhere | first letter upper | names kept capitalised | any punctuation | terminal kept | comma ratio | numbers as digits | Latin kept | ё kept |",
          "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|"]
    for m, p in P.items():
        c, pu, d, la = p["casing"], p["punctuation"], p["digits"], p["latin"]
        L.append(f"| `{m}` | {_pct(c['has_upper_rate'])} | {_pct(c['first_upper_rate'])} | {_pct(c['proper_case_kept_rate'])} | "
                 f"{_pct(pu['any_rate'])} | {_pct(pu['terminal_kept_rate'])} | {_pct(pu['comma_ratio'])} | "
                 f"{_pct(d['kept_digits_rate'])} | {_pct(la['kept_latin_rate'])} | {_pct(p['yo']['kept_rate'])} |")
    by_model: dict[str, list[dict]] = defaultdict(list)
    for t in tables.term_manglings:
        by_model[t["asr_model"]].append(t)
    conf_by_model: dict[str, list[dict]] = defaultdict(list)
    for c in tables.confusions:
        conf_by_model[c["asr_model"]].append(c)
    for m, p in P.items():
        L += ["", f"## `{m}`", ""]
        L.append("What the cleanup LLM must learn to undo:")
        L += [f"- {x}" for x in _undo_list(p)]
        mang = [t for t in by_model.get(m, []) if t["exact_rate"] < 1.0]
        mang.sort(key=lambda t: (t["exact_rate"], -t["n"], t["term"]))
        if mang:
            L += ["", "Top term manglings:", "", "| term | context | n | exact | variants |", "|---|---|---:|---:|---|"]
            for t in mang[:top]:
                var = "; ".join(f"{v['hyp'] or '∅'} ×{v['count']}" for v in t["variants"][:4])
                L.append(f"| {t['term']} | {t['lang_context']} | {t['n']} | {_pct(t['exact_rate'])} | {var} |")
        errs = [c for c in conf_by_model.get(m, []) if c["kind"] in ERROR_KINDS and not c["in_term"]]
        if errs:
            L += ["", "Top non-term recognition errors:", "", "| kind | ref | hyp | count |", "|---|---|---|---:|"]
            for c in errs[:top]:
                L.append(f"| {c['kind']} | {c['ref'] or '∅'} | {c['hyp'] or '∅'} | {c['count']} |")
        if p["digits"]["examples"]:
            ex = "; ".join(f"{r} → {h}" for r, h, _ in p["digits"]["examples"][:4])
            L += ["", f"Number style examples: {ex}"]
        if p["hallucination"]["tails"]:
            ex = "; ".join(f"“{t}” ×{n}" for t, n in p["hallucination"]["tails"][:4])
            L += ["", f"Hallucinated tails: {ex}"]
    L.append("")
    return "\n".join(L)


def write_outputs(tables: MinedTables, out: Path, title: str, sources: Sequence[str]) -> None:
    out.mkdir(parents=True, exist_ok=True)
    (out / "profiles.json").write_text(json.dumps(tables.profiles, ensure_ascii=False, indent=1, sort_keys=True) + "\n",
                                       encoding="utf-8")
    with open(out / "confusions.jsonl", "w", encoding="utf-8") as fh:
        for c in tables.confusions:
            fh.write(json.dumps(c, ensure_ascii=False, sort_keys=True) + "\n")
    with open(out / "term_manglings.jsonl", "w", encoding="utf-8") as fh:
        for t in tables.term_manglings:
            fh.write(json.dumps(t, ensure_ascii=False, sort_keys=True) + "\n")
    (out / "REPORT.md").write_text(render_report(tables, title, sources), encoding="utf-8")


def main(argv: Sequence[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0],
                                 formatter_class=argparse.RawDescriptionHelpFormatter, epilog=__doc__)
    ap.add_argument("inputs", nargs="+", help="round-trip result JSONL files (interface 2)")
    ap.add_argument("--out", required=True, help="output directory for profiles.json, confusions.jsonl, ...")
    ap.add_argument("--title", default="ASR error mining report")
    ap.add_argument("--jobs", type=int, default=1, help="alignment worker processes (output is identical)")
    ap.add_argument("--models", nargs="*", help="only mine these asr_model ids")
    ap.add_argument("--tail-min", type=int, default=TAIL_MIN_WORDS,
                    help="trailing insertion run length counted as a hallucination tail")
    args = ap.parse_args(argv)
    rows = read_jsonl(args.inputs)
    if args.models:
        rows = [r for r in rows if r["asr_model"] in set(args.models)]
    if not rows:
        print("no rows", file=sys.stderr)
        return 1
    tables = mine_rows(rows, jobs=args.jobs, tail_min=args.tail_min)
    sources = [Path(p).name for p in args.inputs]
    write_outputs(tables, Path(args.out), args.title, sources)
    print(f"mined {tables.n_rows} rows, {len(tables.profiles)} models -> {args.out}")
    for m, p in tables.profiles.items():
        print(f"  {m}: n={p['n']} wer={p['wer']} style_wer={p['wer_style_aware']} term_exact={p['terms']['exact_rate']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
