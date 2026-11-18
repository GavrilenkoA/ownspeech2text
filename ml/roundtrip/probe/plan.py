"""Slot planning for teacher-driven probe generation.

A *slot* is one sentence to request: language, domain, target length and the
lexicon terms it must use (with the written forms allowed for that language).
Planning balances the generated set: the language furthest below its target
share is chosen, then the least-used domain for that language, then the
category furthest below its share of the lexicon. Inside the category the
entry used least across seed *and* generated sentences wins, so terms the
seed set never covered come first. Extra terms come from the same category
with probability ``SAME_CATEGORY_P`` (more coherent sentences), otherwise from
another category. All randomness comes from ``random.Random`` seeded
with ``(seed, batch index)``, so the same accepted state and seed always yield
the same plan, which keeps interrupted runs reproducible.
"""
from __future__ import annotations

import random
from collections import Counter
from dataclasses import dataclass, field

from ml.roundtrip.probe.lexicon import EXTRA_PROBE_CATEGORIES, Entry, TermMatcher
from ml.roundtrip.probe.schema import DOMAINS, LANGS, ProbeRecord
from ml.roundtrip.probe.textutil import has_cyrillic, is_cyrillic_form

RU_ONLY_CATEGORIES: frozenset[str] = frozenset({"dev_slang_ru", "ai_ml_jargon_ru"})
DEFAULT_LANG_SHARE: dict[str, float] = {"ru": 0.3, "en": 0.25, "mixed": 0.45}
SAME_CATEGORY_P = 0.6
SHORT_SLOT_MAX_WORDS = 12  # slots this short get at most 2 terms
TERMS_PER_SLOT: tuple[tuple[int, float], ...] = ((1, 0.3), (2, 0.45), (3, 0.25))
WORD_RANGES: tuple[tuple[tuple[int, int], float], ...] = (
    ((6, 12), 0.25), ((9, 16), 0.3), ((12, 22), 0.25), ((16, 28), 0.13), ((22, 35), 0.07),
)


def forms_for(entry: Entry, lang: str) -> tuple[str, ...]:
    """Written forms of an entry usable in a sentence of ``lang`` (empty: unusable)."""
    cyr = tuple(f for f in entry.written_forms if is_cyrillic_form(f))
    lat = tuple(f for f in entry.written_forms if not has_cyrillic(f))
    if lang == "ru":
        return cyr
    if lang == "en":
        return () if entry.category in RU_ONLY_CATEGORIES else lat
    if lang == "mixed":
        return lat or cyr
    raise ValueError(f"unknown lang {lang!r}")


@dataclass(frozen=True)
class SlotTerm:
    term: str
    category: str
    use_one_of: tuple[str, ...]
    notes: str

    def to_prompt(self) -> dict[str, object]:
        d: dict[str, object] = {"term": self.term, "category": self.category, "use_one_of": list(self.use_one_of)}
        if self.notes:
            d["notes"] = self.notes
        return d


@dataclass(frozen=True)
class Slot:
    batch: int
    index: int
    lang: str
    domain: str
    words: tuple[int, int]
    terms: tuple[SlotTerm, ...]

    @property
    def rid(self) -> str:
        return slot_id(self.lang, self.batch, self.index)

    def to_prompt(self) -> dict[str, object]:
        return {"slot": self.index, "lang": self.lang, "domain": self.domain, "words": list(self.words),
                "terms": [t.to_prompt() for t in self.terms]}


def slot_id(lang: str, batch: int, index: int) -> str:
    return f"gen-{lang}-b{batch:05d}-s{index:02d}"


@dataclass
class Usage:
    """Tallies used for balancing.

    ``langs``, ``lang_domain`` and ``lang_category`` should describe the
    generated file only; ``terms`` should also include the seed set.
    """
    langs: Counter[str] = field(default_factory=Counter)
    lang_domain: Counter[tuple[str, str]] = field(default_factory=Counter)
    lang_category: Counter[tuple[str, str]] = field(default_factory=Counter)
    terms: Counter[str] = field(default_factory=Counter)

    @classmethod
    def from_records(cls, records: list[ProbeRecord], matcher: TermMatcher) -> "Usage":
        u = cls()
        for r in records:
            lang = r.get("lang", "")
            u.langs[lang] += 1
            u.lang_domain[(lang, r.get("domain", ""))] += 1
            for t in r.get("terms", []):
                if t.get("category") in EXTRA_PROBE_CATEGORIES:
                    continue
                e = matcher.resolve(t["term"])
                if e is not None:
                    u.terms[e.term] += 1
                    u.lang_category[(lang, e.category)] += 1
        return u

    def add_slot(self, slot: Slot) -> None:
        self.langs[slot.lang] += 1
        self.lang_domain[(slot.lang, slot.domain)] += 1
        for t in slot.terms:
            self.terms[t.term] += 1
            self.lang_category[(slot.lang, t.category)] += 1


