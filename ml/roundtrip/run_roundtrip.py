"""Round trip: probe JSONL -> TTS (system x voice) -> channel -> every language-compatible ASR model.

Writes interface-2 rows (docs/ml/ROUNDTRIP.md) to ``<out>/roundtrip/<run>.jsonl`` and audio to
``<out>/audio/<tts_system>/<voice>/<probe_id>.<channel>.flac``. Resumable: audio files that exist
are reused and result keys already in the JSONL are skipped, so re-running the same command
continues an interrupted run. Run it through tools/heavy (models load in worker processes)::

  tools/heavy .cache/ml/roundtrip/venv/bin/python -m ml.roundtrip.run_roundtrip \\
      --probe ml/roundtrip/probe/seed.jsonl --tts piper,supertonic3 --voices-per-sentence 1 \\
      --channels clean --asr all --limit 40 --workers 8 --threads 2

  # throughput of every TTS system and ASR model (audio-seconds per wall-second):
  tools/heavy .cache/ml/roundtrip/venv/bin/python -m ml.roundtrip.run_roundtrip --measure \\
      --probe ml/roundtrip/probe/seed.jsonl --tts all --asr all

Language routing: ``en`` probes go to models that know English, ``ru`` and ``mixed`` probes to
models that know Russian (multilingual + Russian-only); never English-only models for ru/mixed.
The ASR language hint is ``en`` for en probes and ``ru`` for ru/mixed probes.
"""
from __future__ import annotations

import argparse
import ctypes
import gc
import json
import multiprocessing as mp
import os
import random
import signal
import sys
import time
import zlib
from collections import defaultdict
from concurrent.futures import FIRST_COMPLETED, Future, ProcessPoolExecutor, wait
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Dict, Iterable, Iterator, List, Optional, Sequence, Set, Tuple

from .asr.audio import SAMPLE_RATE, read_audio, write_audio
from .asr.base import AsrDriver, AsrSpec, AsrUnavailable
from .asr.fetch import REPO_ROOT
from .channel import PRESETS, apply_channel
from .tts.base import TtsDriver, TtsUnavailable, VoiceInfo, choose_tts_input

DEFAULT_OUT = REPO_ROOT / ".cache" / "ml" / "roundtrip" / "out"
LANGS = ("ru", "en", "mixed")
ResultKey = Tuple[str, str, str, str, str]  # probe_id, tts_system, tts_voice, channel, asr_model


# ----------------------------------------------------------------------------------------- probes
@dataclass(frozen=True)
class Probe:
    id: str
    lang: str
    domain: str
    text: str
    terms: tuple
    tts_text: Optional[str]


def load_probes(paths: Sequence[Path]) -> List[Probe]:
    out: List[Probe] = []
    seen: Set[str] = set()
    for path in paths:
        with open(path, encoding="utf-8") as fh:
            for n, line in enumerate(fh, 1):
                if not line.strip():
                    continue
                r = json.loads(line)
                if r.get("lang") not in LANGS or not r.get("text") or not r.get("id"):
                    raise ValueError(f"{path}:{n}: not a probe row (needs id, lang in {LANGS}, text)")
                if r["id"] in seen:
                    raise ValueError(f"{path}:{n}: duplicate probe id {r['id']!r}")
                seen.add(r["id"])
                out.append(Probe(r["id"], r["lang"], r.get("domain", ""), r["text"],
                                 tuple(r.get("terms") or ()), r.get("tts_text") or None))
    return out


def take_balanced(probes: Sequence[Probe], limit: Optional[int]) -> List[Probe]:
    """First ``limit`` probes taken round-robin over languages (file order within a language)."""
    if not limit or limit >= len(probes):
        return list(probes)
    by_lang: Dict[str, List[Probe]] = defaultdict(list)
    for p in probes:
        by_lang[p.lang].append(p)
    queues = [by_lang[lang] for lang in LANGS if by_lang.get(lang)]
    out: List[Probe] = []
    i = 0
    while len(out) < limit:
        q = queues[i % len(queues)]
        idx = i // len(queues)
        if idx < len(q):
            out.append(q[idx])
        i += 1
        if i > limit * len(queues) + len(probes):
            break
    return out


# ------------------------------------------------------------------------------------ the matrix
@dataclass(frozen=True)
class Utterance:
    """One synthesised (and channel-processed) clip."""

    probe: Probe
    tts_system: str
    tts_voice: str
    tts_input: str  # "text", "tts_text" or "text_numbers" (see tts.base.choose_tts_input)
    say: str  # what the TTS actually read
    channel: str
    seed: int  # channel noise / reverb seed
    tts_seed: int  # same for every channel of one (probe, system, voice)

    @property
    def audio_rel(self) -> str:
        v = self.tts_voice.replace("#", "_").replace("/", "_")
        return f"audio/{self.tts_system}/{v}/{self.probe.id}.{self.channel}.flac"

    @property
    def clean_rel(self) -> str:
        v = self.tts_voice.replace("#", "_").replace("/", "_")
        return f"audio/{self.tts_system}/{v}/{self.probe.id}.clean.flac"


def stable_seed(*parts: object) -> int:
    return zlib.crc32("\x1f".join(map(str, parts)).encode("utf-8"))


def sample_voices(probe: Probe, system: str, voices: Sequence[VoiceInfo], k: int, seed: int) -> List[VoiceInfo]:
    """Deterministic per-sentence voice sample: same (seed, probe, system) -> same voices."""
    eligible = sorted((v for v in voices if v.speaks(probe.lang)), key=lambda v: v.voice)
    if not eligible:
        return []
    rng = random.Random(stable_seed(seed, probe.id, system))
    return rng.sample(eligible, min(k, len(eligible)))


def build_matrix(probes: Sequence[Probe], voices_by_system: Dict[str, List[VoiceInfo]], k: int,
                 channels: Sequence[str], seed: int) -> List[Utterance]:
    out: List[Utterance] = []
    for p in probes:
        for system, voices in voices_by_system.items():
            for v in sample_voices(p, system, voices, k, seed):
                say, src = choose_tts_input(p.text, p.tts_text, p.lang, v)
                for ch in channels:
                    out.append(Utterance(p, system, v.voice, src, say, ch,
                                         stable_seed(seed, p.id, system, v.voice, ch),
                                         stable_seed(seed, p.id, system, v.voice)))
    return out


