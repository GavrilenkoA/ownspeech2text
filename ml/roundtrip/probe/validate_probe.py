#!/usr/bin/env python3
"""Validate probe-sentence JSONL files against the binding schema and print
lexicon coverage statistics.

Checks (any violation -> exit code 1):
  * lexicon: schema of every entry, unique terms and forms, every form matches itself
  * record schema: exact key set, id format, lang/domain enums, types
  * text: NFC, no stray whitespace, 6-35 words, ends with sentence punctuation
  * lang label agrees with the script mix (ru = no Latin letters, en = no Cyrillic,
    mixed = both with a Cyrillic majority)
  * terms: 1-4 lexicon terms (+ up to 3 person names), each occurs verbatim in text,
    each resolves to a lexicon entry of the stated category
  * tts_text: null or a non-empty string; Cyrillic-only for ru/mixed, Latin-only for en;
    required for mixed
  * ids unique across all files; no exact / near / template duplicates per language
  * every lexicon category is used by at least --min-category-sentences sentences
  * optional per-language minimum counts (--min-lang ru=170,en=100,mixed=150)

Usage:
  python3 ml/roundtrip/probe/validate_probe.py ml/roundtrip/probe/seed.jsonl
  python3 ml/roundtrip/probe/validate_probe.py seed.jsonl gen.jsonl --format json
"""
from __future__ import annotations

import argparse
import json
import statistics
import sys
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from pathlib import Path

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parents[3]))

from ml.roundtrip.probe.dedup import DedupIndex
from ml.roundtrip.probe.lexicon import LEXICON_CATEGORIES, TermMatcher, load_lexicon, validate_lexicon
from ml.roundtrip.probe.schema import DOMAINS, LANGS, Limits, ProbeRecord, check_record, read_jsonl
from ml.roundtrip.probe.textutil import word_count

HERE = Path(__file__).resolve().parent
MAX_PRINTED = 200


@dataclass
class Report:
    errors: list[str] = field(default_factory=list)
    records: list[ProbeRecord] = field(default_factory=list)
    stats: dict = field(default_factory=dict)

    @property
    def ok(self) -> bool:
        return not self.errors


def parse_min_lang(spec: str | None) -> dict[str, int]:
    if not spec:
        return {}
    out: dict[str, int] = {}
    for part in spec.split(","):
        lang, _, num = part.partition("=")
        lang = lang.strip()
        if lang not in LANGS or not num.strip().isdigit():
            raise argparse.ArgumentTypeError(f"bad --min-lang item {part!r}")
        out[lang] = int(num)
    return out


def validate_files(paths: list[Path], lexicon_path: Path, limits: Limits = Limits(),
                   near_threshold: float = 0.85, template_threshold: float = 0.92,
                   min_category_sentences: int = 1, min_lang: dict[str, int] | None = None) -> Report:
    rep = Report()
    entries = load_lexicon(lexicon_path)
    rep.errors.extend(f"lexicon: {p}" for p in validate_lexicon(entries))
    matcher = TermMatcher(entries)
    index = DedupIndex(near_threshold=near_threshold, template_threshold=template_threshold)
    ids: dict[str, str] = {}
    for path in paths:
        for lineno, obj in read_jsonl(path):
            where = f"{path.name}:{lineno}"
            if isinstance(obj, json.JSONDecodeError):
                rep.errors.append(f"{where}: invalid JSON: {obj}")
                continue
            problems = check_record(obj, matcher, limits)
            rid = obj.get("id") if isinstance(obj, dict) else None
            if isinstance(rid, str):
                if rid in ids:
                    problems.append(f"duplicate id (first at {ids[rid]})")
                else:
                    ids[rid] = where
            if not problems and isinstance(obj, dict):
                surfaces = [t["term"] for t in obj["terms"]]
                hit = index.check(obj["lang"], obj["text"], surfaces)
                if hit:
                    problems.append(f"{hit.kind} duplicate of {hit.other_id} (score {hit.score})")
                index.add(obj["id"], obj["lang"], obj["text"], surfaces)
            rep.errors.extend(f"{where} [{rid}]: {p}" for p in problems)
            if isinstance(obj, dict) and not problems:
                rep.records.append(obj)  # type: ignore[arg-type]
    rep.stats = coverage(rep.records, matcher)
    for cat, row in rep.stats["categories"].items():
        if row["sentences"] < min_category_sentences:
            rep.errors.append(f"category {cat!r} used by {row['sentences']} sentences, need {min_category_sentences}")
    for lang, need in (min_lang or {}).items():
        have = rep.stats["langs"].get(lang, 0)
        if have < need:
            rep.errors.append(f"lang {lang!r} has {have} valid sentences, need {need}")
    return rep


