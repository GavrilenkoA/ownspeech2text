#!/usr/bin/env python3
"""HF checkpoint -> GGUF (BF16) -> quantised GGUF(s) with the pinned llama.cpp, plus a manifest
(sha256, bytes, quant, llama.cpp build) for the model card / catalog entry.

  python -m ml.train.export --run OUTPUT_DIR --name murmur-cleanup-qwen3.5-0.8b [--quants Q4_0 Q4_K_M]
        [--imatrix-data train.jsonl --imatrix-n 400] [--keep-bf16]
  python -m ml.train.export --hf HF_DIR --name stock-qwen3.5-0.8b        # stock baseline, same pipeline

--run picks OUTPUT_DIR/merged (LoRA/DoRA, run ml.train.merge first) or OUTPUT_DIR/final (full FT).
Qwen3.5 is converted text-only with --no-mtp (the MTP head is dropped by transformers anyway and the
app does not use speculative decoding). Env: MURMUR_LLAMA_SRC (checkout with convert_hf_to_gguf.py),
MURMUR_LLAMA_BIN (dir with llama-quantize / llama-imatrix).
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
from pathlib import Path

from ml.eval import contract
from ml.eval.run_eval import sha256

REPO = contract.REPO
LLAMA_SRC = os.environ.get("MURMUR_LLAMA_SRC", str(REPO / ".cache/ml/train/src/llama.cpp-b12503"))
LLAMA_BIN = os.environ.get("MURMUR_LLAMA_BIN", str(REPO / ".cache/ml/llm/bin/llama-b12503"))


def run(cmd: list[str], log: Path, env: dict | None = None) -> float:
    t0 = time.time()
    with open(log, "a") as f:
        f.write("$ " + " ".join(cmd) + "\n")
        f.flush()
        r = subprocess.run(cmd, stdout=f, stderr=subprocess.STDOUT, env=env)
    if r.returncode:
        raise SystemExit(f"command failed ({r.returncode}), see {log}: {' '.join(cmd)}")
    return round(time.time() - t0, 1)


def bin_env() -> dict:
    return {**os.environ, "LD_LIBRARY_PATH": f"{LLAMA_BIN}:{os.environ.get('LD_LIBRARY_PATH', '')}"}


def llama_build() -> str | None:
    """'version: 1.0.0-dev (build 12503, commit 4577932fa)' -> 'b12503 (4577932fa)'. llama-quantize has
    no --version (prints usage, rc 1); llama-server prints the version on stderr."""
    import re
    try:
        r = subprocess.run([f"{LLAMA_BIN}/llama-server", "--version"], capture_output=True, text=True, env=bin_env(),
                           timeout=60)
    except Exception:
        return None
    m = re.search(r"build (\d+), commit (\w+)", r.stderr + r.stdout)
    return f"b{m.group(1)} ({m.group(2)})" if m else None


def model_type(hf: Path) -> str:
    return json.loads((hf / "config.json").read_text()).get("model_type", "")


def imatrix_text(data: list[str], template: str, n: int, system: str, out: Path) -> Path:
    """Calibration text = rendered prompt + target, i.e. exactly the distribution the model sees."""
    rows = contract.load(data)
    rows = [r for r in rows if r["split"] == "train"][:n] or rows[:n]
    out.write_text("\n".join(contract.render_prompt(r, system, template) + contract.completion_text(r, template)
                             for r in rows), encoding="utf-8")
    return out


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--run")
    ap.add_argument("--hf")
    ap.add_argument("--name", required=True)
    ap.add_argument("--out", help="default: RUN/gguf or HF/../gguf-NAME")
    ap.add_argument("--quants", nargs="+", default=["Q4_0"])
    ap.add_argument("--outtype", default="bf16")
    ap.add_argument("--imatrix-data", nargs="*", default=None)
    ap.add_argument("--imatrix-n", type=int, default=400)
    ap.add_argument("--imatrix-system", default="finetuned")
    ap.add_argument("--threads", type=int, default=int(os.environ.get("MURMUR_THREADS", "6")))
    ap.add_argument("--keep-bf16", action="store_true", help="keep the BF16 GGUF (needed to measure quant loss)")
    a = ap.parse_args(argv)

    if a.run:
        run_dir = Path(a.run)
        hf = run_dir / "merged" if (run_dir / "merged" / "config.json").exists() else run_dir / "final"
        out = Path(a.out or run_dir / "gguf")
    else:
        hf = Path(a.hf)
        out = Path(a.out or hf.parent / f"gguf-{a.name}")
    if not (hf / "config.json").exists():
        raise SystemExit(f"{hf} has no config.json (LoRA run? run `python -m ml.train.merge --run {a.run}` first)")
    out.mkdir(parents=True, exist_ok=True)
    log = out / "export.log"
    mt = model_type(hf)
    template = contract.MODEL_TYPE_TO_TEMPLATE.get(mt)
    if template is None:
        raise SystemExit(f"unsupported model_type {mt!r}")
    manifest = {"name": a.name, "source": str(hf), "model_type": mt, "template": template, "files": [],
                "llama_cpp_src": LLAMA_SRC, "llama_cpp_bin": LLAMA_BIN, "timings_s": {}}
    manifest["llama_cpp_build"] = llama_build()

    f16 = out / f"{a.name}-{a.outtype.upper()}.gguf"
    cmd = [sys.executable, f"{LLAMA_SRC}/convert_hf_to_gguf.py", str(hf), "--outtype", a.outtype, "--outfile", str(f16),
           "--model-name", a.name]
    if mt.startswith("qwen3_5"):
        cmd.append("--no-mtp")
    env = {**os.environ, "PYTHONPATH": f"{LLAMA_SRC}/gguf-py:{os.environ.get('PYTHONPATH', '')}"}
    manifest["timings_s"]["convert"] = run(cmd, log, env)

    imat = None
    if a.imatrix_data:
        calib = imatrix_text(a.imatrix_data, template, a.imatrix_n, a.imatrix_system, out / "imatrix_calib.txt")
        imat = out / "imatrix.gguf"
        manifest["timings_s"]["imatrix"] = run([f"{LLAMA_BIN}/llama-imatrix", "-m", str(f16), "-f", str(calib),
                                                "-o", str(imat), "-t", str(a.threads), "-c", "512",
                                                "--parse-special"], log, bin_env())
        manifest["imatrix"] = {"data": a.imatrix_data, "n": a.imatrix_n, "system": a.imatrix_system}

    files = [(f16, a.outtype.upper())]
    for q in a.quants:
        dst = out / f"{a.name}-{q}{'-imat' if imat else ''}.gguf"
        cmd = [f"{LLAMA_BIN}/llama-quantize"] + (["--imatrix", str(imat)] if imat else []) + [str(f16), str(dst), q,
                                                                                               str(a.threads)]
        manifest["timings_s"][f"quantize_{q}"] = run(cmd, log, bin_env())
        files.append((dst, q))
    for p, q in files:
        manifest["files"].append({"path": p.name, "quant": q, "bytes": p.stat().st_size, "sha256": sha256(p),
                                  "imatrix": bool(imat) and q != a.outtype.upper()})
    (out / "manifest.json").write_text(json.dumps(manifest, indent=1))
    if not a.keep_bf16:
        f16.unlink()
        manifest["files"][0]["deleted"] = True
        (out / "manifest.json").write_text(json.dumps(manifest, indent=1))
    print(json.dumps(manifest, indent=1))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
