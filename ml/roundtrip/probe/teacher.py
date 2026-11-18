"""Minimal teacher-LLM client (stdlib only) for the Responses and chat-completions APIs.

Configuration comes only from the environment:
  MURMUR_TEACHER_BASE_URL   e.g. http://127.0.0.1:18899/v1  ("/responses" or "/chat/completions" is appended)
  MURMUR_TEACHER_API_KEY    bearer token; never printed, logged or stored
  MURMUR_TEACHER_MODEL      model name sent in the request
  MURMUR_TEACHER_API        "responses" (OpenAI Responses API, the default) or "chat" (chat
                            completions). The Murmur proxy speaks Responses only
                            (docs/ml/PLAN_V2.md, section Teacher).

Responses request shape (docs/ml/PLAN_V2.md, section Teacher):
  {"model", "instructions": <system>, "input": [{"type": "message", "role": "user",
   "content": [{"type": "input_text", "text": ...}]}, ...], "store": false}
  answer = text of the ``output_text`` parts of ``output[]`` items with type "message".

Transient failures (timeouts, connection errors, HTTP 408/409/425/429/5xx,
unparseable envelopes, empty answers) are retried with exponential backoff,
honouring Retry-After. Other HTTP errors raise ``TeacherError`` at once.
"""
from __future__ import annotations

import json
import os
import socket
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from typing import Callable, Mapping

ENV_BASE_URL = "MURMUR_TEACHER_BASE_URL"
ENV_API_KEY = "MURMUR_TEACHER_API_KEY"
ENV_MODEL = "MURMUR_TEACHER_MODEL"
ENV_API = "MURMUR_TEACHER_API"
APIS = ("responses", "chat")
RETRYABLE_STATUS = frozenset({408, 409, 425, 429, 500, 502, 503, 504})
MAX_RETRY_AFTER_S = 120.0


class TeacherConfigError(RuntimeError):
    """The teacher environment variables are missing or malformed."""


class TeacherError(RuntimeError):
    """The teacher endpoint failed permanently (after retries, or non-retryable)."""


@dataclass(frozen=True)
class Message:
    role: str  # "user" | "assistant"
    text: str


@dataclass(frozen=True)
class TeacherConfig:
    base_url: str
    api_key: str = field(repr=False)
    model: str
    api: str = "responses"
    timeout_s: float = 300.0
    max_retries: int = 4
    backoff_s: float = 2.0

    @property
    def url(self) -> str:
        base = self.base_url.rstrip("/")
        suffix = "/responses" if self.api == "responses" else "/chat/completions"
        return base if base.endswith(suffix) else base + suffix


def config_from_env(env: Mapping[str, str] | None = None, **overrides: object) -> TeacherConfig:
    env = os.environ if env is None else env
    missing = [k for k in (ENV_BASE_URL, ENV_API_KEY, ENV_MODEL) if not env.get(k, "").strip()]
    if missing:
        raise TeacherConfigError(
            "teacher LLM is not configured: set " + ", ".join(missing)
            + " (run the command through tools/with-teacher; see docs/ml/PLAN_V2.md, section Teacher)."
            + " Use --dry-run to inspect prompts without it."
        )
    base = env[ENV_BASE_URL].strip()
    if not base.startswith(("http://", "https://")):
        raise TeacherConfigError(f"{ENV_BASE_URL} must start with http:// or https://")
    api = env.get(ENV_API, "").strip().lower() or "responses"
    if api not in APIS:
        raise TeacherConfigError(f"{ENV_API} must be one of {APIS}, got {api!r}")
    return TeacherConfig(base_url=base, api_key=env[ENV_API_KEY].strip(), model=env[ENV_MODEL].strip(),
                         api=api, **overrides)  # type: ignore[arg-type]


def build_body(config: TeacherConfig, system: str, messages: list[Message],
               temperature: float | None = None) -> dict[str, object]:
    """Request body for the configured API."""
    if config.api == "responses":
        items: list[dict[str, object]] = []
        for m in messages:
            part = "input_text" if m.role == "user" else "output_text"
            items.append({"type": "message", "role": m.role, "content": [{"type": part, "text": m.text}]})
        # temperature is not sent: reasoning models behind the Responses proxy reject it
        return {"model": config.model, "instructions": system, "input": items, "store": False}
    body: dict[str, object] = {
        "model": config.model,
        "messages": [{"role": "system", "content": system}] + [{"role": m.role, "content": m.text} for m in messages],
    }
    if temperature is not None:
        body["temperature"] = temperature
    return body


