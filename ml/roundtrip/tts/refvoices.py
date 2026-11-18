"""Reference voices for the cloning TTS systems (Qwen3-TTS Base, Chatterbox).

A reference-voice directory holds short clips (3-15 s, WAV/FLAC) plus a ``manifest.jsonl`` with
one line per clip::

    {"file": "ru_f_01.flac", "text": "exact transcript of the clip", "lang": "ru",
     "licence": "CC0-1.0", "source": "https://...", "attribution": "speaker / dataset name"}

Only clips under licences that allow commercial use with at most attribution are accepted (CC0,
public domain, CC-BY). Anything else is rejected with a reason -- nothing is downloaded here;
the operator places the clips (e.g. a few CC0 Common Voice / CC-BY LibriTTS-R utterances).
"""
from __future__ import annotations

import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import List, Tuple

ALLOWED_LICENCES = ("cc0", "cc0-1.0", "public-domain", "pd", "cc-by-3.0", "cc-by-4.0", "cc-by")
MIN_S, MAX_S = 2.0, 20.0


@dataclass(frozen=True)
class RefVoice:
    voice_id: str  # file stem
    path: Path
    text: str
    lang: str  # "ru" or "en"
    licence: str
    source: str
    attribution: str
    duration_s: float


def licence_ok(licence: str) -> bool:
    lic = re.sub(r"[\s_]+", "-", licence.strip().lower())
    if any(bad in lic.split("-") for bad in ("nc", "nd", "sa")):
        return False
    return lic in ALLOWED_LICENCES


def load_ref_voices(directory: Path | str) -> Tuple[List[RefVoice], List[str]]:
    """Return ``(accepted voices, rejection messages)``. Raises if the manifest is missing."""
    import soundfile as sf

    d = Path(directory)
    manifest = d / "manifest.jsonl"
    if not manifest.exists():
        raise FileNotFoundError(f"{manifest} not found (see ml/roundtrip/tts/refvoices.py for the format)")
    ok: List[RefVoice] = []
    rejected: List[str] = []
    for n, line in enumerate(manifest.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        try:
            row = json.loads(line)
            f = d / row["file"]
            text, lang, lic = row["text"].strip(), row["lang"], row["licence"]
        except (KeyError, json.JSONDecodeError) as exc:
            rejected.append(f"line {n}: bad manifest row ({exc})")
            continue
        if Path(row["file"]).is_absolute() or ".." in Path(row["file"]).parts:
            rejected.append(f"line {n}: file must be a plain name inside {d}")
            continue
        if not licence_ok(lic):
            rejected.append(f"{row['file']}: licence {lic!r} not allowed (need CC0 / public domain / CC-BY)")
            continue
        if lang not in ("ru", "en"):
            rejected.append(f"{row['file']}: lang {lang!r} must be ru or en")
            continue
        if not f.exists():
            rejected.append(f"{row['file']}: file missing")
            continue
        info = sf.info(str(f))
        dur = float(info.frames) / float(info.samplerate)
        if not (MIN_S <= dur <= MAX_S):
            rejected.append(f"{row['file']}: duration {dur:.1f}s outside [{MIN_S}, {MAX_S}] s")
            continue
        if not text:
            rejected.append(f"{row['file']}: empty transcript")
            continue
        ok.append(RefVoice(voice_id=f.stem, path=f, text=text, lang=lang, licence=lic,
                           source=row.get("source", ""), attribution=row.get("attribution", ""),
                           duration_s=dur))
    return ok, rejected
