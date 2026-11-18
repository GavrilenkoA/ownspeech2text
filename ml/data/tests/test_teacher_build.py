import json
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

import pytest

from ml.data import generate as G
from ml.data.synth import build as B
from ml.data.synth import filters as F
from ml.data.synth import sampler as S
from ml.data.synth import screen as SCR
from ml.data.synth import teacher as TC
from ml.data.synth.channel import Channel

SCHEMA = {"type": "object", "additionalProperties": False, "required": ["a"], "properties": {"a": {"type": "string"}}}


class _H(BaseHTTPRequestHandler):
    calls = []

    def do_POST(self):  # noqa: N802
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        _H.calls.append((self.path, body, self.headers.get("Authorization")))
        n = len(_H.calls)
        if n == 1:
            self.send_response(429)
            self.send_header("Retry-After", "1")
            self.end_headers()
            self.wfile.write(b"slow down")
            return
        text = "not json" if n == 2 else json.dumps({"a": "кошка"}, ensure_ascii=False)
        resp = {"status": "completed", "output": [{"type": "reasoning"}, {"type": "message", "content": [
            {"type": "output_text", "text": text}]}], "usage": {"input_tokens": 10, "output_tokens": 3}}
        data = json.dumps(resp).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *a):
        pass


@pytest.fixture()
def server(monkeypatch):
    _H.calls = []
    srv = HTTPServer(("127.0.0.1", 0), _H)
    th = threading.Thread(target=srv.serve_forever, daemon=True)
    th.start()
    monkeypatch.setenv("MURMUR_TEACHER_BASE_URL", f"http://127.0.0.1:{srv.server_port}/v1")
    monkeypatch.setenv("MURMUR_TEACHER_API_KEY", "test-key")
    monkeypatch.setenv("MURMUR_TEACHER_API", "responses")
    monkeypatch.setattr(TC.time, "sleep", lambda s: None)
    yield srv
    srv.shutdown()


def test_responses_client_retries_429_and_bad_json(server, tmp_path):
    t = TC.make_teacher("responses")
    job = {"id": "j1", "instructions": "sys", "input": "hi", "schema": SCHEMA, "name": "t"}
    st = TC.run_sharded([job], t, tmp_path, "gen", concurrency=1)
    assert st["ok"] == 1 and st["failed"] == 0
    path, body, auth = _H.calls[-1]
    assert path == "/v1/responses" and auth == "Bearer test-key"
    assert body["instructions"] == "sys" and body["store"] is False and body["reasoning"] == {"effort": "low"}
    assert body["input"][0]["content"][0] == {"type": "input_text", "text": "hi"}
    assert body["text"]["format"]["type"] == "json_schema" and body["text"]["format"]["strict"] is True
    assert len(_H.calls) == 3  # 429, invalid JSON, ok
    res = TC.load_results(tmp_path, "gen")
    assert res["j1"]["result"] == {"a": "кошка"}
    # resumable: a second run sends nothing
    st2 = TC.run_sharded([job], t, tmp_path, "gen", concurrency=1)
    assert st2["resumed"] == 1 and len(_H.calls) == 3


def test_request_cap_stops_cleanly(server, tmp_path):
    t = TC.make_teacher("responses", max_requests=1)
    jobs = [{"id": f"j{i}", "instructions": "s", "input": "x", "schema": SCHEMA, "name": "t"} for i in range(3)]
    st = TC.run_sharded(jobs, t, tmp_path, "gen", concurrency=1)
    assert st["quota_stop"] and st["ok"] == 0


def test_strict_json_rejects_fences_and_schema_errors():
    with pytest.raises(TC.TeacherError):
        TC.strict_json('```json\n{"a": "x"}\n```', SCHEMA)
    with pytest.raises(TC.TeacherError):
        TC.strict_json('{"a": 1}', SCHEMA)
    assert TC.strict_json(' {"a": "x"} ', SCHEMA) == {"a": "x"}


