"""Mining and noise-sampler fixes from the adversarial review (2026-11-19).

Language gate, smoothed term errors, per-language word rates, phrase-level term manglings, the TTS breakdown,
unit abbreviations as number style, real-word substitutions for LM decoders, foreign-script output and the
hub-id map.
"""
from __future__ import annotations

import json
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT))

import pytest  # noqa: E402

from ml.roundtrip.mine.align import align, term_hits  # noqa: E402
from ml.roundtrip.mine.mine_errors import hardest_terms, mine_rows, render_report  # noqa: E402
from ml.roundtrip.mine.noise import (NoiseModel, UnsupportedLanguage, compare_profiles, load_hub_map,  # noqa: E402
                                     simulate_rows)


def row(pid, lang, text, hyp, model, terms=(), tts="supertonic3", tts_input="text"):
    return {"probe_id": pid, "lang": lang, "text": text, "terms": [{"term": t, "category": "x"} for t in terms],
            "tts_system": tts, "tts_voice": "v", "channel": "clean", "audio": "", "audio_s": 3.0, "asr_model": model,
            "hypothesis": hyp, "asr_wall_s": 1.0, "tts_input": tts_input}


# ------------------------------------------------------------------------------------ language gate
def test_simulate_never_produces_a_language_the_model_was_not_mined_on():
    rows = [row("r1", "ru", "Задеплой сервис на прод.", "задеплой сервис на прод", "t-one@onnx"),
            row("e1", "en", "Deploy the service.", "Deploy the service.", "distil-large-v3.5@onnx-int8")]
    nm = NoiseModel.from_tables(mine_rows(rows))
    with pytest.raises(UnsupportedLanguage):
        nm.simulate("I'm learning Rust on weekends.", "t-one@onnx", lang="en", seed=1)
    with pytest.raises(UnsupportedLanguage):
        nm.simulate("Задеплой сервис.", "distil-large-v3.5@onnx-int8", lang="ru", seed=1)
    probes = [{"id": "a", "lang": "en", "text": "Ship it today."}, {"id": "b", "lang": "ru", "text": "Выкатим сегодня."}]
    skipped: Counter = Counter()
    out = simulate_rows(nm, probes, nm.models(), seed=0, severity=1.0, skipped=skipped)
    assert {(r["asr_model"], r["lang"]) for r in out} == {("t-one@onnx", "ru"), ("distil-large-v3.5@onnx-int8", "en")}
    assert skipped == Counter({("t-one@onnx", "en"): 1, ("distil-large-v3.5@onnx-int8", "ru"): 1})


# ------------------------------------------------------------------------------- smoothed term errors
def test_a_term_seen_once_correctly_still_fails_at_about_the_model_rate():
    m = "whisper-large-v3-turbo@onnx-int8"
    rows = [row("k", "en", "Deploy it to Kubernetes now.", "Deploy it to Kubernetes now.", m, ["Kubernetes"])]
    # 9 other terms, all garbled: the model's English term error rate is 0.9
    for i in range(9):
        rows.append(row(f"g{i}", "en", f"Ask Zorblax{i} about it.", "Ask the blacks about it.", m, [f"Zorblax{i}"]))
    nm = NoiseModel.from_tables(mine_rows(rows))
    prior = nm._term_error_prior(m, "en")
    assert prior == pytest.approx(0.9)
    sims = [nm.simulate("Deploy it to Kubernetes now.", m, [{"term": "Kubernetes", "category": "x"}], "en", seed=s)
            for s in range(400)]
    garbled = sum("Kubernetes" not in s.hypothesis for s in sims) / len(sims)
    # (0 errors + 3 x 0.9) / (1 + 3) = 0.675; the old raw estimate was 0
    assert 0.55 < garbled < 0.8


# ------------------------------------------------------------------------------ per-language rates
def test_word_error_rates_are_mined_and_replayed_per_language():
    m = "parakeet-tdt-0.6b-v3@onnx-int8"
    en = [row(f"e{i}", "en", "please send the final report to the whole team today",
              "please send the final report to the whole team today", m) for i in range(20)]
    ru = [row(f"r{i}", "ru", "пожалуйста отправь итоговый отчёт всей команде сегодня вечером",
              "пожалуйста отправ итоговый отчет всей комнате сегодня вечер", m) for i in range(20)]
    tables = mine_rows(en + ru)
    by_lang = tables.profiles[m]["sampler"]["by_lang"]
    assert by_lang["en"]["p_sub"] == 0.0 and by_lang["ru"]["p_sub"] > 0.3
    nm = NoiseModel.from_tables(tables)
    sim = mine_rows(simulate_rows(nm, en + ru, None, seed=3, severity=1.0, repeats=3, use_row_model=True))
    w = sim.profiles[m]["wer_by_lang"]
    assert w["en"]["wer_style_aware"] < 0.1 < w["ru"]["wer_style_aware"]
    cmp = compare_profiles(tables.profiles, sim.profiles)
    assert "wer_style_aware@en" in cmp[m] and "term_error_rate@ru" in cmp[m]


