"""Phone-microphone channel simulation (numpy/scipy only), applied to 16 kHz mono float32 PCM.

Presets (``PRESETS``):

- ``clean``     -- identity.
- ``phone``     -- 120 Hz-7 kHz band-limit, light reverb, slow AGC, mild soft clipping, noise at 25 dB SNR.
- ``phone_noisy`` -- same chain with stronger reverb and pink-ish noise at 12 dB SNR.
- ``far``       -- 200 Hz-5 kHz band-limit, strong reverb, AGC, noise at 18 dB SNR (arm's length).

Every stage is deterministic given ``seed`` (noise and room impulse response come from
``numpy.random.default_rng(seed)``), so a round-trip key reproduces its audio exactly.
"""
from __future__ import annotations

import argparse
import sys
from dataclasses import dataclass
from typing import Dict, Optional

import numpy as np

SR = 16000


@dataclass(frozen=True)
class ChannelPreset:
    name: str
    band: Optional[tuple] = None  # (low Hz, high Hz)
    rt60_s: float = 0.0  # reverb decay time; 0 = no reverb
    wet: float = 0.0  # reverb mix 0..1
    agc: bool = False
    drive: float = 1.0  # soft-clip drive (>1 = more saturation)
    snr_db: Optional[float] = None
    noise_colour: str = "white"  # "white" or "pink"


PRESETS: Dict[str, ChannelPreset] = {
    "clean": ChannelPreset("clean"),
    "phone": ChannelPreset("phone", band=(120.0, 7000.0), rt60_s=0.25, wet=0.12, agc=True, drive=1.6,
                           snr_db=25.0, noise_colour="pink"),
    "phone_noisy": ChannelPreset("phone_noisy", band=(150.0, 7000.0), rt60_s=0.4, wet=0.2, agc=True,
                                 drive=2.0, snr_db=12.0, noise_colour="pink"),
    "far": ChannelPreset("far", band=(200.0, 5000.0), rt60_s=0.6, wet=0.35, agc=True, drive=1.3,
                         snr_db=18.0, noise_colour="white"),
}


def _rms(x: np.ndarray) -> float:
    return float(np.sqrt(np.mean(np.square(x, dtype=np.float64)))) if x.size else 0.0


def band_limit(x: np.ndarray, low: float, high: float, sr: int = SR) -> np.ndarray:
    from scipy.signal import butter, sosfilt

    high = min(high, 0.45 * sr)
    sos = butter(4, [low, high], btype="bandpass", fs=sr, output="sos")
    return sosfilt(sos, x).astype(np.float32)


def reverb(x: np.ndarray, rt60_s: float, wet: float, rng: np.random.Generator, sr: int = SR) -> np.ndarray:
    """Exponentially decaying noise impulse response (a cheap diffuse-room model)."""
    from scipy.signal import fftconvolve

    if rt60_s <= 0 or wet <= 0:
        return x
    n = max(1, int(rt60_s * sr))
    t = np.arange(n) / sr
    ir = rng.standard_normal(n) * np.exp(-6.9 * t / rt60_s)  # -60 dB at rt60
    ir[: int(0.003 * sr)] = 0.0  # 3 ms pre-delay before reflections
    ir /= np.sqrt(np.sum(ir ** 2)) + 1e-12
    tail = fftconvolve(x, ir)[: len(x)]
    y = (1.0 - wet) * x + wet * tail * (_rms(x) / (_rms(tail) + 1e-12))
    return y.astype(np.float32)


def agc(x: np.ndarray, target_rms: float = 0.1, win_s: float = 0.4, max_gain: float = 8.0,
        sr: int = SR) -> np.ndarray:
    """Slow automatic gain control: smoothed RMS envelope, gain toward ``target_rms``."""
    from scipy.ndimage import uniform_filter1d

    win = max(1, int(win_s * sr))
    env = np.sqrt(uniform_filter1d(x.astype(np.float64) ** 2, size=win, mode="nearest")) + 1e-4
    gain = np.clip(target_rms / env, 1.0 / max_gain, max_gain)
    gain = uniform_filter1d(gain, size=win, mode="nearest")
    return (x * gain).astype(np.float32)


def soft_clip(x: np.ndarray, drive: float) -> np.ndarray:
    if drive <= 1.0:
        return np.clip(x, -1.0, 1.0).astype(np.float32)
    return (np.tanh(drive * x) / np.tanh(drive)).astype(np.float32)


def noise(n: int, colour: str, rng: np.random.Generator) -> np.ndarray:
    w = rng.standard_normal(n)
    if colour == "pink":  # 1/f power via spectral shaping
        spec = np.fft.rfft(w)
        f = np.arange(spec.size, dtype=np.float64)
        f[0] = 1.0
        w = np.fft.irfft(spec / np.sqrt(f), n)
    elif colour != "white":
        raise ValueError(f"unknown noise colour {colour!r}")
    return (w / (_rms(w) + 1e-12)).astype(np.float32)


def add_noise(x: np.ndarray, snr_db: float, colour: str, rng: np.random.Generator) -> np.ndarray:
    """Add noise so that 10*log10(P_signal / P_noise) == snr_db (signal power over the whole clip)."""
    p = _rms(x)
    if p == 0:
        return x
    nz = noise(len(x), colour, rng) * (p / (10.0 ** (snr_db / 20.0)))
    return (x + nz).astype(np.float32)


def apply_channel(x: np.ndarray, preset: str, seed: int = 0, sr: int = SR) -> np.ndarray:
    """Apply ``preset`` to 16 kHz mono float32 audio. ``clean`` returns the input unchanged."""
    try:
        p = PRESETS[preset]
    except KeyError:
        raise KeyError(f"unknown channel preset {preset!r}; known: {', '.join(PRESETS)}") from None
    x = np.asarray(x, dtype=np.float32)
    if p.name == "clean":
        return x
    rng = np.random.default_rng(seed)
    y = x
    if p.rt60_s > 0:
        y = reverb(y, p.rt60_s, p.wet, rng, sr)
    if p.band:
        y = band_limit(y, p.band[0], p.band[1], sr)
    if p.agc:
        y = agc(y, sr=sr)
    if p.snr_db is not None:
        y = add_noise(y, p.snr_db, p.noise_colour, rng)
    y = soft_clip(y, p.drive)
    peak = float(np.max(np.abs(y))) if y.size else 0.0
    return (y * (0.95 / peak) if peak > 0.95 else y).astype(np.float32)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="Apply a phone-mic channel preset to a WAV/FLAC file.")
    ap.add_argument("input")
    ap.add_argument("output")
    ap.add_argument("--preset", default="phone", choices=sorted(PRESETS))
    ap.add_argument("--seed", type=int, default=0)
    args = ap.parse_args(argv)
    from .asr.audio import read_audio, write_audio

    write_audio(args.output, apply_channel(read_audio(args.input), args.preset, args.seed))
    return 0


if __name__ == "__main__":
    sys.exit(main())
