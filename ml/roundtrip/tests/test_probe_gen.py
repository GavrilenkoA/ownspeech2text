"""gen_probe end to end against a local fake teacher (http.server): request shape, parsing,
retries, the repair turn, validation, rejects, resume, and secret hygiene. No real teacher needed."""
from __future__ import annotations

import json
import re
import sys
import threading
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Callable, Iterator

sys.path.insert(0, str(Path(__file__).resolve().parents[3]))

import pytest  # noqa: E402

from ml.roundtrip.probe import gen_probe  # noqa: E402
from ml.roundtrip.probe.gen_probe import Candidate, Generator, ReplyError, build_candidate_record, parse_reply  # noqa: E402
from ml.roundtrip.probe.lexicon import TermMatcher, load_lexicon  # noqa: E402
from ml.roundtrip.probe.plan import Planner, Slot, SlotTerm  # noqa: E402
from ml.roundtrip.probe.prompt import load_prompt  # noqa: E402
from ml.roundtrip.probe.teacher import (  # noqa: E402
    Message,
    TeacherClient,
    TeacherConfig,
    TeacherConfigError,
    TeacherError,
    config_from_env,
)
from ml.roundtrip.probe.validate_probe import validate_files  # noqa: E402

PROBE = Path(__file__).resolve().parents[1] / "probe"
API_KEY = "sk-test-SECRET-0123456789"

TINY_LEXICON = [
    {"term": "Kubernetes", "category": "devops_infra", "written_forms": ["Kubernetes"], "ru_spoken": ["кубернетес"],
     "en_spoken": [], "notes": ""},
    {"term": "LoRA", "category": "ai_ml_jargon", "written_forms": ["LoRA"], "ru_spoken": ["лора"],
     "en_spoken": [], "notes": ""},
    {"term": "GPU", "category": "acronyms", "written_forms": ["GPU"], "ru_spoken": ["гэпэу", "джи пи ю"],
     "en_spoken": [], "notes": ""},
    {"term": "деплой", "category": "dev_slang_ru", "written_forms": ["деплой"], "ru_spoken": ["деплой"],
     "en_spoken": [], "notes": "deployment"},
    {"term": "инференс", "category": "ai_ml_jargon_ru", "written_forms": ["инференс"], "ru_spoken": ["инференс"],
     "en_spoken": [], "notes": "inference"},
]

FRAMES = {
    "ru": ["Завтра утром проверю {t}, а потом сразу напишу тебе в личку.",
           "Коллеги, сегодня вечером обсуждаем {t} на общей встрече в большой переговорке.",
           "Не забудь записать в заметки, что {t} надо разобрать до пятницы.",
           "Слушай, я всё ещё жду ответа насчёт {t}, напомни им, пожалуйста.",
           "Мне кажется, {t} стоит перенести на следующий спринт, слишком много рисков.",
           "Напоминаю всем: в четверг созвон по теме {t}, начало ровно в 11:00.",
           "Вчера полдня разбирался, почему {t} так тормозит у клиентов из Казани.",
           "Отправь, пожалуйста, Марине короткую сводку про {t} до конца дня."],
    "en": ["Tomorrow morning I will look at {t} and then message you directly.",
           "Team, tonight we discuss {t} at the shared meeting in the big room.",
           "Remember to note that {t} must be sorted out before Friday.",
           "I am still waiting for an answer about {t}, please remind them.",
           "Honestly {t} should move to the next sprint, there are too many risks.",
           "Quick reminder: Thursday call about {t} starts at 11:00 sharp.",
           "Spent half of yesterday figuring out why {t} is so slow for customers.",
           "Please send Marina a short summary on {t} before the end of the day."],
}


def sentence_for(slot: dict, k: int) -> str:
    forms = [t["use_one_of"][0] for t in slot["terms"]]
    lang = "en" if slot["lang"] == "en" else "ru"
    joined = (" and " if lang == "en" else " и ").join(forms)
    return FRAMES[lang][k % len(FRAMES[lang])].format(t=joined)


