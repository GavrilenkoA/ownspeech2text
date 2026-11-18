"""tts_text respelling and slot planning."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[3]))

import pytest  # noqa: E402

from ml.roundtrip.probe.lexicon import TermMatcher, load_lexicon  # noqa: E402
from ml.roundtrip.probe.plan import Planner, Usage, forms_for  # noqa: E402
from ml.roundtrip.probe.respell import keep_inflection, pick_reading, respell, spell_acronyms  # noqa: E402
from ml.roundtrip.probe.textutil import has_cyrillic, has_latin, is_cyrillic_form  # noqa: E402

LEXICON = Path(__file__).resolve().parents[1] / "probe" / "terms.jsonl"


@pytest.fixture(scope="module")
def entries():  # type: ignore[no-untyped-def]
    return load_lexicon(LEXICON)


@pytest.fixture(scope="module")
def matcher(entries) -> TermMatcher:  # type: ignore[no-untyped-def]
    return TermMatcher(entries)


# ------------------------------------------------------------------ respell
@pytest.mark.parametrize("readings, surface, canonical, expected", [
    (("паракит", "паракит ти ди ти"), "Parakeet TDT", "Parakeet TDT", "паракит ти ди ти"),  # canonical: cover all words
    (("гэлакси зет фолд семь", "фолд семь"), "Fold7", "Galaxy Z Fold7", "фолд семь"),        # alt form: do not add words
    (("материал три", "материал ю"), "Material You", "Material 3", "материал ю"),           # alt form: sounds like it
    (("хаггинг фейс", "аш эф"), "HF", "Hugging Face", "аш эф"),                              # acronym spelled out
    (("эр ти икс пятьдесят девяносто", "пятьдесят девяностая"), "5090", "RTX 5090", "пятьдесят девяностая"),
    (("кубер", "кубернетес"), "k8s", "k8s", "кубер"),                                        # author's first choice
])
def test_pick_reading(readings: tuple[str, ...], surface: str, canonical: str, expected: str) -> None:
    assert pick_reading(readings, surface, canonical) == expected


def test_keep_inflection_and_spelling() -> None:
    assert keep_inflection("аб тест", "A/B-теста") == "аб теста"
    assert keep_inflection("лора", "LoRA") == "лора"
    assert spell_acronyms("HF") == "эйч эф"


def test_respell_mixed_leaves_no_latin(matcher: TermMatcher) -> None:
    rs = respell("Подними KV cache до 8 гигабайт на H100 и перезапусти vLLM.", "mixed", matcher)
    assert rs.tts_text is not None and not has_latin(rs.tts_text) and rs.leftovers == ()
    assert "аш сто" in rs.tts_text


def test_respell_reports_unknown_latin(matcher: TermMatcher) -> None:
    rs = respell("Спроси у Zorblax про LoRA на завтра.", "mixed", matcher)
    assert rs.leftovers == ("Zorblax",)
    rs2 = respell("Спроси у Zorblax про LoRA на завтра.", "mixed", matcher, persons={"Zorblax": "Зорблакс"})
    assert rs2.leftovers == () and "Зорблакс" in (rs2.tts_text or "")


def test_respell_en_and_ru(matcher: TermMatcher) -> None:
    assert respell("Roll the k8s cluster back before noon.", "en", matcher).tts_text == \
        "Roll the kubernetes cluster back before noon."
    assert respell("Ship the Grafana dashboards before noon.", "en", matcher).tts_text is None
    assert respell("Задеплоил на прод.", "ru", matcher).tts_text is None


# ------------------------------------------------------------------ planning
def test_forms_for_respects_language(entries) -> None:  # type: ignore[no-untyped-def]
    by_term = {e.term: e for e in entries}
    assert forms_for(by_term["Kubernetes"], "ru") == ()
    assert forms_for(by_term["Kubernetes"], "mixed") == ("Kubernetes",)
    assert forms_for(by_term["задеплоить"], "en") == ()
    assert all(is_cyrillic_form(f) for f in forms_for(by_term["задеплоить"], "ru"))


def test_plan_is_deterministic_and_balanced(entries) -> None:  # type: ignore[no-untyped-def]
    def plan(seed: int) -> list:
        planner = Planner(entries, seed=seed, batch_size=8)
        usage = Usage()
        return [s for b in range(20) for s in planner.plan_batch(b, usage)]

    a, b, c = plan(1), plan(1), plan(2)
    assert a == b and a != c
    langs = {lang: sum(1 for s in a if s.lang == lang) for lang in ("ru", "en", "mixed")}
    assert abs(langs["ru"] - 0.3 * 160) <= 2 and abs(langs["mixed"] - 0.45 * 160) <= 2
    domains = {s.domain for s in a}
    assert len(domains) == 10
    for s in a:
        assert 1 <= len(s.terms) <= 3
        assert len({t.term for t in s.terms}) == len(s.terms)
        if s.words[1] <= 12:
            assert len(s.terms) <= 2
        if s.lang == "ru":
            assert all(is_cyrillic_form(f) for t in s.terms for f in t.use_one_of)
        if s.lang == "en":
            assert not any(has_cyrillic(f) for t in s.terms for f in t.use_one_of)
        if s.lang == "mixed":
            assert any(has_latin(f) for t in s.terms for f in t.use_one_of)
    assert a[0].rid == "gen-" + a[0].lang + "-b00000-s00"


def test_plan_prefers_unused_terms(entries) -> None:  # type: ignore[no-untyped-def]
    usage = Usage()
    for e in entries:
        if e.term != "Tarantool":
            usage.terms[e.term] = 5
    planner = Planner(entries, seed=0, lang_share={"mixed": 1.0}, batch_size=1)
    picked = [t.term for b in range(40) for s in planner.plan_batch(b, usage) for t in s.terms]
    assert "Tarantool" in picked
