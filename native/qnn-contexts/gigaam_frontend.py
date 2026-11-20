"""GigaAM v3 front end, the Python reference the Kotlin port (infer/asr qnnep/GigaAmFrontEnd.kt) is checked against.

The model's own preprocessor (istupakov/gigaam-v3-onnx v3_e2e_ctc.yaml = gigaam.preprocess.FeatureExtractor):
torchaudio MelSpectrogram(sample_rate 16000, n_fft 320, win_length 320, hop_length 160, n_mels 64, center False,
mel_scale "htk", norm None, power 2, periodic Hann window, f_min 0, f_max 8000), then log(clamp(x, 1e-9, 1e9)).
The v3 checkpoint stores the featurizer's buffers (window, mel filterbank) in bfloat16, so the model was trained on
features made with bf16-rounded constants: both are rounded here exactly like that (round to nearest even), which
reproduces onnx-asr 0.12.0's stored gigaam_v3 window and filterbank bit for bit (the library the ONNX export was made
for). Frames = (samples - 320) // 160 + 1. The arithmetic is float64 (no torch); onnx-asr's float32 numpy
preprocessor is the independent cross-check (`check_against_onnx_asr`).

  python -I gigaam_frontend.py fixtures <clips.json> <out_dir>   write the parity fixture for the JVM test
  python -I gigaam_frontend.py check <wav> [...]                 max |d| vs onnx-asr's preprocessor
"""
from __future__ import annotations

import gzip
import hashlib
import json
import shutil
import sys
import wave
from pathlib import Path

import numpy as np

SAMPLE_RATE = 16_000
N_FFT = 320
WIN = 320
HOP = 160
N_MELS = 64
CLAMP_MIN = 1e-9
CLAMP_MAX = 1e9


def hz_to_mel(f):
    return 2595.0 * np.log10(1.0 + np.asarray(f, dtype=np.float64) / 700.0)


def mel_to_hz(m):
    return 700.0 * (10.0 ** (np.asarray(m, dtype=np.float64) / 2595.0) - 1.0)


def mel_filterbank() -> np.ndarray:
    """torchaudio.functional.melscale_fbanks(161, 0, 8000, 64, 16000, norm=None, mel_scale="htk"): [161, 64]."""
    n_freqs = N_FFT // 2 + 1
    all_freqs = np.linspace(0.0, SAMPLE_RATE / 2, n_freqs)
    m_pts = np.linspace(hz_to_mel(0.0), hz_to_mel(SAMPLE_RATE / 2), N_MELS + 2)
    f_pts = mel_to_hz(m_pts)
    f_diff = f_pts[1:] - f_pts[:-1]
    slopes = f_pts[None, :] - all_freqs[:, None]
    down = -slopes[:, :-2] / f_diff[:-1]
    up = slopes[:, 2:] / f_diff[1:]
    return np.maximum(0.0, np.minimum(down, up))


def hann() -> np.ndarray:
    """torch.hann_window(320) (periodic)."""
    n = np.arange(WIN, dtype=np.float64)
    return 0.5 - 0.5 * np.cos(2.0 * np.pi * n / WIN)


def bf16(x) -> np.ndarray:
    """float32 -> bfloat16 (round to nearest, ties to even) -> float64."""
    bits = np.asarray(x, dtype=np.float32).view(np.uint32).astype(np.uint64)
    bits = ((bits + 0x7FFF + ((bits >> 16) & 1)) >> 16) << 16
    return bits.astype(np.uint32).view(np.float32).astype(np.float64)


WINDOW = bf16(hann())
FBANK = bf16(mel_filterbank())


def frames(n_samples: int) -> int:
    return 0 if n_samples < WIN else (n_samples - WIN) // HOP + 1


