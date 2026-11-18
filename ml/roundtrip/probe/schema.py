"""Probe-sentence record: the binding JSONL interface and its per-record checks.

  {"id": str, "lang": "ru"|"en"|"mixed", "domain": str, "text": str,
   "terms": [{"term": str, "category": str}], "tts_text": str|null}

``text`` is the intended final written form. ``terms`` lists the hard terms in
``text`` with their exact surface form (inflected where Russian grammar
inflects them). ``tts_text`` is an optional pronunciation-friendly variant:
for ru/mixed it is Cyrillic-only (Latin terms respelled), for en it replaces
spellings an English voice would misread ("k8s" -> "kubernetes").
"""
from __future__ import annotations

import json
import re
import unicodedata
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Iterator, TypedDict

from ml.roundtrip.probe.lexicon import (
    EXTRA_PROBE_CATEGORIES,
    LEXICON_CATEGORIES,
    TermMatcher,
    surface_occurs,
)
from ml.roundtrip.probe.textutil import detect_lang, has_cyrillic, has_latin, word_count

LANGS: tuple[str, ...] = ("ru", "en", "mixed")
DOMAINS: tuple[str, ...] = (
    "messenger",     # personal / casual chat replies
    "work_chat",     # Slack / Telegram work threads, status pings
    "email",         # e-mail bodies and subjects
    "notes",         # personal notes, todo items
    "standup",       # stand-up / status updates
    "commit_pr",     # commit messages, PR / MR descriptions, review comments
    "ai_prompt",     # prompts dictated to an AI assistant
    "terminal",      # terminal-ish requests, commands said aloud
    "calendar",      # meeting scheduling, reminders
    "docs",          # documentation, tickets, specs
)
RECORD_KEYS = ("id", "lang", "domain", "text", "terms", "tts_text")
REQUIRED_KEYS = ("id", "lang", "domain", "text", "terms")
_ID_RE = re.compile(r"^[a-z0-9][a-z0-9._-]{0,63}$")
_END_RE = re.compile(r"[.!?…][)\"»”']?$")


class ProbeTerm(TypedDict):
    term: str
    category: str


class ProbeRecord(TypedDict, total=False):
    id: str
    lang: str
    domain: str
    text: str
    terms: list[ProbeTerm]
    tts_text: str | None


@dataclass(frozen=True)
class Limits:
    min_words: int = 6
    max_words: int = 35
    min_lexicon_terms: int = 1
    max_lexicon_terms: int = 4
    max_person_terms: int = 3
    require_tts_for_mixed: bool = True
    allowed_domains: tuple[str, ...] | None = DOMAINS  # None = any non-empty string


def check_record(rec: object, matcher: TermMatcher | None, limits: Limits = Limits()) -> list[str]:
    """All problems with one record; empty list means valid.

    With a matcher, every non-person term must resolve to a lexicon entry of
    the same category.
    """
    if not isinstance(rec, dict):
        return ["record is not a JSON object"]
    out: list[str] = []
    missing = [k for k in REQUIRED_KEYS if k not in rec]
    extra = [k for k in rec if k not in RECORD_KEYS]
    if missing:
        out.append(f"missing keys {missing}")
    if extra:
        out.append(f"unknown keys {extra}")
    if missing:
        return out

    rid, lang, domain, text, terms = rec["id"], rec["lang"], rec["domain"], rec["text"], rec["terms"]
    tts = rec.get("tts_text")
    if not isinstance(rid, str) or not _ID_RE.match(rid):
        out.append(f"bad id {rid!r}")
    if lang not in LANGS:
        out.append(f"bad lang {lang!r}")
    if not isinstance(domain, str) or not domain:
        out.append("domain must be a non-empty string")
    elif limits.allowed_domains is not None and domain not in limits.allowed_domains:
        out.append(f"unknown domain {domain!r}")
    if not isinstance(text, str) or not text.strip():
        out.append("text must be a non-empty string")
        return out
    out.extend(_check_text(text, "text"))
    n_words = word_count(text)
    if not limits.min_words <= n_words <= limits.max_words:
        out.append(f"text has {n_words} words, allowed {limits.min_words}-{limits.max_words}")
    if not _END_RE.search(text):
        out.append("text must end with sentence punctuation")
    detected = detect_lang(text)
    if lang in LANGS and detected != lang:
        out.append(f"lang {lang!r} but script mix says {detected!r}")

    out.extend(_check_terms(terms, text, matcher, limits))

    if tts is not None:
        if not isinstance(tts, str) or not tts.strip():
            out.append("tts_text must be null or a non-empty string")
        else:
            out.extend(_check_text(tts, "tts_text"))
            if tts == text:
                out.append("tts_text equals text (use null)")
            if lang in ("ru", "mixed") and has_latin(tts):
                out.append("tts_text for ru/mixed must not contain Latin letters")
            if lang == "en" and has_cyrillic(tts):
                out.append("tts_text for en must not contain Cyrillic letters")
    elif lang == "mixed" and limits.require_tts_for_mixed:
        out.append("mixed sentence needs tts_text (Cyrillic respelling)")
    return out