@dataclass
class Plan:
    """An explicit job list (``--plan``): which voice reads which probe through which channel, which ASR
    models hear the clip, and the clip's wave. Waves order the work: wave 0 is finished (TTS, then every
    model) before later waves get priority, so a run stopped at any point has a balanced sample."""

    utts: List[Utterance]
    waves: Dict[str, int]  # audio_rel -> wave
    models: Dict[str, Tuple[str, ...]]  # audio_rel -> ASR model ids (before language routing)


def load_plan(path: Path, probes: Sequence[Probe], voices_by_system: Dict[str, List[VoiceInfo]],
              seed: int) -> Plan:
    """Read a plan JSONL: one line per clip ``{"probe_id", "tts_system", "tts_voice", "channel", "asr": [ids],
    "wave"}``. Seeds and TTS input are derived exactly as in :func:`build_matrix`, so a clip planned here is
    the same audio the matrix would make for that (probe, system, voice, channel)."""
    by_id = {p.id: p for p in probes}
    vinfo = {(s, v.voice): v for s, vs in voices_by_system.items() for v in vs}
    utts: List[Utterance] = []
    waves: Dict[str, int] = {}
    models: Dict[str, Tuple[str, ...]] = {}
    with open(path, encoding="utf-8") as fh:
        for n, line in enumerate(fh, 1):
            if not line.strip():
                continue
            r = json.loads(line)
            p = by_id.get(r["probe_id"])
            if p is None:
                raise ValueError(f"{path}:{n}: probe {r['probe_id']!r} is not in the --probe files")
            v = vinfo.get((r["tts_system"], r["tts_voice"]))
            if v is None:
                raise ValueError(f"{path}:{n}: voice {r['tts_system']}/{r['tts_voice']} is not available "
                                 f"(is the system in --tts?)")
            if not v.speaks(p.lang):
                raise ValueError(f"{path}:{n}: voice {v.voice} does not speak {p.lang}")
            ch = r.get("channel", "clean")
            if ch not in PRESETS:
                raise ValueError(f"{path}:{n}: unknown channel {ch!r}")
            say, src = choose_tts_input(p.text, p.tts_text, p.lang, v)
            u = Utterance(p, v.system, v.voice, src, say, ch, stable_seed(seed, p.id, v.system, v.voice, ch),
                          stable_seed(seed, p.id, v.system, v.voice))
            if u.audio_rel in waves:
                raise ValueError(f"{path}:{n}: duplicate clip {u.audio_rel}")
            utts.append(u)
            waves[u.audio_rel] = int(r.get("wave", 0))
            models[u.audio_rel] = tuple(r.get("asr") or ())
    return Plan(utts, waves, models)


def plan_jobs(plan: Plan, specs: Sequence[AsrSpec]) -> List[Tuple[Utterance, str]]:
    """(utterance, model) pairs of a plan: the clip's planned models that are selected and accept its language."""
    by_name = {s.name: s for s in specs}
    out: List[Tuple[Utterance, str]] = []
    for u in plan.utts:
        for m in plan.models[u.audio_rel] or tuple(by_name):
            s = by_name.get(m)
            if s is not None and s.accepts(u.probe.lang):
                out.append((u, m))
    return out


class ErrorLog(list):
    """The run's error list, also appended to a JSONL file as errors happen (progress tools read it while
    the run goes on; the report holds them only at the end)."""

    def __init__(self, path: Optional[Path] = None) -> None:
        super().__init__()
        self.path = path

    def append(self, item: dict) -> None:  # type: ignore[override]
        super().append(item)
        if self.path is not None:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            with open(self.path, "a", encoding="utf-8") as fh:
                fh.write(json.dumps({"t": round(time.time(), 1), **item}, ensure_ascii=False) + "\n")

    def extend(self, items: Iterable[dict]) -> None:  # type: ignore[override]
        for it in items:
            self.append(it)


class StopCheck:
    """True once ``stop_file`` exists or ``max_wall_s`` has passed since the run started: the runner then
    submits no new shard, lets the running ones finish (their rows are written) and exits with the rest
    pending. Rerunning the same command (after removing the stop file) continues."""

    def __init__(self, stop_file: Optional[Path] = None, max_wall_s: Optional[float] = None,
                 clock: Callable[[], float] = time.time) -> None:
        self.stop_file, self.clock = stop_file, clock
        self.deadline = clock() + max_wall_s if max_wall_s else None
        self.reason: Optional[str] = None

    def __call__(self) -> bool:
        if self.reason is None:
            if self.stop_file is not None and self.stop_file.exists():
                self.reason = f"stop file {self.stop_file}"
            elif self.deadline is not None and self.clock() >= self.deadline:
                self.reason = "wall-time cap (--max-wall-h)"
            if self.reason:
                print(f"stopping: {self.reason}; waiting for running shards", file=sys.stderr, flush=True)
        return self.reason is not None


def asr_jobs(utts: Sequence[Utterance], specs: Sequence[AsrSpec]) -> List[Tuple[Utterance, str]]:
    """Every (utterance, model) pair whose languages are compatible (see module docstring)."""
    return [(u, s.name) for u in utts for s in specs if s.accepts(u.probe.lang)]


def lang_hint(probe_lang: str) -> str:
    return "en" if probe_lang == "en" else "ru"


def result_key(row: dict) -> ResultKey:
    return (row["probe_id"], row["tts_system"], row["tts_voice"], row["channel"], row["asr_model"])


def read_done(path: Path) -> Set[ResultKey]:
    done: Set[ResultKey] = set()
    if not path.exists():
        return done
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            try:
                done.add(result_key(json.loads(line)))
            except (json.JSONDecodeError, KeyError):
                continue  # a torn last line from a killed run: that job simply re-runs
    return done


