"""Teacher client: OpenAI-compatible endpoint, Responses API (default) or chat completions, plus resumable shards.

Config comes from the environment (tools/with-teacher sets these for one command; values are never logged):
  MURMUR_TEACHER_BASE_URL   e.g. http://127.0.0.1:18899/v1
  MURMUR_TEACHER_API_KEY    bearer token (optional for local servers)
  MURMUR_TEACHER_MODEL      e.g. gpt-6.2-sol
  MURMUR_TEACHER_API        responses (default) | chat
  MURMUR_TEACHER_EFFORT     reasoning effort for the Responses API (default: low)
  MURMUR_TEACHER_CONCURRENCY  parallel requests (default 2: the quota is shared)

Responses request shape (docs/ml/PLAN_V2.md): {"model", "instructions", "input": [{"type": "message", "role":
"user", "content": [{"type": "input_text", "text"}]}], "store": false, "reasoning": {"effort"}, "text": {"format":
{"type": "json_schema", "name", "schema", "strict": true}}}. The answer is the output_text of the item with
type "message". Parsing is strict: the whole text must be one JSON object that validates against the schema,
otherwise the call counts as failed and is retried.

Retries: 408/409/425/429/5xx, timeouts and connection errors, with exponential backoff and jitter; a
Retry-After header (seconds or HTTP date) pauses ALL workers until then. A hard request cap
(`max_requests`) stops new requests (used to keep test runs inside the shared quota).

Shards: <dir>/<stage>-00000.jsonl, one line per finished job {"id","ok","result","error","attempts","usage",
"latency_s","model"}; finished ok jobs are skipped on re-run, so a killed run resumes where it stopped.
"""
from __future__ import annotations

import email.utils
import json
import os
import random
import sys
import threading
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field
from pathlib import Path

from ml.common.jsonschema_lite import validate as validate_json

RETRY_CODES = {408, 409, 425, 429, 500, 502, 503, 504}


def _env(name: str, default: str = "") -> str:
    return os.environ.get(name, default)


@dataclass
class TeacherConfig:
    base_url: str = field(default_factory=lambda: _env("MURMUR_TEACHER_BASE_URL", "http://127.0.0.1:18899/v1"))
    model: str = field(default_factory=lambda: _env("MURMUR_TEACHER_MODEL", "gpt-6.2-sol"))
    api: str = field(default_factory=lambda: _env("MURMUR_TEACHER_API", "responses"))
    effort: str = field(default_factory=lambda: _env("MURMUR_TEACHER_EFFORT", "low"))
    concurrency: int = field(default_factory=lambda: int(_env("MURMUR_TEACHER_CONCURRENCY", "2")))
    timeout: float = field(default_factory=lambda: float(_env("MURMUR_TEACHER_TIMEOUT", "300")))
    max_retries: int = 5
    max_requests: int = 0            # 0 = unlimited; counts every HTTP attempt
    temperature: float | None = None  # Responses: omitted unless set (reasoning models may reject it)
    max_output_tokens: int = 8000

    @property
    def api_key(self) -> str:  # read on use, never stored in reprs or logs
        return _env("MURMUR_TEACHER_API_KEY")

    def public(self) -> dict:
        return {"base_url_host": self.base_url.split("//")[-1].split("/")[0], "model": self.model, "api": self.api,
                "effort": self.effort, "concurrency": self.concurrency}


class TeacherError(Exception):
    """Bad output (not JSON / schema violation / empty). Retryable."""


class QuotaExhausted(Exception):
    """The run's request cap was reached."""


class HTTPFailure(Exception):
    def __init__(self, code: int, retry_after: float | None, body: str):
        super().__init__(f"HTTP {code}: {body[:300]}")
        self.code, self.retry_after = code, retry_after


def parse_retry_after(v: str | None) -> float | None:
    if not v:
        return None
    try:
        return max(0.0, float(v))
    except ValueError:
        try:
            dt = email.utils.parsedate_to_datetime(v)
            return max(0.0, dt.timestamp() - time.time())
        except (TypeError, ValueError):
            return None


def strict_json(txt: str, schema: dict) -> dict:
    txt = (txt or "").strip()
    if not txt:
        raise TeacherError("empty output")
    try:
        obj = json.loads(txt)
    except json.JSONDecodeError as e:
        raise TeacherError(f"not JSON: {e}: {txt[:160]!r}") from e
    errs = validate_json(obj, schema)
    if errs:
        raise TeacherError("schema: " + "; ".join(errs[:5]))
    return obj


