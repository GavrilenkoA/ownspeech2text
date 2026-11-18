"""ASR driver interface used by the round-trip runner.

A driver's ``name`` is the model id from ``docs/research/asr-catalog.json`` where one exists.
Drivers are inference-only noise sources: they run the same ONNX graphs as the phone's
CPU path and return the raw recogniser text untouched.
"""
from __future__ import annotations

import abc
from dataclasses import dataclass, field
from typing import FrozenSet, Optional

from .audio import AudioInput

# Probe languages understood by the runner.
PROBE_LANGS = ("ru", "en", "mixed")


@dataclass(frozen=True)
class AsrSpec:
    """Static description of one recogniser (no model loaded)."""

    name: str
    family: str
    languages: FrozenSet[str]  # subset of {"ru", "en"} relevant to the probe
    catalog_id: Optional[str]  # id in asr-catalog.json, None when the catalog has no CPU entry
    notes: str = ""
    licence: str = ""
    extra: dict = field(default_factory=dict, compare=False, hash=False)

    @property
    def multilingual(self) -> bool:
        return {"ru", "en"} <= set(self.languages)

    def accepts(self, probe_lang: str) -> bool:
        """Language routing: en -> models with en; ru -> models with ru;
        mixed -> multilingual models plus the Russian-only ones (never English-only)."""
        if probe_lang == "en":
            return "en" in self.languages
        if probe_lang in ("ru", "mixed"):
            return "ru" in self.languages
        raise ValueError(f"unknown probe language {probe_lang!r}")


class AsrUnavailable(RuntimeError):
    """The model cannot be loaded on this machine; the message says why."""


class AsrDriver(abc.ABC):
    """One recogniser. ``load()`` is idempotent; ``transcribe`` loads lazily."""

    spec: AsrSpec

    def __init__(self, spec: AsrSpec, num_threads: int = 2) -> None:
        self.spec = spec
        self.num_threads = num_threads

    @property
    def name(self) -> str:
        return self.spec.name

    @abc.abstractmethod
    def load(self) -> None:
        """Download (if needed) and load the model into memory."""

    @abc.abstractmethod
    def unload(self) -> None:
        """Free the model."""

    @property
    @abc.abstractmethod
    def loaded(self) -> bool: ...

    @abc.abstractmethod
    def _transcribe(self, samples, lang_hint: str) -> str:
        """``samples``: float32 mono 16 kHz. ``lang_hint``: "ru", "en" or "auto"."""

    def transcribe(self, audio: AudioInput, lang_hint: str = "auto") -> str:
        from .audio import as_samples

        if not self.loaded:
            self.load()
        return self._transcribe(as_samples(audio), lang_hint)


class UnavailableDriver(AsrDriver):
    """Registered placeholder for a model that cannot run here; keeps the reason visible."""

    def __init__(self, spec: AsrSpec, reason: str, num_threads: int = 1) -> None:
        super().__init__(spec, num_threads)
        self.reason = reason

    def load(self) -> None:
        raise AsrUnavailable(f"{self.spec.name}: {self.reason}")

    def unload(self) -> None:
        return None

    @property
    def loaded(self) -> bool:
        return False

    def _transcribe(self, samples, lang_hint: str) -> str:  # pragma: no cover - load() raises first
        raise AsrUnavailable(f"{self.spec.name}: {self.reason}")
