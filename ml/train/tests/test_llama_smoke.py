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
