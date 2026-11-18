"""Inference backends for eval. All decode greedily from a raw prompt string rendered by
contract.render_prompt(), so every backend sees byte-identical prompts.

  llama   GGUF through llama.cpp's llama-server (same engine + quantisation as the phone).
          One server process per eval run, bound to 127.0.0.1 on a free port, killed at the end.
          cache_prompt=true reuses the system-prefix KV cache like the app does.
  hf      HF checkpoint (bf16/fp32) through transformers.generate (reference / pre-export check).
  teacher The teacher LLM (Responses API, dev server only): baseline B3 / upper bound.

max_new_tokens = min(1024, 2 * transcript_tokens + 64) as in prompts/README.md.
"""
from __future__ import annotations

import json
import os
import socket
import subprocess
import time
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path


@dataclass
class Gen:
    text: str
    prompt_tokens: int | None = None
    completion_tokens: int | None = None
    prefill_ms: float | None = None
    decode_ms: float | None = None
    latency_ms: float | None = None
    extra: dict = field(default_factory=dict)


def max_new_tokens(transcript_tokens: int) -> int:
    return min(1024, 2 * transcript_tokens + 64)


def _post(url: str, payload: dict, timeout: float = 600) -> dict:
    req = urllib.request.Request(url, data=json.dumps(payload).encode(), headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read())


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class LlamaServer:
    """llama-server wrapper. `bin_dir` holds llama-server and its shared libraries."""
    name = "llama"

    def __init__(self, model: str, bin_dir: str, threads: int = 6, ctx: int = 4096, extra_args: list[str] | None = None,
                 log_path: str | None = None, startup_timeout: float = 600):
        self.model, self.port = model, free_port()
        self.url = f"http://127.0.0.1:{self.port}"
        exe = str(Path(bin_dir) / "llama-server")
        env = {**os.environ, "LD_LIBRARY_PATH": f"{bin_dir}:{os.environ.get('LD_LIBRARY_PATH', '')}"}
        cmd = [exe, "-m", model, "--host", "127.0.0.1", "--port", str(self.port), "-t", str(threads), "-c", str(ctx),
               "-np", "1", "--no-webui", "--no-warmup", *(extra_args or [])]
        self._log = open(log_path or os.devnull, "w")
        self.proc = subprocess.Popen(cmd, stdout=self._log, stderr=subprocess.STDOUT, env=env)
        t0 = time.time()
        while True:
            if self.proc.poll() is not None:
                raise RuntimeError(f"llama-server exited with {self.proc.returncode}; see {log_path}")
            try:
                with urllib.request.urlopen(self.url + "/health", timeout=5) as r:
                    if json.loads(r.read()).get("status") == "ok":
                        break
            except Exception:
                pass
            if time.time() - t0 > startup_timeout:
                self.close()
                raise TimeoutError("llama-server did not become healthy")
            time.sleep(0.5)
        self.load_s = time.time() - t0

    def n_tokens(self, text: str) -> int:
        return len(_post(self.url + "/tokenize", {"content": text, "add_special": False})["tokens"])

    def generate(self, prompt: str, transcript: str) -> Gen:
        n_pred = max_new_tokens(self.n_tokens(transcript))
        t0 = time.time()
        r = _post(self.url + "/completion", {
            "prompt": prompt, "n_predict": n_pred, "temperature": 0.0, "top_k": 1, "top_p": 1.0, "min_p": 0.0,
            "repeat_penalty": 1.0, "presence_penalty": 0.0, "frequency_penalty": 0.0, "cache_prompt": True,
            "stream": False, "seed": 0})
        wall = (time.time() - t0) * 1000
        tm = r.get("timings") or {}
        return Gen(text=r.get("content", ""), prompt_tokens=r.get("tokens_evaluated") or tm.get("prompt_n"),
                   completion_tokens=r.get("tokens_predicted") or tm.get("predicted_n"),
                   prefill_ms=tm.get("prompt_ms"), decode_ms=tm.get("predicted_ms"), latency_ms=round(wall, 1),
                   extra={"stop_type": r.get("stop_type"), "truncated": r.get("truncated"),
                          "cached_prompt_tokens": tm.get("cache_n")})

    def close(self):
        if self.proc and self.proc.poll() is None:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=20)
            except subprocess.TimeoutExpired:
                self.proc.kill()
        self._log.close()


