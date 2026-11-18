"""sherpa-onnx CPU TTS systems: Supertonic 3 (ru + en, 10 voices) and Kokoro v1.0 (English voices).

Archives come from the k2-fsa/sherpa-onnx ``tts-models`` GitHub release and are fetched lazily.

- Supertonic 3 (Supertone; code MIT, model weights BigScience OpenRAIL-M: commercial use allowed
  with the use-based restrictions of its Attachment A; verified from Supertone/supertonic-3 LICENSE
  @ 3cadd1ee and the archive README; sherpa-onnx int8 export): one multilingual model,
  language chosen per call (``GenerationConfig.extra["lang"]``). It is a character-level model, so
  Latin words in a Russian sentence are read with whatever the model learned for Latin letters
  under ``lang=ru``; see docs/ml/ROUNDTRIP.md for what that sounds like. It is treated as
  ``LATIN_NATIVE`` (reads ``text`` as written) because that is exactly the kind of messy
  pronunciation a Russian speaker produces.
- Kokoro v1.0 (hexgrad/Kokoro-82M, Apache-2.0): no Russian voice, used for English only. Text is
  phonemised with espeak-ng (the phonemiser shipped in ``piper-tts``) and passed as phoneme code
  points: the bundled lexicon path skips out-of-vocabulary words such as "Kubernetes" or digits
  (verified: "OOV word skipped"), which would silently delete exactly the terms we probe.
"""
from __future__ import annotations

from typing import Any, List, Optional

import numpy as np

from ..asr.fetch import FetchError, fetch_archive
from .base import LATIN_NATIVE, TtsDriver, TtsUnavailable, VoiceInfo, to_16k

RELEASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"
SUPERTONIC = "sherpa-onnx-supertonic-3-tts-int8-2026-05-11"
KOKORO = "kokoro-multi-lang-v1_0"

# Kokoro v1.0 speaker table (model metadata ``id2speaker``), English voices only.
KOKORO_EN = (
    "af_alloy af_aoede af_bella af_heart af_jessica af_kore af_nicole af_nova af_river af_sarah af_sky "
    "am_adam am_echo am_eric am_fenrir am_liam am_michael am_onyx am_puck am_santa "
    "bf_alice bf_emma bf_isabella bf_lily bm_daniel bm_fable bm_george bm_lewis").split()


def _sherpa():
    try:
        import sherpa_onnx
    except ImportError as exc:  # pragma: no cover - environment problem
        raise TtsUnavailable("sherpa-onnx is not installed in the round-trip venv") from exc
    return sherpa_onnx


def _archive(name: str):
    try:
        return fetch_archive(f"{RELEASE}/{name}.tar.bz2", name)
    except FetchError as exc:
        raise TtsUnavailable(str(exc)) from exc


class SupertonicDriver(TtsDriver):
    system = "supertonic3"
    NUM_SPEAKERS = 10
    NUM_STEPS = 8  # flow-matching steps; upstream example uses 8

    def __init__(self, num_threads: int = 2) -> None:
        super().__init__(num_threads)
        self._tts: Optional[Any] = None

    def voices(self) -> List[VoiceInfo]:
        return [VoiceInfo(system=self.system, voice=f"sid{i}", languages=frozenset({"ru", "en"}),
                          licence="BigScience OpenRAIL-M (model), MIT (code)", latin_in_ru=LATIN_NATIVE, extra={"sid": i})
                for i in range(self.NUM_SPEAKERS)]

    def load(self) -> None:
        if self._tts is not None:
            return
        so = _sherpa()
        d = _archive(SUPERTONIC)
        cfg = so.OfflineTtsConfig(model=so.OfflineTtsModelConfig(
            supertonic=so.OfflineTtsSupertonicModelConfig(
                duration_predictor=str(d / "duration_predictor.int8.onnx"),
                text_encoder=str(d / "text_encoder.int8.onnx"),
                vector_estimator=str(d / "vector_estimator.int8.onnx"),
                vocoder=str(d / "vocoder.int8.onnx"), tts_json=str(d / "tts.json"),
                unicode_indexer=str(d / "unicode_indexer.bin"), voice_style=str(d / "voice.bin")),
            num_threads=self.num_threads, provider="cpu"))
        if not cfg.validate():
            raise TtsUnavailable(f"{self.system}: invalid sherpa-onnx config for {d}")
        self._tts = so.OfflineTts(cfg)

    def unload(self) -> None:
        self._tts = None

    def _synthesize(self, text: str, voice: VoiceInfo, lang: str, seed: int) -> np.ndarray:
        so = _sherpa()
        self.load()
        g = so.GenerationConfig()
        g.sid = voice.extra["sid"]
        g.num_steps = self.NUM_STEPS
        g.extra["lang"] = "en" if lang == "en" else "ru"
        a = self._tts.generate(text, g)
        return to_16k(np.asarray(a.samples, dtype=np.float32), a.sample_rate) if a.samples else \
            np.zeros(0, dtype=np.float32)


class KokoroDriver(TtsDriver):
    system = "kokoro1"

    def __init__(self, num_threads: int = 2) -> None:
        super().__init__(num_threads)
        self._tts: Optional[Any] = None
        self._ph: Optional[Any] = None

    def voices(self) -> List[VoiceInfo]:
        sid = {name: i for i, name in enumerate(KOKORO_EN)}  # af_/am_/bf_/bm_ ids are 0..27
        return [VoiceInfo(system=self.system, voice=n, languages=frozenset({"en"}), licence="Apache-2.0",
                          latin_in_ru=LATIN_NATIVE, gender=n[1],
                          extra={"sid": sid[n], "espeak": "en" if n[0] == "b" else "en-us"})
                for n in KOKORO_EN]

    def load(self) -> None:
        if self._tts is not None:
            return
        so = _sherpa()
        try:
            from piper.phonemize_espeak import EspeakPhonemizer
        except ImportError as exc:
            raise TtsUnavailable("kokoro1 needs the espeak-ng phonemiser from piper-tts") from exc
        d = _archive(KOKORO)
        cfg = so.OfflineTtsConfig(model=so.OfflineTtsModelConfig(
            kokoro=so.OfflineTtsKokoroModelConfig(
                model=str(d / "model.onnx"), voices=str(d / "voices.bin"), tokens=str(d / "tokens.txt"),
                data_dir=str(d / "espeak-ng-data"),
                lexicon=f"{d / 'lexicon-us-en.txt'},{d / 'lexicon-zh.txt'}"),
            num_threads=self.num_threads, provider="cpu"))
        if not cfg.validate():
            raise TtsUnavailable(f"{self.system}: invalid sherpa-onnx config for {d}")
        self._tts = so.OfflineTts(cfg)
        self._ph = EspeakPhonemizer()

    def unload(self) -> None:
        self._tts = None
        self._ph = None

    def _synthesize(self, text: str, voice: VoiceInfo, lang: str, seed: int) -> np.ndarray:
        so = _sherpa()
        self.load()
        sentences = self._ph.phonemize(voice.extra["espeak"], text)
        g = so.GenerationConfig()
        g.sid = voice.extra["sid"]
        g.phoneme_codepoints = [[ord(ch) for ch in "".join(s)] for s in sentences if s]
        a = self._tts.generate("", g)
        return to_16k(np.asarray(a.samples, dtype=np.float32), a.sample_rate) if a.samples else \
            np.zeros(0, dtype=np.float32)
