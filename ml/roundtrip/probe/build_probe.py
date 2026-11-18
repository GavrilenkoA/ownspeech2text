#!/usr/bin/env python3
"""Build terms.jsonl and seed.jsonl from the hand-written sources in probe/src/.

Edit the sources, not the JSONL:

src/terms.txt  -- one lexicon entry per line under "## <category>" headers:
    term | extra written forms ; ... | ru_spoken ; ... | en_spoken ; ... | notes

src/seed.txt   -- one sentence per line under "@<lang> <domain>" headers:
    Обновил [Helm chart] и [задеплоил] на [стейджинг], {Laura=Лора} проверит.
  [x]        a lexicon term (surface as written; must resolve to an entry)
  {Name}     a person's name; {Latin=Кириллица} also gives its Cyrillic reading
             for tts_text. Inside Russian sentences write names in declined Cyrillic
             ({Марком}), as a careful writer would.
  <20=двадцать>  a number written in digits in text (prompts/system/default.v5.md
             rule 4) and spoken as the words after "=" in tts_text, declined as said
             ("до <11:00=одиннадцати>"). A sentence with such a marker gets a tts_text
             in every language, ru included.
  Lexicon terms the matcher finds outside brackets are added too and listed
  as warnings, so annotations stay complete.

The build fails (exit 1) on any unresolved term, invalid record or duplicate.
"""
from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parents[3]))

from ml.roundtrip.probe.dedup import DedupIndex
from ml.roundtrip.probe.lexicon import (
    LEXICON_CATEGORIES,
    Entry,
    TermMatcher,
    dump_lexicon,
    validate_lexicon,
)
from ml.roundtrip.probe.respell import respell
from ml.roundtrip.probe.schema import DOMAINS, LANGS, ProbeRecord, check_record, dumps

HERE = Path(__file__).resolve().parent
_MARK_RE = re.compile(r"\[([^\[\]]+)\]|\{([^{}=]+)(?:=([^{}]+))?\}|<([^<>=]+)=([^<>]+)>")


def _split(field: str) -> list[str]:
    return [x.strip() for x in field.split(";") if x.strip()]


def parse_terms_src(text: str) -> list[Entry]:
    entries: list[Entry] = []
    category: str | None = None
    for lineno, raw in enumerate(text.splitlines(), 1):
        line = raw.strip()
        if not line or (line.startswith("#") and not line.startswith("##")):
            continue
        if line.startswith("## "):
            category = line[3:].strip()
            if category not in LEXICON_CATEGORIES:
                raise ValueError(f"terms.txt:{lineno}: unknown category {category!r}")
            continue
        if category is None:
            raise ValueError(f"terms.txt:{lineno}: entry before any category header")
        cols = [c.strip() for c in line.split("|")]
        if len(cols) != 5:
            raise ValueError(f"terms.txt:{lineno}: expected 5 '|' columns, got {len(cols)}")
        term, forms, ru, en, notes = cols
        written = [term] + [f for f in _split(forms) if f != term]
        entries.append(Entry(term, category, tuple(written), tuple(_split(ru)), tuple(_split(en)), notes))
    return entries


@dataclass
class SeedLine:
    lineno: int
    lang: str
    domain: str
    raw: str


