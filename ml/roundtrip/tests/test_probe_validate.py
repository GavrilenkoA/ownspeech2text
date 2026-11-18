"""Probe-set validator, record schema and lexicon invariants."""
from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[3]))

import pytest  # noqa: E402

from ml.roundtrip.probe import validate_probe  # noqa: E402
from ml.roundtrip.probe.lexicon import (  # noqa: E402
    LEXICON_CATEGORIES,
    TermMatcher,
    load_lexicon,
    validate_lexicon,
)
from ml.roundtrip.probe.schema import check_record  # noqa: E402
from ml.roundtrip.probe.textutil import detect_lang, word_count  # noqa: E402

PROBE = Path(__file__).resolve().parents[1] / "probe"
LEXICON = PROBE / "terms.jsonl"


@pytest.fixture(scope="module")
def matcher() -> TermMatcher:
    return TermMatcher(load_lexicon(LEXICON))


def rec(**kw: object) -> dict:
    base = {"id": "t-1", "lang": "mixed", "domain": "work_chat",
            "text": "Задеплоил новый релиз в Kubernetes, проверьте, пожалуйста, логи.",
            "terms": [{"term": "Задеплоил", "category": "dev_slang_ru"},
                      {"term": "Kubernetes", "category": "devops_infra"}],
            "tts_text": "Задеплоил новый релиз в кубернетес, проверьте, пожалуйста, логи."}
    base.update(kw)
    return base


# ------------------------------------------------------------------ shipped data
def test_shipped_lexicon_is_valid_and_large() -> None:
    entries = load_lexicon(LEXICON)
    assert len(entries) >= 500
    assert validate_lexicon(entries) == []
    assert {e.category for e in entries} == set(LEXICON_CATEGORIES)


def test_shipped_seed_passes_with_required_counts() -> None:
    rep = validate_probe.validate_files([PROBE / "seed.jsonl"], LEXICON,
                                        min_lang={"ru": 170, "en": 100, "mixed": 150})
    assert rep.errors == []
    assert rep.stats["sentences"] >= 420
    assert all(row["sentences"] > 0 for row in rep.stats["categories"].values())


def test_cli_exit_codes(tmp_path: Path, capsys: pytest.CaptureFixture[str]) -> None:
    assert validate_probe.main([str(PROBE / "seed.jsonl"), "--format", "none"]) == 0
    bad = tmp_path / "bad.jsonl"
    bad.write_text(json.dumps(rec(lang="ru"), ensure_ascii=False) + "\n{not json\n", encoding="utf-8")
    assert validate_probe.main([str(bad), "--format", "none"]) == 1
    err = capsys.readouterr().err
    assert "script mix" in err and "invalid JSON" in err
    assert validate_probe.main([str(bad), "--lexicon", str(tmp_path / "missing.jsonl")]) == 2


# ------------------------------------------------------------------ record checks
def test_good_record_passes(matcher: TermMatcher) -> None:
    assert check_record(rec(), matcher) == []


@pytest.mark.parametrize("change, needle", [
    ({"terms": [{"term": "Helm", "category": "devops_infra"}]}, "does not occur in text"),
    ({"terms": [{"term": "Kubernetes", "category": "ai_tools"}]}, "is 'devops_infra' in the lexicon"),
    ({"terms": [{"term": "Kubernetes", "category": "nonsense"}]}, "unknown category"),
    ({"terms": []}, "0 lexicon terms"),
    ({"lang": "ru"}, "script mix"),
    ({"lang": "xx"}, "bad lang"),
    ({"domain": "poetry"}, "unknown domain"),
    ({"id": "Bad Id"}, "bad id"),
    ({"tts_text": None}, "needs tts_text"),
    ({"tts_text": "Задеплоил новый релиз в Kubernetes, проверьте логи."}, "must not contain Latin"),
    ({"text": "Задеплоил в Kubernetes."}, "words, allowed 6-35"),
    ({"text": "Задеплоил новый релиз в Kubernetes, проверьте, пожалуйста, логи"}, "sentence punctuation"),
    ({"text": "Задеплоил  новый релиз в Kubernetes, проверьте, пожалуйста, логи."}, "stray whitespace"),
    ({"extra": 1}, "unknown keys"),
])
def test_bad_records_are_caught(matcher: TermMatcher, change: dict, needle: str) -> None:
    problems = check_record(rec(**change), matcher)
    assert any(needle in p for p in problems), problems


def test_duplicate_ids_and_texts_fail(tmp_path: Path) -> None:
    f = tmp_path / "dup.jsonl"
    same_text = rec(id="t-2", text="Задеплоил новый релиз в Kubernetes, проверьте пожалуйста логи!")
    same_id = rec(text="Задеплоил вчера вечером в Kubernetes, теперь ждём метрик до утра.")
    f.write_text("\n".join(json.dumps(r, ensure_ascii=False) for r in (rec(), same_text, same_id)) + "\n",
                 encoding="utf-8")
    rep = validate_probe.validate_files([f], LEXICON)
    assert any("duplicate id" in e for e in rep.errors)
    assert any("exact duplicate" in e for e in rep.errors)


def test_min_lang_and_category_requirements(tmp_path: Path) -> None:
    f = tmp_path / "one.jsonl"
    f.write_text(json.dumps(rec(), ensure_ascii=False) + "\n", encoding="utf-8")
    rep = validate_probe.validate_files([f], LEXICON, min_lang={"en": 1}, min_category_sentences=1)
    assert any("lang 'en' has 0" in e for e in rep.errors)
    assert any("category 'hardware' used by 0" in e for e in rep.errors)


# ------------------------------------------------------------------ text helpers
@pytest.mark.parametrize("text, lang", [
    ("Завтра выкатим релиз на прод в 10:30.", "ru"),
    ("Ship the LoRA adapter to staging by Friday.", "en"),
    ("Завтра выкатим LoRA на стейджинг.", "mixed"),
    ("Ship the LoRA адаптер to staging today.", None),
])
def test_detect_lang(text: str, lang: str | None) -> None:
    assert detect_lang(text) == lang


def test_word_count_ignores_punctuation_tokens() -> None:
    assert word_count("Привет — это тест, v1.2 и 10:30 !") == 6


@pytest.mark.parametrize("surface, term", [
    ("на стейджинге", "стейджинг"),
    ("задеплоили", "задеплоить"),
    ("Kubernetes", "Kubernetes"),
    ("k8s", "k8s"),
    ("llama.cpp", "llama.cpp"),
])
def test_matcher_resolves_inflected_and_latin_forms(matcher: TermMatcher, surface: str, term: str) -> None:
    found = [m for m in matcher.find(f"Вчера {surface} всё сломалось.")]
    assert any(m.entry.term == term for m in found), [(m.surface, m.entry.term) for m in found]


def test_matcher_respects_case_for_capitalised_forms(matcher: TermMatcher) -> None:
    assert all(m.entry.term != "Room" for m in matcher.find("Book a meeting room for Friday."))
