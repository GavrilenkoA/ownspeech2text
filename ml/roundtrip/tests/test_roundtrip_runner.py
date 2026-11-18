"""Round-trip runner with tiny fake TTS/ASR drivers: matrix sampling, language routing, resume, measure."""
from __future__ import annotations

import io
import json
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[3]))

import numpy as np  # noqa: E402
import pytest  # noqa: E402

from ml.roundtrip import run_roundtrip as rr  # noqa: E402
from ml.roundtrip.asr.base import AsrDriver, AsrSpec, AsrUnavailable  # noqa: E402
from ml.roundtrip.tts.base import LATIN_NATIVE, LATIN_RESPELL, TtsDriver, VoiceInfo  # noqa: E402

SR = 16000

# ------------------------------------------------------------------------------------------ fakes
SPECS = {
    "multi": AsrSpec("multi", "fake", frozenset({"ru", "en"}), "multi"),
    "ru-only": AsrSpec("ru-only", "fake", frozenset({"ru"}), "ru-only"),
    "en-only": AsrSpec("en-only", "fake", frozenset({"en"}), "en-only"),
    "broken": AsrSpec("broken", "fake", frozenset({"ru", "en"}), "broken"),
}
CALLS = {"tts": 0, "asr": 0}


class FakeTts(TtsDriver):
    """Two systems: 'fa' (ru voice that needs respelling + en voices), 'fb' (one multilingual voice)."""

    def __init__(self, system: str, num_threads: int = 1) -> None:
        super().__init__(num_threads)
        self.system = system
        self._on = False

    def voices(self):
        if self.system == "fa":
            return [VoiceInfo("fa", "ru1", frozenset({"ru"}), "CC0", LATIN_RESPELL),
                    VoiceInfo("fa", "en1", frozenset({"en"}), "CC0", LATIN_NATIVE),
                    VoiceInfo("fa", "en2", frozenset({"en"}), "CC0", LATIN_NATIVE),
                    VoiceInfo("fa", "en3", frozenset({"en"}), "CC0", LATIN_NATIVE)]
        return [VoiceInfo("fb", "m1", frozenset({"ru", "en"}), "MIT", LATIN_NATIVE)]

    def load(self):
        self._on = True

    def unload(self):
        self._on = False

    def _synthesize(self, text, voice, lang, seed):
        CALLS["tts"] += 1
        rng = np.random.default_rng(seed)
        return (0.1 * rng.standard_normal(SR // 2 + 80 * len(text))).astype(np.float32)


class FakeAsr(AsrDriver):
    def __init__(self, spec: AsrSpec, num_threads: int = 1) -> None:
        super().__init__(spec, num_threads)
        self._on = False

    def load(self):
        if self.spec.name == "broken":
            raise AsrUnavailable("broken: cannot load on this machine")
        self._on = True

    def unload(self):
        self._on = False

    @property
    def loaded(self):
        return self._on

    def _transcribe(self, samples, lang_hint):
        CALLS["asr"] += 1
        return f"{self.spec.name}:{lang_hint}:{len(samples)}"


def tts_factory(system, threads):
    return FakeTts(system, threads)


def asr_factory(name, threads):
    return FakeAsr(SPECS[name], threads)


def thread_pool(workers):
    return ThreadPoolExecutor(max_workers=1)


PROBES = [
    {"id": "ru-1", "lang": "ru", "domain": "d", "text": "Задеплоил прод.", "terms": [], "tts_text": None},
    {"id": "en-1", "lang": "en", "domain": "d", "text": "Ship the LoRA.", "terms": [{"term": "LoRA", "category": "ml"}],
     "tts_text": None},
    {"id": "mx-1", "lang": "mixed", "domain": "d", "text": "Залей в Kubernetes.",
     "terms": [{"term": "Kubernetes", "category": "devops"}], "tts_text": "Залей в кубернетес."},
    {"id": "ru-2", "lang": "ru", "domain": "d", "text": "Созвон в десять.", "terms": [], "tts_text": None},
    {"id": "en-2", "lang": "en", "domain": "d", "text": "Merge the PR.", "terms": [], "tts_text": None},
    {"id": "mx-2", "lang": "mixed", "domain": "d", "text": "Открой PR в GitHub.", "terms": [], "tts_text": None},
]


@pytest.fixture()
def probe_file(tmp_path):
    p = tmp_path / "probe.jsonl"
    p.write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in PROBES), encoding="utf-8")
    return p