# ------------------------------------------------------------------ fake teacher
@dataclass
class FakeTeacher:
    """Scripted Responses / chat-completions endpoint. ``script`` items are consumed one per
    request: ``"good"`` answers every slot, ``"garbage"`` answers non-JSON text, ``"drop_terms"``
    answers sentences without the required terms, ``(status, headers)`` answers an HTTP error."""
    script: list[object] = field(default_factory=list)
    requests: list[dict] = field(default_factory=list)
    counter: int = 0
    lock: threading.Lock = field(default_factory=threading.Lock)

    def handle(self, path: str, headers: dict[str, str], body: dict) -> tuple[int, dict[str, str], bytes]:
        with self.lock:
            self.requests.append({"path": path, "headers": headers, "body": body})
            action = self.script.pop(0) if self.script else "good"
            self.counter += 1
            base = self.counter * 3
        if isinstance(action, tuple):
            status, extra = action
            return status, extra, json.dumps({"error": {"message": f"scripted {status}"}}).encode()
        user_text = self.first_user_text(path, body)
        slots = json.loads(re.search(r"Slots:\n(\[.*?\n\])\n", user_text, re.DOTALL).group(1))
        if action == "garbage":
            answer = "Sorry, I cannot produce JSON today."
        else:
            items = []
            for i, slot in enumerate(slots):
                text = sentence_for(slot, base + i)
                if action == "drop_terms":
                    text = FRAMES["en"][i].format(t="the thing") if slot["lang"] == "en" else \
                        FRAMES["ru"][i].format(t="эту штуку")
                items.append({"slot": slot["slot"], "text": text, "tts_text": None, "names": ["Марине", "Marina"]})
            answer = "```json\n" + json.dumps({"sentences": items}, ensure_ascii=False) + "\n```"
        return 200, {}, json.dumps(self.envelope(path, answer), ensure_ascii=False).encode()

    @staticmethod
    def first_user_text(path: str, body: dict) -> str:
        if path.endswith("/responses"):
            return body["input"][0]["content"][0]["text"]
        return next(m["content"] for m in body["messages"] if m["role"] == "user")

    @staticmethod
    def envelope(path: str, answer: str) -> dict:
        if path.endswith("/responses"):
            return {"id": "resp_1", "object": "response", "output": [
                {"type": "reasoning", "summary": []},
                {"type": "message", "role": "assistant", "content": [{"type": "output_text", "text": answer}]}]}
        return {"choices": [{"message": {"role": "assistant", "content": answer}}]}


@pytest.fixture()
def fake() -> Iterator[tuple[FakeTeacher, str]]:
    teacher = FakeTeacher()

    class Handler(BaseHTTPRequestHandler):
        def do_POST(self) -> None:  # noqa: N802
            body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            status, extra, payload = teacher.handle(self.path, dict(self.headers), body)
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            for k, v in extra.items():
                self.send_header(k, v)
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

        def log_message(self, *args: object) -> None:
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield teacher, f"http://127.0.0.1:{server.server_address[1]}/v1"
    finally:
        server.shutdown()
        server.server_close()


@pytest.fixture()
def lexicon(tmp_path: Path) -> Path:
    p = tmp_path / "terms.jsonl"
    p.write_text("".join(json.dumps(e, ensure_ascii=False) + "\n" for e in TINY_LEXICON), encoding="utf-8")
    return p


def teacher_env(monkeypatch: pytest.MonkeyPatch, url: str, api: str = "responses") -> None:
    monkeypatch.setenv("MURMUR_TEACHER_BASE_URL", url)
    monkeypatch.setenv("MURMUR_TEACHER_API_KEY", API_KEY)
    monkeypatch.setenv("MURMUR_TEACHER_MODEL", "fake-model")
    monkeypatch.setenv("MURMUR_TEACHER_API", api)


def run_cli(lexicon: Path, out: Path, *extra: str) -> int:
    return gen_probe.main(["--lexicon", str(lexicon), "--out", str(out), "--existing", "--batch", "3",
                           "--seed", "7", *extra])


def read_jsonl(p: Path) -> list[dict]:
    return [json.loads(line) for line in p.read_text(encoding="utf-8").splitlines() if line.strip()]


