"""Piper (VITS) voices on CPU through the ``piper-tts`` package (espeak-ng phonemiser built in).

Voices come from ``rhasspy/piper-voices`` pinned to one commit. Only voices whose MODEL_CARD
licence allows commercial use are registered (see ``REJECTED`` and docs/ml/ROUNDTRIP.md).

Latin words in Russian sentences: espeak-ng's Russian phonemiser switches to English phonemes for
Latin-script words (e.g. "Kubernetes" -> k u: b @ n E t i: z). The Russian Piper voices were
never trained on those phonemes, so the result is badly garbled. These voices therefore read the
probe's ``tts_text`` (Cyrillic respelling) for ru/mixed probes when one exists.

``piper-tts`` is GPL-3.0-or-later; it is a build-time tool on the server and is never shipped.
"""
from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Dict, List, Optional

import numpy as np

from ..asr.fetch import FetchError, HfFile, fetch_hf
from .base import LATIN_RESPELL, LATIN_NATIVE, TtsDriver, TtsUnavailable, VoiceInfo, to_16k

REPO = "rhasspy/piper-voices"
REVISION = "0ca208afc2a2ee102b1e961e72e0a7e4907be3d0"


@dataclass(frozen=True)
class _PiperModel:
    path: str  # directory inside the repo, e.g. "ru/ru_RU/denis/medium"
    stem: str  # file stem, e.g. "ru_RU-denis-medium"
    lang: str
    licence: str
    gender: str
    speakers: tuple = (None,)  # speaker ids for multi-speaker models


_MODELS: Dict[str, _PiperModel] = {
    m.stem: m
    for m in (
        _PiperModel("ru/ru_RU/denis/medium", "ru_RU-denis-medium", "ru", "CC0 (dataset)", "m"),
        _PiperModel("ru/ru_RU/dmitri/medium", "ru_RU-dmitri-medium", "ru", "CC0 (dataset)", "m"),
        _PiperModel("en/en_US/joe/medium", "en_US-joe-medium", "en", "CC0 (dataset)", "m"),
        _PiperModel("en/en_US/john/medium", "en_US-john-medium", "en", "public domain (LibriVox)", "m"),
        _PiperModel("en/en_US/kristin/medium", "en_US-kristin-medium", "en", "public domain (LibriVox)", "f"),
        _PiperModel("en/en_US/norman/medium", "en_US-norman-medium", "en", "public domain (LibriVox)", "m"),
        _PiperModel("en/en_GB/alba/medium", "en_GB-alba-medium", "en", "CC-BY-4.0", "f"),
        _PiperModel("en/en_GB/cori/medium", "en_GB-cori-medium", "en", "CC-BY-4.0 (NVIDIA Hi-Fi TTS)", "f"),
        _PiperModel("en/en_US/libritts_r/medium", "en_US-libritts_r-medium", "en", "CC-BY-4.0 (LibriTTS-R)", "",
                    speakers=(0, 117, 263, 509, 771)),
    )
}

# Voices looked at and not used, with the reason (from each voice's MODEL_CARD at REVISION).
REJECTED: Dict[str, str] = {
    "ru_RU-ruslan-medium": "dataset licence CC BY-NC-SA 4.0 (non-commercial)",
    "ru_RU-irina-medium": "dataset licence 'Unknown' (RHVoice)",
    "en_US-ryan-*": "dataset licence CC BY-NC-SA 4.0 (non-commercial)",
    "en_US-hfc_female/hfc_male-medium": "dataset licence CC BY-NC-SA 4.0 (non-commercial)",
    "en_US-lessac-*": "Blizzard 2013 Lessac licence (research/non-commercial terms)",
    "en_US-amy-*, en_GB-alan-*, en_GB-jenny_dioco-*": "MODEL_CARD says only 'See URL'; licence not verifiable",
    "en_GB-northern_english_male-medium": "CC-BY-SA 4.0 (share-alike) - avoided to keep outputs unencumbered",
}


def _voice_id(stem: str, speaker: Optional[int]) -> str:
    return stem if speaker is None else f"{stem}#{speaker}"


class PiperDriver(TtsDriver):
    system = "piper"

    def __init__(self, num_threads: int = 2) -> None:
        super().__init__(num_threads)
        self._loaded: Dict[str, object] = {}  # stem -> PiperVoice

    def voices(self) -> List[VoiceInfo]:
        out = []
        for m in _MODELS.values():
            for spk in m.speakers:
                out.append(VoiceInfo(
                    system=self.system, voice=_voice_id(m.stem, spk), languages=frozenset({m.lang}),
                    licence=m.licence, latin_in_ru=LATIN_RESPELL if m.lang == "ru" else LATIN_NATIVE,
                    gender=m.gender, extra={"stem": m.stem, "speaker": spk}))
        return out

    def load(self) -> None:
        """Models load per voice on first use (each is a separate ONNX file)."""
        try:
            import piper  # noqa: F401
            import onnxruntime  # noqa: F401
        except ImportError as exc:
            raise TtsUnavailable("piper-tts / onnxruntime not installed in the round-trip venv") from exc

    def unload(self) -> None:
        self._loaded.clear()

    def prepare(self, voice: str) -> None:
        self._model(self.voice(voice).extra["stem"])

    def _model(self, stem: str):
        pv = self._loaded.get(stem)
        if pv is not None:
            return pv
        self.load()
        import onnxruntime as ort
        from piper import PiperVoice
        from piper.config import PiperConfig

        m = _MODELS[stem]
        try:
            d = fetch_hf([HfFile(REPO, f"{m.path}/{m.stem}.onnx", REVISION),
                          HfFile(REPO, f"{m.path}/{m.stem}.onnx.json", REVISION)])
        except FetchError as exc:
            raise TtsUnavailable(str(exc)) from exc
        opts = ort.SessionOptions()
        opts.intra_op_num_threads = self.num_threads
        opts.inter_op_num_threads = 1
        sess = ort.InferenceSession(str(d / m.path / f"{m.stem}.onnx"), sess_options=opts,
                                    providers=["CPUExecutionProvider"])
        with open(d / m.path / f"{m.stem}.onnx.json", encoding="utf-8") as fh:
            cfg = PiperConfig.from_dict(json.load(fh))
        pv = PiperVoice(config=cfg, session=sess)
        self._loaded[stem] = pv
        return pv

    def _synthesize(self, text: str, voice: VoiceInfo, lang: str, seed: int) -> np.ndarray:
        from piper.config import SynthesisConfig

        pv = self._model(voice.extra["stem"])
        # VITS sampling noise comes from onnxruntime's RNG inside the graph (not seedable from here);
        # outputs are close to deterministic but not bit-exact across runs.
        cfg = SynthesisConfig(speaker_id=voice.extra["speaker"])
        chunks = list(pv.synthesize(text, cfg))
        if not chunks:
            return np.zeros(0, dtype=np.float32)
        audio = np.concatenate([c.audio_float_array for c in chunks])
        return to_16k(audio, chunks[0].sample_rate)
