"""ml/pod/env.sh budget functions: billed time is summed over sessions on the volume, checks refuse work
that would end past the cap, and the target limit is separate from the hard cap."""
from __future__ import annotations

import os
import shutil
import subprocess
import time

import pytest

from ml.eval import contract

ENV_SH = contract.REPO / "ml/pod/env.sh"
pytestmark = pytest.mark.skipif(not (shutil.which("bash") and shutil.which("jq")), reason="needs bash + jq")


def sh(ws, script: str, **env) -> subprocess.CompletedProcess:
    e = {**os.environ, "MURMUR_WORKSPACE": str(ws), "MURMUR_REPO": str(contract.REPO),
         "BUDGET_SESSION_OVERHEAD_S": "300", **{k: str(v) for k, v in env.items()}}
    return subprocess.run(["bash", "-c", f"source {ENV_SH}; {script}"], env=e, capture_output=True, text=True)


def test_budget_sums_sessions_and_overhead(tmp_path):
    now = int(time.time())
    # an earlier session of 1 h (start, last heartbeat) persisted on the volume
    (tmp_path / ".budget").mkdir()
    (tmp_path / ".budget/sessions.tsv").write_text(f"{now - 90000}\t{now - 86400}\n")
    r = sh(tmp_path, "budget_used", MURMUR_SESSION_START=now - 600)
    used = int(r.stdout.strip())
    assert abs(used - (3600 + 300 + 600 + 300)) <= 5, r
    # the same session again: heartbeat updated, no new row
    sh(tmp_path, "budget_used", MURMUR_SESSION_START=now - 600)
    assert len((tmp_path / ".budget/sessions.tsv").read_text().splitlines()) == 2


def test_budget_check_hard_and_target(tmp_path):
    now = int(time.time())
    start = now - 2 * 3600  # 2 h billed in this session (+ 300 s overhead)
    ok = sh(tmp_path, "budget_check run-x 1800 hard", MURMUR_SESSION_START=start, MAX_GPU_HOURS=5)
    assert ok.returncode == 0, ok.stderr
    tgt = sh(tmp_path, "budget_check run-x 1800 target", MURMUR_SESSION_START=start, TARGET_GPU_HOURS=2.5)
    assert tgt.returncode == 5 and "NOT started" in tgt.stderr
    cap = sh(tmp_path, "budget_check run-y 3600 hard", MURMUR_SESSION_START=start, MAX_GPU_HOURS=2.5)
    assert cap.returncode == 5


def test_budget_deadline_and_default_cap(tmp_path):
    """The training deadline leaves AFTER_S for the rest of the run inside MAX_GPU_HOURS (default 4 h)."""
    now = int(time.time())
    start = now - 3600  # 1 h + 300 s overhead billed
    r = sh(tmp_path, "echo $MAX_GPU_HOURS; budget_deadline 600", MURMUR_SESSION_START=start)
    cap, dl = r.stdout.split()
    assert cap == "4"
    assert abs(int(dl) - (now + 4 * 3600 - 900 - 3900 - 600)) <= 5, r   # 900 s default tail kept back


# What happens after the scripts end, in seconds (REQUEST.md "Time budget", TRAINING_RUNBOOK section 1 step 3-5):
POLL_S, SYNC_GGUF_S, STOP_S = 300, 180, 60   # operator polls every ~5 min; WITH_GGUF=1 sync ~3 GB ~3 min; stop ~1 min


def test_budget_tail_keeps_sync_and_stop_inside_cap(tmp_path):
    """The hard cap keeps BUDGET_TAIL_S (default 900 s) back: the last step that may start ends at 4 h - 15 min at
    the latest, so the operator's next poll, the final WITH_GGUF=1 sync and the stop still end inside 4 h."""
    now = int(time.time())
    start = now - (3 * 3600 + 1800 - 300)   # 3 h 30 min billed incl. the 300 s session overhead
    tail = int(sh(tmp_path, "echo $BUDGET_TAIL_S").stdout)
    assert tail == 900 and tail >= POLL_S + SYNC_GGUF_S + STOP_S + 300   # + 5 min slack (slow link, slow stop)
    lim = int(sh(tmp_path, "budget_limit hard").stdout)
    assert lim == 4 * 3600 - tail and int(sh(tmp_path, "budget_limit target").stdout) == 3 * 3600 - tail
    # used ~12,600 s: an 890 s step ends just inside 13,500 = 4 h - tail -> allowed; 960 s would end past it -> refused
    ok = sh(tmp_path, "budget_check last-eval 890 hard", MURMUR_SESSION_START=start)
    assert ok.returncode == 0, ok.stderr
    late = sh(tmp_path, "budget_check last-eval 960 hard", MURMUR_SESSION_START=start)
    assert late.returncode == 5 and "kept for sync + stop" in late.stderr
    # worst case: the allowed step ends at the limit, then poll + sync + stop must still end inside the cap
    assert lim + POLL_S + SYNC_GGUF_S + STOP_S <= 4 * 3600
    # without the tail (old behaviour) the same 960 s step would have started and left 0 s for the sync
    assert sh(tmp_path, "budget_check last-eval 960 hard", MURMUR_SESSION_START=start, BUDGET_TAIL_S=0).returncode == 0
    # the training deadline honours the tail as well
    dl = int(sh(tmp_path, "budget_deadline 0", MURMUR_SESSION_START=start).stdout)
    assert abs(dl - (now + lim - 12600)) <= 5


# as on a fresh pod shell (the Makefile exports MURMUR_LLAMA_BIN for local runs; empty = unset for env.sh)
CLEAN = {"MURMUR_LLAMA_BIN": "", "MURMUR_LLAMA_NGL": "", "_MURMUR_LLAMA_BIN_AUTO": "", "_MURMUR_LLAMA_NGL_AUTO": ""}


def test_llama_bin_follows_bootstrap_markers_in_nested_stages(tmp_path):
    """run_all.sh sources env.sh BEFORE bootstrap writes its markers; the stages it starts afterwards source env.sh
    again and must pick the build the kernel smoke test chose (or the CPU fallback), not the inherited value.
    An explicit MURMUR_LLAMA_BIN is kept."""
    mark = tmp_path / ".bootstrap"
    r = sh(tmp_path, f"""echo $MURMUR_LLAMA_BIN $MURMUR_LLAMA_NGL
        echo /alt/cuda13.5 > {mark}/llama-cuda-bin; source {ENV_SH}; echo $MURMUR_LLAMA_BIN $MURMUR_LLAMA_NGL
        rm {mark}/llama-cuda-bin; touch {mark}/llama-cpu-fallback; source {ENV_SH}; echo $MURMUR_LLAMA_BIN $MURMUR_LLAMA_NGL""",
           **CLEAN)
    first, alt, cpu = r.stdout.splitlines()
    assert first == f"{tmp_path}/llama/cuda/llama-b12503 99", r
    assert alt == "/alt/cuda13.5 99" and cpu == f"{tmp_path}/llama/cpu/llama-b12503 0"
    kept = sh(tmp_path, f"touch {mark}/llama-cpu-fallback; source {ENV_SH}; echo $MURMUR_LLAMA_BIN",
              **{**CLEAN, "MURMUR_LLAMA_BIN": "/explicit"})
    assert kept.stdout.split()[-1] == "/explicit"