@dataclass
class Planner:
    entries: list[Entry]
    seed: int
    lang_share: dict[str, float] = field(default_factory=lambda: dict(DEFAULT_LANG_SHARE))
    batch_size: int = 8

    def __post_init__(self) -> None:
        total = sum(self.lang_share.values())
        if total <= 0 or any(v < 0 for v in self.lang_share.values()) or set(self.lang_share) - set(LANGS):
            raise ValueError(f"bad language shares {self.lang_share}")
        self.lang_share = {k: v / total for k, v in self.lang_share.items() if v > 0}
        self._usable: dict[str, dict[str, list[Entry]]] = {}
        for lang in LANGS:
            by_cat: dict[str, list[Entry]] = {}
            for e in self.entries:
                if forms_for(e, lang):
                    by_cat.setdefault(e.category, []).append(e)
            self._usable[lang] = by_cat
        # a mixed sentence needs at least one Latin-script term
        self._mixed_primary = {c: [e for e in es if e.has_latin_form] for c, es in self._usable["mixed"].items()}
        self._mixed_primary = {c: es for c, es in self._mixed_primary.items() if es}

    def rng(self, batch: int) -> random.Random:
        return random.Random(f"probe-gen:{self.seed}:{batch}")

    def plan_batch(self, batch: int, usage: Usage) -> list[Slot]:
        """Plan one request. ``usage`` is updated with the planned slots."""
        rng = self.rng(batch)
        slots: list[Slot] = []
        for i in range(self.batch_size):
            slot = self._plan_slot(rng, batch, i, usage)
            usage.add_slot(slot)
            slots.append(slot)
        return slots

    # ----------------------------------------------------------------- internals
    def _plan_slot(self, rng: random.Random, batch: int, index: int, usage: Usage) -> Slot:
        lang = self._pick_lang(rng, usage)
        domain = _least(rng, list(DOMAINS), lambda d: usage.lang_domain[(lang, d)])
        words = _weighted(rng, WORD_RANGES)
        k = _weighted(rng, TERMS_PER_SLOT)
        if words[1] <= SHORT_SLOT_MAX_WORDS:
            k = min(k, 2)
        primary_pool = self._mixed_primary if lang == "mixed" else self._usable[lang]
        cat = _least(rng, sorted(primary_pool), lambda c: usage.lang_category[(lang, c)] / len(primary_pool[c]))
        chosen = [_least(rng, primary_pool[cat], lambda e: usage.terms[e.term])]
        pool = self._usable[lang]
        others = [c for c in sorted(pool) if c != cat]
        while len(chosen) < k:
            if cat in pool and (not others or rng.random() < SAME_CATEGORY_P):
                c = cat
            else:
                weights = [len(pool[o]) / (1.0 + usage.lang_category[(lang, o)]) for o in others]
                c = rng.choices(others, weights=weights)[0]
            cands = [e for e in pool[c] if e not in chosen]
            if not cands:
                break
            chosen.append(_least(rng, cands, lambda e: usage.terms[e.term]))
        terms = tuple(SlotTerm(e.term, e.category, forms_for(e, lang), e.notes) for e in chosen)
        return Slot(batch, index, lang, domain, words, terms)

    def _pick_lang(self, rng: random.Random, usage: Usage) -> str:
        total = sum(usage.langs[lang] for lang in self.lang_share) + 1
        return _least(rng, sorted(self.lang_share), lambda lang: usage.langs[lang] - self.lang_share[lang] * total)


def _least(rng: random.Random, items: list, key) -> object:  # type: ignore[no-untyped-def]
    """Item with the smallest key; ties broken by the rng."""
    if not items:
        raise ValueError("nothing to choose from")
    scored = [(key(x), rng.random(), x) for x in items]
    return min(scored, key=lambda s: (s[0], s[1]))[2]


def _weighted(rng: random.Random, options):  # type: ignore[no-untyped-def]
    values = [v for v, _ in options]
    weights = [w for _, w in options]
    return rng.choices(values, weights=weights)[0]
