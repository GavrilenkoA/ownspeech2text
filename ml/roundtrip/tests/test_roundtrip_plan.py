"""--plan runs: explicit clip list, wave-interleaved ordering, stop file / wall-time cap, live error log."""
from __future__ import annotations

import json
import sys
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[3]))

import pytest  # noqa: E402

from ml.roundtrip import run_roundtrip as rr  # noqa: E402
from ml.roundtrip.tests.test_roundtrip_runner import (  # noqa: E402
    PROBES, SPECS, asr_factory, thread_pool, tts_factory)


def _probe(lang: str, i: int) -> rr.Probe:
    return rr.Probe(f"{lang}-{i}", lang, "d", f"text {i}", (), None)


def _utt(p: rr.Probe, voice: str = "v") -> rr.Utterance:
    return rr.Utterance(p, "fx", voice, "text", p.text, "clean", 0, 0)


@pytest.fixture()
def probe_file(tmp_path):
    p = tmp_path / "probe.jsonl"
    p.write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in PROBES), encoding="utf-8")
    return p


def _plan_rows():
    # wave 0: one clip per language; wave 1: the rest, one of them through phone_noisy and one with a
    # restricted model list
    return [
        {"probe_id": "ru-1", "tts_system": "fb", "tts_voice": "m1", "channel": "clean", "asr": [], "wave": 0},
        {"probe_id": "en-1", "tts_system": "fa", "tts_voice": "en2", "channel": "clean", "asr": [], "wave": 0},
        {"probe_id": "mx-1", "tts_system": "fa", "tts_voice": "ru1", "channel": "clean", "asr": [], "wave": 0},
        {"probe_id": "ru-2", "tts_system": "fb", "tts_voice": "m1", "channel": "phone_noisy", "asr": [], "wave": 1},
        {"probe_id": "en-2", "tts_system": "fb", "tts_voice": "m1", "channel": "clean", "asr": ["multi"], "wave": 1},
        {"probe_id": "mx-2", "tts_system": "fb", "tts_voice": "m1", "channel": "clean", "asr": [], "wave": 1},
        {"probe_id": "mx-2", "tts_system": "fa", "tts_voice": "ru1", "channel": "clean", "asr": [], "wave": 1},
    ]


def _write_plan(tmp_path, rows):
    p = tmp_path / "plan.jsonl"
    p.write_text("".join(json.dumps(r) + "\n" for r in rows), encoding="utf-8")
    return p


def _run(probe_file, out, plan, extra=()):
    args = rr.parse_args(["--probe", str(probe_file), "--out", str(out), "--workers", "1", "--threads", "1",
                          "--asr-shard", "1", "--tts-shard", "1", "--run", "full", "--plan", str(plan), *extra])
    return rr.execute(args, tts_factory=tts_factory, asr_factory=asr_factory, spec_for=SPECS.__getitem__,
                      pool_factory=thread_pool, systems=["fa", "fb"], models=["multi", "ru-only", "en-only"])


def _rows(out):
    return [json.loads(x) for x in (out / "roundtrip" / "full.jsonl").read_text(encoding="utf-8").splitlines()]


def test_plan_run_writes_exactly_the_planned_jobs_and_resumes(probe_file, tmp_path):
    out = tmp_path / "out"
    plan = _write_plan(tmp_path, _plan_rows())
    rep = _run(probe_file, out, plan)
    rows = _rows(out)
    # ru/mixed clips -> multi + ru-only (2 each, 5 clips); en-1 -> multi + en-only; en-2 -> multi only
    assert len(rows) == 5 * 2 + 2 + 1 == rep["jobs"]
    assert rep["pending"] == 0 and rep["errors"] == [] and rep["stopped"] is None
    assert {r["channel"] for r in rows if r["probe_id"] == "ru-2"} == {"phone_noisy"}
    assert [r["asr_model"] for r in rows if r["probe_id"] == "en-2"] == ["multi"]
    # the respelling voice read tts_text on the mixed probe, the native one read text
    mx = {(r["tts_voice"], r["tts_input"]) for r in rows if r["probe_id"] == "mx-2"}
    assert mx == {("m1", "text"), ("ru1", "text")}  # mx-2 has no tts_text: both read text
    assert {r["tts_input"] for r in rows if r["probe_id"] == "mx-1"} == {"tts_text"}
    # rerun: nothing pending, no duplicates
    rep2 = _run(probe_file, out, plan)
    assert rep2["jobs"] == 0 and len(_rows(out)) == len(rows)
    keys = [rr.result_key(r) for r in _rows(out)]
    assert len(keys) == len(set(keys))
    # the error log holds one start marker per invocation and no errors
    log = [json.loads(x) for x in (out / "roundtrip" / "full.errors.jsonl").read_text().splitlines()]
    assert [x.get("event") for x in log] == ["start", "start"]


def test_plan_rows_are_written_wave_by_wave_with_models_interleaved(probe_file, tmp_path):
    out = tmp_path / "out"
    _run(probe_file, out, _write_plan(tmp_path, _plan_rows()))
    rows = _rows(out)
    wave0 = {"ru-1", "en-1", "mx-1"}
    first_later = next(i for i, r in enumerate(rows) if r["probe_id"] not in wave0)
    assert all(r["probe_id"] in wave0 for r in rows[:first_later])
    assert all(r["probe_id"] not in wave0 for r in rows[first_later:])
    # inside wave 0, the second shard is already another model than the first
    assert rows[0]["asr_model"] != rows[1]["asr_model"]


