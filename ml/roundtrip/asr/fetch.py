"""Lazy, cached, concurrency-safe model downloads (Hugging Face files and GitHub release archives).

Every model used by the round trip is fetched on first use into the round-trip cache
(``$MURMUR_ROUNDTRIP_CACHE``, default ``<repo>/.cache/ml/roundtrip``) and reused afterwards.
Several worker processes may ask for the same model at once; a per-target file lock makes the
first one download while the others wait.

Shared by the ASR and TTS drivers.
"""
from __future__ import annotations

import fcntl
import os
import shutil
import tarfile
import tempfile
import urllib.request
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path
from typing import Iterator, Sequence

REPO_ROOT = Path(__file__).resolve().parents[3]
CHUNK = 1 << 20


def cache_root() -> Path:
    """Root of every download, venv and audio file of the round trip (gitignored)."""
    env = os.environ.get("MURMUR_ROUNDTRIP_CACHE")
    root = Path(env) if env else REPO_ROOT / ".cache" / "ml" / "roundtrip"
    root.mkdir(parents=True, exist_ok=True)
    return root


def models_dir() -> Path:
    d = cache_root() / "models"
    d.mkdir(parents=True, exist_ok=True)
    return d


def keep_hf_state_local() -> None:
    """Point huggingface_hub's Xet chunk cache and logs into our cache instead of ~/.cache/huggingface
    (observed: every download left a log file under ~/.cache/huggingface/xet/logs). Call before
    huggingface_hub is imported; explicit settings in the environment win."""
    os.environ.setdefault("HF_XET_CACHE", str(models_dir() / "xet"))
    os.environ.setdefault("HF_HUB_DISABLE_TELEMETRY", "1")


class FetchError(RuntimeError):
    """A model file could not be downloaded or unpacked."""


@contextmanager
def _locked(target: Path) -> Iterator[None]:
    target.parent.mkdir(parents=True, exist_ok=True)
    lock_path = target.parent / f".{target.name}.lock"
    with open(lock_path, "w") as fh:
        fcntl.flock(fh, fcntl.LOCK_EX)
        try:
            yield
        finally:
            fcntl.flock(fh, fcntl.LOCK_UN)


@dataclass(frozen=True)
class HfFile:
    """One file of a Hugging Face model repo, pinned to a commit."""

    repo: str
    path: str
    revision: str


def fetch_hf(files: Sequence[HfFile]) -> Path:
    """Download pinned files of one HF repo and return the snapshot directory that holds them.

    Uses ``huggingface_hub`` with an explicit cache dir, so the layout is
    ``models/hf/models--<org>--<name>/snapshots/<revision>/<path>``.
    """
    if not files:
        raise FetchError("fetch_hf() needs at least one file")
    repos = {(f.repo, f.revision) for f in files}
    if len(repos) != 1:
        raise FetchError(f"fetch_hf() expects files of one repo/revision, got {sorted(repos)}")
    keep_hf_state_local()
    try:
        from huggingface_hub import hf_hub_download
    except ImportError as exc:  # pragma: no cover - environment problem
        raise FetchError("huggingface_hub is not installed in the round-trip venv") from exc
    cache = models_dir() / "hf"
    local: Path | None = None
    for f in files:
        try:
            p = Path(hf_hub_download(f.repo, f.path, revision=f.revision, cache_dir=str(cache)))
        except Exception as exc:  # network, 404, disk full: surface one clear error
            raise FetchError(f"download failed: hf://{f.repo}@{f.revision[:8]}/{f.path}: {exc}") from exc
        root = p
        for _ in Path(f.path).parts:
            root = root.parent
        local = root
    assert local is not None
    return local


def _download(url: str, dest: Path) -> None:
    req = urllib.request.Request(url, headers={"User-Agent": "murmur-roundtrip/1"})
    try:
        with urllib.request.urlopen(req, timeout=60) as resp, open(dest, "wb") as out:
            shutil.copyfileobj(resp, out, CHUNK)
    except Exception as exc:
        raise FetchError(f"download failed: {url}: {exc}") from exc


def fetch_archive(url: str, name: str) -> Path:
    """Download and unpack a ``.tar.bz2``/``.tar.gz`` release archive once; return its top directory.

    ``name`` is the directory the archive unpacks to (the archive stem for sherpa-onnx releases).
    The archive itself is deleted after extraction to save disk.
    """
    target = models_dir() / "gh" / name
    if (target / ".complete").exists():
        return target
    with _locked(target):
        if (target / ".complete").exists():
            return target
        tmp = Path(tempfile.mkdtemp(prefix=f".{name}.", dir=target.parent))
        try:
            archive = tmp / "archive"
            _download(url, archive)
            with tarfile.open(archive) as tar:
                tar.extractall(tmp / "x", filter="data")
            archive.unlink()
            inner = tmp / "x" / name
            src = inner if inner.is_dir() else tmp / "x"
            if target.exists():
                shutil.rmtree(target)
            src.rename(target)
            (target / ".complete").write_text(url + "\n")
        except tarfile.TarError as exc:
            raise FetchError(f"bad archive {url}: {exc}") from exc
        finally:
            shutil.rmtree(tmp, ignore_errors=True)
    return target


def fetch_url(url: str, rel_path: str) -> Path:
    """Download a single file (no extraction) to ``models/<rel_path>`` once."""
    target = models_dir() / rel_path
    if target.exists():
        return target
    with _locked(target):
        if target.exists():
            return target
        part = target.with_name(target.name + ".part")
        _download(url, part)
        part.rename(target)
    return target
