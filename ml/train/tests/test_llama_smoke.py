"""ml/train/llama_smoke.py (bootstrap step 6b): the GPU-vs-CPU comparison rule, the failure path of a build that
cannot run (as a missing sm_120 kernel image aborts llama-server), and a full PASS on a tiny model with the CPU
build standing in for the GPU one (the real GPU run is UNVERIFIABLE here)."""
from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path

import pytest

from ml.eval import contract
from ml.train.llama_smoke import PROMPTS, compare

CPU_BIN = Path(os.environ.get("MURMUR_LLAMA_BIN", contract.REPO / ".cache/ml/llm/bin/llama-b12503"))
LLAMA_SRC = Path(os.environ.get("MURMUR_LLAMA_SRC", contract.REPO / ".cache/ml/train/src/llama.cpp-b12503"))
TOK_LFM3 = Path(os.environ.get("MURMUR_TOK_LFM3", contract.REPO / ".cache/ml/train/models/LFM3-1.7B-tok"))


def test_compare_rule():
    cpu = [" 13, 14, 15, 16,", " Friday, Saturday, Sunday and more", " K, L, M", " май, июнь"]
    assert all(r["agree"] for r in compare(cpu, list(cpu), 16))
    late = list(cpu); late[1] = " Friday, Saturday, Sunday or less"   # late divergence (rounding): agrees
    assert all(r["agree"] for r in compare(cpu, late, 16))
    broken = list(cpu); broken[0] = "!!!!!!!!!!!!!!!!"                 # diverges at the first token
    rows = compare(cpu, broken, 16)
    assert [r["agree"] for r in rows] == [False, True, True, True] and rows[0]["prefix_chars"] == 0
    assert not compare([""] * 4, [""] * 4, 16)[0]["agree"]            # empty output never passes
    assert len(PROMPTS) == 4


def test_build_that_cannot_run_fails(tmp_path):
    fake = tmp_path / "bin"
    fake.mkdir()
    (fake / "llama-server").write_text("#!/bin/sh\necho 'CUDA error: no kernel image is available for execution on "
                                       "the device' >&2\nexit 1\n")
    (fake / "llama-server").chmod(0o755)
    gguf = tmp_path / "m.gguf"
    gguf.write_bytes(b"GGUF")
    out = tmp_path / "r.json"
    r = subprocess.run([sys.executable, "-m", "ml.train.llama_smoke", "--gguf", str(gguf), "--work", str(tmp_path),
                        "--gpu-bin", str(fake), "--cpu-bin", str(fake), "--require-gpu", "--json", str(out)],
                       cwd=contract.REPO, capture_output=True, text=True, timeout=120)
    res = json.loads(out.read_text())
    assert r.returncode == 1 and res["status"] == "FAIL" and "no kernel image" in res["reason"]


def test_tiny_model_passes_with_cpu_build_as_stand_in(tmp_path):
    if not ((CPU_BIN / "llama-server").exists() and (LLAMA_SRC / "convert_hf_to_gguf.py").exists()
            and (TOK_LFM3 / "config.json").exists()):
        pytest.skip("needs the local llama.cpp build + source and the LFM3 config/tokenizer dir")
    from ml.train.tiny_chain import tiny_model
    hf = tiny_model("lfm3", TOK_LFM3, tmp_path / "tiny-lfm3")
    out = tmp_path / "r.json"
    env = {**os.environ, "MURMUR_LLAMA_SRC": str(LLAMA_SRC)}
    r = subprocess.run([sys.executable, "-m", "ml.train.llama_smoke", "--hf", str(hf), "--work", str(tmp_path / "w"),
                        "--gpu-bin", str(CPU_BIN), "--cpu-bin", str(CPU_BIN), "--threads", "2", "-n", "8",
                        "--json", str(out)], cwd=contract.REPO, env=env, capture_output=True, text=True, timeout=600)
    res = json.loads(out.read_text())
    assert r.returncode == 0 and res["status"] == "PASS", res
    assert len(res["prompts"]) == 4 and Path(res["gguf"]).name.endswith("-Q4_0.gguf")


def test_smoke_model_failure_is_prep_fail_not_a_build_fail(tmp_path, monkeypatch, capsys):
    """A smoke model that cannot be downloaded / converted is PREP_FAIL (exit 3, JSON always written), never the
    build FAIL (exit 1) that sends bootstrap through the fallback builds; also for errors that are not SystemExit."""
    from ml.train import llama_smoke
    broken = tmp_path / "broken-model"
    broken.mkdir()
    (broken / "config.json").write_text("{}")
    out = tmp_path / "prep.json"
    r = subprocess.run([sys.executable, "-m", "ml.train.llama_smoke", "--prepare-only", "--hf", str(broken),
                        "--work", str(tmp_path / "w"), "--cpu-bin", str(tmp_path / "nobin"), "--json", str(out)],
                       cwd=contract.REPO, capture_output=True, text=True, timeout=300)
    res = json.loads(out.read_text())
    assert r.returncode == 3 and res["status"] == "PREP_FAIL" and "not a GPU build problem" in res["reason"], r.stderr
    for err in (OSError("connection reset by peer"), SystemExit("GGUF build failed: unsupported model_type")):
        def boom(*a, _e=err, **k):
            raise _e
        monkeypatch.setattr(llama_smoke, "build_gguf", boom)
        j = tmp_path / "full.json"
        assert llama_smoke.main(["--hf", "x/y", "--work", str(tmp_path / "w"), "--gpu-bin", str(tmp_path / "g"),
                                 "--cpu-bin", str(tmp_path / "c"), "--require-gpu", "--json", str(j)]) == 3
        res = json.loads(j.read_text())
        assert res["status"] == "PREP_FAIL" and str(err) in res["reason"]
    capsys.readouterr()


