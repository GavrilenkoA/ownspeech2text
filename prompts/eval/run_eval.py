#!/usr/bin/env python3
"""Run an eval set through a local GGUF with llama-server, greedy, with a cached system prefix.

The request-independent prefix (system prompt + few-shot turns) is evaluated once, saved with
/slots?action=save and restored into the slot before every request (works for the hybrid
recurrent/attention models too, whose KV cannot be truncated). Each request then only pre-fills its
own user message, which is also what the phone does.

  tools/heavy python3 prompts/eval/run_eval.py --model .cache/ml/llm/Qwen3.5-2B-Q4_0.gguf \
      --template qwen3.5 --profile default --set dev --out .cache/ml/prompt/runs/q2b.default.dev.jsonl

Writes OUT (one JSON per case: out = raw model text, final = after prompts/guard.py) and OUT.meta.json
(timings, token counts, scores). Must run under tools/heavy (the model is ~0.3-1.5 GB RSS).
"""
from __future__ import annotations

import argparse
import json
import os
import queue
import socket
import subprocess
import sys
import threading
import time
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(HERE.parent))
sys.path.insert(0, str(HERE))
import guard  # noqa: E402
import render  # noqa: E402
import score  # noqa: E402

DEFAULT_BIN = ROOT / ".cache/ml/llm/bin/llama-b12503"
SLOTS = ROOT / ".cache/ml/prompt/slots"
GREEDY = {"temperature": 0.0, "top_k": 1, "top_p": 1.0, "min_p": 0.0, "repeat_penalty": 1.0,
          "presence_penalty": 0.0, "frequency_penalty": 0.0, "seed": 0, "cache_prompt": True}


def free_port() -> int:
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


class Server:
    def __init__(self, model: str, bin_dir: Path, threads: int, n_par: int, ctx_per_slot: int, log: Path):
        self.port = free_port()
        SLOTS.mkdir(parents=True, exist_ok=True)
        cmd = [str(bin_dir / "llama-server"), "-m", model, "-t", str(threads), "-tb", str(threads),
               "-c", str(ctx_per_slot * n_par), "-np", str(n_par), "--host", "127.0.0.1", "--port", str(self.port),
               "--slot-save-path", str(SLOTS), "--no-webui", "-fa", "auto", "--jinja", "--reasoning-format", "none"]
        self.log = open(log, "w")
        self.proc = subprocess.Popen(cmd, env={**os.environ, "LD_LIBRARY_PATH": str(bin_dir)},
                                     stdout=self.log, stderr=subprocess.STDOUT)
        for _ in range(600):
            if self.proc.poll() is not None:
                raise RuntimeError(f"llama-server exited, see {log}")
            try:
                urllib.request.urlopen(f"http://127.0.0.1:{self.port}/health", timeout=2)
                return
            except Exception:  # noqa: BLE001
                time.sleep(0.5)
        raise RuntimeError("llama-server did not become healthy")

    def post(self, path: str, body: dict, timeout: float = 1800) -> dict:
        req = urllib.request.Request(f"http://127.0.0.1:{self.port}{path}", data=json.dumps(body).encode(),
                                     headers={"Content-Type": "application/json"})
        return json.load(urllib.request.urlopen(req, timeout=timeout))

    def tokens(self, text: str) -> list[int]:
        """Token ids exactly as written: special tokens parsed, no BOS added (the rendered prompt carries its
        own BOS where the template has one, e.g. LFM's <|startoftext|>; a string prompt would get a second one)."""
        return self.post("/tokenize", {"content": text, "add_special": False, "parse_special": True})["tokens"]

    def ntok(self, text: str) -> int:
        return len(self.tokens(text))

    def close(self):
        self.proc.terminate()
        try:
            self.proc.wait(20)
        except subprocess.TimeoutExpired:
            self.proc.kill()
        self.log.close()