# ------------------------------------------------------------------ end to end
def test_generate_responses_api_end_to_end(fake, lexicon: Path, tmp_path: Path,
                                           monkeypatch: pytest.MonkeyPatch, capsys) -> None:
    teacher, url = fake
    teacher_env(monkeypatch, url)
    out = tmp_path / "gen.jsonl"
    assert run_cli(lexicon, out, "--n", "6") == 0

    recs = read_jsonl(out)
    assert len(recs) == 6
    assert len(teacher.requests) == 2
    req = teacher.requests[0]
    assert req["path"] == "/v1/responses"
    assert req["headers"]["Authorization"] == f"Bearer {API_KEY}"
    body = req["body"]
    assert set(body) == {"model", "instructions", "input", "store"} and body["store"] is False
    assert body["model"] == "fake-model" and "probe sentences" in body["instructions"]
    assert body["input"][0]["role"] == "user" and body["input"][0]["content"][0]["type"] == "input_text"

    # every accepted record passes the same validator as the seed set
    rep = validate_files([out], lexicon, min_category_sentences=0)
    assert rep.errors == [], rep.errors
    assert {r["lang"] for r in recs} == {"ru", "en", "mixed"}
    for r in recs:
        assert re.fullmatch(r"gen-(ru|en|mixed)-b0000[01]-s0[0-2]", r["id"])
        if r["lang"] == "mixed":
            assert r["tts_text"] and not re.search("[A-Za-z]", r["tts_text"])
        persons = [t["term"] for t in r["terms"] if t["category"] == "person"]
        assert persons == (["Марине"] if "Марине" in r["text"] else ["Marina"] if "Marina" in r["text"] else [])

    state = json.loads((tmp_path / "gen.jsonl.state.json").read_text())
    assert state["next_batch"] == 2 and state["requests"] == 2 and state["accepted"] == 6 and state["seed"] == 7
    logs = capsys.readouterr()
    for blob in (logs.out, logs.err, out.read_text(), (tmp_path / "gen.jsonl.state.json").read_text()):
        assert API_KEY not in blob


