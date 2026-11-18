"""Dataset adapters: deterministic sampling, interface-2 shape, and style exclusion of unstyled references."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[3]))

import pytest  # noqa: E402

from ml.roundtrip.mine.adapters import ENRICH_MODELS, enrich_rows  # noqa: E402
from ml.roundtrip.mine.mine_errors import mine_rows  # noqa: E402

INTERFACE2 = {"probe_id", "lang", "text", "terms", "tts_system", "tts_voice", "channel", "audio", "audio_s",
              "asr_model", "hypothesis", "asr_wall_s"}


def _records() -> list[dict]:
    out = []
    for k in range(30):
        out.append({"source_id": f"cv:{k}", "domain": "cv21_ru", "duration_s": 3.0 + k,
                    "ref": f"Привет, это фраза номер {k}.", "clean_text": f"Привет, это фраза номер {k}.",
                    "gigaam_text": f"Привет, это фраза номер {k}.", "nemotron_text": f"привет это фраза {k}",
                    "tier": "A", "has_latin": False})
    for k in range(10):
        out.append({"source_id": f"sova:{k}", "domain": "sova", "duration_s": 2.0, "ref": "ага хорошо",
                    "clean_text": "Ага, хорошо.", "gigaam_text": "Ага, хорошо.", "nemotron_text": "Ага, хорошо.",
                    "tier": "A", "has_latin": False})
    out.append({"source_id": "fleurs:1", "domain": "fleurs", "duration_s": 2.0, "ref": "текст",
                "clean_text": "Текст.", "gigaam_text": None, "nemotron_text": None, "tier": "A", "has_latin": False})
    return out


def test_rows_have_interface_fields_and_both_models():
    rows = list(enrich_rows(_records(), ["cv21_ru"], "ref", 5, 0, sorted(ENRICH_MODELS)))
    assert len(rows) == 10
    assert all(INTERFACE2 <= set(r) for r in rows)
    assert {r["asr_model"] for r in rows} == {v[1] for v in ENRICH_MODELS.values()}
    assert all(r["style_comparable"] for r in rows)


def test_sampling_is_deterministic_and_order_independent():
    recs = _records()
    a = list(enrich_rows(recs, ["cv21_ru"], "ref", 7, 3, ["gigaam"]))
    b = list(enrich_rows(list(reversed(recs)), ["cv21_ru"], "ref", 7, 3, ["gigaam"]))
    c = list(enrich_rows(recs, ["cv21_ru"], "ref", 7, 4, ["gigaam"]))
    assert a == b
    assert [r["probe_id"] for r in a] != [r["probe_id"] for r in c]


def test_rows_without_hypotheses_are_dropped():
    rows = list(enrich_rows(_records(), ["fleurs"], "ref", 0, 0, ["gigaam"]))
    assert rows == []


def test_unstyled_reference_is_excluded_from_style_statistics():
    rows = list(enrich_rows(_records(), ["sova"], "ref", 0, 0, ["gigaam"]))
    assert rows and not any(r["style_comparable"] for r in rows)
    prof = mine_rows(rows).profiles[ENRICH_MODELS["gigaam"][1]]
    assert prof["style_excluded_rows"] == len(rows)
    assert prof["casing"]["label"] == "unknown"
    assert prof["wer"] == 0.0  # recognition errors are still measured


def test_bad_arguments():
    with pytest.raises(ValueError):
        list(enrich_rows(_records(), None, "nope", 1, 0, ["gigaam"]))
    with pytest.raises(ValueError):
        list(enrich_rows(_records(), None, "ref", 1, 0, ["whisper"]))
