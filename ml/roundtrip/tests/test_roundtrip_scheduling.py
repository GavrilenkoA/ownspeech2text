"""Runner: shard planning by expected wall time, exit status, and workers that die with the runner."""
from __future__ import annotations

import os
import signal
import subprocess
import sys
import textwrap
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT))

import ml.roundtrip.run_roundtrip as rr  # noqa: E402


def _utts(n: int, lang: str = "ru", prefix: str = "p") -> list:
    return [rr.Utterance(rr.Probe(f"{prefix}{i}", lang, "", "x", (), None), "s", "v", "text", "x", "clean", 0, 0)
            for i in range(n)]


def test_shards_are_cut_by_expected_wall_time_and_longest_first():
    by_model = {"slow": _utts(10), "fast": _utts(10)}
    shards = rr.plan_asr_shards(by_model, Path("/nonexistent"), max_clips=48, xrt={"slow": 0.25, "fast": 5.0},
                                target_s=100.0, duration=lambda p: 5.0)
    # slow: 5 s / 0.25 = 20 s per clip -> 5 clips per 100 s shard; fast: 1 s per clip -> one shard of 10
    sizes = [(m, len(part)) for m, part in shards]
    assert sizes == [("slow", 5), ("slow", 5), ("fast", 10)]
    # every clip is planned exactly once
    assert sorted(u.probe.id for m, part in shards if m == "slow" for u in part) == sorted(f"p{i}" for i in range(10))


def test_shards_respect_clip_cap_and_language_hint():
    by_model = {"m": _utts(6, "ru", "r") + _utts(6, "en", "e")}
    shards = rr.plan_asr_shards(by_model, Path("/x"), max_clips=4, xrt={"m": 100.0}, target_s=1e9,
                                duration=lambda p: 1.0)
    assert all(len(part) <= 4 for _, part in shards)
    for _, part in shards:
        assert len({rr.lang_hint(u.probe.lang) for u in part}) == 1
    assert sum(len(p) for _, p in shards) == 12


def test_exit_status():
    assert rr.exit_status({"errors": [], "pending": 0}, allow_errors=False) == 0
    assert rr.exit_status({"errors": [{"stage": "asr"}], "pending": 0}, allow_errors=False) == 1
    assert rr.exit_status({"errors": [], "pending": 3}, allow_errors=False) == 1
    assert rr.exit_status({"errors": [{"stage": "asr"}], "pending": 3}, allow_errors=True) == 0


def test_check_parent_is_a_no_op_outside_workers():
    assert rr._PARENT_PID is None
    rr.check_parent()  # must not exit the test process


def _alive(pid: int) -> bool:
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    # a zombie (exited, not yet reaped by init) counts as gone
    try:
        with open(f"/proc/{pid}/stat", encoding="utf-8") as fh:
            return fh.read().split()[2] != "Z"
    except OSError:
        return False


def test_pool_workers_exit_when_the_runner_is_killed(tmp_path):
    """SIGKILL of the runner must not leave workers transcribing for minutes (observed before the fix)."""
    script = tmp_path / "runner.py"
    script.write_text(textwrap.dedent(f"""
        import os, sys, time
        sys.path.insert(0, {str(ROOT)!r})
        import ml.roundtrip.run_roundtrip as rr
        if __name__ == "__main__":
            pool = rr.make_recycling_pool(2)
            pids = [pool.submit(os.getpid).result()]
            fut = pool.submit(time.sleep, 120)  # a long shard
            time.sleep(1.0)
            print(" ".join(str(p) for p in pids), flush=True)
            time.sleep(120)
    """), encoding="utf-8")
    proc = subprocess.Popen([sys.executable, str(script)], stdout=subprocess.PIPE, text=True)
    try:
        line = proc.stdout.readline().strip()
        assert line, "runner did not start"
        # the sleeping worker is a fresh process (max_tasks_per_child=1): find the children of the runner
        children = [int(p) for p in subprocess.run(["pgrep", "-P", str(proc.pid)], capture_output=True,
                                                    text=True).stdout.split()]
        workers = [p for p in children if _alive(p)]
        assert workers, "no worker processes found"
        os.kill(proc.pid, signal.SIGKILL)
        proc.wait(timeout=10)
        deadline = time.time() + 10
        while time.time() < deadline and any(_alive(p) for p in workers):
            time.sleep(0.2)
        assert not any(_alive(p) for p in workers), "workers survived the runner"
    finally:
        if proc.poll() is None:
            proc.kill()


def test_one_model_never_takes_the_whole_memory_budget():
    """A heavy model going first must leave room for the others (it may hold at most 2/3 of the budget)."""
    import threading
    from concurrent.futures import ThreadPoolExecutor

    mem = {"heavy": 4, "light": 1}
    state = {"heavy": 0, "peak_heavy": 0, "light_started_while_heavy": False}
    lock = threading.Lock()

    def job(m):
        with lock:
            if m == "heavy":
                state["heavy"] += 1
                state["peak_heavy"] = max(state["peak_heavy"], state["heavy"])
            elif state["heavy"]:
                state["light_started_while_heavy"] = True
                state["heavy_when_light"] = max(state.get("heavy_when_light", 0), state["heavy"])
        time.sleep(0.02)
        with lock:
            if m == "heavy":
                state["heavy"] -= 1
        return m

    shards = [("heavy", [i]) for i in range(6)] + [("light", [i]) for i in range(4)]
    with ThreadPoolExecutor(max_workers=8) as pool:
        got = list(rr._bounded_submit(pool, shards, 8, 12, mem, lambda m, part: (job, m)))
    assert len(got) == 10
    # while light shards wait, heavy may hold 2/3 of the 12-unit budget (2 x 4), not all of it (3 x 4)
    assert state["light_started_while_heavy"] and state["heavy_when_light"] == 2


def test_the_cap_lifts_when_only_one_model_is_left():
    import threading
    from concurrent.futures import ThreadPoolExecutor

    state = {"n": 0, "peak": 0}
    lock = threading.Lock()

    def job(m):
        with lock:
            state["n"] += 1
            state["peak"] = max(state["peak"], state["n"])
        time.sleep(0.05)
        with lock:
            state["n"] -= 1
        return m

    with ThreadPoolExecutor(max_workers=8) as pool:
        got = list(rr._bounded_submit(pool, [("heavy", [i]) for i in range(6)], 8, 12, {"heavy": 4},
                                      lambda m, part: (job, m)))
    assert len(got) == 6 and state["peak"] == 3  # the whole budget, nothing else is waiting
