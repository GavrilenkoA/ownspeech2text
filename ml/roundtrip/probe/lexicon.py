"""Hard-term lexicon: schema, validation and term matching in text.

Lexicon line (ml/roundtrip/probe/terms.jsonl):
  {"term": str, "category": str, "written_forms": [str, ...],  # first == term
   "ru_spoken": [Cyrillic, ...], "en_spoken": [str, ...], "notes": str}

Matching rules (``TermMatcher``):
  * Latin forms match whole tokens; a form containing an upper-case letter is
    matched case-sensitively ("Room" is not "room"), an all-lower-case form
    case-insensitively ("llama.cpp", "kubectl apply").
  * Cyrillic words are matched with Russian inflection: a light stemmer strips
    the infinitive / adjective / final-vowel ending and allows a short tail, so
    "стейджинг" matches "на стейджинге" and "задеплоить" matches "задеплоили".
  * Overlapping matches resolve longest-first ("Spring Boot" beats "Spring").
"""
from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable, Iterator

from ml.roundtrip.probe.textutil import has_cyrillic, has_latin, nfc

LEXICON_CATEGORIES: tuple[str, ...] = (
    "devops_infra",
    "databases_data",
    "languages_frameworks",
    "ai_tools",
    "ai_ml_jargon",
    "ai_ml_jargon_ru",
    "model_names",
    "dev_slang_ru",
    "cli_commands",
    "files_config",
    "acronyms",
    "cloud_services",
    "hardware",
    "mobile_android",
)
# Probe sentences may also tag names of people; they are not lexicon entries.
EXTRA_PROBE_CATEGORIES: tuple[str, ...] = ("person",)

LEXICON_KEYS = ("term", "category", "written_forms", "ru_spoken", "en_spoken", "notes")
_RU_SPOKEN_RE = re.compile(r"^[А-Яа-яЁё0-9][А-Яа-яЁё0-9 \-']*$")

_VERB_SUFFIXES = ("ться", "тись", "ить", "ать", "ять", "еть", "уть", "ыть", "оть", "ть")
_ADJ_SUFFIXES = ("ая", "яя", "ое", "ее", "ый", "ий", "ой", "ые", "ие", "ую", "юю")
_FINAL_VOWELS = "аяоеёиыйьуюэ"
_SHORT_TAIL = "(?:а|о|у|ом|е|и|ы|ов|ам|ами|ах|ой|ою|ей|ю|юю|я|ем|ям|ями|ях|ым|ыми)?"
_CYR_WORD_RE = re.compile(r"^[А-Яа-яЁё]+$")


@dataclass(frozen=True)
class Entry:
    term: str
    category: str
    written_forms: tuple[str, ...]
    ru_spoken: tuple[str, ...]
    en_spoken: tuple[str, ...]
    notes: str = ""

    @property
    def has_latin_form(self) -> bool:
        return any(not has_cyrillic(f) for f in self.written_forms)

    @property
    def has_cyrillic_form(self) -> bool:
        return any(has_cyrillic(f) and not has_latin(f) for f in self.written_forms)

    def to_json(self) -> dict:
        return {
            "term": self.term,
            "category": self.category,
            "written_forms": list(self.written_forms),
            "ru_spoken": list(self.ru_spoken),
            "en_spoken": list(self.en_spoken),
            "notes": self.notes,
        }


@dataclass(frozen=True)
class Match:
    start: int
    end: int
    surface: str
    entry: Entry
    form: str


def entry_from_json(obj: dict) -> Entry:
    return Entry(
        term=obj["term"],
        category=obj["category"],
        written_forms=tuple(obj["written_forms"]),
        ru_spoken=tuple(obj["ru_spoken"]),
        en_spoken=tuple(obj["en_spoken"]),
        notes=obj.get("notes", ""),
    )


def load_lexicon(path: str | Path) -> list[Entry]:
    """Load terms.jsonl. Raises ValueError on a malformed line."""
    entries: list[Entry] = []
    with open(path, encoding="utf-8") as fh:
        for lineno, line in enumerate(fh, 1):
            if not line.strip():
                continue
            try:
                obj = json.loads(line)
            except json.JSONDecodeError as exc:
                raise ValueError(f"{path}:{lineno}: invalid JSON: {exc}") from exc
            problems = check_entry_shape(obj)
            if problems:
                raise ValueError(f"{path}:{lineno}: " + "; ".join(problems))
            entries.append(entry_from_json(obj))
    return entries


def check_entry_shape(obj: object) -> list[str]:
    """Per-entry schema problems (empty list when fine)."""
    if not isinstance(obj, dict):
        return ["entry is not an object"]
    out: list[str] = []
    missing = [k for k in LEXICON_KEYS if k not in obj]
    extra = [k for k in obj if k not in LEXICON_KEYS]
    if missing:
        out.append(f"missing keys {missing}")
    if extra:
        out.append(f"unknown keys {extra}")
    if out:
        return out
    term = obj["term"]
    if not isinstance(term, str) or not term.strip() or term != term.strip():
        out.append("term must be a non-empty trimmed string")
    if obj["category"] not in LEXICON_CATEGORIES:
        out.append(f"unknown category {obj['category']!r}")
    for key in ("written_forms", "ru_spoken", "en_spoken"):
        val = obj[key]
        if not isinstance(val, list) or not all(isinstance(x, str) and x.strip() for x in val):
            out.append(f"{key} must be a list of non-empty strings")
    if not isinstance(obj["notes"], str):
        out.append("notes must be a string")
    if out:
        return out
    if not obj["written_forms"] or obj["written_forms"][0] != term:
        out.append("written_forms[0] must equal term")
    if len(set(obj["written_forms"])) != len(obj["written_forms"]):
        out.append("duplicate written_forms")
    if not obj["ru_spoken"]:
        out.append("ru_spoken must not be empty")
    for s in obj["ru_spoken"]:
        if not _RU_SPOKEN_RE.match(s):
            out.append(f"ru_spoken {s!r} must be Cyrillic words")
    for s in obj["en_spoken"]:
        if has_cyrillic(s):
            out.append(f"en_spoken {s!r} must not contain Cyrillic")
    return out


