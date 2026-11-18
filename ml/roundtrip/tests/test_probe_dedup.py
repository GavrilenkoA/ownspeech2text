"""Exact / near / template duplicate detection for probe sentences."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[3]))

from ml.roundtrip.probe.dedup import MASK_NUM, MASK_TERM, DedupIndex, skeleton_tokens  # noqa: E402
from ml.roundtrip.probe.textutil import norm_key  # noqa: E402


def index_with(*items: tuple[str, str, str, list[str]]) -> DedupIndex:
    idx = DedupIndex()
    for rid, lang, text, terms in items:
        idx.add(rid, lang, text, terms)
    return idx


BASE = ("a", "mixed", "Завтра утром выкатим LoRA на стейджинг и посмотрим метрики.", ["LoRA", "стейджинг"])


def test_norm_key_ignores_case_punctuation_and_yo() -> None:
    assert norm_key("Ещё раз: Деплой — на ПРОД!") == norm_key("еще раз деплой на прод")


def test_exact_duplicate() -> None:
    hit = index_with(BASE).check("mixed", "завтра утром выкатим LoRA на стейджинг, и посмотрим метрики!",
                                 ["LoRA", "стейджинг"])
    assert hit is not None and hit.kind == "exact" and hit.other_id == "a"


def test_near_duplicate() -> None:
    hit = index_with(BASE).check("mixed", "Завтра утром выкатим LoRA на стейджинг и посмотрим все метрики.",
                                 ["LoRA", "стейджинг"])
    assert hit is not None and hit.kind == "near" and hit.score >= 0.85


def test_template_duplicate_with_other_terms_and_numbers() -> None:
    idx = index_with(("b", "en", "Please restart Grafana on node 3 before the 10:30 sync.", ["Grafana"]))
    hit = idx.check("en", "Please restart Prometheus on node 7 before the 11:45 sync.", ["Prometheus"])
    assert hit is not None and hit.kind == "template"


def test_other_language_is_not_a_duplicate() -> None:
    assert index_with(BASE).check("ru", BASE[2], BASE[3]) is None


def test_different_sentences_pass() -> None:
    idx = index_with(BASE)
    assert idx.check("mixed", "Вчера весь вечер ловил OOM на H100, батч пришлось уменьшить вдвое.",
                     ["OOM", "H100", "батч"]) is None
    assert len(idx) == 1


def test_skeleton_masks_terms_and_numbers() -> None:
    sk = skeleton_tokens("Restart Grafana at 10:30 on v1.2 node.", ["Grafana"])
    assert MASK_TERM in sk and sk.count(MASK_NUM) == 2
    assert "grafana" not in sk