def test_plan_rejects_unknown_voice_and_wrong_language(probe_file, tmp_path):
    bad = [{"probe_id": "ru-1", "tts_system": "fa", "tts_voice": "en1", "channel": "clean", "wave": 0}]
    with pytest.raises(ValueError, match="does not speak"):
        _run(probe_file, tmp_path / "o1", _write_plan(tmp_path, bad))
    bad = [{"probe_id": "ru-1", "tts_system": "fa", "tts_voice": "nope", "channel": "clean", "wave": 0}]
    with pytest.raises(ValueError, match="not available"):
        _run(probe_file, tmp_path / "o2", _write_plan(tmp_path, bad))


def test_interleaved_shards_cover_every_model_in_each_wave_before_the_next():
    us = [_utt(_probe("ru", i)) for i in range(12)]
    waves = {u.audio_rel: i // 4 for i, u in enumerate(us)}  # 3 waves of 4 clips
    by_model = {"slow": list(us), "fast": list(us), "mid": list(us)}
    xrt = {"slow": 0.25, "fast": 8.0, "mid": 1.0}
    shards = rr.plan_asr_shards(by_model, Path("/nonexistent"), 48, xrt, target_s=8.0,
                                duration=lambda p: 2.0, waves=waves)
    seq = [(waves[part[0].audio_rel], m) for m, part in shards]
    assert all(len({waves[u.audio_rel] for u in part}) == 1 for _, part in shards)  # never spans waves
    assert [w for w, _ in seq] == sorted(w for w, _ in seq)  # wave order
    for w in range(3):
        ms = [m for ww, m in seq if ww == w]
        assert set(ms[:3]) == {"slow", "fast", "mid"}  # round-robin: every model in the first three
        assert ms[0] == "slow"  # most work in the wave goes first
    # every clip of every model is in exactly one shard
    cnt = Counter((m, u.audio_rel) for m, part in shards for u in part)
    assert len(cnt) == 36 and set(cnt.values()) == {1}


def test_stop_file_stops_new_work_and_rerun_continues(probe_file, tmp_path):
    out = tmp_path / "out"
    plan = _write_plan(tmp_path, _plan_rows())
    stop = tmp_path / "STOP"
    stop.write_text("")
    rep = _run(probe_file, out, plan, ["--stop-file", str(stop)])
    assert rep["stopped"].startswith("stop file") and rep["pending"] == rep["jobs"] > 0
    assert rr.exit_status(rep, False) == 1
    stop.unlink()
    rep2 = _run(probe_file, out, plan, ["--stop-file", str(stop)])
    assert rep2["stopped"] is None and rep2["pending"] == 0
    keys = [rr.result_key(r) for r in _rows(out)]
    assert len(keys) == len(set(keys)) == 13


def test_stop_check_wall_time_cap():
    now = [100.0]
    s = rr.StopCheck(None, 10.0, clock=lambda: now[0])
    assert not s()
    now[0] = 111.0
    assert s() and "wall-time" in s.reason


def test_bounded_submit_submits_nothing_after_stop():
    from concurrent.futures import ThreadPoolExecutor

    calls = []
    flag = {"stop": False}

    def work(m, part):
        calls.append(m)
        flag["stop"] = True  # the first finished shard triggers the stop
        return m

    shards = [("a", [1]), ("b", [2]), ("c", [3])]
    with ThreadPoolExecutor(max_workers=1) as pool:
        got = [m for _, m, _ in rr._bounded_submit(pool, shards, 1, None, {}, lambda m, p: (work, m, p),
                                                   stop=lambda: flag["stop"])]
    assert got == ["a"] and calls == ["a"]


def test_lookahead_keeps_cheap_models_within_k_waves_of_a_memory_bound_one():
    """A heavy model that fits only once in memory must not let a cheap model race ahead: with lookahead 1
    no shard starts while another shard more than one wave older is still queued or running."""
    import threading
    from concurrent.futures import ThreadPoolExecutor

    waves_n = 6
    shards = []
    for w in range(waves_n):
        shards += [("heavy", [w, "h"]), ("cheap", [w, "c"])]
    mem = {"heavy": 9, "cheap": 1}
    lock = threading.Lock()
    open_shards = []
    violations = []

    def work(m, part):
        import time as _t

        with lock:
            oldest = min(p[0] for _, p in open_shards) if open_shards else part[0]
            if part[0] > oldest + 1:
                violations.append((m, part[0], oldest))
            open_shards.append((m, part))
        _t.sleep(0.05 if m == "heavy" else 0.001)
        with lock:
            open_shards.remove((m, part))
        return m

    def run(lookahead):
        violations.clear()
        order = []
        with ThreadPoolExecutor(max_workers=4) as pool:
            for _, m, _ in rr._bounded_submit(pool, shards, 4, 10, mem, lambda m, p: (work, m, p),
                                              wave_of=lambda m, p: p[0], lookahead=lookahead):
                order.append(m)
        return order

    assert len(run(1)) == 2 * waves_n and violations == []
    run(None)  # the old behaviour: cheap shards of late waves start while early heavy ones still run
    assert violations