def _args(probe_file, out, extra=()):
    return rr.parse_args(["--probe", str(probe_file), "--out", str(out), "--workers", "1", "--threads", "1",
                          "--asr-shard", "2", "--tts-shard", "2", *extra])


def _execute(args, models=("multi", "ru-only", "en-only")):
    return rr.execute(args, tts_factory=tts_factory, asr_factory=asr_factory, spec_for=SPECS.__getitem__,
                      pool_factory=thread_pool, systems=["fa", "fb"], models=list(models))


# ---------------------------------------------------------------------------------- unit pieces
def test_load_probes_rejects_duplicates_and_bad_rows(tmp_path):
    p = tmp_path / "p.jsonl"
    p.write_text(json.dumps(PROBES[0]) + "\n" + json.dumps(PROBES[0]) + "\n", encoding="utf-8")
    with pytest.raises(ValueError, match="duplicate"):
        rr.load_probes([p])
    p.write_text(json.dumps({"id": "x", "lang": "de", "text": "Hallo"}) + "\n", encoding="utf-8")
    with pytest.raises(ValueError, match="not a probe"):
        rr.load_probes([p])


def test_take_balanced_round_robins_languages(probe_file):
    probes = rr.load_probes([probe_file])
    got = rr.take_balanced(probes, 3)
    assert sorted(p.lang for p in got) == ["en", "mixed", "ru"]
    assert rr.take_balanced(probes, None) == probes


def test_voice_sampling_is_deterministic_and_language_aware(probe_file):
    probes = {p.id: p for p in rr.load_probes([probe_file])}
    voices = FakeTts("fa").voices()
    a = rr.sample_voices(probes["en-1"], "fa", voices, 2, seed=1)
    b = rr.sample_voices(probes["en-1"], "fa", voices, 2, seed=1)
    assert a == b and len(a) == 2 and all("en" in v.languages for v in a)
    # mixed probes need a Russian voice; only ru1 qualifies
    assert [v.voice for v in rr.sample_voices(probes["mx-1"], "fa", voices, 3, seed=1)] == ["ru1"]
    # different seeds eventually pick different voice pairs
    picks = {tuple(v.voice for v in rr.sample_voices(probes["en-1"], "fa", voices, 2, s)) for s in range(20)}
    assert len(picks) > 1


def test_matrix_uses_tts_text_only_for_respelling_voices(probe_file):
    probes = rr.load_probes([probe_file])
    voices = {"fa": FakeTts("fa").voices(), "fb": FakeTts("fb").voices()}
    utts = rr.build_matrix(probes, voices, 1, ["clean", "phone"], seed=3)
    mx = {(u.tts_system, u.channel): u for u in utts if u.probe.id == "mx-1"}
    assert mx[("fa", "clean")].say == "Залей в кубернетес." and mx[("fa", "clean")].tts_input == "tts_text"
    assert mx[("fb", "clean")].say == "Залей в Kubernetes." and mx[("fb", "clean")].tts_input == "text"
    # mx-2 has no tts_text: even the respelling voice reads the original
    assert {u.tts_input for u in utts if u.probe.id == "mx-2"} == {"text"}
    # channels share one TTS seed, differ in channel seed
    c, p = mx[("fa", "clean")], mx[("fa", "phone")]
    assert c.tts_seed == p.tts_seed and c.seed != p.seed
    assert len(utts) == 6 * 2 * 2  # 6 probes x 2 systems x 1 voice x 2 channels


def test_language_routing():
    probes = [rr.Probe("a", "en", "", "x", (), None), rr.Probe("b", "ru", "", "x", (), None),
              rr.Probe("c", "mixed", "", "x", (), None)]
    utts = [rr.Utterance(p, "fa", "v", "text", "x", "clean", 0, 0) for p in probes]
    jobs = {(u.probe.lang, m) for u, m in rr.asr_jobs(utts, [SPECS["multi"], SPECS["ru-only"], SPECS["en-only"]])}
    assert jobs == {("en", "multi"), ("en", "en-only"), ("ru", "multi"), ("ru", "ru-only"),
                    ("mixed", "multi"), ("mixed", "ru-only")}
    assert rr.lang_hint("mixed") == "ru" and rr.lang_hint("en") == "en"


