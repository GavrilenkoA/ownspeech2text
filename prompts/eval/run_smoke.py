#!/usr/bin/env python3
"""Smoke-test the default/finetuned prompts on local GGUF models with llama.cpp.

NOT RUN in the design phase (running local LLM inference on the shared build
server was not permitted). Intended for a GPU/CPU box the orchestrator approves.

  python3 run_smoke.py --llama-bin DIR_WITH_llama-completion \
      --model /path/Qwen3.5-0.8B-Q4_0.gguf --template qwen3.5 [--system default]

Greedy decoding (temperature 0, top-k 1), no presence/repetition penalties:
penalties hurt an editing task whose output must repeat the input's words.
"""
import argparse, json, os, re, subprocess, sys, tempfile, time
from pathlib import Path
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import render  # noqa: E402


def check(c, out):
    o = out.strip()
    if c.get("exact"):
        return o == c["ref"].strip()
    ok = all(m in o for m in c.get("must", [])) and not any(m.lower() in o.lower() for m in c.get("must_not", []))
    for alts in c.get("must_any", []):
        ok = ok and any(a in o for a in alts)
    return ok


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--llama-bin", required=True)
    ap.add_argument("--model", required=True)
    ap.add_argument("--template", choices=["qwen3.5", "lfm3"], required=True)
    ap.add_argument("--system", default="default", choices=["default", "finetuned"])
    ap.add_argument("--threads", default="6")
    ap.add_argument("--cases", default=str(HERE / "smoke_cases.v1.jsonl"))
    a = ap.parse_args()
    cases = [json.loads(l) for l in open(a.cases, encoding="utf-8")]
    sysmsg = render.system_prompt(a.system)
    env = {**os.environ, "LD_LIBRARY_PATH": a.llama_bin}
    npass = 0
    with tempfile.TemporaryDirectory() as td:
        pf = Path(td) / "prompt.txt"
        for c in cases:
            pf.write_text(render.chat(sysmsg, render.user_message(c["ctx"], c["transcript"]), a.template), encoding="utf-8")
            t0 = time.time()
            r = subprocess.run([f"{a.llama_bin}/llama-completion", "-m", a.model, "-f", str(pf), "-no-cnv",
                                "--temp", "0", "--top-k", "1", "-n", "300", "-t", a.threads, "-c", "4096",
                                "--no-display-prompt", "--simple-io"], capture_output=True, text=True, env=env, timeout=900)
            out = r.stdout.replace("[end of text]", "").strip()
            ok = check(c, out)
            npass += ok
            print(json.dumps({"id": c["id"], "cat": c["cat"], "ok": ok, "out": out, "ref": c["ref"],
                              "wall_s": round(time.time() - t0, 2)}, ensure_ascii=False), flush=True)
    print(f"# {Path(a.model).name} system={a.system}: {npass}/{len(cases)} passed", file=sys.stderr)


if __name__ == "__main__":
    main()
