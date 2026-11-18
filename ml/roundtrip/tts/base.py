"""TTS driver interface used by the round-trip runner.

A TTS system turns probe text into 16 kHz mono float32 PCM. TTS is inference-only here: a source of
realistic acoustic variety for the ASR stage, never a product component.

Mixed ru+en sentences: each voice declares how it treats Latin words inside a Russian sentence
(``latin_in_ru``) and in which languages its own text normaliser reads digits (``reads_digits``).
The runner uses both to pick between the probe's ``text``, its ``tts_text`` (Latin terms respelled,
numbers in words) and ``text`` with only the numbers spoken -- see :func:`choose_tts_input`.
"""
from __future__ import annotations

import abc
from dataclasses import dataclass, field
from typing import FrozenSet, List, Optional, Sequence, Tuple

import numpy as np

from ..asr.audio import SAMPLE_RATE, resample, to_mono_float
from ..probe.spoken import has_number_token, spell_digits, transfer_spoken_numbers

# How a voice handles Latin-script words in a Russian sentence.
LATIN_NATIVE = "native"  # reads them acceptably (language switching or multilingual model)
LATIN_RESPELL = "respell"  # cannot: feed the Cyrillic tts_text when the probe has one
LATIN_POLICIES = (LATIN_NATIVE, LATIN_RESPELL)

# Which probe field the TTS read (the ``tts_input`` field of round-trip rows).
INPUT_TEXT = "text"  # ``text`` as written
INPUT_TTS_TEXT = "tts_text"  # the probe's ``tts_text``
INPUT_TEXT_NUMBERS = "text_numbers"  # ``text`` with its digits replaced by spoken words


@dataclass(frozen=True)
class VoiceInfo:
    """One selectable voice of a TTS system."""

    system: str
    voice: str  # stable id, unique within the system
    languages: FrozenSet[str]  # subset of {"ru", "en"}
    licence: str
    latin_in_ru: str = LATIN_RESPELL
    gender: str = ""  # "f", "m" or "" (unknown)
    # spoken languages ("ru"/"en") whose digits the engine's own normaliser reads correctly; elsewhere
    # numbers are handed over as words (Supertonic under lang=ru drops "80%" and "11:30")
    reads_digits: FrozenSet[str] = frozenset()
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
    """Return ``(string to synthesise, INPUT_TEXT | INPUT_TTS_TEXT | INPUT_TEXT_NUMBERS)``.

    - ``en`` and ``ru``: ``tts_text`` whenever the probe has one. It differs from ``text`` only where a
      speaker says something else than the spelling ("kubectl" -> "kube control", "20" -> "двадцать").
    - ``mixed``: ``respell`` voices read ``tts_text``. ``native`` voices read ``text`` with its Latin terms
      as written; if the voice cannot read digits in Russian, the numbers are taken from ``tts_text``
      (declined as said), and numbers ``tts_text`` cannot supply are spelled in the nominative.
    - Any digits left in a language the voice does not read digits in are spelled out.
    """
    spoken_lang = "en" if lang == "en" else "ru"
    digits_ok = spoken_lang in voice.reads_digits
    if tts_text and (lang != "mixed" or voice.latin_in_ru == LATIN_RESPELL):
        say, src = tts_text, INPUT_TTS_TEXT
    elif tts_text and not digits_ok and has_number_token(text):
        say, src = transfer_spoken_numbers(text, tts_text, lang), INPUT_TEXT_NUMBERS
    else:
        say, src = text, INPUT_TEXT
    if not digits_ok and has_number_token(say):
        spelled = spell_digits(say, lang)
        if spelled != say:
            say, src = spelled, (INPUT_TEXT_NUMBERS if src == INPUT_TEXT else src)
    return say, src


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
