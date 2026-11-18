"""Driver plumbing without model downloads: channel presets, registries, routing, reference voices,
GPU drivers in dry-run mode (fake model objects)."""
from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[3]))

import numpy as np  # noqa: E402
import pytest  # noqa: E402

from ml.roundtrip import channel  # noqa: E402
from ml.roundtrip.asr import registry as asr_reg  # noqa: E402
from ml.roundtrip.asr.base import AsrUnavailable, UnavailableDriver  # noqa: E402
from ml.roundtrip.tts import registry as tts_reg  # noqa: E402
from ml.roundtrip.tts.base import LATIN_NATIVE, LATIN_RESPELL, TtsUnavailable, VoiceInfo, choose_tts_input, to_16k  # noqa: E402
from ml.roundtrip.tts.gpu_tts import ChatterboxDriver, Qwen3TtsDriver  # noqa: E402
from ml.roundtrip.tts.refvoices import licence_ok, load_ref_voices  # noqa: E402

SR = 16000


def tone(f=440.0, s=1.0):
    t = np.arange(int(SR * s)) / SR
    return (0.3 * np.sin(2 * np.pi * f * t)).astype(np.float32)


# ---------------------------------------------------------------------------------------- channel
def test_clean_is_identity():
    x = tone()
    assert np.array_equal(channel.apply_channel(x, "clean"), x)


@pytest.mark.parametrize("preset", [p for p in channel.PRESETS if p != "clean"])
def test_presets_deterministic_bounded_and_seeded(preset):
    x = tone(s=2.0)
    a = channel.apply_channel(x, preset, seed=7)
    b = channel.apply_channel(x, preset, seed=7)
    c = channel.apply_channel(x, preset, seed=8)
    assert np.array_equal(a, b) and not np.array_equal(a, c)
    assert a.dtype == np.float32 and a.shape == x.shape and np.max(np.abs(a)) <= 0.951


def test_add_noise_hits_requested_snr():
    rng = np.random.default_rng(0)
    x = tone(s=3.0)
    y = channel.add_noise(x, 12.0, "pink", rng)
    snr = 10 * np.log10(np.mean(x.astype(np.float64) ** 2) / np.mean((y - x).astype(np.float64) ** 2))
    assert abs(snr - 12.0) < 0.1