class HFBackend:
    name = "hf"

    def __init__(self, model: str, threads: int = 6, dtype: str = "bfloat16", device: str | None = None):
        import torch
        from transformers import AutoModelForCausalLM, AutoTokenizer
        torch.set_num_threads(threads)
        self.torch = torch
        self.device = device or ("cuda" if torch.cuda.is_available() else "cpu")
        t0 = time.time()
        self.tok = AutoTokenizer.from_pretrained(model)
        self.model = AutoModelForCausalLM.from_pretrained(model, dtype=getattr(torch, dtype)).to(self.device).eval()
        self.load_s = time.time() - t0
        self.eos = [self.tok.convert_tokens_to_ids("<|im_end|>")]

    def generate(self, prompt: str, transcript: str) -> Gen:
        torch = self.torch
        ids = self.tok(prompt, add_special_tokens=False, return_tensors="pt").input_ids.to(self.device)
        n_pred = max_new_tokens(len(self.tok(transcript, add_special_tokens=False).input_ids))
        t0 = time.time()
        with torch.no_grad():
            out = self.model.generate(ids, attention_mask=torch.ones_like(ids), max_new_tokens=n_pred, do_sample=False,
                                      eos_token_id=self.eos, pad_token_id=self.tok.pad_token_id or self.eos[0])
        wall = (time.time() - t0) * 1000
        new = out[0, ids.shape[1]:]
        text = self.tok.decode(new, skip_special_tokens=True)
        return Gen(text=text, prompt_tokens=ids.shape[1], completion_tokens=int(new.shape[0]), latency_ms=round(wall, 1))

    def close(self):
        pass


class TeacherBackend:
    """Baseline B3 (upper bound): the teacher LLM through the Responses API on the dev server
    (PLAN_V2), with the same system prompt / profile and user message as the small models.
    Env from `tools/with-teacher`; quota etiquette (Retry-After, backoff) shared with ml.eval.judge.
    Not greedy-comparable (remote model, server-side decoding)."""
    name = "teacher"
    wants_messages = True

    def __init__(self, cfg=None):
        from .judge import JudgeConfig
        self.cfg = cfg or JudgeConfig()
        self.load_s = 0.0

    def generate_messages(self, system: str, turns: list[tuple[str, str]], user: str) -> Gen:
        from .judge import post_with_retries
        c = self.cfg
        if c.api == "responses":
            items = []
            for u, a in turns:
                items.append({"type": "message", "role": "user", "content": [{"type": "input_text", "text": u}]})
                items.append({"type": "message", "role": "assistant", "content": [{"type": "output_text", "text": a}]})
            items.append({"type": "message", "role": "user", "content": [{"type": "input_text", "text": user}]})
            path, body = "/responses", {"model": c.model, "instructions": system, "input": items, "store": False}
        else:
            msgs = [{"role": "system", "content": system}]
            for u, a in turns:
                msgs += [{"role": "user", "content": u}, {"role": "assistant", "content": a}]
            msgs.append({"role": "user", "content": user})
            path, body = "/chat/completions", {"model": c.model, "messages": msgs, "temperature": 0.0, "max_tokens": 1024}
        t0 = time.time()
        d = post_with_retries(c, path, body)
        from .judge import response_text
        u = d.get("usage") or {}
        return Gen(text=response_text(c.api, d), prompt_tokens=u.get("input_tokens", u.get("prompt_tokens")),
                   completion_tokens=u.get("output_tokens", u.get("completion_tokens")),
                   latency_ms=round((time.time() - t0) * 1000, 1))

    def close(self):
        pass
