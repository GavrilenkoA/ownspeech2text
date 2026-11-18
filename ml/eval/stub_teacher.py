#!/usr/bin/env python3
"""Deterministic stub of the teacher endpoint, for testing plumbing without quota or a GPU.

Endpoints: GET /health, GET /v1/models, POST /v1/responses (the shape of the dev-server teacher,
docs/ml/PLAN_V2.md), POST /v1/chat/completions, POST /v1/completions.
Requests whose system text / instructions look like the judge rubric get {"score": 4, "drift": false};
any other request gets "stub". --fail-first N answers the first N POSTs with 429 + Retry-After: 0
(exercises the client's retry path).

  python ml/eval/stub_teacher.py --port 0 --port-file /tmp/port   # 0 = pick a free port
"""
from __future__ import annotations

import argparse
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

MODEL = "stub-teacher"


class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _send(self, obj, code=200):
        b = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def do_GET(self):
        if self.path in ("/health", "/v1/health"):
            return self._send({"status": "ok"})
        if self.path == "/v1/models":
            return self._send({"object": "list", "data": [{"id": MODEL, "object": "model"}]})
        self._send({"error": "not found"}, 404)

    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0))
        req = json.loads(self.rfile.read(n) or b"{}")
        usage = {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}
        if self.server.fail_left > 0:
            self.server.fail_left -= 1
            b = b'{"error": "rate limited"}'
            self.send_response(429)
            self.send_header("Retry-After", "0")
            self.send_header("Content-Length", str(len(b)))
            self.end_headers()
            self.wfile.write(b)
            return
        if self.path == "/v1/responses":
            if not isinstance(req.get("input"), list) or "instructions" not in req:
                return self._send({"error": "bad request"}, 400)  # same rejection as the real proxy
            judge = "You grade a dictation editor" in req["instructions"]
            text = json.dumps({"score": 4, "drift": False, "reason": "stub"}) if judge else "stub"
            return self._send({"id": "stub", "object": "response", "model": req.get("model", MODEL),
                               "output": [{"type": "reasoning", "summary": []},
                                          {"type": "message", "role": "assistant",
                                           "content": [{"type": "output_text", "text": text}]}],
                               "usage": {"input_tokens": 1, "output_tokens": 1}})
        if self.path == "/v1/chat/completions":
            msgs = req.get("messages", [])
            sys_txt = next((m["content"] for m in msgs if m["role"] == "system"), "")
            if "You grade a dictation editor" in sys_txt:
                content = json.dumps({"score": 4, "drift": False, "reason": "stub"})
            else:
                content = "stub"
            return self._send({"id": "stub", "object": "chat.completion", "model": req.get("model", MODEL),
                               "choices": [{"index": 0, "finish_reason": "stop",
                                            "message": {"role": "assistant", "content": content}}], "usage": usage})
        if self.path == "/v1/completions":
            return self._send({"id": "stub", "object": "text_completion", "model": req.get("model", MODEL),
                               "choices": [{"index": 0, "text": "stub", "finish_reason": "stop"}], "usage": usage})
        self._send({"error": "not found"}, 404)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=0)
    ap.add_argument("--port-file")
    ap.add_argument("--fail-first", type=int, default=0)
    a = ap.parse_args()
    srv = ThreadingHTTPServer((a.host, a.port), H)
    srv.fail_left = a.fail_first
    if a.port_file:
        Path(a.port_file).write_text(str(srv.server_address[1]))
    print(f"stub teacher on http://{a.host}:{srv.server_address[1]}/v1", flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