def _check_text(s: str, name: str) -> list[str]:
    out: list[str] = []
    if unicodedata.normalize("NFC", s) != s:
        out.append(f"{name} is not NFC")
    if s != s.strip() or "  " in s or "\n" in s or "\t" in s or "\r" in s:
        out.append(f"{name} has stray whitespace")
    return out


def _check_terms(terms: object, text: str, matcher: TermMatcher | None, limits: Limits) -> list[str]:
    if not isinstance(terms, list):
        return ["terms must be a list"]
    out: list[str] = []
    allowed = set(LEXICON_CATEGORIES) | set(EXTRA_PROBE_CATEGORIES)
    seen: set[str] = set()
    n_lex = n_person = 0
    for t in terms:
        if not isinstance(t, dict) or set(t) != {"term", "category"}:
            out.append(f"term {t!r} must be an object with exactly term and category")
            continue
        surface, cat = t["term"], t["category"]
        if not isinstance(surface, str) or not surface.strip():
            out.append("empty term")
            continue
        if surface in seen:
            out.append(f"duplicate term {surface!r}")
        seen.add(surface)
        if cat not in allowed:
            out.append(f"term {surface!r} has unknown category {cat!r}")
            continue
        if not surface_occurs(surface, text):
            out.append(f"term {surface!r} does not occur in text")
        if cat in EXTRA_PROBE_CATEGORIES:
            n_person += 1
            continue
        n_lex += 1
        if matcher is not None:
            entry = matcher.resolve(surface)
            if entry is None:
                out.append(f"term {surface!r} is not in the lexicon")
            elif entry.category != cat:
                out.append(f"term {surface!r} is {entry.category!r} in the lexicon, not {cat!r}")
    if not limits.min_lexicon_terms <= n_lex <= limits.max_lexicon_terms:
        out.append(f"{n_lex} lexicon terms, allowed {limits.min_lexicon_terms}-{limits.max_lexicon_terms}")
    if n_person > limits.max_person_terms:
        out.append(f"{n_person} person terms, allowed <= {limits.max_person_terms}")
    return out


def lexicon_terms(rec: ProbeRecord, matcher: TermMatcher) -> list[str]:
    """Canonical lexicon terms (Entry.term) referenced by a record."""
    out: list[str] = []
    for t in rec.get("terms", []):
        if t.get("category") in EXTRA_PROBE_CATEGORIES:
            continue
        e = matcher.resolve(t["term"])
        if e is not None:
            out.append(e.term)
    return out


def read_jsonl(path: str | Path) -> Iterator[tuple[int, object]]:
    """Yield (line number, parsed object); a broken line yields (n, JSONDecodeError)."""
    with open(path, encoding="utf-8") as fh:
        for lineno, line in enumerate(fh, 1):
            if not line.strip():
                continue
            try:
                yield lineno, json.loads(line)
            except json.JSONDecodeError as exc:
                yield lineno, exc


def load_records(paths: Iterable[str | Path]) -> list[ProbeRecord]:
    """Load valid-JSON objects from several JSONL files (missing files skipped)."""
    out: list[ProbeRecord] = []
    for p in paths:
        if not Path(p).exists():
            continue
        for _, obj in read_jsonl(p):
            if isinstance(obj, dict):
                out.append(obj)  # type: ignore[arg-type]
    return out


def dumps(rec: ProbeRecord) -> str:
    ordered = {k: rec.get(k) for k in RECORD_KEYS}
    return json.dumps(ordered, ensure_ascii=False)