def test_retry_after_http_date():
    assert TC.parse_retry_after("3") == 3.0
    assert TC.parse_retry_after("Wed, 21 Oct 2015 07:28:00 GMT") == 0.0
    assert TC.parse_retry_after(None) is None


def _ctx_sc(category="context_resolution", counterpart=True, lang="ru"):
    for sc in S.plan(300, 0, 0, seed=2):
        if sc["category"] == category and sc["counterpart"] == counterpart and sc["lang"] == lang:
            return sc
    raise AssertionError


def test_context_resolution_and_counterpart():
    sc = _ctx_sc()
    term = sc["spoken_terms"][0]["text"]
    ex = {"sid": sc["sid"], "spoken": f"Слушай, я вчера настроил {term}, вроде всё работает.",
          "target": f"Слушай, я вчера настроил {term}, вроде всё работает.",
          "screen": {"title": "Команда", "messages": [{"author": "Оля", "is_me": False, "text": f"Кто настраивал {term}?"}],
                     "before_cursor": "", "snippets": []}}
    recs = B.build(sc, ex, Channel(None), "test", SCR.Backend("fixture"))
    assert [r["category"] for r in recs] == ["context_resolution", "context_counterpart"]
    pos, cp = recs
    assert term in pos["target"] and term.lower() not in pos["transcript"].lower()
    assert pos["ctx"]["v"] == 2 and SCR.on_screen(pos["ctx"], term)
    assert pos["meta"]["screen"]["spec"]["messages"] and cp["meta"]["screen"]["stripped"] == term
    assert term not in json.dumps(cp["ctx"], ensure_ascii=False) and term not in cp["target"]
    assert cp["transcript"] == pos["transcript"] and cp["split"] == pos["split"]
    assert pos["meta"]["garble"]["form"].lower().split()[0] in cp["target"].lower()
    assert cp["meta"]["check"]["forbid"] == [term]


def test_filters_catch_answers_translation_and_invention():
    sc = _ctx_sc("dictated_question", False, "ru")
    base = {"id": "x", "lang": "ru", "category": "dictated_question", "source": "sim", "split": "train",
            "ctx": {"app": {"label": "Chrome", "category": "messaging"}, "field": {"kind": "message"}},
            "transcript": "как перенести базу на новую версию",
            "meta": {"spoken": "Как перенести базу на новую версию?"}}
    assert F.check(base | {"target": "Как перенести базу на новую версию?"}) == []
    assert "question_without_mark" in F.check(base | {"target": "Как перенести базу на новую версию."})
    assert "novel_content" in F.check(base | {"target": "Как перенести базу данных PostgreSQL на новую версию?"})
    assert "cyrillic_in_en" not in F.check(base | {"target": "How to move the database to a new version?"})
    assert "ru_not_cyrillic" in F.check(base | {"target": "How to move the database to a new version?"})
    assert "dropped_content" in F.check(base | {"target": "Как перенести базу?"})
    assert "preamble" in F.check(base | {"target": "Конечно, вот ответ: как перенести базу на новую версию?"})
    assert sc  # plan has the category


def test_stub_pipeline_end_to_end(tmp_path):
    import argparse
    a = argparse.Namespace(out=str(tmp_path), n=120, dev=20, test=20, seed=1, teacher="stub", mined=None, batch=6,
                           overgen=1.15, concurrency=2, max_requests=0, max_retries=2, shard_size=10, limit=0, topup=False,
                           screens="fixture", allow_fixture=True, workers=1)
    st = G.run(a)
    assert st["final"] > 40
    assert not st["leakage"]["group_overlap"] and not st["leakage"]["entity_overlap_train_test"]
    from ml.common.schema import validate_file
    for split in ("train", "dev", "test"):
        v = validate_file(tmp_path / f"{split}.jsonl", strict=True)
        assert v["invalid"] == 0, v["errors"][:3]