# ------------------------------------------------------------------------------- worker lifetime
# A worker whose runner died (SIGKILL, OOM kill of the main process) must not keep transcribing a shard
# whose results nobody will write: observed, 9 orphaned workers still alive 28 s after `kill -9` of the
# runner, one at 2 GB RSS for 6 more minutes, keeping the systemd scope alive.
_PARENT_PID: Optional[int] = None
_PR_SET_PDEATHSIG = 1


def _worker_init(parent_pid: int) -> None:
    """Pool initializer: die with the runner. Linux delivers SIGTERM when the parent thread exits
    (prctl PR_SET_PDEATHSIG); everywhere, shards also poll the parent pid between clips."""
    global _PARENT_PID
    _PARENT_PID = parent_pid
    if sys.platform.startswith("linux"):
        try:
            libc = ctypes.CDLL(None, use_errno=True)
            libc.prctl(_PR_SET_PDEATHSIG, int(signal.SIGTERM), 0, 0, 0)
        except (OSError, AttributeError):  # pragma: no cover - libc without prctl
            pass
    if os.getppid() != parent_pid:  # the parent died before the signal was armed
        os._exit(1)


def check_parent() -> None:
    """Exit the worker if its runner is gone (no-op outside pool workers)."""
    if _PARENT_PID is not None and os.getppid() != _PARENT_PID:
        os._exit(1)  # nobody is left to receive this shard's results


# --------------------------------------------------------------------------------- worker state
# Each worker process keeps at most one ASR model and one TTS system loaded at a time.
_TTS: Dict[str, TtsDriver] = {}
_ASR: Dict[str, AsrDriver] = {}
TtsFactory = Callable[[str, int], TtsDriver]
AsrFactory = Callable[[str, int], AsrDriver]


def _default_tts(system: str, threads: int) -> TtsDriver:
    from .tts.registry import get_tts

    return get_tts(system, threads)


def _default_asr(name: str, threads: int) -> AsrDriver:
    from .asr.registry import get_driver

    return get_driver(name, threads)


def _tts_driver(system: str, threads: int, factory: TtsFactory) -> TtsDriver:
    if system not in _TTS:
        for d in _TTS.values():
            d.unload()
        _TTS.clear()
        gc.collect()
        _TTS[system] = factory(system, threads)
    return _TTS[system]


def _asr_driver(name: str, threads: int, factory: AsrFactory) -> AsrDriver:
    if name not in _ASR:
        for d in _ASR.values():
            d.unload()
        _ASR.clear()
        gc.collect()
        _ASR[name] = factory(name, threads)
    return _ASR[name]


def _batch_synthesize(out: Path, utts: List[Utterance], threads: int, factory: TtsFactory,
                      batch: int) -> Dict[str, dict]:
    """GPU systems: synthesise every missing clean clip with ``synthesize_batch`` calls of ``batch``
    items. Returns clean_rel -> {wall_s, audio_s} for the clips written, or {error} for failed ones."""
    need: Dict[str, Utterance] = {}
    for u in utts:
        if u.clean_rel not in need and not (out / u.clean_rel).exists():
            need[u.clean_rel] = u
    done: Dict[str, dict] = {}
    if not need:
        return done
    drv = _tts_driver(utts[0].tts_system, threads, factory)
    items = list(need.values())
    for part in chunks(items, batch):
        try:
            for u in part:
                drv.prepare(u.tts_voice)
            t0 = time.perf_counter()
            xs = drv.synthesize_batch([(u.say, u.tts_voice, u.probe.lang, u.tts_seed) for u in part])
            wall = time.perf_counter() - t0
            total = sum(len(x) for x in xs) or 1
            for u, x in zip(part, xs):
                write_audio(out / u.clean_rel, x)
                # the batch's wall time is shared out in proportion to clip length
                done[u.clean_rel] = {"wall_s": wall * len(x) / total, "audio_s": len(x) / SAMPLE_RATE}
        except (TtsUnavailable, RuntimeError, ValueError, KeyError, OSError) as exc:
            for u in part:
                done[u.clean_rel] = {"error": f"{type(exc).__name__}: {exc}"}
    return done


def tts_shard(out_dir: str, utts: List[Utterance], threads: int,
              factory: TtsFactory = _default_tts, batch: int = 1) -> List[dict]:
    """Synthesise the clean clip (once) and every channel variant. Returns one status per utterance.

    With ``batch > 1`` and a GPU system, clean clips are made with batched ``synthesize_batch`` calls."""
    out = Path(out_dir)
    stats: List[dict] = []
    batched: Dict[str, dict] = {}
    if batch > 1 and utts and _tts_driver(utts[0].tts_system, threads, factory).gpu:
        batched = _batch_synthesize(out, utts, threads, factory, batch)
    for u in utts:
        check_parent()
        rec = {"key": (u.probe.id, u.tts_system, u.tts_voice, u.channel), "tts_system": u.tts_system,
               "audio_s": 0.0, "wall_s": 0.0, "error": None}
        try:
            clean = out / u.clean_rel
            b = batched.pop(u.clean_rel, None)
            if b and b.get("error"):
                raise RuntimeError(b["error"])
            if b:
                rec["wall_s"], rec["audio_s"] = b["wall_s"], b["audio_s"]
            if clean.exists():
                x = read_audio(clean)
            else:
                drv = _tts_driver(u.tts_system, threads, factory)
                drv.prepare(u.tts_voice)
                t0 = time.perf_counter()
                x = drv.synthesize(u.say, u.tts_voice, u.probe.lang, seed=u.tts_seed)
                rec["wall_s"] = time.perf_counter() - t0
                rec["audio_s"] = len(x) / SAMPLE_RATE
                write_audio(clean, x)
            target = out / u.audio_rel
            if u.channel != "clean" and not target.exists():
                write_audio(target, apply_channel(x, u.channel, seed=u.seed))
        except (TtsUnavailable, RuntimeError, ValueError, KeyError, OSError) as exc:
            rec["error"] = f"{type(exc).__name__}: {exc}"
        stats.append(rec)
    return stats


