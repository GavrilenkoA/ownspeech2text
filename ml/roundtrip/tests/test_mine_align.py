"""Alignment edge cases: scripts, numbers, multi-word manglings, empty and hallucinated hypotheses."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[3]))

import pytest  # noqa: E402

from ml.roundtrip.mine.align import align, describe, raw_wer_counts, term_hits  # noqa: E402


def kinds(ref: str, hyp: str) -> list[tuple[str, str, str]]:
    al = align(ref, hyp)
    return [(op.kind, al.ref_span(op), al.hyp_span(op)) for op in al.ops]


def test_style_only_differences_are_not_errors():
    al = align("Созвон с Laura по Kubernetes в 10:30.", "созвон с лаурой по кубернетис в десять тридцать")
    assert al.errors == 0
    assert [op.kind for op in al.ops] == ["case", "match", "translit", "match", "translit", "match", "number"]
    # the conventional WER still counts transliteration and spelled numbers
    assert raw_wer_counts(al.ref_text, al.hyp_text).rate > 0.5


@pytest.mark.parametrize("ref,hyp", [
    ("ClickHouse", "клик хаус"),
    ("CI/CD", "си ай си ди"),
    ("CI/CD", "сиай сиди"),
    ("OpenAI", "опен эй ай"),
    ("p99", "пи девяносто девять"),
    ("Qwen3", "квен три"),
    ("H100", "аш сто"),
    ("Hugging Face", "хаггингфейс"),
])
def test_multi_word_manglings_merge_into_one_translit_block(ref, hyp):
    assert kinds(ref, hyp) == [("translit", ref, hyp)]


@pytest.mark.parametrize("ref,hyp", [
    ("10:30", "десять тридцать"),
    ("2025", "twenty twenty five"),
    ("2025", "две тысячи двадцать пять"),
    ("15%", "пятнадцать процентов"),
    ("15%", "fifteen percent"),
    ("0,35", "ноль целых тридцати пяти"),
    ("0.0003", "zero point zero zero zero three"),
    ("9:05", "девять ноль пять"),
    ("4:15", "4.15"),
    ("18:00", "восемнадцати"),
    ("5-го", "пятого"),
])
def test_numbers_digits_vs_words_both_ways(ref, hyp):
    assert [k for k, _, _ in kinds(ref, hyp)] == ["number"]
    assert [k for k, _, _ in kinds(hyp, ref)] == ["number"]


def test_number_with_scale_word():
    assert ("number", "128 тысяч", "128 000") in kinds("на 128 тысяч токенов", "на 128 000 токенов")
    assert ("number", "1.2", "one point two") in kinds("to 1.2 million", "to one point two million")


def test_wrong_number_is_an_error():
    al = align("ошибка 502", "ошибка пятьсот три")
    assert al.errors > 0


def test_case_yo_and_separators():
    assert kinds("Ёлка", "елка") == [("yo", "Ёлка", "елка")]
    assert kinds("Release", "release") == [("case", "Release", "release")]
    assert kinds("e-mail", "email") == [("punct", "e-mail", "email")]
    assert kinds("Node.js", "node JS") == [("punct", "Node.js", "node JS")]


def test_garbled_split_term_is_an_error_not_translit():
    # one written word split into two spoken words that are not its CamelCase parts
    assert kinds("Grafana", "графа не") == [("sub_near", "Grafana", "графа не")]
    assert kinds("Kafka", "cafe")[0][0] == "sub"


def test_near_miss_and_unrelated_substitution():
    assert kinds("Priya", "Freya")[0][0] == "sub_near"
    assert kinds("воркер", "рокер")[0][0] == "sub_near"
    assert kinds("сервер", "лопата")[0][0] == "sub"


def test_empty_hypothesis_is_all_deletions():
    al = align("Привет, как дела?", "")
    assert [op.kind for op in al.ops] == ["del", "del", "del"]
    assert al.wer_style == 1.0


def test_empty_reference_is_all_insertions():
    al = align("", "спасибо за просмотр")
    assert [op.kind for op in al.ops] == ["ins", "ins", "ins"]


def test_hallucinated_tail_is_trailing_insertions():
    al = align("Мы обновили PyTorch на сервере.", "Мы обновили Пайторч на сервере. Продолжение следует...")
    lo, hi = al.trailing_insertions()
    assert al.hyp_surface(lo, hi) == "Продолжение следует"
    assert al.ops[-1].kind == "ins" and al.errors == 2


def test_term_hits_map_terms_to_hypothesis_spans():
    al = align("Задеплой сервис в ClickHouse через CI/CD.", "задеплой сервис в клик хаус через си ай си ди")
    hits = term_hits(al, [{"term": "ClickHouse", "category": "databases_data"},
                          {"term": "CI/CD", "category": "acronyms"}])
    assert [(h.term, h.hyp, h.exact, h.is_error) for h in hits] == [
        ("ClickHouse", "клик хаус", False, False), ("CI/CD", "си ай си ди", False, False)]


def test_term_hit_exact_and_deleted():
    al = align("Открой issue в GitHub", "Открой в GitHub")
    hits = {h.term: h for h in term_hits(al, [{"term": "issue"}, {"term": "GitHub"}])}
    assert hits["GitHub"].exact
    assert hits["issue"].hyp == "" and hits["issue"].is_error


def test_alignment_is_deterministic():
    a = align("Попробуй fine-tuning через LoRA на Qwen3, rank поставь 16.",
              "попробуй файн тюнинг через лору на квен три ранк поставь шестнадцать")
    b = align("Попробуй fine-tuning через LoRA на Qwen3, rank поставь 16.",
              "попробуй файн тюнинг через лору на квен три ранк поставь шестнадцать")
    assert a.ops == b.ops
    assert a.errors == 0


def test_term_merged_with_a_russian_neighbour_is_an_error_not_translit():
    """Observed in the real round trip (T-one, GigaAM): "кину в Slack" -> "киновслаг", "по Kubernetes" -> "покубернитесь"."""
    al = align("ссылку кину в Slack.", "ссылку киновслаг")
    assert al.errors > 0 and "translit" not in [op.kind for op in al.ops], describe(al)
    # here the cheapest account is "по" deleted + "Kubernetes" transliterated with a glued prefix
    al = align("Созвон по Kubernetes перенесли.", "Созвон покубернитесь перенесли")
    assert al.errors > 0, describe(al)
