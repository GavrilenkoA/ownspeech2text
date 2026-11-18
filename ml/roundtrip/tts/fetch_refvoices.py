"""Fetch CC-BY-4.0 reference clips for the GPU cloning TTS systems from Google FLEURS.

FLEURS (google/fleurs on Hugging Face, licence CC-BY-4.0 per the dataset card) is read speech by many
speakers with exact transcripts, in Russian and English among others. This script streams one split's audio
archive at a pinned dataset revision and keeps a few clips per language, alternating speaker gender, with
lengths inside the cloning window. It writes them plus ``manifest.jsonl`` in the format
``ml/roundtrip/tts/refvoices.py`` reads, so the directory can be passed as ``run_roundtrip --ref-dir``.

  .cache/ml/roundtrip/venv/bin/python -m ml.roundtrip.tts.fetch_refvoices --ru 6 --en 4

FLEURS has no speaker ids; clips of different sentences may share a speaker (UNVERIFIED distinctness).
Clips are chosen deterministically (sorted by a hash of the file name with ``--seed``), so the same
revision and arguments give the same set. The split's archive (ru dev 187 MB, en dev 171 MB) is downloaded
with huggingface_hub into ``<out>/.download`` and deleted afterwards; reading stops as soon as every chosen
clip has been extracted.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import io
import json
import sys
import shutil
import tarfile
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Sequence

from ..asr.fetch import REPO_ROOT, keep_hf_state_local

REVISION = "70bb2e84b976b7e960aa89f1c648e09c59f894dd"  # google/fleurs main on 2026-11-19
BASE = "https://huggingface.co/datasets/google/fleurs/resolve"
CONFIGS = {"ru": "ru_ru", "en": "en_us"}
LICENCE = "CC-BY-4.0"
ATTRIBUTION = "FLEURS (Google; Conneau et al., 2022), CC BY 4.0"
SR = 16000
MIN_S, MAX_S = 4.0, 12.0
DEFAULT_OUT = REPO_ROOT / ".cache" / "ml" / "roundtrip" / "refvoices"


@dataclass(frozen=True)
class Clip:
    lang: str
    file: str
    text: str
    seconds: float
    gender: str


def _url(lang: str, rel: str) -> str:
    return f"{BASE}/{REVISION}/data/{CONFIGS[lang]}/{rel}"


def _download(lang: str, rel: str, tmp: Path) -> Path:
    keep_hf_state_local()
    from huggingface_hub import hf_hub_download

    return Path(hf_hub_download("google/fleurs", f"data/{CONFIGS[lang]}/{rel}", repo_type="dataset",
                                revision=REVISION, local_dir=str(tmp)))


def read_index(lang: str, split: str, tmp: Path) -> List[Clip]:
    text = _download(lang, f"{split}.tsv", tmp).read_text(encoding="utf-8")
    out = []
    for row in csv.reader(io.StringIO(text), delimiter="\t", quoting=csv.QUOTE_NONE):
        if len(row) < 7:
            continue
        out.append(Clip(lang, row[1], row[2].strip(), int(row[5]) / SR, row[6].strip().lower()))
    return out


def choose(clips: Sequence[Clip], n: int, seed: int) -> List[Clip]:
    """``n`` clips of MIN_S..MAX_S seconds, one per distinct transcript, genders alternating."""
    ok = [c for c in clips if MIN_S <= c.seconds <= MAX_S and c.text]
    ok.sort(key=lambda c: hashlib.sha256(f"{seed}:{c.file}".encode()).hexdigest())
    by_gender: Dict[str, List[Clip]] = {}
    seen_text = set()
    for c in ok:
        if c.text in seen_text:
            continue
        seen_text.add(c.text)
        by_gender.setdefault(c.gender, []).append(c)
    order = sorted(by_gender)
    picked: List[Clip] = []
    i = 0
    while len(picked) < n and any(by_gender.values()):
        g = order[i % len(order)]
        if by_gender[g]:
            picked.append(by_gender[g].pop(0))
        i += 1
    return picked


def stream_extract(lang: str, split: str, wanted: Dict[str, Clip], out: Path, tmp: Path) -> Dict[str, Path]:
    import numpy as np
    import soundfile as sf

    got: Dict[str, Path] = {}
    archive = _download(lang, f"audio/{split}.tar.gz", tmp)
    with tarfile.open(archive, mode="r|gz") as tar:
        for m in tar:
            name = Path(m.name).name
            if not m.isfile() or name not in wanted:
                continue
            data = tar.extractfile(m).read()  # type: ignore[union-attr]
            x, sr = sf.read(io.BytesIO(data), dtype="float32")
            if sr != SR:
                raise RuntimeError(f"{name}: expected {SR} Hz, got {sr}")
            dst = out / f"fleurs_{lang}_{wanted[name].gender[:1]}_{Path(name).stem[-8:]}.flac"
            sf.write(str(dst), np.asarray(x, dtype=np.float32), SR, subtype="PCM_16")
            got[name] = dst
            if len(got) == len(wanted):
                break
    return got


def main(argv: Sequence[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", type=Path, default=DEFAULT_OUT)
    ap.add_argument("--ru", type=int, default=6, help="Russian clips")
    ap.add_argument("--en", type=int, default=4, help="English clips")
    ap.add_argument("--split", default="dev", choices=("dev", "test", "train"))
    ap.add_argument("--seed", type=int, default=1)
    args = ap.parse_args(argv)
    args.out.mkdir(parents=True, exist_ok=True)
    rows = []
    tmp = args.out / ".download"
    for lang, n in (("ru", args.ru), ("en", args.en)):
        if n <= 0:
            continue
        try:
            picked = choose(read_index(lang, args.split, tmp), n, args.seed)
            got = stream_extract(lang, args.split, {c.file: c for c in picked}, args.out, tmp)
        finally:
            shutil.rmtree(tmp, ignore_errors=True)
        for c in picked:
            if c.file not in got:
                print(f"warning: {c.file} not found in the archive", file=sys.stderr)
                continue
            rows.append({"file": got[c.file].name, "text": c.text, "lang": lang, "licence": LICENCE,
                         "source": _url(lang, f"audio/{args.split}.tar.gz") + "#" + c.file,
                         "attribution": ATTRIBUTION, "gender": c.gender, "seconds": round(c.seconds, 2)})
    with open(args.out / "manifest.jsonl", "w", encoding="utf-8") as fh:
        for r in rows:
            fh.write(json.dumps(r, ensure_ascii=False) + "\n")
    print(f"wrote {len(rows)} reference clips -> {args.out}")
    return 0 if rows else 1


if __name__ == "__main__":
    sys.exit(main())