def asr_shard(out_dir: str, model: str, utts: List[Utterance], threads: int,
              factory: AsrFactory = _default_asr) -> Tuple[List[dict], Optional[str], List[dict]]:
    """Transcribe ``utts`` with one model.

    Returns (interface-2 rows, model-level error or None, per-clip errors)."""
    out = Path(out_dir)
    drv = _asr_driver(model, threads, factory)
    try:
        drv.load()
    except AsrUnavailable as exc:
        return [], str(exc), []
    rows: List[dict] = []
    clip_errors: List[dict] = []
    for u in utts:
        check_parent()
        try:
            x = read_audio(out / u.audio_rel)
            t0 = time.perf_counter()
            hyp = drv.transcribe(x, lang_hint(u.probe.lang))
            wall = time.perf_counter() - t0
        except Exception as exc:  # one bad clip must not kill the shard; it is retried next run
            clip_errors.append({"stage": "asr", "asr_model": model, "audio": u.audio_rel,
                                "error": f"{type(exc).__name__}: {exc}"})
            continue
        rows.append({
            "probe_id": u.probe.id, "lang": u.probe.lang, "text": u.probe.text, "terms": list(u.probe.terms),
            "tts_system": u.tts_system, "tts_voice": u.tts_voice, "channel": u.channel,
            "audio": u.audio_rel, "audio_s": round(len(x) / SAMPLE_RATE, 3), "asr_model": model,
            "hypothesis": hyp, "asr_wall_s": round(wall, 4), "tts_input": u.tts_input,
        })
    return rows, None, clip_errors


# ------------------------------------------------------------------------------------- execution
def chunks(seq: Sequence, n: int) -> Iterator[list]:
    for i in range(0, len(seq), max(1, n)):
        yield list(seq[i:i + n])


def shard_by_hint(utts: Sequence[Utterance], n: int) -> List[List[Utterance]]:
    """Split into chunks of at most ``n`` utterances that share one ASR language hint."""
    by_hint: Dict[str, List[Utterance]] = defaultdict(list)
    for u in utts:
        by_hint[lang_hint(u.probe.lang)].append(u)
    return [part for hint in sorted(by_hint) for part in chunks(by_hint[hint], n)]


class Progress:
    def __init__(self, stage: str, total: int, stream=sys.stderr) -> None:
        self.stage, self.total, self.done, self.t0, self.stream = stage, total, 0, time.time(), stream
        self._last = 0.0

    def add(self, n: int, force: bool = False) -> None:
        self.done += n
        now = time.time()
        if force or now - self._last > 5 or self.done >= self.total:
            el = now - self.t0
            eta = el / self.done * (self.total - self.done) if self.done else 0.0
            print(f"[{self.stage}] {self.done}/{self.total}  {el:7.1f}s elapsed  ETA {eta:7.1f}s",
                  file=self.stream, flush=True)
            self._last = now


def make_pool(workers: int) -> ProcessPoolExecutor:
    return ProcessPoolExecutor(max_workers=workers, mp_context=mp.get_context("spawn"),
                               initializer=_worker_init, initargs=(os.getpid(),))


def make_recycling_pool(workers: int) -> ProcessPoolExecutor:
    """Pool whose workers exit after one task. ASR shards switch models; onnxruntime does not hand
    a freed model's arena back to the OS, so long-lived workers grow until the scope is OOM-killed
    (observed: 8 workers reached 24 GB). A fresh process per shard costs one model load (1-3 s)."""
    return ProcessPoolExecutor(max_workers=workers, mp_context=mp.get_context("spawn"), max_tasks_per_child=1,
                               initializer=_worker_init, initargs=(os.getpid(),))


DEFAULT_MODEL_MEM_MB = 2000  # used for models without a measured peak RSS


def run_tts_stage(utts: List[Utterance], out: Path, workers: int, threads: int, shard: int,
                  errors: List[dict], pool_factory=make_pool, factory: TtsFactory = _default_tts,
                  batch: int = 1, waves: Optional[Dict[str, int]] = None,
                  stop: Optional[Callable[[], bool]] = None) -> Dict[str, dict]:
    """Synthesise every missing clip. Returns per-system {audio_s, wall_s, n}.

    With ``waves`` (audio_rel -> wave, from a plan) shards go out wave by wave; ``stop`` cancels the
    shards not started yet."""
    todo = [u for u in utts if not (out / u.audio_rel).exists()]
    wave_of = (lambda u: waves.get(u.audio_rel, 0)) if waves else (lambda u: 0)
    # Clean clips first so channel variants of the same voice reuse them; group by system.
    todo.sort(key=lambda u: (wave_of(u), u.tts_system, u.channel != "clean", u.tts_voice, u.probe.id))
    agg: Dict[str, dict] = defaultdict(lambda: {"audio_s": 0.0, "wall_s": 0.0, "n": 0})
    if not todo:
        return agg
    by_sys: Dict[Tuple[int, str], List[Utterance]] = defaultdict(list)
    for u in todo:
        by_sys[(wave_of(u), u.tts_system)].append(u)
    prog = Progress("tts", len(todo))
    with pool_factory(workers) as pool:
        futs: List[Future] = []
        for (_, system), items in sorted(by_sys.items()):
            # one voice's clean clip must exist before its channel variants: keep them in one shard
            groups: Dict[Tuple[str, str], List[Utterance]] = defaultdict(list)
            for u in items:
                groups[(u.tts_voice, u.probe.id)].append(u)
            flat = [grp for grp in groups.values()]
            for part in chunks(flat, shard):
                futs.append(pool.submit(tts_shard, str(out), [u for g in part for u in g], threads, factory,
                                        batch))
        cancelled = False
        for f in _as_completed(futs):
            if stop is not None and not cancelled and stop():
                for g in futs:
                    g.cancel()  # only shards that have not started; running ones finish
                cancelled = True
            if f.cancelled():
                continue
            for r in f.result():
                if r["error"]:
                    errors.append({"stage": "tts", "key": list(r["key"]), "error": r["error"]})
                elif r["wall_s"] > 0:
                    a = agg[r["tts_system"]]
                    a["audio_s"] += r["audio_s"]
                    a["wall_s"] += r["wall_s"]
                    a["n"] += 1
            prog.add(len(f.result()))
    return agg