def test_read_done_tolerates_torn_last_line(tmp_path):
    p = tmp_path / "r.jsonl"
    row = {"probe_id": "a", "tts_system": "s", "tts_voice": "v", "channel": "clean", "asr_model": "m"}
    p.write_text(json.dumps(row) + "\n" + '{"probe_id": "b", "tts_sys', encoding="utf-8")
    assert rr.read_done(p) == {("a", "s", "v", "clean", "m")}


# --------------------------------------------------------------------------------- end to end
def test_run_writes_interface_rows_and_resumes(probe_file, tmp_path):
    out = tmp_path / "out"
    CALLS.update(tts=0, asr=0)
    rep = _execute(_args(probe_file, out, ["--channels", "clean,phone"]))
    rows = [json.loads(line) for line in (out / "roundtrip" / "probe.jsonl").read_text(encoding="utf-8").splitlines()]
    # per utterance: en -> multi + en-only; ru/mixed -> multi + ru-only. 6 probes x 2 systems x 2 channels x 2 models
    assert len(rows) == 6 * 2 * 2 * 2 == rep["jobs"]
    keys = {"probe_id", "lang", "text", "terms", "tts_system", "tts_voice", "channel", "audio", "audio_s",
            "asr_model", "hypothesis", "asr_wall_s"}
    for r in rows:
        assert keys <= set(r)
        assert (out / r["audio"]).exists()
        assert r["asr_model"] != "en-only" or r["lang"] == "en"
        assert r["asr_model"] != "ru-only" or r["lang"] in ("ru", "mixed")
        assert r["hypothesis"].startswith(f"{r['asr_model']}:{'en' if r['lang'] == 'en' else 'ru'}:")
    assert CALLS["tts"] == 12  # clean clip synthesised once, channel variant derived from it
    assert set(rep["tts"]) == {"fa", "fb"} and set(rep["asr"]) == {"multi", "ru-only", "en-only"}

    # resume: nothing left to do, nothing re-synthesised or re-transcribed, no duplicate rows
    CALLS.update(tts=0, asr=0)
    rep2 = _execute(_args(probe_file, out, ["--channels", "clean,phone"]))
    assert rep2["jobs"] == 0 and CALLS == {"tts": 0, "asr": 0}
    assert len((out / "roundtrip" / "probe.jsonl").read_text(encoding="utf-8").splitlines()) == len(rows)

    # an interrupted run: drop half of the rows and one audio file, rerun fills exactly the gap
    lines = (out / "roundtrip" / "probe.jsonl").read_text(encoding="utf-8").splitlines()
    (out / "roundtrip" / "probe.jsonl").write_text("\n".join(lines[: len(lines) // 2]) + "\n", encoding="utf-8")
    victim = json.loads(lines[-1])
    (out / victim["audio"]).unlink()
    CALLS.update(tts=0, asr=0)
    rep3 = _execute(_args(probe_file, out, ["--channels", "clean,phone"]))
    assert rep3["jobs"] == len(lines) - len(lines) // 2 == CALLS["asr"]
    assert CALLS["tts"] == (1 if victim["channel"] == "clean" else 0)
    final = [json.loads(x) for x in (out / "roundtrip" / "probe.jsonl").read_text(encoding="utf-8").splitlines()]
    assert len({rr.result_key(r) for r in final}) == len(final) == len(rows)


def test_unavailable_model_is_reported_not_fatal(probe_file, tmp_path):
    out = tmp_path / "out"
    rep = _execute(_args(probe_file, out, ["--limit", "3"]), models=("multi", "broken"))
    assert any(e.get("asr_model") == "broken" and "cannot load" in e["error"] for e in rep["errors"])
    rows = (out / "roundtrip" / "probe.jsonl").read_text(encoding="utf-8").splitlines()
    assert {json.loads(r)["asr_model"] for r in rows} == {"multi"}


def test_voices_per_sentence_multiplies_english_only(probe_file, tmp_path):
    out = tmp_path / "out"
    rep = _execute(_args(probe_file, out, ["--voices-per-sentence", "2"]), models=("multi",))
    # fa: en probes get 2 of 3 English voices, ru/mixed only the single ru voice; fb has 1 voice
    assert rep["utterances"] == (2 * 2 + 4 * 1) + 6


def test_measure_prints_every_system(probe_file, tmp_path):
    buf = io.StringIO()
    probes = rr.load_probes([probe_file])
    rep = rr.measure(probes, ["fa", "fb"], ["multi", "en-only", "broken"], tmp_path, threads=1, n_per_lang=1,
                     seed=0, tts_factory=tts_factory, asr_factory=asr_factory, spec_for=SPECS.__getitem__,
                     stream=buf)
    text = buf.getvalue()
    for name in ("fa", "fb", "multi", "en-only"):
        assert name in text
    assert "x realtime" in text and "asr:broken: unavailable" in text
    assert rep["tts"]["fa"]["n"] == 3 and rep["tts"]["fa"]["x_realtime"] > 0
    assert rep["asr"]["en-only"]["n"] == 1  # only the English clip is routed to it
    assert json.loads((tmp_path / "measure.json").read_text())["status"]["tts:fb"] == "ok"


def test_bounded_submit_respects_memory_budget_and_worker_count():
    import threading
    import time as _time

    lock = threading.Lock()
    state = {"mem": 0, "peak_mem": 0, "n": 0, "peak_n": 0}
    mem = {"big": 6, "small": 2}

    def job(m):
        with lock:
            state["mem"] += mem[m]
            state["n"] += 1
            state["peak_mem"] = max(state["peak_mem"], state["mem"])
            state["peak_n"] = max(state["peak_n"], state["n"])
        _time.sleep(0.02)
        with lock:
            state["mem"] -= mem[m]
            state["n"] -= 1
        return m

    shards = [("big", [1]), ("big", [2]), ("small", [3]), ("small", [4]), ("small", [5]), ("big", [6])]
    with ThreadPoolExecutor(max_workers=8) as pool:
        got = [(f.result(), m, n) for f, m, n in
               rr._bounded_submit(pool, shards, 3, 10, mem, lambda m, part: (job, m))]
    assert sorted(m for _, m, _ in got) == sorted(m for m, _ in shards)
    assert state["peak_mem"] <= 10 and state["peak_n"] <= 3
    # a model larger than the whole budget still runs (alone)
    with ThreadPoolExecutor(max_workers=2) as pool:
        got = list(rr._bounded_submit(pool, [("big", [1])], 2, 4, mem, lambda m, part: (job, m)))
    assert len(got) == 1 and got[0][0].result() == "big"


def test_registry_carries_memory_estimates():
    from ml.roundtrip.asr import registry as reg

    assert set(reg.MEM_MB) == set(reg.ALL_MODELS)
    assert all(reg.spec_for(n).extra["mem_mb"] == reg.MEM_MB[n] for n in reg.ALL_MODELS)


def test_asr_shards_hold_one_language_hint():
    probes = [rr.Probe(f"{lang}{i}", lang, "", "x", (), None) for lang in ("en", "ru", "mixed") for i in range(5)]
    utts = [rr.Utterance(p, "fa", "v", "text", "x", "clean", 0, 0) for p in probes]
    parts = rr.shard_by_hint(utts, 4)
    assert sum(len(p) for p in parts) == 15 and all(len(p) <= 4 for p in parts)
    assert all(len({rr.lang_hint(u.probe.lang) for u in p}) == 1 for p in parts)


def test_whisper_driver_keeps_one_recogniser(monkeypatch):
    from ml.roundtrip.asr import sherpa_drivers as sd
    from ml.roundtrip.asr.registry import spec_for

    built = []
    drv = sd.SherpaDriver(spec_for("whisper-small@onnx-int8"), 1)
    monkeypatch.setattr(drv, "_build", lambda lang: built.append(lang) or object())
    drv._recogniser("ru"), drv._recogniser("ru"), drv._recogniser("en")
    assert built == ["ru", "en"] and list(drv._rec) == ["en"]
