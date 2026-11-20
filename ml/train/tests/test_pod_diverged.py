"""ml/pod/run_one.sh + train_matrix.sh after a diverged training: the run is reported as diverged (exit 4), a
re-run skips it instead of resuming from its last good checkpoint into the same divergence, select ignores it,
and FORCE_RETRAIN=1 trains it again. sft is a stand-in that diverges and writes DIVERGED.json like the real one
(the real sft writing / honouring the marker: test_completion_loss.py)."""
from __future__ import annotations

import os
import shutil
import subprocess
import sys
import time

import pytest

from ml.eval import contract

pytestmark = pytest.mark.skipif(not (shutil.which("bash") and shutil.which("jq")), reason="needs bash + jq")

FAKE_PY = """#!/usr/bin/env bash
if [[ " $* " == *" ml.train.sft "* ]]; then
  for x; do [[ $x == output_dir=* ]] && out=${x#output_dir=}; done
  echo "sft FORCE_RETRAIN=${FORCE_RETRAIN:-}" >>"$FAKE_CALLS"
  mkdir -p "$out"
  [[ "${FORCE_RETRAIN:-}" == 1 && -f "$out/DIVERGED.json" ]] && mv "$out/DIVERGED.json" "$out/DIVERGED.old.json"
  echo '{"step": 9, "reason": "non-finite training loss in step 9"}' >"$out/DIVERGED.json"
  exit 4
fi
exec %s "$@"
"""


@pytest.fixture()
def pod(tmp_path):
    ws = tmp_path / "ws"
    (ws / "venv-train/bin").mkdir(parents=True)
    py = ws / "venv-train/bin/python"
    py.write_text(FAKE_PY % sys.executable)
    py.chmod(0o755)
    (ws / "data").mkdir()
    (ws / "data/smoke.v2.test.jsonl").write_text("")
    cfg = tmp_path / "cfg"
    cfg.mkdir()
    (cfg / "x.yaml").write_text("extends: base.yaml\n")
    calls = tmp_path / "calls.txt"
    calls.write_text("")
    env = {**os.environ, "MURMUR_WORKSPACE": str(ws), "MURMUR_REPO": str(contract.REPO), "CONFIG_DIR": str(cfg),
           "MURMUR_SESSION_START": str(int(time.time())), "BUDGET_SESSION_OVERHEAD_S": "0",
           "FAKE_CALLS": str(calls), "PHASE_A": "x", "PHASE_B": ""}
    for k in ("FORCE_RETRAIN", "MURMUR_DATA", "MURMUR_RUNS", "MURMUR_LOGS", "MURMUR_EVAL"):
        env.pop(k, None)

    def run(script, *args, **extra):
        return subprocess.run(["bash", str(contract.REPO / "ml/pod" / script), *args], env={**env, **extra},
                              capture_output=True, text=True, timeout=120)
    return ws, calls, run


def test_diverged_run_is_skipped_on_rerun_until_force_retrain(pod):
    ws, calls, run = pod
    r = run("run_one.sh", "x")
    assert r.returncode == 4, r.stderr
    assert "train x DIVERGED (step 9: non-finite training loss in step 9" in r.stderr
    assert calls.read_text().count("sft ") == 1
    # the documented recovery (re-run the same command) must not train it again
    r = run("run_one.sh", "x")
    assert r.returncode == 4 and "DIVERGED in an earlier attempt (step 9" in r.stderr, r.stderr
    assert calls.read_text().count("sft ") == 1
    m = run("train_matrix.sh")
    assert m.returncode == 1, m.stderr
    assert "skip x: its training DIVERGED in an earlier attempt (step 9" in m.stderr
    assert "select: skip x: training diverged" in m.stderr and "(1 diverged)" in m.stderr
    assert "phase A winner" not in m.stderr
    assert calls.read_text().count("sft ") == 1
    # FORCE_RETRAIN=1 trains it again (sft gets the variable: it archives the marker and drops the checkpoints)
    f = run("run_one.sh", "x", FORCE_RETRAIN="1")
    assert f.returncode == 4 and calls.read_text().splitlines()[-1] == "sft FORCE_RETRAIN=1", f.stderr
    fm = run("train_matrix.sh", FORCE_RETRAIN="1")
    assert fm.returncode == 1 and "skip x: its training" not in fm.stderr and calls.read_text().count("sft ") == 3, fm.stderr


def test_preflight_refuses_when_the_smoke_model_was_never_prepared(pod):
    """bootstrap step 6b exit 7 (ml/pod/kernel_smoke.sh) leaves llama-smoke-unprepared: no build was tested."""
    ws, calls, run = pod
    (ws / ".bootstrap").mkdir(exist_ok=True)
    (ws / ".bootstrap/llama-smoke-unprepared").write_text("Qwen/Qwen3.5-0.8B\n")
    r = run("train_matrix.sh")
    assert r.returncode == 6 and "could not prepare the llama.cpp kernel smoke model (Qwen/Qwen3.5-0.8B)" in r.stderr
    assert calls.read_text() == ""
    r = run("train_matrix.sh", ALLOW_UNTESTED_LLAMA="1")
    assert r.returncode == 1 and calls.read_text().count("sft ") == 1, r.stderr   # went on (and the stand-in diverged)