def run(a) -> dict:
    set_path = (Path(a.set) if a.set.endswith(".jsonl") else
                HERE / "sets" / (f"{a.set}.jsonl" if "." in a.set else f"{a.set}.v1.jsonl"))
    cases = score.load_jsonl(set_path)
    if a.ids:
        keep = set(a.ids.split(","))
        cases = [c for c in cases if c["id"] in keep]
    prof = render.load_profile(a.profile)
    if a.ctx2:  # ablation: override the profile's ctx v2 options
        prof = {**prof, "ctx2": {**(prof.get("ctx2") or {}), **json.loads(a.ctx2)}, "name": f"{prof['name']}+{a.ctx2}"}
    out = Path(a.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    srv = Server(a.model, Path(a.llama_bin), a.threads, a.np, a.ctx, out.with_suffix(".server.log"))
    slot_file = f"{os.getpid()}-{out.stem}.bin"
    try:
        prefix = render.cache_prefix(prof, a.template)
        t0 = time.time()
        prefix_ids = srv.tokens(prefix)
        r = srv.post("/completion", {**GREEDY, "prompt": prefix_ids, "n_predict": 0, "id_slot": 0})
        prefix_ms = r["timings"]["prompt_ms"]
        srv.post("/slots/0?action=save", {"filename": slot_file})
        prefix_tokens = len(prefix_ids)
        jobs: queue.Queue = queue.Queue()
        for c in cases:
            jobs.put(c)
        results: dict[str, dict] = {}

        def worker(slot: int):
            while True:
                try:
                    c = jobs.get_nowait()
                except queue.Empty:
                    return
                full = render.render_prompt(prof, c["ctx"], c["transcript"], a.template)
                assert full.startswith(prefix)
                full_ids = srv.tokens(full)
                assert full_ids[:prefix_tokens] == prefix_ids, "prefix tokenisation is not stable"
                n_tr = srv.ntok(c["transcript"]) if c["transcript"] else 0
                n_pred = min(1024, 2 * n_tr + 64)
                ts = time.time()
                srv.post(f"/slots/{slot}?action=restore", {"filename": slot_file})
                res = srv.post("/completion", {**GREEDY, "prompt": full_ids, "n_predict": n_pred, "id_slot": slot,
                                               "stop": ["<|im_end|>", "<|endoftext|>"]})
                text = res["content"]
                v = guard.guard(c["transcript"], text, c["ctx"])
                tm = res.get("timings", {})
                results[c["id"]] = {"id": c["id"], "out": text.strip(), "final": v.text, "guard": v.action,
                                    "guard_reason": v.reason, "prompt_n": tm.get("prompt_n"),
                                    "user_tokens": len(full_ids) - prefix_tokens, "gen_n": tm.get("predicted_n"),
                                    "prompt_ms": round(tm.get("prompt_ms", 0)), "gen_ms": round(tm.get("predicted_ms", 0)),
                                    "truncated": res.get("stop_type") == "limit", "wall_s": round(time.time() - ts, 2)}
                if a.verbose:
                    print(c["id"], json.dumps(text[:120], ensure_ascii=False), flush=True)

        ths = [threading.Thread(target=worker, args=(i,)) for i in range(a.np)]
        for t in ths:
            t.start()
        for t in ths:
            t.join()
        wall = time.time() - t0
    finally:
        srv.close()
        try:
            (SLOTS / slot_file).unlink()
        except FileNotFoundError:
            pass
    rows = [results[c["id"]] for c in cases if c["id"] in results]
    out.write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows), encoding="utf-8")
    _, s_raw = score.score_run(cases, {r["id"]: r["out"] for r in rows})
    _, s_fin = score.score_run(cases, {r["id"]: r["final"] for r in rows})
    n = max(1, len(rows))
    meta = {"model": Path(a.model).name, "template": a.template, "profile": prof.get("name"), "set": set_path.name,
            "n": len(rows), "np": a.np, "threads": a.threads, "prefix_tokens": prefix_tokens,
            "prefix_prefill_ms": round(prefix_ms), "mean_user_tokens": round(sum(r["user_tokens"] for r in rows) / n, 1),
            "mean_gen_tokens": round(sum(r["gen_n"] or 0 for r in rows) / n, 1),
            "truncated": sum(r["truncated"] for r in rows),
            "guard_fired": sum(r["guard"] != "accept" for r in rows), "wall_s": round(wall, 1),
            "score_raw": s_raw, "score_guarded": s_fin}
    out.with_suffix(".meta.json").write_text(json.dumps(meta, ensure_ascii=False, indent=1), encoding="utf-8")
    return meta


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--m", help="model key in prompts/eval/models.json (sets --model and --template)")
    ap.add_argument("--model")
    ap.add_argument("--template", choices=sorted(render.TEMPLATES))
    ap.add_argument("--profile", default="default", help="prompts/profiles/<name>.json or a system prompt name")
    ap.add_argument("--set", default="dev", help="dev | test | path.jsonl")
    ap.add_argument("--ids", help="comma-separated subset of case ids")
    ap.add_argument("--out", help="default: .cache/ml/prompt/runs/<m>.<profile>.<set>.jsonl")
    ap.add_argument("--llama-bin", default=str(DEFAULT_BIN))
    ap.add_argument("--threads", type=int, default=6)
    ap.add_argument("--np", type=int, default=3, help="parallel slots (continuous batching)")
    ap.add_argument("--ctx", type=int, default=4096, help="context per slot")
    ap.add_argument("--ctx2", help='JSON overrides of the ctx v2 render options, e.g. {"order": "reading"}')
    ap.add_argument("--tag", default="", help="suffix for the default output name (ablations)")
    ap.add_argument("--verbose", action="store_true")
    a = ap.parse_args(argv)
    if a.m:
        spec = json.loads((HERE / "models.json").read_text(encoding="utf-8"))[a.m]
        a.model = a.model or str(ROOT / spec["gguf"])
        a.template = a.template or spec["template"]
    if not (a.model and a.template):
        ap.error("--m or --model/--template required")
    a.out = a.out or str(ROOT / ".cache/ml/prompt/runs" / f"{a.m or Path(a.model).stem}.{a.profile}{a.tag}.{Path(a.set).stem if a.set.endswith('.jsonl') else a.set}.jsonl")
    m = run(a)
    print(f"# {m['model']} {m['profile']} {m['set']}: prefix {m['prefix_tokens']} tok, user {m['mean_user_tokens']} tok, "
          f"gen {m['mean_gen_tokens']} tok, wall {m['wall_s']} s, guard fired {m['guard_fired']}")
    print(score.fmt_table(m["score_raw"], "raw model output"))
    print(score.fmt_table(m["score_guarded"], "after guard"))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
