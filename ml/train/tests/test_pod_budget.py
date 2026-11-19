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
