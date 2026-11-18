"""Duplicate detection for probe sentences.

Three levels, all within one language label:
  exact     same normalised text (case, ё/е, punctuation, spacing ignored)
  near      token-sequence similarity >= ``near_threshold``
  template  same skeleton once terms, names and numbers are masked, with
            similarity >= ``template_threshold`` ("Deploy X to Y" twice with
            different X/Y is a template, not two probes)

Candidates are drawn from an inverted index over tokens, so adding N
sentences costs roughly O(N * shared-token fan-out), not O(N^2) comparisons.
"""
from __future__ import annotations

import re
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from difflib import SequenceMatcher

from ml.roundtrip.probe.textutil import norm_key, norm_tokens

MASK_TERM = "⟨t⟩"
MASK_NUM = "⟨n⟩"
_NUM_RE = re.compile(r"\d+(?:[.:,]\d+)*")


@dataclass(frozen=True)
class Hit:
    kind: str  # "exact" | "near" | "template"
    other_id: str
    score: float


def skeleton_tokens(text: str, term_surfaces: list[str]) -> list[str]:
    """Normalised tokens with term surfaces and numbers masked."""
    s = text
    for surf in sorted(set(term_surfaces), key=len, reverse=True):
        if surf:
            s = s.replace(surf, " qqmaskqq ")
    s = _NUM_RE.sub(" qqnumqq ", s)
    toks = norm_tokens(s)
    return [MASK_TERM if t == "qqmaskqq" else MASK_NUM if t == "qqnumqq" else t for t in toks]


def similarity(a: list[str], b: list[str]) -> float:
    if not a and not b:
        return 1.0
    return SequenceMatcher(None, a, b, autojunk=False).ratio()


@dataclass
class _Item:
    rid: str
    toks: list[str]
    skel: list[str]


@dataclass
class DedupIndex:
    near_threshold: float = 0.85
    template_threshold: float = 0.92
    _exact: dict[tuple[str, str], str] = field(default_factory=dict)
    _items: dict[str, list[_Item]] = field(default_factory=lambda: defaultdict(list))
    _inv: dict[str, dict[str, set[int]]] = field(default_factory=lambda: defaultdict(lambda: defaultdict(set)))
    _sinv: dict[str, dict[str, set[int]]] = field(default_factory=lambda: defaultdict(lambda: defaultdict(set)))

    def __len__(self) -> int:
        return len(self._exact)

    def check(self, lang: str, text: str, term_surfaces: list[str]) -> Hit | None:
        """First duplicate hit for a candidate, or None if it is new."""
        key = norm_key(text)
        if (lang, key) in self._exact:
            return Hit("exact", self._exact[(lang, key)], 1.0)
        toks = key.split()
        best = self._best(lang, toks, self._inv[lang], use_skeleton=False)
        if best and best[1] >= self.near_threshold:
            return Hit("near", best[0], round(best[1], 3))
        skel = skeleton_tokens(text, term_surfaces)
        sbest = self._best(lang, skel, self._sinv[lang], use_skeleton=True)
        if sbest and sbest[1] >= self.template_threshold:
            return Hit("template", sbest[0], round(sbest[1], 3))
        return None

    def add(self, rid: str, lang: str, text: str, term_surfaces: list[str]) -> None:
        key = norm_key(text)
        self._exact.setdefault((lang, key), rid)
        item = _Item(rid, key.split(), skeleton_tokens(text, term_surfaces))
        items = self._items[lang]
        idx = len(items)
        items.append(item)
        for t in set(item.toks):
            self._inv[lang][t].add(idx)
        for t in set(item.skel):
            if t not in (MASK_TERM, MASK_NUM):
                self._sinv[lang][t].add(idx)

    def _best(self, lang: str, toks: list[str], inv: dict[str, set[int]], use_skeleton: bool) -> tuple[str, float] | None:
        if not toks:
            return None
        counts: Counter[int] = Counter()
        content = [t for t in set(toks) if t not in (MASK_TERM, MASK_NUM)]
        for t in content:
            for idx in inv.get(t, ()):
                counts[idx] += 1
        if not counts:
            return None
        # a pair can only reach the threshold if it shares a good part of the tokens
        need = max(1, int(0.4 * len(content)))
        items = self._items[lang]
        best: tuple[str, float] | None = None
        for idx, shared in counts.most_common(50):
            if shared < need:
                break
            other = items[idx].skel if use_skeleton else items[idx].toks
            score = similarity(toks, other)
            if best is None or score > best[1]:
                best = (items[idx].rid, score)
        return best