def validate_lexicon(entries: list[Entry]) -> list[str]:
    """Cross-entry invariants: unique terms, no form owned by two entries,
    every form is found by the matcher in its own spelling."""
    problems: list[str] = []
    seen_terms: dict[str, str] = {}
    seen_forms: dict[str, str] = {}
    for e in entries:
        problems.extend(f"{e.term}: {p}" for p in check_entry_shape(e.to_json()))
        key = e.term.casefold()
        if key in seen_terms:
            problems.append(f"duplicate term {e.term!r}")
        seen_terms[key] = e.term
        for f in e.written_forms:
            fk = f.casefold()
            if fk in seen_forms and seen_forms[fk] != e.term:
                problems.append(f"form {f!r} belongs to {seen_forms[fk]!r} and {e.term!r}")
            seen_forms[fk] = e.term
    if problems:
        return problems
    matcher = TermMatcher(entries)
    for e in entries:
        for f in e.written_forms:
            found = matcher.resolve(f)
            if found is None or found.term != e.term:
                other = found.term if found else None
                problems.append(f"form {f!r} of {e.term!r} resolves to {other!r}")
    return problems


# ---------------------------------------------------------------- matching
def _stem_ru(word: str) -> tuple[str, str]:
    """Return (stem, tail-regex) for one Cyrillic word."""
    w = word.lower()
    for suf in _VERB_SUFFIXES:
        if w.endswith(suf) and len(w) - len(suf) >= 3:
            return w[: -len(suf)], "[а-яё]{0,5}"
    if len(w) >= 6:
        for suf in _ADJ_SUFFIXES:
            if w.endswith(suf):
                return w[:-2], "[а-яё]{0,4}"
    if len(w) >= 4 and w[-1] in _FINAL_VOWELS:
        w = w[:-1]
    if len(w) >= 5:
        return w, "[а-яё]{0,4}"
    return w, _SHORT_TAIL


def _form_pattern(form: str) -> re.Pattern[str]:
    """Compile the search pattern for one written form."""
    form = nfc(form)
    if has_cyrillic(form):
        parts: list[str] = []
        # keep separators (spaces, hyphens) so "юнит-тесты" and "векторной базе" work
        for piece in re.split(r"(\s+|-)", form):
            if not piece:
                continue
            if piece.isspace():
                parts.append(r"\s+")
            elif piece == "-":
                parts.append("-")
            elif _CYR_WORD_RE.match(piece) and len(piece) >= 2:
                stem, tail = _stem_ru(piece)
                parts.append(re.escape(stem) + tail)
            else:
                parts.append(re.escape(piece))
        body = "".join(parts)
        return re.compile(rf"(?<![\w]){body}(?![\w])", re.IGNORECASE)
    body = r"\s+".join(re.escape(p) for p in form.split())
    flags = 0 if any(c.isupper() for c in form) else re.IGNORECASE
    return re.compile(rf"(?<![\w]){body}(?![\w])", flags)


@dataclass
class TermMatcher:
    entries: list[Entry]
    _patterns: list[tuple[re.Pattern[str], Entry, str]] = field(init=False, repr=False)
    by_term: dict[str, Entry] = field(init=False, repr=False)

    def __post_init__(self) -> None:
        self._patterns = []
        self.by_term = {}
        for e in self.entries:
            self.by_term[e.term] = e
            for f in e.written_forms:
                self._patterns.append((_form_pattern(f), e, f))

    def _all_matches(self, text: str) -> Iterator[Match]:
        for pat, entry, form in self._patterns:
            for m in pat.finditer(text):
                yield Match(m.start(), m.end(), m.group(0), entry, form)

    def find(self, text: str) -> list[Match]:
        """Non-overlapping matches, longest first, returned in text order."""
        cands = sorted(self._all_matches(text), key=lambda m: (-(m.end - m.start), m.start))
        taken: list[Match] = []
        for m in cands:
            if all(m.end <= t.start or m.start >= t.end for t in taken):
                taken.append(m)
        return sorted(taken, key=lambda m: m.start)

    def resolve(self, surface: str) -> Entry | None:
        """Entry whose form covers the whole surface string (exact form first)."""
        surface = nfc(surface.strip())
        best: tuple[int, int, Entry] | None = None
        for pat, entry, form in self._patterns:
            m = pat.fullmatch(surface)
            if not m:
                continue
            rank = (0 if form == surface else 1 if form.casefold() == surface.casefold() else 2, -len(form))
            if best is None or rank < best[:2]:
                best = (rank[0], rank[1], entry)
        return best[2] if best else None

    def entries_in(self, category: str) -> list[Entry]:
        return [e for e in self.entries if e.category == category]


def surface_occurs(term: str, text: str) -> bool:
    """Whole-token occurrence of an exact surface string in text."""
    pat = re.compile(rf"(?<![\w]){re.escape(term)}(?![\w])")
    return bool(pat.search(text))


def dump_lexicon(entries: Iterable[Entry], path: str | Path) -> None:
    with open(path, "w", encoding="utf-8") as fh:
        for e in entries:
            fh.write(json.dumps(e.to_json(), ensure_ascii=False) + "\n")
