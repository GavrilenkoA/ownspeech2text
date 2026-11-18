"""TTS driver interface used by the round-trip runner.

A TTS system turns probe text into 16 kHz mono float32 PCM. TTS is inference-only here: a source of
realistic acoustic variety for the ASR stage, never a product component.

Mixed ru+en sentences: each voice declares how it treats Latin words inside a Russian sentence
(``latin_in_ru``). The runner uses that to pick between the probe's ``text`` and its ``tts_text``
(a Cyrillic respelling of the Latin terms) -- see :func:`choose_tts_input`.
"""
from __future__ import annotations

import abc
from dataclasses import dataclass, field
from typing import FrozenSet, List, Optional, Sequence, Tuple

import numpy as np

from ..asr.audio import SAMPLE_RATE, resample, to_mono_float

# How a voice handles Latin-script words in a Russian sentence.
LATIN_NATIVE = "native"  # reads them acceptably (language switching or multilingual model)
LATIN_RESPELL = "respell"  # cannot: feed the Cyrillic tts_text when the probe has one
LATIN_POLICIES = (LATIN_NATIVE, LATIN_RESPELL)


@dataclass(frozen=True)
class VoiceInfo:
    """One selectable voice of a TTS system."""

    system: str
    voice: str  # stable id, unique within the system
    languages: FrozenSet[str]  # subset of {"ru", "en"}
    licence: str
    latin_in_ru: str = LATIN_RESPELL
    gender: str = ""  # "f", "m" or "" (unknown)
    extra: dict = field(default_factory=dict, compare=False, hash=False)

    def speaks(self, probe_lang: str) -> bool:
        """``mixed`` probes are Russian sentences with English terms: they need a Russian voice."""
        need = "en" if probe_lang == "en" else "ru"
        return need in self.languages

    @property
    def key(self) -> Tuple[str, str]:
        return (self.system, self.voice)


class TtsUnavailable(RuntimeError):
    """The TTS system cannot run here (missing package, model, GPU...)."""


def choose_tts_input(text: str, tts_text: Optional[str], lang: str, voice: VoiceInfo) -> Tuple[str, str]:
    """Return ``(string to synthesise, which field it came from: "text"|"tts_text")``.

    Russian voices that cannot pronounce Latin words get the probe's ``tts_text`` for ``ru`` and
    ``mixed`` sentences when it exists. Everything else is read from ``text`` as written.
    """
    if lang != "en" and voice.latin_in_ru == LATIN_RESPELL and tts_text:
        return tts_text, "tts_text"
    return text, "text"


def to_16k(samples: np.ndarray, sr: int, peak: float = 0.9) -> np.ndarray:
    """Resample to 16 kHz mono float32 and peak-normalise (TTS engines differ wildly in level)."""
    x = resample(to_mono_float(samples), int(sr), SAMPLE_RATE)
    m = float(np.max(np.abs(x))) if x.size else 0.0
    if m > 0:
        x = (x * (peak / m)).astype(np.float32)
    return x


class TtsDriver(abc.ABC):
    """One TTS system with several voices. ``load()`` is idempotent; ``synthesize`` loads lazily."""

    system: str = ""
    gpu: bool = False

    def __init__(self, num_threads: int = 2) -> None:
        self.num_threads = num_threads

    @abc.abstractmethod
    def voices(self) -> List[VoiceInfo]:
        """All voices this driver offers (no model loaded)."""

    def voice(self, voice_id: str) -> VoiceInfo:
        for v in self.voices():
            if v.voice == voice_id:
                return v
        raise KeyError(f"{self.system}: unknown voice {voice_id!r}")

    @abc.abstractmethod
    def load(self) -> None: ...

    def prepare(self, voice: str) -> None:
        """Download/load everything ``voice`` needs, so synthesis timing excludes it."""
        self.load()

    @abc.abstractmethod
    def unload(self) -> None: ...

    @abc.abstractmethod
    def _synthesize(self, text: str, voice: VoiceInfo, lang: str, seed: int) -> np.ndarray:
        """Return 16 kHz mono float32 PCM."""

    def synthesize(self, text: str, voice: str, lang: str, seed: int = 0) -> np.ndarray:
        """``lang`` is the probe language ("ru", "en", "mixed"). Returns 16 kHz mono float32."""
        v = self.voice(voice)
        if not v.speaks(lang):
            raise ValueError(f"{self.system}/{voice} does not speak {lang!r}")
        if not text.strip():
            raise ValueError("empty text")
        x = self._synthesize(text, v, lang, seed)
        if x.size == 0:
            raise RuntimeError(f"{self.system}/{voice} produced no audio for {text!r}")
        return x

    def synthesize_batch(self, items: Sequence[Tuple[str, str, str, int]]) -> List[np.ndarray]:
        """``items``: (text, voice, lang, seed). GPU drivers override this to batch; CPU loops."""
        return [self.synthesize(t, v, lang, seed) for t, v, lang, seed in items]
