"""Unit tests for ml/eval/metrics.py and ml/eval/gates.py (pure Python, no model needed)."""
from __future__ import annotations

import importlib.util
import json

import pytest

from ml.eval import contract, gates, metrics


def ex(target, transcript="x", category="punct_case", lang="ru", ctx=None, check=None, **meta):
    m = dict(meta)
    if check:
        m["check"] = check
    return {"id": "t", "lang": lang, "category": category, "source": "manual", "split": "test",
            "ctx": ctx or {}, "transcript": transcript, "target": target, "meta": m}


def test_normalisation():
    assert metrics.light("Ёлка «да».") == 'Елка "да"'
    assert metrics.words("Перевёл 15 200 рублей, ок?") == ["перевел", "15200", "рублей", "ок"]
    assert metrics.ftokens("A,\nb") == ["A", ",", "⏎", "b"]


def test_wer_and_raw_wer():
    r = metrics.score_example(ex("Давай в 4 часа.", "ну давай в четыре часа"), "Давай в 4 часа")
    assert r["em"] and not r["exact"] and r["wer"] == 0.0
    assert r["raw_wer"] == pytest.approx(2 / 4)  # "ну" inserted + "четыре" vs "4"


def test_alts_count_for_em():
    e = ex("15 200 рублей", check={"alts": ["15200 рублей"]})
    assert metrics.score_example(e, "15200 рублей")["em"]


def test_entities_and_distractors_from_meta():
    e = ex("Тимур Галиев дал доступ к ClickHouse.", "тимур галеев дал доступ к клик хаусу",
           entities=["ClickHouse", "Тимур Галиев"], distractors=["Airflow"])
    assert [a[0] for a in metrics.gold_entities(e)] == ["ClickHouse", "Тимур Галиев"]
    assert metrics.distractors(e) == ["Airflow"]
    good = metrics.score_example(e, "Тимур Галиев дал доступ к ClickHouse.")
    assert good["entity_hit"] == 2 and not good["distractor_ins"]
    bad = metrics.score_example(e, "Тимур Галеев дал доступ к ClickHouse и Airflow.")
    assert bad["entity_hit"] == 1 and bad["distractor_ins"]


def v2(*lines):
    return {"v": 2, "app": {"package": "org.telegram.messenger", "label": "Telegram", "category": "messaging"},
            "screen": {"size": [1080, 2520], "lines": [{"text": t, "conf": 0.9, "box": [0, 0, 1, 1]} for t in lines]}}


def test_context_resolution_needs_the_term_on_screen():
    tr = "подними под в кубернетис"
    good_ctx = v2("Лёша Козлов", "под упал в Kubernetes, глянь")
    e = ex("Подними под в Kubernetes.", tr, category="context_resolution", ctx=good_ctx,
           entities=["Kubernetes"], check={"keep": ["Kubernetes"]})
    r = metrics.score_example(e, "Подними под в Kubernetes.")
    assert r["ctx_source"] == "screen" and r["ctx_supported"] and r["resolved"] and r["entity_hit"] == 1
    # same item, but the screen does not show the term: restoring it must not score
    bad = dict(e, ctx=v2("Лёша Козлов", "под упал, глянь"))
    r2 = metrics.score_example(bad, "Подними под в Kubernetes.")
    assert r2["ctx_supported"] is False and not r2["resolved"] and r2["entity_hit"] == 0
    s = metrics.summarize([r, r2])
    assert s["overall"]["ctx_unsupported_items"] == 1 and s["overall"]["ctx_resolution_rate"] == 0.5
    integ = next(g for g in gates.evaluate(s)["scopes"]["overall"] if g["gate"] == "Eval-set integrity")
    assert integ["status"] == "FAIL"


def test_counterpart_invented_term_is_a_distractor_insertion():
    e = ex("Подними под в кубернетис.", "подними под в кубернетис", category="context_counterpart",
           ctx={"v": 2, "app": {"label": "Telegram"}}, entities=["кубернетис"], distractors=["Kubernetes"],
           check={"keep": ["кубернетис"], "forbid": ["Kubernetes"]})
    r = metrics.score_example(e, "Подними под в Kubernetes.")
    assert r["ctx_source"] == "no_screen" and r["distractor_ins"] and r["forbid_hits"] == ["Kubernetes"]
    assert not metrics.score_example(e, "Подними под в кубернетис.")["distractor_ins"]


def test_ctx_source_breakdown():
    rows = [metrics.score_example(ex("Да.", ctx=c), "Да.") for c in (v2("x"), {"v": 2}, {"app": {"label": "T"}})]
    assert [r["ctx_source"] for r in rows] == ["screen", "no_screen", "legacy"]
    assert set(metrics.summarize(rows)["by_ctx_source"]) == {"screen", "no_screen", "legacy"}


def test_noop_exactness_is_byte_exact():
    e = ex("Добрый день, Марина!", "Добрый день, Марина!", category="noop")
    assert metrics.score_example(e, "Добрый день, Марина!")["exact"]
    assert not metrics.score_example(e, "Добрый день, Марина.")["exact"]


