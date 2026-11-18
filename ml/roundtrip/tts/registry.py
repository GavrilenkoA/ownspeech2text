"""TTS system registry: system id -> driver class."""
from __future__ import annotations

from typing import Callable, Dict, List

from .base import TtsDriver
from .gpu_tts import ChatterboxDriver, Qwen3TtsDriver
from .piper_tts import PiperDriver
from .sherpa_tts import KokoroDriver, SupertonicDriver

SYSTEMS: Dict[str, Callable[..., TtsDriver]] = {
    PiperDriver.system: PiperDriver,
    SupertonicDriver.system: SupertonicDriver,
    KokoroDriver.system: KokoroDriver,
    Qwen3TtsDriver.system: Qwen3TtsDriver,
    ChatterboxDriver.system: ChatterboxDriver,
}
CPU_SYSTEMS: List[str] = [s for s, cls in SYSTEMS.items() if not cls.gpu]
GPU_SYSTEMS: List[str] = [s for s, cls in SYSTEMS.items() if cls.gpu]


def resolve_systems(selector: str) -> List[str]:
    """``cpu``, ``gpu``, ``all`` or a comma list of system ids."""
    s = selector.strip()
    if s == "cpu":
        return list(CPU_SYSTEMS)
    if s == "gpu":
        return list(GPU_SYSTEMS)
    if s == "all":
        return list(SYSTEMS)
    out = [x.strip() for x in s.split(",") if x.strip()]
    bad = [x for x in out if x not in SYSTEMS]
    if bad:
        raise KeyError(f"unknown TTS system(s) {bad}; known: {', '.join(SYSTEMS)}")
    return out


def get_tts(system: str, num_threads: int = 2, **kw) -> TtsDriver:
    try:
        cls = SYSTEMS[system]
    except KeyError:
        raise KeyError(f"unknown TTS system {system!r}; known: {', '.join(SYSTEMS)}") from None
    return cls(num_threads=num_threads, **kw)
