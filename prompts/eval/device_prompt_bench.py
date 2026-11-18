#!/usr/bin/env python3
"""Prompt-profile prefill cost and output parity: phone (adb) or host (--local).

For one model and a few prompt profiles it renders, with prompts/render.py, (a) the profile's cached prefix and
(b) full requests for N cases of an eval set, runs each through llama-completion (greedy, thinking off, the same
n_predict rule as run_eval.py) and reports prefill tokens / ms, generation tok/s, and how many outputs equal the
host llama-server run stored in .cache/ml/prompt/runs/<m>.<profile>.<set>.jsonl (greedy parity).

  # host (any x86_64 build), through tools/heavy:
  tools/heavy python3 prompts/eval/device_prompt_bench.py --local --m q08 --profiles default.v4,default.v4s --n 6
  # phone: after tools/device/llm_npu_smoke.sh pushed the arm64 build + GGUFs to /data/local/tmp/murmur-llm
  python3 prompts/eval/device_prompt_bench.py --m lfm3 --profiles default.v4,default.v4s --n 12 --threads 6

Output: a markdown table on stdout and .cache/device-reports/prompt_bench-<ts>/{report.json,outputs.jsonl}.
"""
from __future__ import annotations

import argparse
import json
import re
import shlex
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(HERE.parent))
sys.path.insert(0, str(HERE))
import render  # noqa: E402
import score  # noqa: E402

DEV_DIR = "/data/local/tmp/murmur-llm"
HOST_BIN = ROOT / ".cache/ml/llm/bin/llama-b12503"
T_PROMPT = re.compile(r"prompt eval time\s*=\s*([\d.]+) ms /\s*(\d+) tokens")
T_EVAL = re.compile(r"\beval time\s*=\s*([\d.]+) ms /\s*(\d+) runs")


class Target:
    def __init__(self, local: bool, model: str, threads: int):
        self.local, self.threads = local, threads
        self.model = model if local else f"{DEV_DIR}/{Path(model).name}"
        self.adb = ["adb"]

    def put(self, path: Path, name: str) -> str:
        if self.local:
            return str(path)
        dst = f"{DEV_DIR}/prompts/{name}"
        subprocess.run(self.adb + ["shell", f"mkdir -p {DEV_DIR}/prompts"], check=True)
        subprocess.run(self.adb + ["push", str(path), dst], check=True, capture_output=True)
        return dst

    def complete(self, prompt_file: str, n_predict: int) -> tuple[str, str]:
        args = ["-m", self.model, "-f", prompt_file, "-n", str(n_predict), "-t", str(self.threads), "--temp", "0",
                "--top-k", "1", "--repeat-penalty", "1", "-c", "8192", "-no-cnv", "--no-display-prompt",
                "-r", "<|im_end|>", "--seed", "0", "--no-escape", "--no-warmup"]
        if self.local:
            p = subprocess.run([str(HOST_BIN / "llama-completion")] + args, capture_output=True, text=True,
                               env={"LD_LIBRARY_PATH": str(HOST_BIN), "PATH": "/usr/bin:/bin"}, timeout=900)
        else:
            cmd = f"cd {DEV_DIR} && LD_LIBRARY_PATH={DEV_DIR}/lib {DEV_DIR}/bin/llama-completion " + " ".join(
                shlex.quote(a) for a in args)
            p = subprocess.run(self.adb + ["shell", cmd], capture_output=True, text=True, timeout=900)
        return p.stdout, p.stderr


def timings(err: str) -> dict:
    m, g = T_PROMPT.search(err), T_EVAL.search(err)
    out = {}
    if m:
        out.update(prefill_ms=float(m.group(1)), prefill_tokens=int(m.group(2)))
    if g:
        out.update(gen_ms=float(g.group(1)), gen_tokens=int(g.group(2)))
    return out


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--m", required=True, help="model key in prompts/eval/models.json")
    ap.add_argument("--profiles", default="default.v4,default.v4s")
    ap.add_argument("--set", default="dev.v2")
    ap.add_argument("--n", type=int, default=6, help="cases per profile (first N of the set)")
    ap.add_argument("--threads", type=int, default=6)
    ap.add_argument("--local", action="store_true", help="run on this host instead of adb")
    a = ap.parse_args(argv)
    spec = json.loads((HERE / "models.json").read_text(encoding="utf-8"))[a.m]
    cases = score.load_jsonl(HERE / "sets" / f"{a.set}.jsonl")[: a.n]
    out_dir = ROOT / ".cache/device-reports" / f"prompt_bench-{time.strftime('%Y%m%d-%H%M%S')}"
    out_dir.mkdir(parents=True, exist_ok=True)
    tgt = Target(a.local, str(ROOT / spec["gguf"]), a.threads)
    report, rows = [], []
    for prof in a.profiles.split(","):
        ref_p = ROOT / ".cache/ml/prompt/runs" / f"{a.m}.{prof}.{a.set}.jsonl"
        ref = {r["id"]: r["out"] for r in score.load_jsonl(ref_p)} if ref_p.exists() else {}
        pre = out_dir / f"{prof}.prefix.txt"
        pre.write_text(render.cache_prefix(prof, spec["template"]), encoding="utf-8")
        _, err = tgt.complete(tgt.put(pre, pre.name), 1)
        pt = timings(err)
        res = {"profile": prof, "prefix": pt, "cases": []}
        for c in cases:
            f = out_dir / f"{prof}.{c['id']}.txt"
            f.write_text(render.render_prompt(prof, c["ctx"], c["transcript"], spec["template"]), encoding="utf-8")
            n_pred = min(1024, 2 * max(1, len(c["transcript"]) // 3) + 64)  # chars/3 ~ tokens (no tokenizer here)
            o, err = tgt.complete(tgt.put(f, f.name), n_pred)
            text = o.replace("<|im_end|>", "").strip()
            t = timings(err)
            same = (text == ref.get(c["id"])) if c["id"] in ref else None
            rows.append({"profile": prof, "id": c["id"], "out": text, "ref": ref.get(c["id"]), "same": same, **t})
            res["cases"].append({"id": c["id"], "same": same, **t})
        report.append(res)
    (out_dir / "outputs.jsonl").write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows), encoding="utf-8")
    (out_dir / "report.json").write_text(json.dumps({"model": spec["gguf"], "local": a.local, "threads": a.threads,
                                                     "profiles": report}, indent=1), encoding="utf-8")
    print(f"| profile | prefix tok | prefix prefill ms | request prefill tok (mean) | ms (mean) | gen tok/s | same as host run |")
    print("|---|---:|---:|---:|---:|---:|---:|")
    for r in report:
        cs = [c for c in r["cases"] if "prefill_ms" in c]
        mean = lambda k: round(sum(c[k] for c in cs) / max(1, len(cs)), 1)
        gs = sum(c.get("gen_tokens", 0) for c in cs) / max(1e-9, sum(c.get("gen_ms", 0) for c in cs) / 1000)
        same = [c["same"] for c in r["cases"] if c["same"] is not None]
        print(f"| {r['profile']} | {r['prefix'].get('prefill_tokens')} | {r['prefix'].get('prefill_ms')} | "
              f"{mean('prefill_tokens')} | {mean('prefill_ms')} | {gs:.1f} | {sum(same)}/{len(same)} |")
    print(f"\n{out_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