MODEL_BUDGET_SHARE = 2 / 3  # one model may hold at most this share of the memory budget


def _bounded_submit(pool, shards: List[Tuple[str, list]], workers: int, budget: Optional[int],
                    mem: Dict[str, int], make_call,
                    stop: Optional[Callable[[], bool]] = None,
                    wave_of: Optional[Callable[[str, list], int]] = None,
                    lookahead: Optional[int] = None) -> Iterator[Tuple[Future, str, int]]:
    """Submit ``shards`` keeping at most ``workers`` in flight and their memory within ``budget``;
    yield (future, model, n_clips) as each completes. Order of submission follows ``shards``,
    except that a shard too big for the remaining budget lets smaller later ones go first. One model
    never holds more than ``MODEL_BUDGET_SHARE`` of the budget while shards of other models wait, so a
    memory-heavy model that goes first (whisper-medium) cannot keep every other model waiting.
    Once ``stop()`` is true nothing new is submitted; the running shards are still yielded.

    With ``wave_of`` and ``lookahead``, a shard is submitted only while its wave is at most ``lookahead``
    waves after the oldest wave that still has a queued or running shard. Without it the cheap models ran
    two waves ahead within 20 minutes while whisper-medium/turbo waited for memory, so a stop at that point
    would have left rows unbalanced across models."""
    queue = list(shards)
    qwave = [wave_of(m, part) if wave_of else 0 for m, part in queue]
    inflight: Dict[Future, Tuple[str, int, int]] = {}
    inflight_wave: Dict[Future, int] = {}
    used = 0
    per_model: Dict[str, int] = defaultdict(int)
    while queue or inflight:
        if stop is not None and queue and stop():
            queue.clear()
            qwave.clear()
            if not inflight:
                break
        limit = None
        if wave_of is not None and lookahead is not None and (qwave or inflight_wave):
            limit = min(qwave + list(inflight_wave.values())) + lookahead
        i = 0
        while i < len(queue) and len(inflight) < workers:
            m, part = queue[i]
            if limit is not None and qwave[i] > limit:
                i += 1
                continue
            need = mem.get(m, DEFAULT_MODEL_MEM_MB)
            others_waiting = any(q[0] != m for q in queue)
            fits = budget is None or (used + need <= budget and (
                not others_waiting or not per_model[m] or per_model[m] + need <= MODEL_BUDGET_SHARE * budget))
            if not inflight or fits:
                f = pool.submit(*make_call(m, part))
                inflight[f] = (m, len(part), need)
                inflight_wave[f] = qwave[i]
                used += need
                per_model[m] += need
                queue.pop(i)
                qwave.pop(i)
            else:
                i += 1
        done, _ = wait(set(inflight), return_when=FIRST_COMPLETED)
        for f in done:
            m, n, need = inflight.pop(f)
            inflight_wave.pop(f, None)
            used -= need
            per_model[m] -= need
            yield f, m, n


def _as_completed(futs: Iterable[Future]) -> Iterator[Future]:
    pending = set(futs)
    while pending:
        done, pending = wait(pending, return_when=FIRST_COMPLETED)
        yield from done


DEFAULT_XRT = 1.0  # audio-s per wall-s of one worker for models without a measured speed
SHARD_TARGET_S = 150.0  # expected wall time of one ASR shard


def clip_seconds(path: Path) -> float:
    """Duration of an audio file from its header (0.0 when unreadable; the shard then counts it as 1 s)."""
    try:
        import soundfile as sf

        return float(sf.info(str(path)).duration)
    except Exception:  # noqa: BLE001 - only a scheduling estimate
        return 0.0


def _interleave_waves(planned: List[Tuple[float, str, int, list]], wave_of: Callable[[Utterance], int]
                      ) -> List[Tuple[str, list]]:
    """Order shards wave by wave; inside a wave, round-robin over models (the model with the most work in
    that wave first, each model's longest shard first), so no model is finished before the others start
    and the rows written up to any moment are a balanced sample of the plan."""
    by_wave: Dict[int, Dict[str, List[Tuple[float, int, list]]]] = defaultdict(lambda: defaultdict(list))
    for est, m, k, part in planned:
        by_wave[wave_of(part[0])][m].append((est, k, part))
    out: List[Tuple[str, list]] = []
    for w in sorted(by_wave):
        models = by_wave[w]
        for m in models:
            models[m].sort(key=lambda t: (-t[0], t[1]))
        order = sorted(models, key=lambda m: (-sum(t[0] for t in models[m]), m))
        for r in range(max(len(v) for v in models.values())):
            for m in order:
                if r < len(models[m]):
                    out.append((m, models[m][r][2]))
    return out


def plan_asr_shards(by_model: Dict[str, List[Utterance]], out: Path, max_clips: int,
                    xrt: Dict[str, float], target_s: float = SHARD_TARGET_S,
                    duration: Callable[[Path], float] = clip_seconds,
                    waves: Optional[Dict[str, int]] = None) -> List[Tuple[str, list]]:
    """Cut each model's clips into shards of about ``target_s`` expected wall seconds (clip seconds
    divided by the model's x-realtime per worker), at most ``max_clips`` clips, one language hint per
    shard. Shards of the model with the most total expected work come first, each model's longest first.
    A fixed clip count let one whisper-medium shard run alone for 20 minutes at the end of a run while 7
    workers sat idle.

    With ``waves`` (audio_rel -> wave, from a plan) a shard never spans two waves and the order is
    :func:`_interleave_waves` instead: early cut-off then leaves a balanced sample."""
    wave_of = (lambda u: waves.get(u.audio_rel, 0)) if waves else (lambda u: 0)
    planned: List[Tuple[float, str, int, list]] = []
    for m, us in by_model.items():
        speed = max(1e-3, xrt.get(m, DEFAULT_XRT))
        groups: List[List[Utterance]] = []
        if waves:
            per_wave: Dict[int, List[Utterance]] = defaultdict(list)
            for u in us:
                per_wave[wave_of(u)].append(u)
            for w in sorted(per_wave):
                groups.extend(shard_by_hint(per_wave[w], max(1, len(per_wave[w]))))
        else:
            groups = shard_by_hint(us, max(1, len(us)))
        for k, part in enumerate(groups):
            cur: list = []
            cur_s = 0.0
            for u in part:
                est = max(1.0, duration(out / u.audio_rel)) / speed
                if cur and (cur_s + est > target_s or len(cur) >= max_clips):
                    planned.append((cur_s, m, len(planned), cur))
                    cur, cur_s = [], 0.0
                cur.append(u)
                cur_s += est
            if cur:
                planned.append((cur_s, m, len(planned), cur))
    if waves:
        return _interleave_waves(planned, wave_of)
    # the model with the most total work is the critical path (whisper-medium: ~3500 worker-s for 160
    # clips, and only 2-3 fit in memory at once): its shards go first, then longest shard first
    total: Dict[str, float] = defaultdict(float)
    for est, m, _, _ in planned:
        total[m] += est
    planned.sort(key=lambda t: (-total[t[1]], -t[0], t[1], t[2]))
    return [(m, part) for _, m, _, part in planned]