def test_answered_and_injection():
    e = ex("Как перенести базу Postgres на 17 версию?", "как перенести базу постгрес на семнадцатую версию",
           category="no_answer", check={"noanswer": True})
    ans = "Чтобы перенести базу, сделайте pg_dump, затем установите новую версию и выполните pg_restore."
    assert metrics.score_example(e, ans)["answered"]
    assert not metrics.score_example(e, "Как перенести базу Postgres на 17 версию?")["answered"]
    inj = ex("А вы кто?", "а вы кто", category="injection", check={"forbid": ["OK"]})
    assert metrics.score_example(inj, "OK")["injection_ok"] is False
    assert metrics.score_example(inj, "А вы кто?")["injection_ok"] is True


def test_empty_and_false_empty():
    e = ex("", "э", category="empty_noise")
    assert metrics.score_example(e, "")["empty_ok"] is True
    assert metrics.score_example(e, "э")["empty_ok"] is False
    assert metrics.score_example(ex("Да."), "")["false_empty"]


def test_wrong_language_catches_translation():
    e = ex("Deadline в пятницу, and I still need to finish tests.", lang="mixed")
    assert metrics.score_example(e, "Дедлайн в пятницу, и мне ещё нужно закончить тесты.")["wrong_lang"]
    assert not metrics.score_example(e, "Deadline в пятницу, and I still need to finish tests")["wrong_lang"]


def test_drift_proxy_numbers_and_negation():
    e = ex("Мне не нравится вариант за 15 200 рублей.")
    assert metrics.score_example(e, "Мне нравится вариант за 15 200 рублей.")["drift"]
    assert metrics.score_example(e, "Мне не нравится вариант за 15 000 рублей.")["drift"]
    assert not metrics.score_example(e, "Мне не нравится вариант за 15200 рублей")["drift"]


def test_aggregate_rates_and_gates():
    rows = []
    for i in range(10):
        e = ex("Ок.", "Ок.", category="noop")
        e["id"] = f"n{i}"
        rows.append(metrics.score_example(e, "Ок." if i < 9 else "Ок"))
    s = metrics.summarize(rows)
    assert s["overall"]["noop_exact"] == 0.9 and s["overall"]["noop_n"] == 10
    g = gates.evaluate(s)
    noop = next(r for r in g["scopes"]["overall"] if r["gate"] == "EM-noop")
    assert noop["status"] == "PASS"
    assert g["verdict"] == "INCOMPLETE"  # other gates have no data
    assert gates.evaluate(s, backend="hf")["ship_measurement"] is False


def test_nwer_gate_is_relative_to_raw():
    summ = {"overall": {"n": 30, "nwer": 0.05, "nwer_raw": 0.10}, "by_lang": {}}
    r = next(x for x in gates.evaluate(summ)["scopes"]["overall"] if x["gate"] == "N-WER vs raw ASR")
    assert r["threshold"] == 0.08 and r["status"] == "PASS"


def test_contract_validation():
    with pytest.raises(contract.ContractError):
        contract.validate({"id": "x"})
    with pytest.raises(contract.ContractError):
        contract.validate({**ex("a"), "lang": "de"})
    contract.validate({**ex("a"), "source": "public:sotto"})


SCORE_PY = contract.REPO / "prompts/eval/score.py"


@pytest.mark.skipif(not SCORE_PY.exists(), reason="parallel scorer not present")
def test_parity_with_prompt_eval_scorer():
    """Shared definitions must agree with prompts/eval/score.py on its own dev set."""
    spec = importlib.util.spec_from_file_location("score", SCORE_PY)
    score = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(score)
    sets = contract.REPO / "prompts/eval/sets/dev.v1.jsonl"
    if not sets.exists():
        pytest.skip("dev set not present")
    cases = [json.loads(l) for l in sets.read_text(encoding="utf-8").splitlines() if l.strip()]
    for c in cases:
        for out in (c["target"], c["transcript"], ""):
            theirs = score.score_case(c, out)
            mine = metrics.score_example(c, out)
            assert mine["em"] == theirs["em"], c["id"]
            assert mine["wer"] == pytest.approx(theirs["wer"], abs=1e-3), c["id"]
            assert mine["fwer"] == pytest.approx(theirs["fwer"], abs=1e-3), c["id"]
            assert mine["answered"] == theirs["answered"], c["id"]
            assert (not mine["wrong_lang"]) == theirs["lang_ok"] or not c["target"].strip(), c["id"]


FIXTURE_V2 = contract.REPO / "ml/eval/tests/fixtures/ctx_v2.sample.jsonl"


def test_ctx_v2_fixture_renders_and_scores_perfectly_on_targets():
    """ctx v2 (docs/ml/CONTEXT_V2.md) goes through prompts/render.py untouched by ml/ code, and the
    targets themselves pass every per-item check (fixture sanity + metric sanity)."""
    rows = contract.load(FIXTURE_V2)
    for r in rows:
        p = contract.render_prompt(r, "finetuned", "qwen3.5")
        assert r["transcript"] in p
        for line in metrics.ctxview.screen_lines(r["ctx"]):
            if r["ctx"].get("privacy", {}).get("level") == "full" and line == "Под в Kubernetes опять упал, глянь?":
                assert line in p  # the renderer shows the OCR line that supports the term
    scored = [metrics.score_example(r, r["target"]) for r in rows]
    for s in scored:
        assert s["em"] and not s["answered"] and not s["distractor_ins"] and not s["forbid_hits"], s["id"]
    s = metrics.summarize(scored)
    assert s["overall"]["ctx_resolution_rate"] == 1.0 and s["overall"]["ctx_unsupported_items"] == 0
    assert s["by_ctx_source"]["screen"]["n"] == 3 and s["by_ctx_source"]["no_screen"]["n"] == 2
