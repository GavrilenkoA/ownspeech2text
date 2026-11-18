"""ml/eval/v2_cases.py: CONTEXT_V2 category rules checked on OCR lines (fake build_ctx, no OCR venv)."""
from __future__ import annotations

from ml.eval import v2_cases


def fake_build_ctx(lines):
    def f(spec):
        return {"v": 2, "app": spec.get("app", {}), "screen": {"size": [1080, 2520], "lines": [
            {"text": t, "conf": 0.9, "box": [0, i * 60, 1000, i * 60 + 50]} for i, t in enumerate(lines)]}}
    return f


def case(cat, meta, target="Да, ClickHouse уже открыли."):
    return {"id": f"c-{cat}", "lang": "ru", "category": cat, "split": "test", "spec": {"template": "work_chat"},
            "transcript": "да клик хаус уже открыли", "target": target, "meta": meta}


def test_resolution_needs_term_on_screen():
    c = case("context_resolution", {"entities": ["ClickHouse"]})
    ok, bad = v2_cases.build([c], fake_build_ctx(["доступ к ClickHouse кластеру"]))
    assert len(ok) == 1 and not bad and ok[0]["meta"]["screen_spec"] == {"template": "work_chat"}
    ok, bad = v2_cases.build([c], fake_build_ctx(["доступ к Clickhous кластеру"]))  # OCR broke the term
    assert not ok and "not in OCR lines" in bad[0]["errors"][0]


def test_counterpart_rejects_close_variants_on_screen():
    c = case("context_counterpart", {"distractors": ["ClickHouse"]}, target="Да, клик хаус уже открыли.")
    assert v2_cases.build([c], fake_build_ctx(["доступ к кластеру"]))[0]
    _, bad = v2_cases.build([c], fake_build_ctx(["доступ к clickhous: кластеру"]))  # edit distance 1
    assert bad and "close variant" in bad[0]["errors"][0]