def features(samples: np.ndarray) -> np.ndarray:
    """[-1, 1] samples at 16 kHz -> log-mel [64, frames] (float64)."""
    x = np.asarray(samples, dtype=np.float64)
    t = frames(len(x))
    if t == 0:
        return np.zeros((N_MELS, 0))
    idx = np.arange(WIN)[None, :] + HOP * np.arange(t)[:, None]
    spec = np.abs(np.fft.rfft(x[idx] * WINDOW[None, :], n=N_FFT, axis=1)) ** 2  # [t, 161]
    mel = spec @ FBANK                                                          # [t, 64]
    return np.log(np.clip(mel, CLAMP_MIN, CLAMP_MAX)).T


def read_wav(path: Path) -> np.ndarray:
    """PCM16 mono 16 kHz -> samples / 32768 (what :asr feeds its decoders: AsrSessionCore.pump)."""
    with wave.open(str(path)) as w:
        assert (w.getnchannels(), w.getsampwidth(), w.getframerate()) == (1, 2, SAMPLE_RATE), path
        pcm = np.frombuffer(w.readframes(w.getnframes()), dtype="<i2")
    return pcm.astype(np.float32) / 32768.0


def check_against_onnx_asr(paths: list[Path]) -> dict:
    from onnx_asr.preprocessors.numpy_preprocessor import GigaamPreprocessorNumpy
    pre = GigaamPreprocessorNumpy("gigaam_v3")
    assert np.array_equal(pre._window.astype(np.float64), WINDOW) and np.array_equal(pre._melscale_fbanks.astype(np.float64), FBANK)
    worst = {"max_abs": 0.0, "max_abs_above_minus10": 0.0}
    for p in paths:
        x = read_wav(p)
        ref = features(x)
        got, n = pre(x[None, :], np.array([len(x)], dtype=np.int64))
        got = got[0, :, : int(n[0])].astype(np.float64)
        assert got.shape == ref.shape, (p.name, got.shape, ref.shape)
        d = np.abs(got - ref)
        loud = ref > -10.0  # bins well above the 1e-9 clamp: float32 FFT rounding dominates below
        worst["max_abs"] = max(worst["max_abs"], float(d.max()))
        worst["max_abs_above_minus10"] = max(worst["max_abs_above_minus10"], float(d[loud].max()))
        print(json.dumps({"clip": p.name, "frames": ref.shape[1], "max_abs": float(d.max()),
                          "max_abs_above_minus10": float(d[loud].max()), "mean_abs": float(d.mean())}), flush=True)
    return worst


def write_fixtures(clips_json: Path, out: Path) -> None:
    """fixtures/<id>.wav (copied) + mel_ref.f32.gz (per clip [64, frames] float32 LE, concatenated) + mel_ref.json."""
    clips = json.loads(clips_json.read_text(encoding="utf-8"))
    out.mkdir(parents=True, exist_ok=True)
    index, blobs, offset = [], [], 0
    for c in clips:
        src = Path(c["wav"])
        dst = out / f"{c['id']}.wav"
        shutil.copyfile(src, dst)
        mel = features(read_wav(dst)).astype("<f4")
        index.append({"id": c["id"], "wav": dst.name, "wav_sha256": hashlib.sha256(dst.read_bytes()).hexdigest(),
                      "frames": int(mel.shape[1]), "offset_floats": offset, "ref": c["ref"], "src": c["src"]})
        offset += mel.size
        blobs.append(mel.tobytes())
    with gzip.GzipFile(out / "mel_ref.f32.gz", "wb", mtime=0) as g:
        for b in blobs:
            g.write(b)
    meta = {"generator": "native/qnn-contexts/gigaam_frontend.py", "layout": "per clip [64, frames] float32 LE, row = mel bin",
            "n_mels": N_MELS, "clips": index}
    (out / "mel_ref.json").write_text(json.dumps(meta, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"wrote {len(index)} clips, {offset} floats to {out}")


def main() -> int:
    if sys.argv[1] == "fixtures":
        write_fixtures(Path(sys.argv[2]), Path(sys.argv[3]))
    elif sys.argv[1] == "check":
        print(json.dumps(check_against_onnx_asr([Path(p) for p in sys.argv[2:]])))
    else:
        raise SystemExit(__doc__)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
