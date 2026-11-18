"""Tokenisation, number words and transliteration helpers of the miner."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[3]))

import pytest  # noqa: E402

from ml.roundtrip.mine.norm import (en_cardinal, number_groups, parse_number_words, ru_cardinal,  # noqa: E402
                                    spell_numeric_token, tokenize)
from ml.roundtrip.mine.translit import lat2cyr, phon_sim, phonetic_key  # noqa: E402


def test_tokenize_keeps_internal_punctuation_and_symbols():
    toks = tokenize("«Привет», — сказал он... CI/CD-пайплайн (Kubernetes) в 10:30, C++ и C# 15%; e-mail.")
    assert [t.core for t in toks] == ["Привет", "сказал", "он", "CI/CD-пайплайн", "Kubernetes", "в", "10:30",
                                      "C++", "и", "C#", "15%", "e-mail"]
    assert toks[0].lead == "«" and toks[0].trail == "»,"


@pytest.mark.parametrize("words,expected", [
    ("сто двадцать три", [123]),
    ("десять тридцать", [10, 30]),
    ("twenty twenty five", [20, 25]),
    ("two thousand twenty five", [2025]),
    ("one hundred and twenty three", [123]),
    ("две тысячи двадцать пятого", [2025]),
    ("двадцать первого", [21]),
    ("девять ноль пять", [9, 0, 5]),
])
def test_parse_number_words(words, expected):
    assert parse_number_words(words.split()) == expected


def test_parse_rejects_non_numbers():
    assert parse_number_words(["кубер"]) is None
    assert number_groups(["сервер"]) is None


def test_number_groups_units_and_scales():
    assert number_groups(["15%"]) == ["15", "%"]
    assert number_groups(["пятнадцать", "процентов"]) == ["15", "%"]
    assert number_groups(["128", "тысяч"]) == ["128000"]
    assert number_groups(["one", "point", "two", "million"]) == ["1200000"]


def test_cardinals():
    assert ru_cardinal(1234567) == "один миллион двести тридцать четыре тысячи пятьсот шестьдесят семь"
    assert ru_cardinal(21000) == "двадцать одна тысяча"
    assert en_cardinal(2025) == "two thousand twenty five"


@pytest.mark.parametrize("tok,lang,nxt,expected", [
    ("10:30", "ru", "", "десять тридцать"),
    ("10:05", "en", "", "ten oh five"),
    ("15%", "ru", "", "пятнадцать процентов"),
    ("5-го", "ru", "", "пятого"),
    ("21st", "en", "", "twenty first"),
    ("2", "ru", "раза", "два"),
    ("2", "ru", "минуты", "две"),
    ("1", "ru", "ошибка", "одна"),
    ("3,5", "ru", "", "три и пять"),
])
def test_spell_numeric_token(tok, lang, nxt, expected):
    assert spell_numeric_token(tok, lang, nxt) == expected


def test_phonetic_key_is_script_independent():
    assert phonetic_key("Kubernetes") == phonetic_key("кубернетис")
    assert phonetic_key("ClickHouse") == phonetic_key("клик хаус")
    assert phon_sim("Jira", "джира") == 1.0
    assert phon_sim("Kubernetes", "компьютер") < 0.6


def test_lat2cyr_reads_english():
    assert lat2cyr("ClickHouse", split_camel=True) == "клик хаус"
    assert lat2cyr("CI/CD") == "си ай си ди"
    assert lat2cyr("git pull") == "гит пул"
    assert lat2cyr("Airflow") == "эйрфлоу"


@pytest.mark.parametrize("words,expected", [
    ("двадцать пять", "25"), ("пятнадцать процентов", "15%"), ("two hundred", "200"), ("сто двадцать три", "123"),
    ("двадцать", "20"), ("один", None), ("three", None), ("пятого", None), ("десять тридцать", None),
])
def test_cardinal_word_run(words, expected):
    from ml.roundtrip.mine.norm import cardinal_word_run

    assert cardinal_word_run(words.split()) == expected