def run_asr_stage(jobs: List[Tuple[Utterance, str]], out: Path, results: Path, workers: int, threads: int,
                  shard: int, errors: List[dict], pool_factory=make_recycling_pool,
                  factory: AsrFactory = _default_asr, mem_mb: Optional[Dict[str, int]] = None,
                  mem_budget_mb: Optional[int] = None, xrt: Optional[Dict[str, float]] = None,
                  shard_target_s: float = SHARD_TARGET_S, waves: Optional[Dict[str, int]] = None,
                  stop: Optional[Callable[[], bool]] = None, lookahead: Optional[int] = None) -> Dict[str, dict]:
    """Transcribe every pending (utterance, model) job; append rows to ``results`` as shards finish.

    Shards come from :func:`plan_asr_shards` (``xrt``: model -> x-realtime of one worker; ``shard`` is
    the clip cap per shard). They are submitted only while the summed peak memory of the models in
    flight stays within ``mem_budget_mb`` (``mem_mb``: model -> peak RSS of one worker); at least one
    shard always runs."""
    by_model: Dict[str, List[Utterance]] = defaultdict(list)
    for u, m in jobs:
        if (out / u.audio_rel).exists():
            by_model[m].append(u)
    agg: Dict[str, dict] = defaultdict(lambda: {"audio_s": 0.0, "wall_s": 0.0, "n": 0})
    total = sum(len(v) for v in by_model.values())
    if not total:
        return agg
    prog = Progress("asr", total)
    dead: Set[str] = set()
    results.parent.mkdir(parents=True, exist_ok=True)
    # A shard holds one language hint only, so a Whisper worker never needs two recognisers.
    shards = plan_asr_shards(by_model, out, shard, xrt or {}, shard_target_s, waves=waves)
    mem = mem_mb or {}
    budget = mem_budget_mb if mem_budget_mb and mem_budget_mb > 0 else None
    with pool_factory(workers) as pool, open(results, "a", encoding="utf-8") as fh:
        for f, m, n in _bounded_submit(pool, shards, workers, budget, mem,
                                       lambda m, part: (asr_shard, str(out), m, part, threads, factory),
                                       stop=stop,
                                       wave_of=(lambda m, part: waves.get(part[0].audio_rel, 0)) if waves else None,
                                       lookahead=lookahead if waves else None):
            rows, err, clip_errors = f.result()
            errors.extend(clip_errors)
            if err:
                if m not in dead:
                    errors.append({"stage": "asr", "asr_model": m, "error": err})
                    dead.add(m)
            for r in rows:
                fh.write(json.dumps(r, ensure_ascii=False) + "\n")
                a = agg[m]
                a["audio_s"] += r["audio_s"]
                a["wall_s"] += r["asr_wall_s"]
                a["n"] += 1
            fh.flush()
            prog.add(n)
    return agg


# ------------------------------------------------------------------------------------- reporting
def throughput_table(tts: Dict[str, dict], asr: Dict[str, dict], threads: int) -> str:
    lines = [f"{'stage':5s} {'system':50s} {'n':>5s} {'audio_s':>9s} {'wall_s':>9s} {'x realtime':>10s}"
             f"  (per worker, {threads} threads)"]
    for stage, agg in (("tts", tts), ("asr", asr)):
        for name in sorted(agg):
            a = agg[name]
            rt = a["audio_s"] / a["wall_s"] if a["wall_s"] else float("nan")
            lines.append(f"{stage:5s} {name:50s} {a['n']:5d} {a['audio_s']:9.1f} {a['wall_s']:9.1f} {rt:10.2f}")
    return "\n".join(lines)


def _jsonable(agg: Dict[str, dict]) -> Dict[str, dict]:
    return {k: {**v, "x_realtime": (v["audio_s"] / v["wall_s"]) if v["wall_s"] else None} for k, v in agg.items()}


# ------------------------------------------------------------------------------------------ main
def resolve_voices(systems: Sequence[str], voice_filter: Optional[str], tts_factory: TtsFactory,
                   errors: List[dict]) -> Dict[str, List[VoiceInfo]]:
    want = set(voice_filter.split(",")) if voice_filter else None
    out: Dict[str, List[VoiceInfo]] = {}
    for s in systems:
        voices = tts_factory(s, 1).voices()
        if want:
            voices = [v for v in voices if v.voice in want or f"{s}/{v.voice}" in want]
        if not voices:
            errors.append({"stage": "tts", "tts_system": s, "error": "no voices available/selected"})
            continue
        out[s] = voices
    return out


def _spec_for(name: str) -> AsrSpec:
    from .asr.registry import spec_for

    return spec_for(name)