def coverage(records: list[ProbeRecord], matcher: TermMatcher) -> dict:
    langs = Counter(r["lang"] for r in records)
    domains = Counter(r["domain"] for r in records)
    lang_domain: dict[str, Counter[str]] = defaultdict(Counter)
    term_uses: Counter[str] = Counter()
    cat_sentences: Counter[str] = Counter()
    cat_lang: dict[str, Counter[str]] = defaultdict(Counter)
    n_terms: Counter[int] = Counter()
    words: dict[str, list[int]] = defaultdict(list)
    tts: Counter[str] = Counter()
    persons = 0
    for r in records:
        lang_domain[r["lang"]][r["domain"]] += 1
        words[r["lang"]].append(word_count(r["text"]))
        if r.get("tts_text"):
            tts[r["lang"]] += 1
        cats_here: set[str] = set()
        k = 0
        for t in r["terms"]:
            if t["category"] == "person":
                persons += 1
                continue
            e = matcher.resolve(t["term"])
            if e is None:
                continue
            k += 1
            term_uses[e.term] += 1
            cats_here.add(e.category)
        n_terms[k] += 1
        for c in cats_here:
            cat_sentences[c] += 1
            cat_lang[c][r["lang"]] += 1
    cats = {}
    for c in LEXICON_CATEGORIES:
        ents = matcher.entries_in(c)
        used = sum(1 for e in ents if term_uses[e.term])
        cats[c] = {
            "lexicon": len(ents),
            "used": used,
            "coverage": round(used / len(ents), 3) if ents else 0.0,
            "occurrences": sum(term_uses[e.term] for e in ents),
            "sentences": cat_sentences[c],
            "by_lang": {lang: cat_lang[c][lang] for lang in LANGS},
        }
    total_entries = len(matcher.entries)
    used_entries = sum(1 for e in matcher.entries if term_uses[e.term])
    return {
        "sentences": len(records),
        "langs": {lang: langs[lang] for lang in LANGS},
        "domains": {d: domains[d] for d in sorted(domains, key=lambda d: (DOMAINS.index(d) if d in DOMAINS else 99, d))},
        "lang_domain": {lang: dict(lang_domain[lang]) for lang in LANGS},
        "terms_per_sentence": {str(k): n_terms[k] for k in sorted(n_terms)},
        "term_occurrences": sum(term_uses.values()),
        "person_mentions": persons,
        "lexicon_entries": total_entries,
        "lexicon_used": used_entries,
        "lexicon_coverage": round(used_entries / total_entries, 3) if total_entries else 0.0,
        "words": {lang: {"min": min(v), "median": statistics.median(v), "max": max(v)} for lang, v in words.items()},
        "tts_text": {lang: tts[lang] for lang in LANGS},
        "categories": cats,
        "top_terms": term_uses.most_common(15),
    }


def render_markdown(stats: dict) -> str:
    L: list[str] = []
    L.append(f"Sentences: **{stats['sentences']}** — " + ", ".join(f"{k} {v}" for k, v in stats["langs"].items()))
    L.append(f"Lexicon entries used: **{stats['lexicon_used']} / {stats['lexicon_entries']}** "
             f"({stats['lexicon_coverage']:.0%}); term occurrences {stats['term_occurrences']}; "
             f"person-name mentions {stats['person_mentions']}")
    L.append("Terms per sentence: " + ", ".join(f"{k}: {v}" for k, v in stats["terms_per_sentence"].items()))
    L.append("Words per sentence: " + "; ".join(
        f"{lang} {w['min']}–{w['max']} (median {w['median']})" for lang, w in stats["words"].items()))
    L.append("tts_text set: " + ", ".join(f"{k} {v}" for k, v in stats["tts_text"].items()))
    L.append("")
    L.append("| category | lexicon | used | coverage | occurrences | sentences | ru | en | mixed |")
    L.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|")
    for c, r in stats["categories"].items():
        bl = r["by_lang"]
        L.append(f"| {c} | {r['lexicon']} | {r['used']} | {r['coverage']:.0%} | {r['occurrences']} | "
                 f"{r['sentences']} | {bl['ru']} | {bl['en']} | {bl['mixed']} |")
    L.append("")
    L.append("| domain | " + " | ".join(LANGS) + " | total |")
    L.append("|---|" + "---:|" * (len(LANGS) + 1))
    for d, n in stats["domains"].items():
        cells = [str(stats["lang_domain"][lang].get(d, 0)) for lang in LANGS]
        L.append(f"| {d} | " + " | ".join(cells) + f" | {n} |")
    return "\n".join(L)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("files", nargs="*", type=Path, default=[HERE / "seed.jsonl"], help="probe JSONL files")
    ap.add_argument("--lexicon", type=Path, default=HERE / "terms.jsonl")
    ap.add_argument("--min-words", type=int, default=6)
    ap.add_argument("--max-words", type=int, default=35)
    ap.add_argument("--near-threshold", type=float, default=0.85, help="token similarity for near duplicates")
    ap.add_argument("--template-threshold", type=float, default=0.92, help="masked-skeleton similarity")
    ap.add_argument("--min-category-sentences", type=int, default=1)
    ap.add_argument("--min-lang", type=parse_min_lang, default=None, help="e.g. ru=170,en=100,mixed=150")
    ap.add_argument("--allow-any-domain", action="store_true")
    ap.add_argument("--no-require-mixed-tts", action="store_true")
    ap.add_argument("--format", choices=("md", "json", "none"), default="md", help="coverage output")
    args = ap.parse_args(argv)

    limits = Limits(min_words=args.min_words, max_words=args.max_words,
                    require_tts_for_mixed=not args.no_require_mixed_tts,
                    allowed_domains=None if args.allow_any_domain else DOMAINS)
    try:
        rep = validate_files(args.files, args.lexicon, limits, args.near_threshold, args.template_threshold,
                             args.min_category_sentences, args.min_lang)
    except (OSError, ValueError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    if args.format == "md":
        print(render_markdown(rep.stats))
    elif args.format == "json":
        print(json.dumps(rep.stats, ensure_ascii=False, indent=2))
    for e in rep.errors[:MAX_PRINTED]:
        print(f"ERROR {e}", file=sys.stderr)
    if len(rep.errors) > MAX_PRINTED:
        print(f"... {len(rep.errors) - MAX_PRINTED} more", file=sys.stderr)
    status = "OK" if rep.ok else f"FAILED with {len(rep.errors)} violation(s)"
    print(f"{status}: {len(rep.records)} valid sentences in {len(args.files)} file(s)", file=sys.stderr)
    return 0 if rep.ok else 1


if __name__ == "__main__":
    sys.exit(main())
