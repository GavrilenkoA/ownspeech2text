"""Registry of public sources: where to get them, licence, and our verdict.

Files are downloaded to $MURMUR_DATA_CACHE (default .cache/ml/data/<dir>/).
"""
from __future__ import annotations

import os
import urllib.request
from pathlib import Path

from ml.common.render_bridge import REPO

CACHE = Path(os.environ.get("MURMUR_DATA_CACHE", REPO / ".cache" / "ml" / "data"))
HF = "https://huggingface.co/datasets/{repo}/resolve/{rev}/{path}"

# licence_class: permissive | share_alike | non_commercial | none
SOURCES = {
    "sotto": dict(repo="juanquivilla/sotto-transcript-cleanup", licence="MIT", licence_class="permissive",
                  files={"train.parquet": "data/train-00000-of-00001.parquet",
                         "val.parquet": "data/validation-00000-of-00001.parquet"}),
    "aawaaz": dict(repo="shantanugoel/aawaaz-transcript-cleanup-dataset", licence="MIT", licence_class="permissive",
                   files={f"{c}.jsonl": f"{c}.jsonl" for c in [
                       "academic_research", "casual_conversation", "creative_writing", "email_professional",
                       "financial_business", "legal_contract", "medical_clinical", "meeting_notes", "recipe_cooking",
                       "self_corrections_heavy", "shopping_lists", "technical_code"]}),
    "handy": dict(repo="MagicNoThief/handy-dictation-editing", licence="CC-BY-4.0", licence_class="permissive",
                  files={"handy_editor.jsonl": "handy_editor.jsonl", "handy_eval.jsonl": "handy_eval.jsonl"}),
    "hasan": dict(repo="hasanchadaram/dictation-cleanup", licence="Apache-2.0", licence_class="permissive",
                  files={f"{s}.jsonl": f"notation-v3/{s}.jsonl" for s in ("train", "validation", "test")}),
    "speako": dict(repo="SpeakoFlow/dictation-cleanup-examples", licence="CC-BY-4.0", licence_class="permissive",
                   files={"examples.jsonl": "examples-authored.jsonl"}),
    "gensec": dict(repo="GenSEC-LLM/SLT-Task1-Post-ASR-Text-Correction", licence="Apache-2.0",
                   licence_class="permissive", files={"train.parquet": "data/train-00000-of-00001.parquet",
                                                       "test.parquet": "data/test-00000-of-00001.parquet"}),
    "kul": dict(repo="kul-speech-lab/contextual-reasoning-speechllm", licence="CC-BY-NC-SA-4.0 (derived, research only)",
                licence_class="non_commercial", files={"train_s.parquet": "train_s.parquet"}),
    "gpb11": dict(repo="gpb11/russian-asr-correction", licence="none declared", licence_class="none",
                  files={"train.parquet": "data/train-00000-of-00001.parquet"}),
    "enrich": dict(repo="MishZor256/russian-asr-enrichment", licence="CC-BY-SA-4.0", licence_class="share_alike",
                   files={"train.parquet": "data/train-00000-of-00001.parquet"}),
}


def local(src: str, name: str) -> Path:
    return CACHE / src / name


def download(src: str, rev: str = "main", force: bool = False) -> list[Path]:
    info = SOURCES[src]
    out = []
    for name, path in info["files"].items():
        dst = local(src, name)
        if dst.exists() and not force:
            out.append(dst)
            continue
        dst.parent.mkdir(parents=True, exist_ok=True)
        url = HF.format(repo=info["repo"], rev=rev, path=path)
        tmp = dst.with_suffix(dst.suffix + ".part")
        with urllib.request.urlopen(url, timeout=600) as r, open(tmp, "wb") as f:
            while chunk := r.read(1 << 20):
                f.write(chunk)
        tmp.replace(dst)
        out.append(dst)
    return out
