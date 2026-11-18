"""Noise sampler: determinism, style replay, and statistics within tolerance of the mined tables."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[3]))

import pytest  # noqa: E402

from ml.roundtrip.mine.align import align  # noqa: E402
from ml.roundtrip.mine.mine_errors import mine_rows, read_jsonl  # noqa: E402
from ml.roundtrip.mine.noise import NoiseModel, calibrate, compare_profiles, simulate_rows  # noqa: E402
from ml.roundtrip.mine.norm import has_digit  # noqa: E402

FIXTURE = Path(__file__).resolve().parents[1] / "samples" / "mined" / "fixture_roundtrip.jsonl"
TONE = "t-one@onnx"
WHIS = "whisper-large-v3-turbo@onnx-int8"
TEXT = "Созвон с Laura по Kubernetes в 10:30, потом задеплоим Airflow в прод."
TERMS = [{"term": "Laura", "category": "person"}, {"term": "Kubernetes", "category": "devops_infra"},
         {"term": "Airflow", "category": "devops_infra"}]


@pytest.fixture(scope="module")
def rows():
    return read_jsonl([FIXTURE])


@pytest.fixture(scope="module")
def tables(rows):
    return mine_rows(rows)


@pytest.fixture(scope="module")
def nm(tables):
    return NoiseModel.from_tables(tables)


def test_same_seed_same_output(nm):
    a = nm.simulate(TEXT, TONE, TERMS, "mixed", seed=7)
    b = nm.simulate(TEXT, TONE, TERMS, "mixed", seed=7)
    assert a == b
    outs = {nm.simulate(TEXT, WHIS, TERMS, "mixed", seed=s, severity=2.0).hypothesis for s in range(30)}
    assert len(outs) > 1


def test_t_one_style_is_replayed(nm):
    for s in range(20):
        h = nm.simulate(TEXT, TONE, TERMS, "mixed", seed=s).hypothesis
        assert h == h.lower()
        assert not any(c in h for c in ".,!?:;")
        assert not has_digit(h)
        assert not any("a" <= c <= "z" for c in h)


def test_observed_term_mangling_is_used(nm):
    h = nm.simulate("Задеплой сервис в ClickHouse через CI/CD до пятницы.", TONE,
                    [{"term": "ClickHouse"}, {"term": "CI/CD"}], "mixed", seed=1).hypothesis
    assert "клик хаус" in h and "сиай сиди" in h


def test_severity_zero_has_no_recognition_errors(nm, rows):
    for r in rows[:60]:
        sim = nm.simulate(r["text"], r["asr_model"], r["terms"], r["lang"], seed=3, severity=0.0)
        al = align(r["text"], sim.hypothesis)
        assert al.errors == 0, (r["text"], sim.hypothesis)


def test_severity_increases_errors(nm, rows):
    def mean_errors(sev):
        tot = 0
        for r in rows:
            for s in range(3):
                h = nm.simulate(r["text"], r["asr_model"], r["terms"], r["lang"], seed=s, severity=sev).hypothesis
                tot += align(r["text"], h).errors
        return tot

    assert mean_errors(0.0) < mean_errors(1.0) < mean_errors(3.0)


def test_unknown_model_raises(nm):
    with pytest.raises(KeyError):
        nm.simulate("текст", "no-such-model", seed=0)


def test_simulated_statistics_within_tolerance_of_tables(nm, rows, tables):
    """Mining the sampler's output must reproduce the mined profile (in-sample, several repeats)."""
    sim_rows = simulate_rows(nm, rows, None, seed=11, severity=1.0, repeats=4, use_row_model=True)
    sim = mine_rows(sim_rows)
    cmp = compare_profiles(tables.profiles, sim.profiles)
    bad = [(m, n, r) for m, rs in cmp.items() for n, r in rs.items() if r["ok"] is False]
    assert not bad, bad
    for m in tables.profiles:
        real_p, sim_p = tables.profiles[m]["sampler"], sim.profiles[m]["sampler"]
        assert abs(real_p["p_sub"] - sim_p["p_sub"]) <= 0.02
        assert abs(real_p["p_del"] - sim_p["p_del"]) <= 0.02
        assert abs(real_p["p_ins"] - sim_p["p_ins"]) <= 0.02


def test_calibrate_writes_report(tmp_path, rows, tables):
    cmp = calibrate(rows, tables, tmp_path, seed=0, repeats=1)
    assert (tmp_path / "CALIBRATION.md").exists() and (tmp_path / "calibration.json").exists()
    assert set(cmp) == set(tables.profiles)


def test_garbled_unseen_term_is_counted_as_an_error_by_the_miner(nm):
    """A forced term error must survive the aligner's transliteration tolerance (not become 'translit')."""
    import random

    from ml.roundtrip.mine.noise import _is_error_variant

    for term in ("Kubernetes", "ClickHouse", "Grafana", "PyTorch", "Airflow", "эмбеддинги", "LangChain"):
        for s in range(10):
            v = nm._fallback_term(term, TONE, "mixed", random.Random(s), force_error=True)
            assert _is_error_variant(term, v), (term, v)