# bootstrap step 6b (ml/pod/kernel_smoke.sh) with a stand-in llama_smoke: what each outcome triggers
FAKE_PY = """#!/usr/bin/env bash
echo "py $*" >>"$FAKE_CALLS"
for x; do [[ $p == --json ]] && j=$x; p=$x; done
if [[ " $* " == *" --prepare-only "* ]]; then
  n=$(grep -c -- '--prepare-only' "$FAKE_CALLS")
  if [[ $FAKE_PREP == ok || ( $FAKE_PREP == once && $n -ge 2 ) ]]; then
    echo '{"status": "PREPARED"}' >"$j"; exit 0; fi
  echo '{"status": "PREP_FAIL", "reason": "could not prepare the smoke GGUF: HTTP 503"}' >"$j"; exit 3
fi
if [[ $FAKE_DECODE == ok ]]; then echo '{"status": "PASS"}' >"$j"; exit 0; fi
echo '{"status": "FAIL", "reason": "4 of 4 prompts differ"}' >"$j"; exit 1
"""


def _kernel_smoke(tmp_path, prep, decode):
    import shutil as sh
    if not (sh.which("bash") and sh.which("jq")):
        pytest.skip("needs bash + jq")
    ws = tmp_path / "ws"
    (ws / "venv-train/bin").mkdir(parents=True)
    (ws / "venv-train/bin/python").write_text(FAKE_PY)
    (ws / "venv-train/bin/python").chmod(0o755)
    cuda = ws / "llama/cuda/llama-b12503"
    cuda.mkdir(parents=True)
    (cuda / "llama-server").write_text("#!/bin/sh\nexit 0\n")
    (cuda / "llama-server").chmod(0o755)
    stub = tmp_path / "stub"
    stub.mkdir()
    for tool in ("curl", "nvcc", "cmake"):   # fallback builds: record, never touch the network / compile
        (stub / tool).write_text(f'#!/bin/sh\necho "{tool} $*" >>"$FAKE_CALLS"\nexit 1\n')
        (stub / tool).chmod(0o755)
    calls = tmp_path / "calls.txt"
    calls.write_text("")
    env = {**os.environ, "MURMUR_WORKSPACE": str(ws), "MURMUR_REPO": str(contract.REPO), "FAKE_CALLS": str(calls),
           "FAKE_PREP": prep, "FAKE_DECODE": decode, "LLAMA_SMOKE_RETRY_S": "0", "LLAMA_TAG": "b12503",
           "PATH": f"{stub}:{os.environ['PATH']}", "LLAMA_SMOKE_MODELS": "Org/model-a Org/model-b"}
    env.pop("MURMUR_LLAMA_TAG", None)
    r = subprocess.run(["bash", str(contract.REPO / "ml/pod/kernel_smoke.sh")], env=env, capture_output=True,
                       text=True, timeout=120)
    return r, calls.read_text().splitlines(), ws / ".bootstrap"


def test_kernel_smoke_prep_failure_is_its_own_error_without_fallback_builds(tmp_path):
    r, calls, marks = _kernel_smoke(tmp_path, prep="never", decode="ok")
    assert r.returncode == 7, r.stderr
    assert "could not be prepared (download or GGUF conversion; NOT a CUDA build problem)" in r.stderr
    assert sum("--prepare-only --hf Org/model-a" in c for c in calls) == 2            # retried once, then stop
    assert not any("--gpu-bin" in c for c in calls), calls                            # no build was tested
    assert not any(c.split()[0] in ("curl", "nvcc", "cmake") for c in calls), calls   # no fallback build
    assert "no llama.cpp CUDA build passed" not in r.stderr
    assert (marks / "llama-smoke-unprepared").read_text().strip() == "Org/model-a"
    assert not (marks / "llama-cpu-fallback").exists()


def test_kernel_smoke_prep_retry_then_build_pass(tmp_path):
    r, calls, marks = _kernel_smoke(tmp_path, prep="once", decode="ok")
    assert r.returncode == 0, r.stderr
    assert sum("--prepare-only" in c for c in calls) == 3                       # a: fail + retry, b: once
    assert any("--label cuda12.8" in c for c in calls) and "kernel smoke PASS" in r.stderr
    assert (marks / "llama-cuda-bin").read_text().strip().endswith("llama/cuda/llama-b12503")
    assert not (marks / "llama-smoke-unprepared").exists() and not (marks / "llama-cpu-fallback").exists()


def test_kernel_smoke_build_mismatch_walks_the_fallbacks(tmp_path):
    r, calls, marks = _kernel_smoke(tmp_path, prep="ok", decode="fail")
    assert r.returncode == 0, r.stderr
    assert any(c.startswith("curl ") and "cuda-13.5" in c for c in calls)          # fallback 2 tried
    assert "no llama.cpp CUDA build passed" in r.stderr and (marks / "llama-cpu-fallback").exists()
    assert not (marks / "llama-smoke-unprepared").exists()