# ------------------------------------------------------------------------- phrase-level manglings
def test_term_merged_with_neighbours_is_a_phrase_mangling_and_the_sampler_replaces_the_phrase():
    m = "t-one@onnx"
    text = "Ссылку кину в Slack."
    al = align(text, "ссылку киновслаг")
    hit = term_hits(al, [{"term": "Slack", "category": "x"}])[0]
    assert hit.merged and hit.hyp == "киновслаг"
    rows = [row(f"s{i}", "mixed", text, "ссылку киновслаг", m, ["Slack"]) for i in range(5)]
    tables = mine_rows(rows)
    t = [x for x in tables.term_manglings if x["term"] == "Slack"][0]
    assert t["variants"] == [] and t["merged"] == [{"ref": "кину в Slack", "hyp": "киновслаг", "count": 5}]
    nm = NoiseModel.from_tables(tables)
    outs = {nm.simulate(text, m, [{"term": "Slack", "category": "x"}], "mixed", seed=s).hypothesis for s in range(30)}
    # the neighbours "кину в" are never repeated next to the phrase mangling
    assert all("кину" not in h or "киновслаг" not in h for h in outs)
    assert any("киновслаг" in h for h in outs)
    # in a different context the phrase does not apply: no "киновслаг" without "кину в"
    other = {nm.simulate("Напиши в Slack.", m, [{"term": "Slack", "category": "x"}], "mixed", seed=s).hypothesis
             for s in range(30)}
    assert not any("киновслаг" in h for h in other)


# ---------------------------------------------------------------------------------- TTS breakdown
def test_profiles_break_statistics_down_by_tts_system():
    m = "gigaam-v3-e2e-ctc@onnx-int8"
    rows = [row("a", "mixed", "Залей в Kubernetes.", "Залей в кубернетес.", m, ["Kubernetes"], "piper", "tts_text"),
            row("a", "mixed", "Залей в Kubernetes.", "Залей в Kubernetes.", m, ["Kubernetes"], "supertonic3")]
    p = mine_rows(rows).profiles[m]
    assert p["by_tts"]["piper"]["by_lang"]["mixed"]["latin_kept_rate"] == 0.0
    assert p["by_tts"]["supertonic3"]["by_lang"]["mixed"]["latin_kept_rate"] == 1.0
    assert p["by_tts"]["piper"]["inputs"] == {"tts_text": 1}
    report = render_report(mine_rows(rows), "t", ["x"])
    assert "## By TTS system" in report


# --------------------------------------------------------------------------------- units as style
@pytest.mark.parametrize("ref,hyp", [("using sixty gigabytes of RAM", "using 60GB of RAM"),
                                     ("кэш занял сорок гигабайт", "кэш занял 40 ГБ"),
                                     ("медленнее на двести миллисекунд", "медленнее на 200 мс")])
def test_number_with_unit_abbreviation_is_style(ref, hyp):
    al = align(ref, hyp)
    assert al.errors == 0 and "number" in [op.kind for op in al.ops]


def test_wrong_number_with_unit_is_still_an_error():
    assert align("using sixty gigabytes", "using 16GB").errors > 0


# --------------------------------------------------------------------------- LM-decoder realism
def test_lm_decoder_substitutions_are_real_words():
    m = "whisper-small@onnx-int8"
    base = "мы обсудили голосовой интерфейс и новый экран настроек на встрече"
    rows = [row(f"x{i}", "ru", base, base.replace("голосовой", "голосом").replace("экран", "экраны"), m)
            for i in range(10)]
    nm = NoiseModel.from_tables(mine_rows(rows))
    vocab = set(nm._words.get("cyr", []))
    seen_subs = set()
    for s in range(200):
        sim = nm.simulate("мы проверили голосовой ввод на новом телефоне вчера вечером", m, lang="ru", seed=s,
                          severity=2.0)
        seen_subs.update(e.split("->", 1)[1] for e in sim.events if e.startswith("sub:"))
    assert seen_subs, "no substitutions drawn"
    assert seen_subs <= vocab | {"во", "и", "на", "а", "в", "о", "под", "па", "но", "ни", "со", "из"}