def test_chat_api_shape(fake, lexicon: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    teacher, url = fake
    teacher_env(monkeypatch, url, api="chat")
    assert run_cli(lexicon, tmp_path / "gen.jsonl", "--n", "3", "--temperature", "0.7") == 0
    req = teacher.requests[0]
    assert req["path"] == "/v1/chat/completions"
    assert [m["role"] for m in req["body"]["messages"]] == ["system", "user"]
    assert req["body"]["temperature"] == 0.7


def test_retry_after_429_and_5xx_then_success(fake, lexicon: Path, tmp_path: Path,
                                               monkeypatch: pytest.MonkeyPatch) -> None:
    teacher, url = fake
    teacher.script = [(429, {"Retry-After": "0"}), (503, {"Retry-After": "0"}), "good"]
    teacher_env(monkeypatch, url)
    out = tmp_path / "gen.jsonl"
    assert run_cli(lexicon, out, "--n", "3") == 0
    assert len(teacher.requests) == 3
    assert len(read_jsonl(out)) == 3
    assert json.loads((tmp_path / "gen.jsonl.state.json").read_text())["requests"] == 3


def test_backoff_without_retry_after_and_giving_up(fake) -> None:
    teacher, url = fake
    teacher.script = [(500, {})] * 3
    sleeps: list[float] = []
    cfg = TeacherConfig(base_url=url, api_key=API_KEY, model="m", api="responses", max_retries=2, backoff_s=0.5)
    client = TeacherClient(cfg, sleep=sleeps.append)
    with pytest.raises(TeacherError) as exc:
        client.complete("sys", [Message("user", "Slots:\n[\n]\n")])
    assert sleeps == [0.5, 1.0] and client.requests_sent == 3
    assert API_KEY not in str(exc.value)


def test_retry_after_header_is_honoured(fake) -> None:
    teacher, url = fake
    teacher.script = [(429, {"Retry-After": "7"}), (429, {"Retry-After": "9999"})]
    sleeps: list[float] = []
    client = TeacherClient(TeacherConfig(base_url=url, api_key=API_KEY, model="m", api="responses"),
                           sleep=sleeps.append)
    assert client.complete("sys", [Message("user", "Slots:\n[\n]\n")]).startswith("```json")
    assert sleeps == [7.0, 120.0]  # capped


def test_non_retryable_error_fails_fast(fake) -> None:
    teacher, url = fake
    teacher.script = [(401, {})]
    client = TeacherClient(TeacherConfig(base_url=url, api_key=API_KEY, model="m", api="responses"),
                           sleep=lambda s: None)
    with pytest.raises(TeacherError, match="HTTP 401"):
        client.complete("sys", [Message("user", "x")])
    assert client.requests_sent == 1


def test_unusable_reply_gets_one_repair_turn(fake, lexicon: Path, tmp_path: Path,
                                             monkeypatch: pytest.MonkeyPatch) -> None:
    teacher, url = fake
    teacher.script = ["garbage", "good"]
    teacher_env(monkeypatch, url)
    out = tmp_path / "gen.jsonl"
    assert run_cli(lexicon, out, "--n", "3") == 0
    assert len(teacher.requests) == 2
    repair = teacher.requests[1]["body"]["input"]
    assert [m["role"] for m in repair] == ["user", "assistant", "user"]
    assert repair[1]["content"][0] == {"type": "output_text", "text": "Sorry, I cannot produce JSON today."}
    assert "not valid JSON" in repair[2]["content"][0]["text"]
    assert len(read_jsonl(out)) == 3


def test_sentences_without_required_terms_are_rejected(fake, lexicon: Path, tmp_path: Path,
                                                       monkeypatch: pytest.MonkeyPatch) -> None:
    teacher, url = fake
    teacher.script = ["drop_terms"] * 10
    teacher_env(monkeypatch, url)
    out = tmp_path / "gen.jsonl"
    assert run_cli(lexicon, out, "--n", "3", "--max-requests", "2") == 1  # budget hit, nothing accepted
    rejects = read_jsonl(tmp_path / "gen.jsonl.rejects")
    assert len(rejects) == 6
    assert all(any("required term(s) missing" in r for r in rej["reasons"]) for rej in rejects)
    assert not out.exists() or read_jsonl(out) == []


def test_resume_continues_and_truncates_partial_line(fake, lexicon: Path, tmp_path: Path,
                                                     monkeypatch: pytest.MonkeyPatch) -> None:
    teacher, url = fake
    teacher_env(monkeypatch, url)
    out = tmp_path / "gen.jsonl"
    assert run_cli(lexicon, out, "--n", "3") == 0
    first = read_jsonl(out)
    with open(out, "a", encoding="utf-8") as fh:
        fh.write('{"id": "half-written')  # interrupted mid-line
    (tmp_path / "gen.jsonl.state.json").unlink()  # stale/missing state: batch number comes from the ids
    assert run_cli(lexicon, out, "--n", "6") == 0
    recs = read_jsonl(out)
    assert recs[:3] == first and len(recs) == 6
    assert len({r["id"] for r in recs}) == 6
    assert all("-b00001-" in r["id"] for r in recs[3:])
    assert run_cli(lexicon, out, "--n", "6") == 0  # already complete: no new request
    assert len(teacher.requests) == 2
    assert run_cli(lexicon, out, "--n", "9", "--seed", "8") == 2  # seed is fixed per output file


def test_missing_env_exits_with_clear_message(lexicon: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch,
                                              capsys) -> None:
    for k in ("MURMUR_TEACHER_BASE_URL", "MURMUR_TEACHER_API_KEY", "MURMUR_TEACHER_MODEL", "MURMUR_TEACHER_API"):
        monkeypatch.delenv(k, raising=False)
    assert run_cli(lexicon, tmp_path / "gen.jsonl", "--n", "3") == 2
    assert "MURMUR_TEACHER_BASE_URL" in capsys.readouterr().err
    with pytest.raises(TeacherConfigError, match="MURMUR_TEACHER_API must be one of"):
        config_from_env({"MURMUR_TEACHER_BASE_URL": "http://x", "MURMUR_TEACHER_API_KEY": "k",
                         "MURMUR_TEACHER_MODEL": "m", "MURMUR_TEACHER_API": "grpc"})


def test_dry_run_needs_no_teacher(lexicon: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys) -> None:
    monkeypatch.delenv("MURMUR_TEACHER_BASE_URL", raising=False)
    assert run_cli(lexicon, tmp_path / "gen.jsonl", "--n", "3", "--dry-run") == 0
    out = capsys.readouterr().out
    assert "=== system" in out and '"slot": 0' in out and "use_one_of" in out


# ------------------------------------------------------------------ units
def test_parse_reply_variants() -> None:
    got = parse_reply('```json\n{"sentences": [{"slot": 1, "text": "B"}, {"slot": 0, "text": "A", "tts_text": "",'
                      ' "names": ["X", 3]}, {"slot": 9, "text": "out of range"}, {"slot": 1, "text": "dup"}]}\n```', 2)
    assert got[0] == Candidate(0, "A", None, ("X",)) and got[1].text == "B"
    for bad in ("not json", "[1, 2]", '{"sentences": [{"slot": "0", "text": "A"}]}'):
        with pytest.raises(ReplyError):
            parse_reply(bad, 2)


def test_candidate_needs_lexicon_respelling_or_teacher_tts(lexicon: Path) -> None:
    matcher = TermMatcher(load_lexicon(lexicon))
    slot = Slot(0, 0, "mixed", "messenger", (6, 20), (SlotTerm("LoRA", "ai_ml_jargon", ("LoRA",), ""),))
    text = "Вечером обучил LoRA для Zorblax, завтра покажу результаты команде."
    v = build_candidate_record(slot, Candidate(0, text, None, ()), matcher)
    assert v.record is None and any("Zorblax" in r for r in v.reasons)
    tts = "Вечером обучил лору для зорблакс, завтра покажу результаты команде."
    v = build_candidate_record(slot, Candidate(0, text, tts, ()), matcher)
    assert v.record is not None and v.record["tts_text"] == tts


def test_generator_rejects_duplicates_of_existing(lexicon: Path, tmp_path: Path) -> None:
    matcher = TermMatcher(load_lexicon(lexicon))
    existing = [{"id": "seed-1", "lang": "en", "domain": "notes",
                 "text": "Remember to note that GPU must be sorted out before Friday.",
                 "terms": [{"term": "GPU", "category": "acronyms"}], "tts_text": None}]
    gen = Generator(matcher, Planner(load_lexicon(lexicon), seed=1, batch_size=1),
                    load_prompt(PROBE / "gen_prompt.md"), tmp_path / "g.jsonl", existing)
    slot = Slot(0, 0, "en", "notes", (6, 20), (SlotTerm("GPU", "acronyms", ("GPU",), ""),))
    res = gen_probe.BatchResult(0, [slot], {0: Candidate(0, existing[0]["text"], None, ())}, None, 1)
    assert gen.commit(res) == (0, 1)
    assert "exact duplicate of seed-1" in read_jsonl(tmp_path / "g.jsonl.rejects")[0]["reasons"][0]


def test_shipped_prompt_has_all_blocks() -> None:
    p = load_prompt(PROBE / "gen_prompt.md")
    user = p.render_user(2, "[]", "- example")
    assert "Write 2 sentences" in user and "$" not in user
    assert "boom" in p.render_repair("boom")


def test_candidate_takes_spoken_numbers_from_teacher_and_normalises_apostrophes(lexicon: Path) -> None:
    matcher = TermMatcher(load_lexicon(lexicon))
    slot = Slot(0, 0, "mixed", "work_chat", (6, 20), (SlotTerm("LoRA", "ai_ml_jargon", ("LoRA",), ""),))
    text = "Обучение через LoRA заняло 20 минут, завтра в 11:00 покажу команде."
    tts = "Обучение через лору заняло двадцать минут, завтра в одиннадцать покажу команде."
    v = build_candidate_record(slot, Candidate(0, text, tts, ()), matcher)
    assert v.record is not None, v.reasons
    # the lexicon respelling of LoRA is kept; only the numbers come from the teacher, declined as said
    assert v.record["tts_text"] == "Обучение через лора заняло двадцать минут, завтра в одиннадцать покажу команде."
    en = Slot(1, 0, "en", "standup", (6, 20), (SlotTerm("GPU", "acronyms", ("GPU",), ""),))
    v = build_candidate_record(en, Candidate(1, "Today I’ll check why the GPU node keeps restarting.", None, ()), matcher)
    assert v.record is not None and "’" not in v.record["text"] and "I'll" in v.record["text"]
    # numbers above ten in words break default.v5 rule 4 and are rejected
    v = build_candidate_record(en, Candidate(1, "The GPU node restarted twenty times since Monday morning.", None, ()),
                               matcher)
    assert v.record is None and any("rule 4" in r for r in v.reasons)


def test_teacher_api_defaults_to_responses() -> None:
    from ml.roundtrip.probe.teacher import config_from_env

    cfg = config_from_env({"MURMUR_TEACHER_BASE_URL": "http://127.0.0.1:1/v1", "MURMUR_TEACHER_API_KEY": "k",
                           "MURMUR_TEACHER_MODEL": "m"})
    assert cfg.api == "responses"
