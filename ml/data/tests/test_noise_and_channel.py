import random
from pathlib import Path

import pytest

from ml.data.synth import asr_noise as AN
from ml.data.synth import disfluency as D
from ml.data.synth.channel import Channel

REPO = Path(__file__).resolve().parents[3]
FIXTURE = REPO / "ml" / "roundtrip" / "samples" / "mined" / "fixture"


def test_builtin_noise_is_deterministic():
    a = AN.noise("давай созвонимся завтра в три", "Давай созвонимся завтра в три.", "ru", "parakeet_v3", 0.5,
                 random.Random(7))
    b = AN.noise("давай созвонимся завтра в три", "Давай созвонимся завтра в три.", "ru", "parakeet_v3", 0.5,
                 random.Random(7))
    assert a.hyp == b.hyp and a.ops == b.ops


def test_t_one_has_no_latin_no_digits_no_punct():
    for k in range(10):
        out = AN.noise("я обновил ClickHouse в пятнадцать тридцать", "Я обновил ClickHouse в 15:30.", "ru", "t_one", 1.0,
                       random.Random(k), entities=[{"text": "ClickHouse", "kind": "tech"}])
        assert not any("a" <= c.lower() <= "z" for c in out.hyp), out.hyp
        assert not any(c.isdigit() for c in out.hyp), out.hyp
        assert out.hyp == out.hyp.lower() and "." not in out.hyp


def test_digitiser_keeps_small_counts_and_ordinal_adverbs():
    toks = "о трёх вещах. Во-первых, фото. Во-вторых, история.".split()
    assert AN._digitise(toks, "ru", random.Random(0)) == toks


def test_digitiser_times_money_percent():
    toks = "до двенадцати ноль ноль скидка двадцать пять процентов пятнадцать тысяч двести рублей".split()
    out = " ".join(AN._digitise(toks, "ru", random.Random(1)))
    assert "12:00" in out and "25" in out and ("15200" in out or "15 200" in out)
    assert "ноль" not in out


def test_sentence_initial_capitals_do_not_leak():
    # 'Давай' is capitalised in the reference only because it starts a sentence: never projected mid-sentence
    for k in range(20):
        out = AN.noise("ну давай в четыре", "Ну давай в четыре.", "ru", "whisper", 0.0, random.Random(k))
        assert "Давай" not in out.hyp.split()[1:], out.hyp


def test_disfluency_records_ops_and_keeps_words():
    r = random.Random(3)
    s, ops = D.inject("Я посмотрел отчёт, там всё нормально, можно отправлять.", "ru", 0.8, r)
    assert ops and len(s.split()) > 7
    for w in "посмотрел отчёт нормально отправлять".split():
        assert w in s
    s0, ops0 = D.inject("Clean text.", "en", 0.0, r)
    assert s0 == "Clean text." and ops0 == []


def test_disfluency_never_doubles_negation():
    for k in range(50):
        s, ops = D.inject("я не приду, не жди", "ru", 0.8, random.Random(k))
        assert "не не" not in s


def test_channel_builtin_models_by_language():
    ch = Channel(None)
    assert ch.source == "builtin"
    assert "t_one" not in ch.models("en") and "gigaam_v3_e2e" not in ch.models("en")
    assert "t_one" in ch.models("ru")


@pytest.mark.skipif(not (FIXTURE / "profiles.json").exists(), reason="round-trip fixture absent")
def test_channel_mined_adapter_uses_noise_py():
    ch = Channel(FIXTURE)
    assert ch.source == "mined:fixture"
    en = ch.models("en")
    assert en and not any("gigaam" in m or "t-one" in m for m in en)
    hyp1, ev1 = ch.transcribe("Слушай, я обновил ClickHouse в пятнадцать тридцать.", "", "mixed", ch.models("mixed")[0],
                              [{"text": "ClickHouse", "category": "databases_data"}], seed=5, severity=1.0)
    hyp2, ev2 = ch.transcribe("Слушай, я обновил ClickHouse в пятнадцать тридцать.", "", "mixed", ch.models("mixed")[0],
                              [{"text": "ClickHouse", "category": "databases_data"}], seed=5, severity=1.0)
    assert hyp1 == hyp2 and ev1 == ev2 and hyp1