def test_band_limit_removes_out_of_band_energy():
    low = channel.band_limit(tone(50.0), 200.0, 5000.0)
    mid = channel.band_limit(tone(1000.0), 200.0, 5000.0)
    assert np.sqrt(np.mean(low[SR // 2:] ** 2)) < 0.05 * np.sqrt(np.mean(mid[SR // 2:] ** 2))


def test_unknown_preset():
    with pytest.raises(KeyError):
        channel.apply_channel(tone(), "telephone-1950")


# ------------------------------------------------------------------------------------ TTS helpers
def test_choose_tts_input():
    ru = VoiceInfo("s", "r", frozenset({"ru"}), "CC0", LATIN_RESPELL)
    multi = VoiceInfo("s", "m", frozenset({"ru", "en"}), "MIT", LATIN_NATIVE)
    assert choose_tts_input("в Kubernetes", "в кубернетес", "mixed", ru) == ("в кубернетес", "tts_text")
    assert choose_tts_input("в Kubernetes", "в кубернетес", "mixed", multi) == ("в Kubernetes", "text")
    assert choose_tts_input("в Kubernetes", None, "mixed", ru) == ("в Kubernetes", "text")
    assert ru.speaks("mixed") and not ru.speaks("en") and multi.speaks("en")


def test_choose_tts_input_en_and_ru_read_tts_text():
    en = VoiceInfo("s", "e", frozenset({"en"}), "CC0", LATIN_NATIVE, reads_digits=frozenset({"en"}))
    ru = VoiceInfo("s", "r", frozenset({"ru"}), "CC0", LATIN_RESPELL, reads_digits=frozenset({"ru"}))
    assert choose_tts_input("Run kubectl get pods.", "Run kube control get pods.", "en", en) == \
        ("Run kube control get pods.", "tts_text")
    assert choose_tts_input("Буду через 20 минут.", "Буду через двадцать минут.", "ru", ru) == \
        ("Буду через двадцать минут.", "tts_text")
    assert choose_tts_input("Run the tests.", None, "en", en) == ("Run the tests.", "text")


def test_choose_tts_input_spells_digits_for_voices_that_cannot_read_them():
    # Supertonic-like: Latin terms native, digits read in English only
    sup = VoiceInfo("s", "x", frozenset({"ru", "en"}), "MIT", LATIN_NATIVE, reads_digits=frozenset({"en"}))
    say, src = choose_tts_input("Обучение через LoRA заняло 20 минут, это 80%.",
                                "Обучение через лора заняло двадцать минут, это восемьдесят процентов.", "mixed", sup)
    assert (say, src) == ("Обучение через LoRA заняло двадцать минут, это восемьдесят процентов.", "text_numbers")
    # no tts_text: digits are spelled in the nominative; a number after a Latin name is part of the name
    say, src = choose_tts_input("Взял RTX 5090 за 3 дня до 11:30.", None, "mixed", sup)
    assert (say, src) == ("Взял RTX 5090 за три дня до одиннадцать тридцать.", "text_numbers")
    # English digits are read by the engine itself
    assert choose_tts_input("Meet at 9:30.", None, "en", sup) == ("Meet at 9:30.", "text")


def test_to_16k_resamples_and_normalises():
    x = 0.01 * np.ones(22050, dtype=np.float32)
    y = to_16k(x, 22050)
    assert len(y) == 16000 and abs(float(np.max(np.abs(y))) - 0.9) < 1e-3


def test_tts_registry_lists_cpu_voices_without_loading():
    assert set(tts_reg.CPU_SYSTEMS) == {"piper", "supertonic3", "kokoro1"}
    assert set(tts_reg.GPU_SYSTEMS) == {"qwen3tts-1.7b", "chatterbox-mtl"}
    for s in tts_reg.CPU_SYSTEMS:
        vs = tts_reg.get_tts(s).voices()
        assert len(vs) >= 5 and len({v.voice for v in vs}) == len(vs)
        assert all("NC" not in v.licence for v in vs)
    langs = {lang for s in ("piper", "supertonic3") for v in tts_reg.get_tts(s).voices() for lang in v.languages}
    assert langs == {"ru", "en"}
    with pytest.raises(KeyError):
        tts_reg.resolve_systems("piper,espeak")


# ----------------------------------------------------------------------------------- ASR registry
def test_asr_registry_resolves_every_model_from_catalog():
    names = asr_reg.resolve_models("all")
    assert len(names) == 12
    # whisper-small now resolves from its catalog entry; only medium and the English Nemotron are off-catalog
    assert asr_reg.spec_for("whisper-small@onnx-int8").catalog_id == "whisper-small@onnx-int8"
    assert asr_reg.spec_for("whisper-medium@onnx-int8").catalog_id is None
    nem_en = asr_reg.spec_for("nemotron-speech-streaming-en-0.6b@onnx-int8-560ms")
    assert nem_en.languages == frozenset({"en"}) and nem_en.extra["catalog_ref"].endswith("@qnn-sm8750-560ms")
    assert all(asr_reg.spec_for(n).extra["xrt"] > 0 for n in names)
    for n in names:
        spec = asr_reg.spec_for(n)
        assert not spec.notes.startswith("UNRESOLVED"), spec.notes
        files = spec.extra["files"]
        assert files and all(len(f["revision"]) == 40 for f in files)
    assert asr_reg.spec_for("distil-large-v3.5@onnx-int8").languages == frozenset({"en"})
    assert asr_reg.spec_for("t-one@onnx").languages == frozenset({"ru"})


def test_asr_selector_and_routing():
    assert asr_reg.resolve_models("gigaam-v3-e2e-ctc,distil") == ["gigaam-v3-e2e-ctc@onnx-int8",
                                                                  "distil-large-v3.5@onnx-int8"]
    with pytest.raises(KeyError):
        asr_reg.resolve_models("whisper")  # ambiguous substring
    giga, distil, qwen = (asr_reg.spec_for(n) for n in ("gigaam-v3-e2e-rnnt@onnx-int8", "distil-large-v3.5@onnx-int8",
                                                        "qwen3-asr-0.6b@onnx-int8"))
    assert giga.accepts("mixed") and not giga.accepts("en")
    assert distil.accepts("en") and not distil.accepts("mixed") and not distil.accepts("ru")
    assert all(qwen.accepts(lang) for lang in ("ru", "en", "mixed"))


def test_unavailable_driver_keeps_reason():
    d = UnavailableDriver(asr_reg.spec_for("t-one@onnx"), "needs 8 kHz input")
    with pytest.raises(AsrUnavailable, match="needs 8 kHz"):
        d.transcribe(np.zeros(16000, dtype=np.float32), "ru")


# ------------------------------------------------------------------------- reference voices / GPU
@pytest.mark.parametrize("lic,ok", [("CC0", True), ("CC0-1.0", True), ("CC BY 4.0", True), ("public domain", True),
                                    ("CC-BY-NC-4.0", False), ("CC BY-SA 4.0", False), ("unknown", False)])
def test_licence_filter(lic, ok):
    assert licence_ok(lic) is ok


@pytest.fixture()
def ref_dir(tmp_path):
    import soundfile as sf

    sf.write(str(tmp_path / "ru_f.wav"), tone(s=3.0), SR)
    sf.write(str(tmp_path / "en_m.wav"), tone(s=4.0), SR)
    sf.write(str(tmp_path / "nc.wav"), tone(s=3.0), SR)
    sf.write(str(tmp_path / "short.wav"), tone(s=0.5), SR)
    rows = [{"file": "ru_f.wav", "text": "Привет.", "lang": "ru", "licence": "CC0-1.0"},
            {"file": "en_m.wav", "text": "Hello.", "lang": "en", "licence": "CC-BY-4.0"},
            {"file": "nc.wav", "text": "x", "lang": "en", "licence": "CC-BY-NC-4.0"},
            {"file": "short.wav", "text": "x", "lang": "en", "licence": "CC0"},
            {"file": "../etc/passwd", "text": "x", "lang": "en", "licence": "CC0"}]
    (tmp_path / "manifest.jsonl").write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows),
                                             encoding="utf-8")
    return tmp_path


def test_ref_voice_loader(ref_dir):
    ok, rejected = load_ref_voices(ref_dir)
    assert sorted(v.voice_id for v in ok) == ["en_m", "ru_f"]
    assert len(rejected) == 3 and any("licence" in r for r in rejected) and any("duration" in r for r in rejected)


def test_gpu_drivers_without_voices_or_packages(ref_dir):
    assert Qwen3TtsDriver(ref_dir=None).voices() == []
    with pytest.raises(TtsUnavailable, match="no reference voices"):
        ChatterboxDriver(ref_dir=None).load()
    d = Qwen3TtsDriver(ref_dir=str(ref_dir))
    assert {v.voice for v in d.voices()} == {"en_m", "ru_f"}
    try:
        import qwen_tts  # noqa: F401
    except ImportError:
        with pytest.raises(TtsUnavailable, match="qwen-tts"):
            d.load()


class _FakeQwenModel:
    """Implements the two Qwen3TTSModel methods the driver uses (shapes per the model card)."""

    def __init__(self):
        self.calls = []

    def create_voice_clone_prompt(self, ref_audio, ref_text, x_vector_only_mode):
        self.prompts = getattr(self, "prompts", 0) + 1
        return [("prompt", Path(ref_audio).stem)]

    def generate_voice_clone(self, text, language, voice_clone_prompt):
        assert len(text) == len(language) == len(voice_clone_prompt)
        self.calls.append((list(text), list(language), [p[1] for p in voice_clone_prompt]))
        return [np.full(24000 * (1 + len(t) % 3), 0.1, dtype=np.float32) for t in text], 24000


def test_qwen3_batch_one_call_one_prompt_per_text(ref_dir):
    d = Qwen3TtsDriver(ref_dir=str(ref_dir))
    d._model = _FakeQwenModel()
    items = [("один", "ru_f", "ru", 1), ("two", "en_m", "en", 1), ("три Kubernetes", "ru_f", "mixed", 1)]
    out = d.synthesize_batch(items)
    assert [len(x) for x in out] == [16000 * (1 + len(t) % 3) for t, *_ in items]
    assert d._model.calls == [(["один", "two", "три Kubernetes"], ["Russian", "English", "Russian"],
                               ["ru_f", "en_m", "ru_f"])]
    assert d._model.prompts == 2  # clone prompts are cached per voice
    with pytest.raises(KeyError, match="unknown voice"):
        d.synthesize_batch([("x", "nobody", "ru", 0)])


def test_chatterbox_dry_run_pins_revision_and_uses_cfg_rule(ref_dir, monkeypatch):
    import types

    import huggingface_hub

    from ml.roundtrip.tts import gpu_tts

    seen = {}

    class _Wav:
        def __init__(self, n):
            self.a = np.full((1, n), 0.2, dtype=np.float32)

        def detach(self):
            return self

        def float(self):
            return self

        def cpu(self):
            return self

        def numpy(self):
            return self.a

    class FakeMtl:
        sr = 24000

        @classmethod
        def from_local(cls, ckpt_dir, device):
            seen["load"] = (ckpt_dir, device)
            return cls()

        def generate(self, text, language_id, audio_prompt_path=None, exaggeration=0.5, cfg_weight=0.5):
            seen.setdefault("gen", []).append((language_id, Path(audio_prompt_path).stem, cfg_weight))
            return _Wav(24000)

    def fake_snapshot(repo, revision=None, allow_patterns=None):
        seen["snap"] = (repo, revision, tuple(allow_patterns))
        return "/fake/ckpt"

    mod = types.ModuleType("chatterbox.mtl_tts")
    mod.ChatterboxMultilingualTTS = FakeMtl
    monkeypatch.setitem(sys.modules, "chatterbox", types.ModuleType("chatterbox"))
    monkeypatch.setitem(sys.modules, "chatterbox.mtl_tts", mod)
    monkeypatch.setattr(huggingface_hub, "snapshot_download", fake_snapshot)
    d = ChatterboxDriver(ref_dir=str(ref_dir), device="cuda:0")
    x = d.synthesize("Привет, Kubernetes.", "ru_f", "mixed", seed=3)
    y = d.synthesize("Hello there.", "ru_f", "en", seed=3)
    assert len(x) == len(y) == 16000
    assert seen["snap"] == (gpu_tts.CHATTERBOX_MODEL, gpu_tts.CHATTERBOX_REVISION, tuple(gpu_tts.CHATTERBOX_FILES))
    assert seen["load"] == ("/fake/ckpt", "cuda")
    # same-language reference keeps cfg_weight, cross-language cloning sets it to 0 (README advice)
    assert seen["gen"] == [("ru", "ru_f", 0.5), ("en", "ru_f", 0.0)]


def test_channel_stats_sees_band_limit_noise_and_clipping(tmp_path, capsys):
    rng = np.random.default_rng(0)
    speechy = np.concatenate([0.3 * rng.standard_normal(SR), np.zeros(SR // 2)]).astype(np.float32)  # white burst + pause
    clean = channel.audio_stats(speechy)
    phone = channel.audio_stats(channel.apply_channel(speechy, "far", seed=1))
    assert phone["hf_db"] < clean["hf_db"] - 10 and phone["lf_db"] < clean["lf_db"] - 10
    assert phone["rolloff99_hz"] < clean["rolloff99_hz"]
    assert clean["snr_db"] > 60 > phone["snr_db"] > 5
    assert channel.audio_stats(np.clip(speechy * 10, -1, 1))["clip_ratio"] > 0.4
    import soundfile as sf

    f = tmp_path / "a.wav"
    sf.write(f, speechy, SR)
    assert channel.main(["stats", str(f), "--preset", "phone"]) == 0
    row = json.loads(capsys.readouterr().out.strip())
    assert row["preset"] == "phone" and row["duration_s"] == 1.5
