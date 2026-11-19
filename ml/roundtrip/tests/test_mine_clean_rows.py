"""Row selection rules for the primary mining tables (clean_rows)."""
from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[3]))

from ml.roundtrip.mine.clean_rows import clean, is_loop, main, max_char_run, max_ngram_repeat  # noqa: E402

MODELS = ["m1", "m2", "m3"]


def _row(pid, hyp, model="m1", tts="supertonic3", lang="ru", text="мы выкатили релиз в пятницу вечером",
         voice="v0", channel="clean"):
    return {"probe_id": pid, "lang": lang, "text": text, "terms": [], "tts_system": tts, "tts_voice": voice,
            "channel": channel, "asr_model": model, "hypothesis": hyp}


def _clip(pid, hyps, **kw):
    return [_row(pid, h, model=m, **kw) for m, h in zip(MODELS, hyps)]


def test_ngram_and_char_runs():
    assert max_ngram_repeat("с с с с с сентенс".split()) == 5
    assert max_ngram_repeat("a b a b a b a b c".split()) == 4
    assert max_ngram_repeat("one two three".split()) == 1
    assert max_char_run("at 14::::::::") == 8
    assert max_char_run("10:30, ok...") == 3


def test_loop_needs_repeat_absent_from_reference():
    assert is_loop("через sentence transformers", "через с с с с с сентенс")
    assert not is_loop("да да да да, конечно", "да да да да конечно")
    assert is_loop("Thursday at 14:30", "Thursday at 14::::::::::::")
    assert not is_loop("мы выкатили релиз", "мы выкатили релиз")


def test_rules_in_order_and_whole_clip_drops():
    good = _clip("g", ["мы выкатили релиз в пятницу вечером"] * 3)
    piper = _clip("p", ["мы выкатили релиз в пятницу вечером"] * 3, tts="piper")
    piper_en = _clip("pe", ["we shipped the release on friday"] * 3, tts="piper", lang="en",
                     text="We shipped the release on Friday.")
    flagged = _clip("f", ["мы выкатили релиз в пятницу вечером"] * 3, voice="v9")
    empty = [_row("e", "  ", model="m1")] + _clip("e", ["", "мы выкатили релиз", "мы выкатили релиз"])[1:]
    fail = _clip("x", ["совсем другое", "ничего похожего тут", "абсолютно не то"])
    skip = _clip("s", ["мы выкатили новый релиз вечером"] * 3,
                 text="мы выкатили новый релиз в пятницу поздно вечером")
    rows = good + piper + piper_en + flagged + empty + fail + skip
    kept, excluded, summary = clean(rows, flags={("f", "supertonic3", "v9", "clean")})
    by = summary["excluded_by_rule"]
    assert by["R1_piper_ru"] == 3 and by["R2_tts_flag"] == 3 and by["R3_empty"] == 1
    assert by["R5_tts_fail"] == 3 and by["R6_tts_skip"] == 3
    assert {r["probe_id"] for r in kept} == {"g", "pe", "e"}
    assert len([r for r in kept if r["probe_id"] == "e"]) == 2
    assert summary["rows_in"] == len(rows) == summary["rows_kept"] + len(excluded)
    # R1 off: Piper Russian rows stay
    kept2, _, _ = clean(rows, flags=set(), keep_piper_ru=True)
    assert len([r for r in kept2 if r["probe_id"] == "p"]) == 3


def test_small_clips_are_not_judged_as_tts_failures():
    rows = [_row("x", "совсем другое", model="m1"), _row("x", "ничего похожего", model="m2")]
    kept, _, summary = clean(rows)
    assert len(kept) == 2 and summary["excluded_by_rule"] == {}


def test_cli_keeps_lines_byte_identical(tmp_path):
    rows = _clip("g", ["мы выкатили релиз в пятницу вечером"] * 3) + _clip("p", ["x"] * 3, tts="piper")
    src = tmp_path / "rows.jsonl"
    src.write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows), encoding="utf-8")
    out, exc, summ = tmp_path / "out.jsonl", tmp_path / "exc.jsonl", tmp_path / "s.json"
    assert main([str(src), "--out", str(out), "--excluded", str(exc), "--summary", str(summ)]) == 0
    lines = src.read_text(encoding="utf-8").splitlines(keepends=True)
    assert out.read_text(encoding="utf-8") == "".join(lines[:3])
    assert len(exc.read_text(encoding="utf-8").splitlines()) == 3
    assert json.loads(summ.read_text(encoding="utf-8"))["rows_kept"] == 3