# --------------------------------------------------------------------------------- foreign script
def test_foreign_script_output_is_mined_and_replayed_rarely():
    m = "qwen3-asr-0.6b@onnx-int8"
    rows = [row(f"o{i}", "ru", "Запусти npm install ещё раз.", "Запусти npm install ещё раз.", m) for i in range(9)]
    rows.append(row("f", "ru", "Запусти npm install ещё раз.", "زحفتي MPM install، مرة أخرى", m))
    p = mine_rows(rows).profiles[m]
    assert p["hallucination"]["foreign_script_rate"] == pytest.approx(0.1)
    nm = NoiseModel.from_tables(mine_rows(rows))
    events = Counter(e for s in range(300) for e in nm.simulate("Запусти сборку ещё раз.", m, lang="ru", seed=s).events
                     if e == "foreign_script")
    assert 10 < events["foreign_script"] < 60


# ------------------------------------------------------------------------------------- report
def test_hardest_terms_rank_by_summed_errors():
    mang = [{"term": "Kafka", "lang_context": "mixed", "asr_model": "a", "n": 4, "error_n": 3,
             "variants": [{"hyp": "кофка", "count": 3}, {"hyp": "кафка", "count": 1}], "merged": []},
            {"term": "Kafka", "lang_context": "mixed", "asr_model": "b", "n": 2, "error_n": 2,
             "variants": [{"hyp": "кавка", "count": 2}], "merged": []},
            {"term": "API", "lang_context": "en", "asr_model": "a", "n": 9, "error_n": 1,
             "variants": [{"hyp": "API", "count": 8}, {"hyp": "a pie", "count": 1}], "merged": []}]
    hard = hardest_terms(mang)
    assert [h["term"] for h in hard] == ["Kafka", "API"]
    assert hard[0]["errors"] == 5 and hard[0]["models"] == 2 and dict(hard[0]["wrong"]) == {"кофка": 3, "кавка": 2}


# ------------------------------------------------------------------------------------- hub map
def test_hub_map_covers_every_catalog_model():
    catalog = json.loads((ROOT / "docs" / "research" / "asr-catalog.json").read_text(encoding="utf-8"))
    hub = load_hub_map()
    from ml.roundtrip.asr.registry import ALL_MODELS

    assert {m["id"] for m in catalog["models"]} == set(hub)
    assert all(v["profile"] in ALL_MODELS for v in hub.values())
    assert hub["qwen3-asr-1.7b@litert-gpu-15s"]["proxy"] is True
    assert hub["t-one@onnx"] == {"profile": "t-one@onnx", "proxy": False, "note": hub["t-one@onnx"]["note"]}


def test_calibration_flags_only_gaps_beyond_sampling_noise():
    real = {"m": {"n": 30, "n_ref_words": 400, "wer": 0.1,
                  "terms": {"exact_rate": 0.6, "n": 26}, "langs": {"en": 30}}}
    sim = {"m": {"n": 30, "n_ref_words": 400, "wer": 0.1, "terms": {"exact_rate": 0.72, "n": 26}}}
    r = compare_profiles(real, sim)["m"]["term_exact_rate"]
    # 0.12 > tolerance 0.08, but 2 x sqrt(0.6 * 0.4 / 26) = 0.19: not measurable with 26 terms
    assert r["over_tol"] is True and r["ok"] is True
    sim["m"]["terms"]["exact_rate"] = 0.9
    assert compare_profiles(real, sim)["m"]["term_exact_rate"]["ok"] is False


# ------------------------------------------------------------------- digits inside kept Latin terms
def test_model_that_never_writes_digits_spells_them_inside_kept_latin_terms():
    m = "nemotron-speech-streaming-en-0.6b@onnx-int8-560ms"
    rows = [row(f"n{i}", "en", f"Book {i + 2} rooms for {i + 10} people.",
                f"Book {w} rooms for {v} people.", m)
            for i, (w, v) in enumerate([("two", "ten"), ("three", "eleven"), ("four", "twelve")])]
    rows.append(row("t", "en", "Ship the Deploy tool today.", "Ship the Deploy tool today.", m, ["Deploy"]))
    nm = NoiseModel.from_tables(mine_rows(rows))
    assert nm.profiles[m]["digits"]["hyp_digit_rate"] == 0.0
    term = [{"term": "Gemini 2.5 Pro", "category": "x"}]
    hyps = [nm.simulate("Is Gemini 2.5 Pro better?", m, term, "en", seed=s).hypothesis for s in range(60)]
    assert not [h for h in hyps if any(c.isdigit() for c in h)], hyps
    assert any("two point five" in h.lower() for h in hyps)
    # a model seen writing digits keeps them in the term
    w = "whisper-large-v3-turbo@onnx-int8"
    nm2 = NoiseModel.from_tables(mine_rows([row("w", "en", "Book 2 rooms.", "Book 2 rooms.", w)]))
    hyps2 = [nm2.simulate("Is Gemini 2.5 Pro better?", w, term, "en", seed=s).hypothesis for s in range(30)]
    assert any("2.5" in h for h in hyps2)
