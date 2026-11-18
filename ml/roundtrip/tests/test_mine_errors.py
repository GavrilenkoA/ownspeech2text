"""Miner over the hand-made fixture: outputs, known model behaviours, determinism."""
from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[3]))

import pytest  # noqa: E402

from ml.roundtrip.mine.mine_errors import main, mine_rows, read_jsonl  # noqa: E402

FIXTURE = Path(__file__).resolve().parents[1] / "samples" / "mined" / "fixture_roundtrip.jsonl"
TONE = "t-one@onnx"
GIGA = "gigaam-v3-e2e-rnnt@onnx-int8"
WHIS = "whisper-large-v3-turbo@onnx-int8"


@pytest.fixture(scope="module")
def tables():
    return mine_rows(read_jsonl([FIXTURE]))


def test_fixture_size_and_models():
    rows = read_jsonl([FIXTURE])
    assert len(rows) >= 120
    assert {r["asr_model"] for r in rows} >= {TONE, GIGA, WHIS}
    # GigaAM and T-one are Russian-only: the fixture never feeds them English
    assert not [r for r in rows if r["asr_model"] in (TONE, GIGA) and r["lang"] == "en"]


def test_t_one_profile(tables):
    p = tables.profiles[TONE]
    assert p["casing"]["label"] == "lowercase"
    assert p["punctuation"]["label"] == "none"
    assert p["digits"]["label"] == "words"
    assert p["latin"]["label"] == "cyrillic"
    assert p["latin"]["hyp_latin_rate"] == 0.0


def test_whisper_profile(tables):
    p = tables.profiles[WHIS]
    assert p["casing"]["label"] == "sentence"
    assert p["punctuation"]["label"] == "full"
    assert p["digits"]["label"] == "digits"
    assert p["hallucination"]["tail_rate"] > 0
    assert any("Продолжение следует" in t for t, _ in p["hallucination"]["tails"])
    assert p["hallucination"]["empty_rate"] > 0


def test_wer_split_and_style_aware_lower_than_raw(tables):
    for p in tables.profiles.values():
        assert p["wer_style_aware"] <= p["wer"] + 1e-9
        assert p["term_wer"] is not None and p["nonterm_wer"] is not None
        assert set(p["wer_by_lang"]) <= {"ru", "en", "mixed"}


def test_term_manglings_catalogue(tables):
    by = {(t["term"], t["asr_model"]): t for t in tables.term_manglings}
    k8s = by[("Kubernetes", TONE)]
    assert k8s["variants"] == [{"hyp": "кубернетис", "count": 1}]
    assert k8s["exact_rate"] == 0.0 and k8s["translit_rate"] == 1.0
    ch = by[("ClickHouse", "qwen3-asr-0.6b@onnx-int8")]
    assert ch["exact_rate"] == 1.0


def test_confusions_have_interface_fields(tables):
    assert tables.confusions
    for c in tables.confusions:
        assert set(c) >= {"asr_model", "lang", "ref", "hyp", "count", "kind"}
        assert c["count"] >= 1


def test_null_hypothesis_rows_are_skipped():
    rows = read_jsonl([FIXTURE])[:5]
    rows[0] = dict(rows[0], hypothesis=None)
    t = mine_rows(rows)
    assert t.n_skipped == 1 and t.n_rows == 4


def test_cli_output_is_byte_identical_across_runs(tmp_path):
    a, b = tmp_path / "a", tmp_path / "b"
    assert main([str(FIXTURE), "--out", str(a)]) == 0
    assert main([str(FIXTURE), "--out", str(b), "--jobs", "2"]) == 0
    for name in ("profiles.json", "confusions.jsonl", "term_manglings.jsonl", "REPORT.md"):
        assert (a / name).read_bytes() == (b / name).read_bytes(), name
    profiles = json.loads((a / "profiles.json").read_text(encoding="utf-8"))
    assert set(profiles) == {r["asr_model"] for r in read_jsonl([FIXTURE])}