def parse_answer(api: str, envelope: object) -> str:
    """Assistant text from a response envelope; raises ValueError when absent."""
    if not isinstance(envelope, dict):
        raise ValueError("envelope is not an object")
    if api == "responses":
        texts: list[str] = []
        for item in envelope.get("output") or []:
            if isinstance(item, dict) and item.get("type") == "message":
                for part in item.get("content") or []:
                    if isinstance(part, dict) and part.get("type") in ("output_text", "text"):
                        texts.append(str(part.get("text", "")))
        if not texts and isinstance(envelope.get("output_text"), str):
            texts.append(envelope["output_text"])
        text = "".join(texts)
    else:
        try:
            content = envelope["choices"][0]["message"]["content"]
        except (KeyError, IndexError, TypeError) as exc:
            raise ValueError(f"no choices[0].message.content ({type(exc).__name__})") from None
        if isinstance(content, list):
            content = "".join(p.get("text", "") for p in content if isinstance(p, dict))
        text = content if isinstance(content, str) else ""
    if not text.strip():
        raise ValueError("empty answer")
    return text


@dataclass
class TeacherClient:
    config: TeacherConfig
    sleep: Callable[[float], None] = time.sleep
    log: Callable[[str], None] = lambda msg: None
    requests_sent: int = 0

    def complete(self, system: str, messages: list[Message], temperature: float | None = None) -> str:
        """Return the assistant text for one request, retrying transient failures."""
        body = build_body(self.config, system, messages, temperature)
        attempt = 0
        while True:
            try:
                return self._post(body)
            except _Retryable as exc:
                attempt += 1
                if attempt > self.config.max_retries:
                    raise TeacherError(f"teacher failed after {attempt} attempts: {exc}") from None
                delay = exc.retry_after if exc.retry_after is not None else self.config.backoff_s * 2 ** (attempt - 1)
                self.log(f"teacher: {exc}; retry {attempt}/{self.config.max_retries} in {delay:.1f}s")
                self.sleep(delay)

    def _redact(self, s: str) -> str:
        return s.replace(self.config.api_key, "***") if self.config.api_key else s

    def _post(self, body: dict[str, object]) -> str:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        req = urllib.request.Request(self.config.url, data=data, method="POST", headers={
            "Content-Type": "application/json",
            "Accept": "application/json",
            "Authorization": f"Bearer {self.config.api_key}",
        })
        self.requests_sent += 1
        try:
            with urllib.request.urlopen(req, timeout=self.config.timeout_s) as resp:
                raw = resp.read()
        except urllib.error.HTTPError as exc:
            detail = self._redact(_safe_body(exc))
            if exc.code in RETRYABLE_STATUS:
                raise _Retryable(f"HTTP {exc.code} {detail}", _retry_after(exc)) from None
            raise TeacherError(f"teacher returned HTTP {exc.code}: {detail}") from None
        except (urllib.error.URLError, socket.timeout, TimeoutError, ConnectionError) as exc:
            reason = self._redact(str(getattr(exc, "reason", exc)))
            raise _Retryable(f"connection problem: {reason}", None) from None
        try:
            envelope = json.loads(raw.decode("utf-8"))
            return parse_answer(self.config.api, envelope)
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise _Retryable(f"unparseable response envelope ({type(exc).__name__})", None) from None
        except ValueError as exc:
            raise _Retryable(f"unusable response: {exc}", None) from None


class _Retryable(Exception):
    def __init__(self, msg: str, retry_after: float | None) -> None:
        super().__init__(msg)
        self.retry_after = retry_after


def _retry_after(exc: urllib.error.HTTPError) -> float | None:
    val = exc.headers.get("Retry-After") if exc.headers else None
    try:
        return min(max(float(val), 0.0), MAX_RETRY_AFTER_S) if val is not None else None
    except ValueError:
        return None


def _safe_body(exc: urllib.error.HTTPError) -> str:
    try:
        text = exc.read().decode("utf-8", "replace")
    except Exception:  # noqa: BLE001 - best effort diagnostics only
        return ""
    return " ".join(text.split())[:300]