def test_unseen_term_style_follows_the_model(nm):
    """Unseen Latin term at severity 0: T-one transliterates to Cyrillic, Whisper keeps the Latin term."""
    text = "Подними Memcached на стейдже."
    terms = [{"term": "Memcached", "category": "devops_infra"}]
    for s in range(10):
        h_tone = nm.simulate(text, TONE, terms, "mixed", seed=s, severity=0.0).hypothesis
        h_whis = nm.simulate(text, WHIS, terms, "mixed", seed=s, severity=0.0).hypothesis
        assert not any("a" <= c <= "z" for c in h_tone.lower()), h_tone
        assert align(text, h_tone).errors == 0, h_tone
        assert "memcached" in h_whis.lower() or "мемкэшд" in h_whis or align(text, h_whis).errors == 0, h_whis


def test_words_to_digits_is_mined_and_replayed():
    """A model that writes spelled-out numbers as digits: the miner measures the rate, the sampler replays it."""
    texts = ["Совещание займёт двадцать пять минут.", "Сборка ускорилась на пятнадцать процентов.",
             "Мы закрыли сто двадцать три тикета.", "Осталось двадцать задач в бэклоге."]
    hyps_digits = ["Совещание займёт 25 минут.", "Сборка ускорилась на 15%.", "Мы закрыли 123 тикета.",
                   "Осталось 20 задач в бэклоге."]
    rows = []
    for k in range(40):
        t = texts[k % 4]
        h = hyps_digits[k % 4] if k % 5 else t  # 80% written as digits
        rows.append({"probe_id": f"n{k}", "lang": "ru", "text": t, "terms": [], "asr_model": "digity",
                     "hypothesis": h})
    tables = mine_rows(rows)
    d = tables.profiles["digity"]["digits"]
    assert d["word_numbers"] == 40 and abs(d["words_to_digits_rate"] - 0.8) < 1e-9
    nm2 = NoiseModel.from_tables(tables)
    sim = mine_rows(simulate_rows(nm2, rows, None, seed=5, severity=0.0, repeats=10, use_row_model=True))
    assert abs(sim.profiles["digity"]["digits"]["words_to_digits_rate"] - 0.8) < 0.08


def test_low_support_metrics_are_reported_but_not_judged():
    """A rate resting on 2 real observations is shown in brackets, never flagged."""
    from ml.roundtrip.mine.noise import CAL_MIN_SUPPORT, render_calibration

    real = {"m": {"n": 50, "n_ref_words": 500, "wer": 0.1, "digits": {"kept_digits_rate": 0.0, "ref_numbers": 2}}}
    sim = {"m": {"n": 50, "n_ref_words": 500, "wer": 0.3, "digits": {"kept_digits_rate": 1.0, "ref_numbers": 2}}}
    cmp = compare_profiles(real, sim)
    assert cmp["m"]["wer"]["ok"] is False and cmp["m"]["wer"]["support"] == 500
    assert cmp["m"]["digits_kept_rate"]["ok"] is None and cmp["m"]["digits_kept_rate"]["support"] < CAL_MIN_SUPPORT
    md = render_calibration(cmp, "t", "note")
    assert "(0.000→1.000)" in md and "Flagged gaps: 1 of 1." in md


def test_model_that_spells_heard_numbers_spells_digits_it_never_saw(tables):
    """No digit numbers mined, but spoken numbers came out as words: digits in the input are spelled out."""
    import copy

    profiles = copy.deepcopy(tables.profiles)
    profiles[TONE]["digits"] = {"ref_numbers": 0, "kept_digits_rate": None, "spelled_rate": None,
                                "hyp_digit_rate": 0.0, "word_numbers": 5, "words_to_digits_rate": 0.0}
    nm2 = NoiseModel(profiles, tables.confusions, tables.term_manglings)
    for s in range(10):
        hyp = nm2.simulate("Встреча в 10:30 на 15 минут.", TONE, [], "ru", seed=s, severity=0.0).hypothesis
        assert not has_digit(hyp), hyp


def test_manglings_are_not_borrowed_across_spoken_languages(tables):
    """A term mangled to Cyrillic only inside Russian speech must not come out Cyrillic in an English sentence."""
    import re

    model = "parakeet-tdt-0.6b-v3@onnx-int8"
    ru_only = [{"asr_model": model, "term": "LoRA", "category": "ai_ml_jargon", "lang_context": "mixed", "n": 4,
                "exact_rate": 0.0, "variants": [{"hyp": "лора", "count": 4}]}]
    nm2 = NoiseModel(tables.profiles, tables.confusions, ru_only)
    for s in range(20):
        en = nm2.simulate("Merge the LoRA adapter now.", model, [{"term": "LoRA", "category": "x"}], "en",
                          seed=s, severity=0.0).hypothesis
        assert not re.search("[а-яё]", en, re.I), en
    ru = {nm2.simulate("Смёржи LoRA адаптер.", model, [{"term": "LoRA", "category": "x"}], "mixed",
                       seed=s, severity=0.0).hypothesis for s in range(5)}
    assert all("лора" in h.lower() for h in ru), ru