def parse_seed_src(text: str) -> list[SeedLine]:
    out: list[SeedLine] = []
    lang = domain = None
    for lineno, raw in enumerate(text.splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("@"):
            parts = line[1:].split()
            if len(parts) != 2 or parts[0] not in LANGS or parts[1] not in DOMAINS:
                raise ValueError(f"seed.txt:{lineno}: bad header {line!r}")
            lang, domain = parts
            continue
        if lang is None or domain is None:
            raise ValueError(f"seed.txt:{lineno}: sentence before any @lang domain header")
        out.append(SeedLine(lineno, lang, domain, line))
    return out


def build_record(sl: SeedLine, rid: str, matcher: TermMatcher) -> tuple[ProbeRecord, list[str], list[str]]:
    """Return (record, errors, warnings) for one source line."""
    errors: list[str] = []
    warnings: list[str] = []
    text_parts: list[str] = []
    spoken_parts: list[str] = []  # the same sentence with number markers in their spoken form
    spans: list[tuple[int, int, dict[str, str]]] = []
    persons: dict[str, str] = {}
    pos = 0
    cur = 0
    for m in _MARK_RE.finditer(sl.raw):
        text_parts.append(sl.raw[pos: m.start()])
        spoken_parts.append(sl.raw[pos: m.start()])
        cur += m.start() - pos
        if m.group(4) is not None:
            written, spoken = m.group(4).strip(), m.group(5).strip()
            if not any(c.isdigit() for c in written):
                errors.append(f"<{written}={spoken}>: the written side of a number marker needs digits")
            text_parts.append(written)
            spoken_parts.append(spoken)
            cur += len(written)
            pos = m.end()
            continue
        if m.group(1) is not None:
            surface = m.group(1)
            entry = matcher.resolve(surface)
            if entry is None:
                errors.append(f"[{surface}] is not in the lexicon")
                term = {"term": surface, "category": "?"}
            else:
                term = {"term": surface, "category": entry.category}
        else:
            surface = m.group(2).strip()
            if m.group(3):
                persons[surface] = m.group(3).strip()
            term = {"term": surface, "category": "person"}
        spans.append((cur, cur + len(surface), term))
        text_parts.append(surface)
        spoken_parts.append(surface)
        cur += len(surface)
        pos = m.end()
    text_parts.append(sl.raw[pos:])
    spoken_parts.append(sl.raw[pos:])
    text = "".join(text_parts)
    spoken = "".join(spoken_parts)
    if any(c in text for c in "[]{}<>"):
        errors.append("unbalanced markup")
    for m in matcher.find(text):
        if any(not (m.end <= s or m.start >= e) for s, e, _ in spans):
            continue
        spans.append((m.start, m.end, {"term": m.surface, "category": m.entry.category}))
        warnings.append(f"auto-tagged {m.surface!r} as {m.entry.term!r} ({m.entry.category})")
    spans.sort(key=lambda s: s[0])
    terms = []
    seen: set[str] = set()
    for _, _, t in spans:
        if t["term"] not in seen:
            terms.append(t)
            seen.add(t["term"])
    rs = respell(spoken, sl.lang, matcher, persons)
    if rs.leftovers:
        errors.append(f"tts_text keeps Latin tokens {list(rs.leftovers)} (add a lexicon entry or {{Name=Имя}})")
    tts_text = rs.tts_text or (spoken if spoken != text else None)
    rec: ProbeRecord = {"id": rid, "lang": sl.lang, "domain": sl.domain, "text": text,
                        "terms": terms, "tts_text": tts_text}  # type: ignore[typeddict-item]
    errors.extend(check_record(rec, matcher))
    return rec, errors, warnings


def build(src_dir: Path, terms_out: Path, seed_out: Path, show_warnings: bool) -> int:
    entries = parse_terms_src((src_dir / "terms.txt").read_text(encoding="utf-8"))
    problems = validate_lexicon(entries)
    if problems:
        for p in problems:
            print(f"lexicon: {p}", file=sys.stderr)
        return 1
    matcher = TermMatcher(entries)
    lines = parse_seed_src((src_dir / "seed.txt").read_text(encoding="utf-8"))
    counters = {lang: 0 for lang in LANGS}
    index = DedupIndex()
    records: list[ProbeRecord] = []
    n_err = 0
    for sl in lines:
        counters[sl.lang] += 1
        rid = f"seed-{sl.lang}-{counters[sl.lang]:04d}"
        rec, errors, warnings = build_record(sl, rid, matcher)
        hit = index.check(sl.lang, rec["text"], [t["term"] for t in rec["terms"]])
        if hit:
            errors.append(f"{hit.kind} duplicate of {hit.other_id} (score {hit.score})")
        for e in errors:
            print(f"seed.txt:{sl.lineno}: ERROR {e}: {rec['text']}", file=sys.stderr)
        if show_warnings:
            for w in warnings:
                print(f"seed.txt:{sl.lineno}: warn {w}", file=sys.stderr)
        n_err += len(errors)
        index.add(rid, sl.lang, rec["text"], [t["term"] for t in rec["terms"]])
        records.append(rec)
    if n_err:
        print(f"{n_err} error(s); nothing written", file=sys.stderr)
        return 1
    dump_lexicon(entries, terms_out)
    with open(seed_out, "w", encoding="utf-8") as fh:
        for rec in records:
            fh.write(dumps(rec) + "\n")
    print(f"wrote {len(entries)} lexicon entries -> {terms_out}")
    print(f"wrote {len(records)} seed sentences -> {seed_out} " + str(counters))
    return 0


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--src", type=Path, default=HERE / "src", help="directory with terms.txt and seed.txt")
    ap.add_argument("--terms-out", type=Path, default=HERE / "terms.jsonl")
    ap.add_argument("--seed-out", type=Path, default=HERE / "seed.jsonl")
    ap.add_argument("--warnings", action="store_true", help="print auto-tagged terms")
    args = ap.parse_args(argv)
    try:
        return build(args.src, args.terms_out, args.seed_out, args.warnings)
    except ValueError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