def responses_text(resp: dict) -> str:
    """Concatenated output_text of the message item(s) of a Responses API result."""
    if resp.get("error"):
        raise TeacherError(f"response error: {str(resp['error'])[:200]}")
    if resp.get("status") not in (None, "completed"):
        raise TeacherError(f"status {resp.get('status')}: {str(resp.get('incomplete_details'))[:200]}")
    parts = []
    for item in resp.get("output") or []:
        if item.get("type") != "message":
            continue
        for c in item.get("content") or []:
            if c.get("type") in ("output_text", "text"):
                parts.append(c.get("text") or "")
            elif c.get("type") == "refusal":
                raise TeacherError("refusal: " + str(c.get("refusal"))[:200])
    return "".join(parts)


class Gate:
    """Shared pause (Retry-After) and request counter for all worker threads."""

    def __init__(self, max_requests: int = 0):
        self.lock = threading.Lock()
        self.resume_at = 0.0
        self.max_requests = max_requests
        self.requests = 0

    def acquire(self) -> None:
        while True:
            with self.lock:
                wait = self.resume_at - time.time()
                if wait <= 0:
                    if self.max_requests and self.requests >= self.max_requests:
                        raise QuotaExhausted(f"request cap {self.max_requests} reached")
                    self.requests += 1
                    return
            time.sleep(min(wait, 5.0))

    def pause(self, seconds: float) -> None:
        with self.lock:
            self.resume_at = max(self.resume_at, time.time() + seconds)


