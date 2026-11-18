"""Audio helpers shared by the TTS, channel and ASR stages (numpy/scipy/soundfile only)."""
from __future__ import annotations

from math import gcd
from pathlib import Path
from typing import Union

import numpy as np

SAMPLE_RATE = 16000
AudioInput = Union[str, Path, np.ndarray]


def to_mono_float(x: np.ndarray) -> np.ndarray:
    """Return a contiguous float32 mono signal (channels averaged, ints scaled to [-1, 1])."""
    a = np.asarray(x)
    if np.issubdtype(a.dtype, np.integer):
        a = a.astype(np.float32) / float(np.iinfo(a.dtype).max)
    a = a.astype(np.float32, copy=False)
    if a.ndim == 2:
        a = a.mean(axis=1)
    elif a.ndim != 1:
        raise ValueError(f"expected 1-D or 2-D audio, got shape {a.shape}")
    return np.ascontiguousarray(a)


def resample(x: np.ndarray, sr_in: int, sr_out: int) -> np.ndarray:
    """Polyphase resampling (scipy.signal.resample_poly); identity when rates match."""
    if sr_in == sr_out:
        return to_mono_float(x)
    from scipy.signal import resample_poly

    g = gcd(int(sr_in), int(sr_out))
    y = resample_poly(to_mono_float(x), int(sr_out) // g, int(sr_in) // g)
    return np.ascontiguousarray(y.astype(np.float32))


def read_audio(path: Union[str, Path], sr: int = SAMPLE_RATE) -> np.ndarray:
    """Read WAV/FLAC as float32 mono at ``sr``."""
    import soundfile as sf

    data, file_sr = sf.read(str(path), dtype="float32", always_2d=False)
    return resample(data, int(file_sr), sr)


def write_audio(path: Union[str, Path], samples: np.ndarray, sr: int = SAMPLE_RATE) -> None:
    """Write 16-bit PCM WAV or FLAC (by extension) atomically (temp file + rename)."""
    import soundfile as sf

    p = Path(path)
    p.parent.mkdir(parents=True, exist_ok=True)
    fmt = "FLAC" if p.suffix.lower() == ".flac" else "WAV"
    tmp = p.with_name(f".{p.name}.tmp")
    clipped = np.clip(to_mono_float(samples), -1.0, 1.0)
    sf.write(str(tmp), clipped, sr, subtype="PCM_16", format=fmt)
    tmp.replace(p)


def as_samples(audio: AudioInput, sr: int = SAMPLE_RATE) -> np.ndarray:
    """Accept a path or a 16 kHz float array; return float32 mono at ``sr``."""
    if isinstance(audio, (str, Path)):
        return read_audio(audio, sr)
    x = to_mono_float(audio)
    return x if sr == SAMPLE_RATE else resample(x, SAMPLE_RATE, sr)


def duration_s(samples: np.ndarray, sr: int = SAMPLE_RATE) -> float:
    return float(len(samples)) / float(sr)