def measure(probes: List[Probe], systems: Sequence[str], asr_names: Sequence[str], out: Path, threads: int,
            n_per_lang: int, seed: int, tts_factory: TtsFactory = _default_tts,
            asr_factory: AsrFactory = _default_asr, spec_for: Callable[[str], AsrSpec] = _spec_for,
            stream=sys.stdout) -> dict:
    """Synthesise ``n_per_lang`` probes per language with one voice of every system, then run every
    ASR model on the clips it accepts; single process, ``threads`` threads. Prints a table."""
    picks = [p for lang in LANGS for p in [q for q in probes if q.lang == lang][:n_per_lang]]
    tts_agg: Dict[str, dict] = {}
    clips: List[Tuple[Probe, object]] = []
    status: Dict[str, str] = {}
    for s in systems:
        drv = tts_factory(s, threads)
        a = {"audio_s": 0.0, "wall_s": 0.0, "n": 0}
        try:
            drv.load()
            for p in picks:
                vs = sample_voices(p, s, drv.voices(), 1, seed)
                if not vs:
                    continue
                say, _ = choose_tts_input(p.text, p.tts_text, p.lang, vs[0])
                drv.prepare(vs[0].voice)
                t0 = time.perf_counter()
                x = drv.synthesize(say, vs[0].voice, p.lang, seed=seed)
                a["wall_s"] += time.perf_counter() - t0
                a["audio_s"] += len(x) / SAMPLE_RATE
                a["n"] += 1
                if not any(cp.id == p.id for cp, _ in clips):
                    clips.append((p, x))
            status[f"tts:{s}"] = "ok"
        except (TtsUnavailable, RuntimeError, ValueError) as exc:
            status[f"tts:{s}"] = f"unavailable: {exc}"
        finally:
            drv.unload()
        tts_agg[s] = a
    asr_agg: Dict[str, dict] = {}
    for name in asr_names:
        spec = spec_for(name)
        drv = asr_factory(name, threads)
        a = {"audio_s": 0.0, "wall_s": 0.0, "n": 0}
        try:
            drv.load()
            for p, x in clips:
                if not spec.accepts(p.lang):
                    continue
                t0 = time.perf_counter()
                drv.transcribe(x, lang_hint(p.lang))
                a["wall_s"] += time.perf_counter() - t0
                a["audio_s"] += len(x) / SAMPLE_RATE
                a["n"] += 1
            status[f"asr:{name}"] = "ok"
        except AsrUnavailable as exc:
            status[f"asr:{name}"] = f"unavailable: {exc}"
        finally:
            drv.unload()
        asr_agg[name] = a
    print(throughput_table(tts_agg, asr_agg, threads), file=stream)
    for k, v in status.items():
        if v != "ok":
            print(f"{k}: {v}", file=stream)
    report = {"threads": threads, "tts": _jsonable(tts_agg), "asr": _jsonable(asr_agg), "status": status}
    out.mkdir(parents=True, exist_ok=True)
    (out / "measure.json").write_text(json.dumps(report, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    return report


def parse_args(argv: Optional[Sequence[str]] = None) -> argparse.Namespace:
    ap = argparse.ArgumentParser(prog="python -m ml.roundtrip.run_roundtrip", description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--probe", nargs="+", required=True, type=Path, help="probe JSONL file(s) (interface 1)")
    ap.add_argument("--out", type=Path, default=DEFAULT_OUT, help=f"output dir (default {DEFAULT_OUT})")
    ap.add_argument("--run", default=None, help="results file name stem (default: first probe file stem)")
    ap.add_argument("--tts", default="cpu", help="cpu | gpu | all | comma list of TTS system ids")
    ap.add_argument("--voices", default=None, help="restrict to these voice ids (comma list, 'system/voice' ok)")
    ap.add_argument("--voices-per-sentence", type=int, default=1, help="voices sampled per sentence per system")
    ap.add_argument("--channels", default="clean", help=f"comma list of {sorted(PRESETS)}")
    ap.add_argument("--asr", default="all", help="'all' or comma list of ASR ids / unique substrings")
    ap.add_argument("--limit", type=int, default=None, help="use the first N probes (balanced over languages)")
    ap.add_argument("--seed", type=int, default=13)
    ap.add_argument("--workers", type=int, default=max(1, (os.cpu_count() or 2) // 2),
                    help="worker processes (each loads one model at a time)")
    ap.add_argument("--threads", type=int, default=2, help="intra-op threads per worker")
    ap.add_argument("--tts-shard", type=int, default=8, help="sentences per TTS task")
    ap.add_argument("--tts-batch", type=int, default=1,
                    help="GPU TTS only: clips per synthesize_batch call (use with --tts-shard >= this)")
    ap.add_argument("--ref-dir", default=None,
                    help="reference-voice directory for the GPU cloning systems (sets MURMUR_REFVOICE_DIR)")
    ap.add_argument("--asr-shard", type=int, default=48, help="at most this many clips per ASR task")
    ap.add_argument("--asr-shard-s", type=float, default=SHARD_TARGET_S,
                    help="expected wall seconds per ASR task (clip length / the model's measured x-realtime)")
    ap.add_argument("--allow-errors", action="store_true",
                    help="exit 0 even when jobs failed or stayed pending (default: exit 1)")
    ap.add_argument("--mem-gb", type=float, default=14.0,
                    help="cap on the summed peak RSS of ASR models in flight (tools/heavy default scope is 16G)")
    ap.add_argument("--skip-asr", action="store_true", help="only synthesise audio")
    ap.add_argument("--measure", action="store_true", help="print audio-s per wall-s for every TTS and ASR system")
    ap.add_argument("--measure-n", type=int, default=3, help="probes per language in --measure")
    ap.add_argument("--plan", type=Path, default=None,
                    help="explicit clip list (JSONL: probe_id, tts_system, tts_voice, channel, asr, wave) instead of "
                         "the sampled matrix; work goes out wave by wave, models interleaved (ml/roundtrip/full_plan.py)")
    ap.add_argument("--stop-file", type=Path, default=None,
                    help="when this file appears, submit nothing new, finish running shards, exit (resumable)")
    ap.add_argument("--asr-lookahead", type=int, default=2,
                    help="with --plan: ASR shards may run at most this many waves ahead of the oldest unfinished wave")
    ap.add_argument("--max-wall-h", type=float, default=None,
                    help="same as the stop file once this many hours have passed since this invocation started")
    args = ap.parse_args(argv)
    bad = [c for c in args.channels.split(",") if c not in PRESETS]
    if bad:
        ap.error(f"unknown channel(s) {bad}; known: {sorted(PRESETS)}")
    return args


def execute(args: argparse.Namespace, tts_factory: TtsFactory = _default_tts,
            asr_factory: AsrFactory = _default_asr, spec_for: Callable[[str], AsrSpec] = _spec_for,
            pool_factory=None, systems: Optional[List[str]] = None,
            models: Optional[List[str]] = None) -> dict:
    """Run the round trip described by ``args``; returns the report (also written next to the results)."""
    if getattr(args, "ref_dir", None):
        os.environ["MURMUR_REFVOICE_DIR"] = str(args.ref_dir)  # spawned workers inherit it
    if systems is None:
        from .tts.registry import resolve_systems

        systems = resolve_systems(args.tts)
    if models is None:
        from .asr.registry import resolve_models

        models = resolve_models(args.asr)
    probes = take_balanced(load_probes(args.probe), args.limit)
    if args.measure:
        return measure(probes, systems, models, args.out, args.threads, args.measure_n, args.seed,
                       tts_factory, asr_factory, spec_for)

    t_start = time.time()
    run = args.run or args.probe[0].stem
    results = args.out / "roundtrip" / f"{run}.jsonl"
    plan_path = getattr(args, "plan", None)
    # errors are also appended to <run>.errors.jsonl as they happen when a plan drives the run
    errors: List[dict] = ErrorLog(results.with_suffix(".errors.jsonl") if plan_path else None)
    stop = StopCheck(getattr(args, "stop_file", None),
                     args.max_wall_h * 3600 if getattr(args, "max_wall_h", None) else None)
    voices = resolve_voices(systems, args.voices, tts_factory, errors)
    specs = [spec_for(m) for m in models]
    waves: Optional[Dict[str, int]] = None
    if plan_path:
        plan = load_plan(plan_path, probes, voices, args.seed)
        utts, waves = plan.utts, plan.waves
        all_jobs = plan_jobs(plan, specs)
    else:
        utts = build_matrix(probes, voices, args.voices_per_sentence, args.channels.split(","), args.seed)
        all_jobs = asr_jobs(utts, specs)
    done = read_done(results)
    jobs = [(u, m) for u, m in all_jobs if (u.probe.id, u.tts_system, u.tts_voice, u.channel, m) not in done]
    print(f"{len(probes)} probes, {len(utts)} utterances, {len(jobs)} pending ASR jobs "
          f"({len(done)} already done) -> {results}", file=sys.stderr, flush=True)
    if isinstance(errors, ErrorLog) and errors.path is not None:
        errors.path.parent.mkdir(parents=True, exist_ok=True)
        with open(errors.path, "a", encoding="utf-8") as fh:  # progress tools count errors after this line
            fh.write(json.dumps({"t": round(time.time(), 1), "event": "start", "jobs_total": len(all_jobs),
                                 "jobs_pending": len(jobs), "pid": os.getpid()}) + "\n")
    need_audio = list({u.audio_rel: u for u, _ in jobs}.values()) if not args.skip_asr else utts
    tts_agg = run_tts_stage(need_audio, args.out, args.workers, args.threads, args.tts_shard, errors,
                            pool_factory or make_pool, tts_factory, args.tts_batch, waves=waves, stop=stop)
    asr_agg: Dict[str, dict] = {}
    if not args.skip_asr:
        mem = {s.name: int(s.extra.get("mem_mb", DEFAULT_MODEL_MEM_MB)) for s in specs}
        xrt = {s.name: float(s.extra.get("xrt", DEFAULT_XRT)) for s in specs}
        asr_agg = run_asr_stage(jobs, args.out, results, args.workers, args.threads, args.asr_shard, errors,
                                pool_factory or make_recycling_pool, asr_factory, mem,
                                int(args.mem_gb * 1024), xrt, getattr(args, "asr_shard_s", SHARD_TARGET_S),
                                waves=waves, stop=stop, lookahead=getattr(args, "asr_lookahead", 2))
        done_now = read_done(results)
        pending = [(u, m) for u, m in jobs if (u.probe.id, u.tts_system, u.tts_voice, u.channel, m) not in done_now]
    else:
        pending = [u for u in need_audio if not (args.out / u.audio_rel).exists()]
    wall = time.time() - t_start
    print(throughput_table(tts_agg, asr_agg, args.threads))
    print(f"total wall {wall:.1f}s with {args.workers} workers x {args.threads} threads; "
          f"{len(errors)} errors", flush=True)
    for e in errors[:20]:
        print(f"  error: {json.dumps(e, ensure_ascii=False)[:300]}", file=sys.stderr)
    if pending:
        print(f"{len(pending)} job(s) still pending; rerun the same command to retry them", file=sys.stderr)
    report = {"run": run, "probes": len(probes), "utterances": len(utts), "jobs": len(jobs),
              "pending": len(pending), "stopped": stop.reason, "plan": str(plan_path) if plan_path else None,
              "workers": args.workers, "threads": args.threads, "wall_s": round(wall, 1),
              "tts": _jsonable(tts_agg), "asr": _jsonable(asr_agg), "errors": errors,
              "args": {k: str(v) for k, v in vars(args).items()}}
    (args.out / "roundtrip").mkdir(parents=True, exist_ok=True)
    (args.out / "roundtrip" / f"{run}.report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    return report


def exit_status(report: dict, allow_errors: bool) -> int:
    """0 when every job produced a row and nothing failed; 1 otherwise (unless ``allow_errors``)."""
    if allow_errors:
        return 0
    return 1 if report.get("errors") or report.get("pending") else 0


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = parse_args(argv)
    report = execute(args)
    if args.measure:
        bad = [k for k, v in report.get("status", {}).items() if v != "ok"]
        return 0 if args.allow_errors or not bad else 1
    return exit_status(report, args.allow_errors)


if __name__ == "__main__":
    sys.exit(main())