class OpenAITeacher:
    """complete_json(instructions, user_text, schema, name) -> (obj, usage) for both API shapes."""

    def __init__(self, cfg: TeacherConfig, gate: Gate | None = None):
        self.cfg = cfg
        self.gate = gate or Gate(cfg.max_requests)
        self.name = f"{cfg.api}:{cfg.model}"

    def _post(self, path: str, body: dict) -> dict:
        self.gate.acquire()
        headers = {"Content-Type": "application/json"}
        if self.cfg.api_key:
            headers["Authorization"] = "Bearer " + self.cfg.api_key
        req = urllib.request.Request(self.cfg.base_url.rstrip("/") + path, data=json.dumps(body).encode("utf-8"),
                                     method="POST", headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=self.cfg.timeout) as r:
                return json.loads(r.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            ra = parse_retry_after(e.headers.get("Retry-After") if e.headers else None)
            try:
                txt = e.read().decode("utf-8", "replace")
            except Exception:  # noqa: BLE001
                txt = ""
            raise HTTPFailure(e.code, ra, txt) from None

    def complete_json(self, instructions: str, user_text: str, schema: dict, name: str) -> tuple[dict, dict]:
        c = self.cfg
        if c.api == "responses":
            body = {"model": c.model, "instructions": instructions,
                    "input": [{"type": "message", "role": "user", "content": [{"type": "input_text", "text": user_text}]}],
                    "store": False, "max_output_tokens": c.max_output_tokens,
                    "text": {"format": {"type": "json_schema", "name": name, "schema": schema, "strict": True}}}
            if c.effort:
                body["reasoning"] = {"effort": c.effort}
            if c.temperature is not None:
                body["temperature"] = c.temperature
            resp = self._post("/responses", body)
            txt = responses_text(resp)
            u = resp.get("usage") or {}
            usage = {"input_tokens": u.get("input_tokens", 0), "output_tokens": u.get("output_tokens", 0),
                     "reasoning_tokens": (u.get("output_tokens_details") or {}).get("reasoning_tokens", 0),
                     "cached_tokens": (u.get("input_tokens_details") or {}).get("cached_tokens", 0)}
        elif c.api == "chat":
            body = {"model": c.model, "max_tokens": c.max_output_tokens,
                    "messages": [{"role": "system", "content": instructions}, {"role": "user", "content": user_text}],
                    "response_format": {"type": "json_schema", "json_schema": {"name": name, "schema": schema, "strict": True}},
                    "chat_template_kwargs": {"enable_thinking": False}}
            if c.temperature is not None:
                body["temperature"] = c.temperature
            resp = self._post("/chat/completions", body)
            txt = (resp["choices"][0]["message"].get("content") or "")
            u = resp.get("usage") or {}
            usage = {"input_tokens": u.get("prompt_tokens", 0), "output_tokens": u.get("completion_tokens", 0)}
        else:
            raise ValueError(f"MURMUR_TEACHER_API={c.api!r}: expected responses|chat")
        return strict_json(txt, schema), usage


def call_with_retries(teacher, job: dict, max_retries: int, log=sys.stderr) -> dict:
    """job: {"id", "instructions", "input", "schema", "name"}. Never raises except QuotaExhausted."""
    last, attempt, t0 = "", 0, time.time()
    for attempt in range(1, max_retries + 1):
        try:
            obj, usage = teacher.complete_json(job["instructions"], job["input"], job["schema"], job["name"])
            return {"id": job["id"], "ok": True, "result": obj, "error": None, "attempts": attempt, "usage": usage,
                    "latency_s": round(time.time() - t0, 1), "model": teacher.name, "gen": job.get("gen")}
        except QuotaExhausted:
            raise
        except HTTPFailure as e:
            last = str(e)[:300]
            if e.code not in RETRY_CODES or attempt == max_retries:
                break
            wait = e.retry_after if e.retry_after is not None else min(120.0, 2.0 * 2 ** attempt) * (0.5 + random.random())
            if e.code == 429 or e.retry_after is not None:
                getattr(teacher, "gate", Gate()).pause(wait)
            print(f"[teacher] {job['id']}: HTTP {e.code}, retry {attempt} in {wait:.0f}s", file=log, flush=True)
            time.sleep(wait)
        except (TeacherError, urllib.error.URLError, TimeoutError, ConnectionError, OSError, KeyError) as e:
            last = f"{type(e).__name__}: {e}"[:300]
            if attempt == max_retries:
                break
            wait = min(60.0, 2.0 * 2 ** attempt) * (0.5 + random.random())
            print(f"[teacher] {job['id']}: {last[:120]} -> retry {attempt} in {wait:.0f}s", file=log, flush=True)
            time.sleep(wait)
    return {"id": job["id"], "ok": False, "result": None, "error": last, "attempts": attempt, "usage": {},
            "latency_s": round(time.time() - t0, 1), "model": teacher.name}


def _read_shard(path: Path) -> dict[str, dict]:
    done = {}
    if path.exists():
        for line in path.read_text(encoding="utf-8").splitlines():
            try:
                r = json.loads(line)
            except json.JSONDecodeError:
                continue  # torn last line after a kill
            if r.get("ok"):
                done[r["id"]] = r
    return done


def run_sharded(jobs: list[dict], teacher, out_dir, stage: str, shard_size: int = 50, concurrency: int = 2,
                max_retries: int = 5, log=sys.stderr) -> dict:
    """Run jobs into resumable shards (jobs are assigned to shards by position: keep the job list deterministic)."""
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    st = {"jobs": len(jobs), "ok": 0, "failed": 0, "resumed": 0, "requests_ok": 0, "input_tokens": 0,
          "output_tokens": 0, "reasoning_tokens": 0, "latency_s": [], "quota_stop": False}
    t0 = time.time()
    lock = threading.Lock()
    for s in range(0, len(jobs), shard_size):
        shard = out / f"{stage}-{s // shard_size:05d}.jsonl"
        chunk = jobs[s:s + shard_size]
        finished = _read_shard(shard)
        todo = [j for j in chunk if j["id"] not in finished]
        n_res = len(chunk) - len(todo)
        st["ok"] += n_res
        st["resumed"] += n_res
        if not todo:
            continue
        with open(shard, "a", encoding="utf-8") as f, ThreadPoolExecutor(max_workers=max(1, concurrency)) as ex:
            futs = [ex.submit(call_with_retries, teacher, j, max_retries, log) for j in todo]
            for fu in as_completed(futs):
                try:
                    r = fu.result()
                except QuotaExhausted as e:
                    st["quota_stop"] = True
                    print(f"[teacher] {e}; stopping (resume later)", file=log, flush=True)
                    for g in futs:
                        g.cancel()
                    break
                with lock:
                    f.write(json.dumps(r, ensure_ascii=False) + "\n")
                    f.flush()
                st["ok" if r["ok"] else "failed"] += 1
                if r["ok"]:
                    st["requests_ok"] += 1
                    st["latency_s"].append(r["latency_s"])
                for k in ("input_tokens", "output_tokens", "reasoning_tokens"):
                    st[k] += (r.get("usage") or {}).get(k, 0) or 0
        el = time.time() - t0
        print(f"[{stage}] {shard.name}: ok={st['ok']}/{st['jobs']} failed={st['failed']} {el:.0f}s", file=log, flush=True)
        if st["quota_stop"]:
            break
    lat = sorted(st.pop("latency_s"))
    st["wall_s"] = round(time.time() - t0, 1)
    st["latency_p50_s"] = lat[len(lat) // 2] if lat else None
    st["latency_mean_s"] = round(sum(lat) / len(lat), 1) if lat else None
    st["http_requests"] = getattr(getattr(teacher, "gate", None), "requests", None)
    return st


def load_results(out_dir, stage: str) -> dict[str, dict]:
    """job id -> ok result."""
    res = {}
    for p in sorted(Path(out_dir).glob(f"{stage}-*.jsonl")):
        res.update(_read_shard(p))
    return res


def make_teacher(kind: str, max_requests: int = 0):
    if kind == "stub":
        from ml.data.synth.stub_teacher import StubTeacher
        return StubTeacher()
    cfg = TeacherConfig(max_requests=max_requests)
    if kind in ("responses", "chat"):
        cfg.api = kind
    return OpenAITeacher(cfg)
