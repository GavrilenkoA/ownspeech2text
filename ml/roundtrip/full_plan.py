"""Build the clip plan for the full CPU round trip (``run_roundtrip --plan``) and print its arithmetic.

Deterministic for a given ``--seed``. Decisions (docs/ml/ROUNDTRIP.md, "Full run plan"):

- **Sentences**: every lexicon entry covered at least once (greedy set cover over seed + generated),
  then filled to ``--n`` by stratified sampling over (language, category of the first term, seed/generated)
  in proportion to the whole probe set.
- **Voices**: one clip per sentence. ``en``: rotated over piper / supertonic3 / kokoro1 and over each system's
  voices. ``ru`` and ``mixed``: Supertonic's ten voices, rotated. Piper's two Russian voices (male, 3-4x the
  error-only WER of Supertonic on the same kind of text) appear only as the *second* voice of the
  ``--second-share`` sentences, so the primary set is free of them and the pairs measure the voice effect.
  ``en`` second voices come from another CPU system than the first.
- **Channel**: ``--noisy-share`` of sentences (both of their clips) go through ``phone_noisy``.
- **ASR**: every CPU model; whisper-medium only on ``--medium-share`` of the clips (balanced per language,
  system and channel). Language routing is the runner's.
- **Order**: sentences interleaved over languages and categories, cut into waves of ``--wave`` sentences.

Usage::

  python -m ml.roundtrip.full_plan --out .cache/ml/roundtrip/out/full/plan.jsonl   # writes plan + summary
"""
from __future__ import annotations

import argparse
import json
import sys
import zlib
from collections import Counter, defaultdict
from pathlib import Path
from typing import Dict, List, Optional, Sequence, Tuple

ROOT = Path(__file__).resolve().parents[2]
PROBE_DIR = ROOT / "ml" / "roundtrip" / "probe"
SAMPLES = ROOT / "ml" / "roundtrip" / "samples" / "roundtrip"
LANGS = ("ru", "en", "mixed")

# Voices (from `python -m ml.roundtrip.tts list --systems cpu`, 2026-11-19). Kept here so planning needs no
# model download; the runner checks every planned voice against the live registry.
PIPER_RU = ["ru_RU-denis-medium", "ru_RU-dmitri-medium"]
PIPER_EN = ["en_US-joe-medium", "en_US-john-medium", "en_US-kristin-medium", "en_US-norman-medium",
            "en_GB-alba-medium", "en_GB-cori-medium", "en_US-libritts_r-medium#0", "en_US-libritts_r-medium#117",
            "en_US-libritts_r-medium#263", "en_US-libritts_r-medium#509", "en_US-libritts_r-medium#771"]
SUPERTONIC = [f"sid{i}" for i in range(10)]
KOKORO_EN = ["af_alloy", "af_aoede", "af_bella", "af_heart", "af_jessica", "af_kore", "af_nicole", "af_nova",
             "af_river", "af_sarah", "af_sky", "am_adam", "am_echo", "am_eric", "am_fenrir", "am_liam",
             "am_michael", "am_onyx", "am_puck", "bf_alice", "bf_emma", "bf_isabella", "bf_lily", "bm_daniel",
             "bm_fable", "bm_george", "bm_lewis"]  # am_santa (a novelty voice) left out
EN_SYSTEMS = {"piper": PIPER_EN, "supertonic3": SUPERTONIC, "kokoro1": KOKORO_EN}

ASR_MODELS = ["parakeet-tdt-0.6b-v3@onnx-int8", "parakeet-ultra-0.6b@onnx-int8",
              "nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms", "gigaam-v3-e2e-rnnt@onnx-int8",
              "gigaam-v3-e2e-ctc@onnx-int8", "t-one@onnx", "qwen3-asr-0.6b@onnx-int8",
              "whisper-large-v3-turbo@onnx-int8", "distil-large-v3.5@onnx-int8", "whisper-small@onnx-int8",
              "nemotron-speech-streaming-en-0.6b@onnx-int8-560ms", "whisper-medium@onnx-int8"]
MEDIUM = "whisper-medium@onnx-int8"
EN_ONLY = {"distil-large-v3.5@onnx-int8", "nemotron-speech-streaming-en-0.6b@onnx-int8-560ms"}
RU_ONLY = {"gigaam-v3-e2e-rnnt@onnx-int8", "gigaam-v3-e2e-ctc@onnx-int8", "t-one@onnx"}

# Audio seconds per word of `text`, measured on the final and local-proof runs (2026-11-19, 42/41/42 clips
# per cell for piper/supertonic3, 15 for kokoro1).
SEC_PER_WORD = {("piper", "en"): 0.321, ("piper", "ru"): 0.389, ("piper", "mixed"): 0.438,
                ("supertonic3", "en"): 0.404, ("supertonic3", "ru"): 0.492, ("supertonic3", "mixed"): 0.531,
                ("kokoro1", "en"): 0.358}


def accepts(model: str, lang: str) -> bool:
    if model in EN_ONLY:
        return lang == "en"
    if model in RU_ONLY:
        return lang != "en"
    return True


def h(*parts: object) -> int:
    return zlib.crc32("\x1f".join(map(str, parts)).encode("utf-8"))


# ------------------------------------------------------------------------------------------ input
def load_probes(paths: Sequence[Path]) -> List[dict]:
    out = []
    for p in paths:
        for line in p.read_text(encoding="utf-8").splitlines():
            if line.strip():
                r = json.loads(line)
                r["_source"] = "seed" if r["id"].startswith("seed-") else "gen"
                out.append(r)
    return out


class Lexicon(dict):
    """term -> category, plus the probe matcher that resolves a probe's term surfaces (``задеплоили``) to
    their lexicon entry (``задеплоить``)."""

    def __init__(self, path: Path) -> None:
        from .probe.lexicon import TermMatcher, load_lexicon as _load

        entries = _load(path)
        super().__init__({e.term: e.category for e in entries})
        self.matcher = TermMatcher(entries)
        self._cache: Dict[str, Optional[str]] = {}

    def entry(self, surface: str) -> Optional[str]:
        if surface not in self._cache:
            e = self.matcher.resolve(surface)
            self._cache[surface] = e.term if e is not None else None
        return self._cache[surface]


def load_lexicon(path: Path) -> Lexicon:
    return Lexicon(path)


def lex_terms(r: dict, lex: Lexicon) -> List[str]:
    """Lexicon entries of a probe's terms (person names and other non-lexicon terms dropped)."""
    if "_lex" not in r:
        ents = [lex.entry(t["term"]) for t in r.get("terms") or [] if t.get("category") in set(lex.values())]
        r["_lex"] = [e for e in ents if e is not None]
    return r["_lex"]


def stratum(r: dict, lex: Dict[str, str]) -> Tuple[str, str, str]:
    ts = lex_terms(r, lex)
    return (r["lang"], lex[ts[0]] if ts else "-", r["_source"])


# ------------------------------------------------------------------------------------- selection
def select(probes: List[dict], lex: Dict[str, str], n: int, seed: int) -> List[dict]:
    """Greedy cover of every lexicon entry, then stratified fill to ``n`` sentences."""
    full = Counter(stratum(r, lex) for r in probes)
    total = len(probes)
    target = {s: n * c / total for s, c in full.items()}
    chosen: List[dict] = []
    have: Counter = Counter()
    covered: set = set()
    pool = sorted(probes, key=lambda r: h(seed, r["id"]))
    remaining = {r["id"]: r for r in pool}
    all_entries = {t for r in probes for t in lex_terms(r, lex)}
    while covered != all_entries:
        best = max(remaining.values(), key=lambda r: (
            len(set(lex_terms(r, lex)) - covered),
            target[stratum(r, lex)] - have[stratum(r, lex)],  # prefer strata still below their share
            -h(seed, r["id"])))
        gain = set(lex_terms(best, lex)) - covered
        if not gain:
            break
        covered |= gain
        chosen.append(best)
        have[stratum(best, lex)] += 1
        del remaining[best["id"]]
    by_stratum: Dict[Tuple[str, str, str], List[dict]] = defaultdict(list)
    for r in remaining.values():
        by_stratum[stratum(r, lex)].append(r)
    while len(chosen) < n:
        cands = [s for s in by_stratum if by_stratum[s]]
        if not cands:
            break
        s = max(cands, key=lambda s: (target[s] - have[s], -h(seed, *s)))
        r = by_stratum[s].pop(0)
        chosen.append(r)
        have[s] += 1
    return chosen


def interleave(chosen: List[dict], lex: Dict[str, str], seed: int) -> List[dict]:
    """Order: within a language, round-robin over categories (shuffled per category); languages merged in
    proportion to their counts, so every prefix has the same mix."""
    per_lang: Dict[str, List[dict]] = {}
    for lang in LANGS:
        cats: Dict[str, List[dict]] = defaultdict(list)
        for r in sorted((r for r in chosen if r["lang"] == lang), key=lambda r: h(seed, "order", r["id"])):
            cats[stratum(r, lex)[1]].append(r)
        order = sorted(cats, key=lambda c: h(seed, lang, c))
        seq: List[dict] = []
        while any(cats.values()):
            for c in order:
                if cats[c]:
                    seq.append(cats[c].pop(0))
        per_lang[lang] = seq
    total = sum(len(v) for v in per_lang.values())
    taken = Counter()
    out: List[dict] = []
    while len(out) < total:
        lang = min((lg for lg in LANGS if taken[lg] < len(per_lang[lg])),
                   key=lambda lg: ((taken[lg] + 0.5) / len(per_lang[lg]), LANGS.index(lg)))
        out.append(per_lang[lang][taken[lang]])
        taken[lang] += 1
    return out


class Rotor:
    """Round-robin over a list, one counter per key."""

    def __init__(self) -> None:
        self.i: Counter = Counter()

    def next(self, key: object, items: Sequence) -> object:
        x = items[self.i[key] % len(items)]
        self.i[key] += 1
        return x


class Share:
    """Deterministic, evenly spread yes/no at a given rate, one accumulator per key."""

    def __init__(self, rate: float) -> None:
        self.rate, self.n = rate, Counter()

    def take(self, key: object) -> bool:
        k = self.n[key]
        self.n[key] += 1
        return int((k + 1) * self.rate + 1e-9) > int(k * self.rate + 1e-9)


def build_plan(ordered: List[dict], second_share: float, noisy_share: float, medium_share: float,
               wave: int) -> List[dict]:
    rot, second, noisy, medium = Rotor(), Share(second_share), Share(noisy_share), Share(medium_share)
    clips: List[dict] = []
    for i, r in enumerate(ordered):
        lang = r["lang"]
        voices: List[Tuple[str, str]] = []
        if lang == "en":
            sys1 = rot.next("en-sys", list(EN_SYSTEMS))
            voices.append((sys1, rot.next(sys1, EN_SYSTEMS[sys1])))
            if second.take(lang):
                others = [s for s in EN_SYSTEMS if s != sys1]
                sys2 = others[rot.next(("en-sys2", sys1), [0, 1])]
                voices.append((sys2, rot.next(sys2, EN_SYSTEMS[sys2])))
        else:
            voices.append(("supertonic3", rot.next("supertonic3", SUPERTONIC)))
            if second.take(lang):
                voices.append(("piper", rot.next("piper-ru", PIPER_RU)))
        channel = "phone_noisy" if noisy.take(lang) else "clean"
        for system, voice in voices:
            models = [m for m in ASR_MODELS if accepts(m, lang)]
            if not medium.take((lang, system, channel)):
                models.remove(MEDIUM)
            clips.append({"probe_id": r["id"], "lang": lang, "tts_system": system, "tts_voice": voice,
                          "channel": channel, "asr": models, "wave": i // wave,
                          "words": len(r["text"].split())})
    return clips


def build_gpu_plan(ordered: List[dict], system: str, refs: Sequence[Tuple[str, str]], n: int,
                   medium_share: float, wave: int) -> List[dict]:
    """Pod plan for one GPU cloning system: the first ``n`` sentences of the CPU run's order (so most of them
    also exist read by a CPU voice), one clean clip each, voices rotated over the reference clips of the
    sentence's spoken language (``refs``: (voice_id, "ru"|"en")). ASR models as in the CPU plan, for the
    later CPU ASR stage."""
    by_lang = {lg: sorted(v for v, l in refs if l == lg) for lg in ("ru", "en")}
    rot, medium = Rotor(), Share(medium_share)
    clips: List[dict] = []
    for i, r in enumerate(ordered[:n]):
        spoken = "en" if r["lang"] == "en" else "ru"
        pool = by_lang[spoken] or by_lang["en" if spoken == "ru" else "ru"]
        if not pool:
            raise ValueError("no reference voices")
        voice = rot.next(spoken, pool)
        models = [m for m in ASR_MODELS if accepts(m, r["lang"])]
        if not medium.take(r["lang"]):
            models.remove(MEDIUM)
        clips.append({"probe_id": r["id"], "lang": r["lang"], "tts_system": system, "tts_voice": voice,
                      "channel": "clean", "asr": models, "wave": i // wave, "words": len(r["text"].split())})
    return clips


def read_refs(ref_dir: Path) -> List[Tuple[str, str]]:
    """(voice_id, lang) of the accepted reference clips (same rules as the GPU drivers)."""
    from .tts.refvoices import load_ref_voices

    ok, _ = load_ref_voices(ref_dir)
    return [(r.voice_id, r.lang) for r in ok]


# ------------------------------------------------------------------------------------- arithmetic
def run_speeds(report: Path, measure: Path) -> Tuple[Dict[str, float], Dict[str, float]]:
    """Per-worker x-realtime inside the final 8-worker run; Kokoro (not in that run) scaled from --measure."""
    rep = json.loads(report.read_text())
    mea = json.loads(measure.read_text())
    tts = {k: v["x_realtime"] for k, v in rep["tts"].items()}
    tts["kokoro1"] = tts["supertonic3"] * mea["tts"]["kokoro1"]["x_realtime"] / mea["tts"]["supertonic3"]["x_realtime"]
    asr = {k: v["x_realtime"] for k, v in rep["asr"].items()}
    return tts, asr


def estimate(clips: List[dict], tts_xrt: Dict[str, float], asr_xrt: Dict[str, float], workers: int,
             eff_workers: float) -> dict:
    audio = Counter()
    tts_ws = 0.0
    asr_ws = Counter()
    rows = Counter()
    for c in clips:
        a = c["words"] * SEC_PER_WORD[(c["tts_system"], c["lang"])]
        audio[c["tts_system"]] += a
        tts_ws += a / tts_xrt[c["tts_system"]]
        for m in c["asr"]:
            if accepts(m, c["lang"]):
                asr_ws[m] += a / asr_xrt[m]
                rows[m] += 1
    asr_total = sum(asr_ws.values())
    tts_wall = tts_ws / workers
    asr_wall = asr_total / eff_workers
    return {"audio_s": {k: round(v) for k, v in audio.items()}, "audio_total_s": round(sum(audio.values())),
            "tts_worker_s": round(tts_ws), "tts_wall_s": round(tts_wall),
            "asr_worker_s": {k: round(v) for k, v in sorted(asr_ws.items(), key=lambda kv: -kv[1])},
            "asr_worker_s_total": round(asr_total), "medium_share_of_asr": round(asr_ws[MEDIUM] / asr_total, 3),
            "asr_wall_s": round(asr_wall), "wall_s": round(tts_wall + asr_wall), "wall_h": round((tts_wall + asr_wall) / 3600, 2),
            "rows": dict(rows), "rows_total": sum(rows.values()), "workers": workers, "eff_workers": eff_workers}


def summary(chosen: List[dict], all_probes: List[dict], lex: Dict[str, str], clips: List[dict]) -> dict:
    occ = sum(len(lex_terms(r, lex)) for r in chosen)
    entries = {t for r in chosen for t in lex_terms(r, lex)}
    by = lambda key: dict(sorted(Counter(key(c) for c in clips).items()))  # noqa: E731
    return {
        "sentences": len(chosen), "of": len(all_probes),
        "by_lang": dict(Counter(r["lang"] for r in chosen)),
        "by_source": dict(Counter(r["_source"] for r in chosen)),
        "by_category": dict(sorted(Counter(stratum(r, lex)[1] for r in chosen).items())),
        "lexicon_entries_covered": len(entries), "lexicon_entries": len(lex), "term_occurrences": occ,
        "clips": len(clips), "second_voice_sentences": len(clips) - len(chosen),
        "clips_by_lang_system": {f"{k[0]}/{k[1]}": v for k, v in by(lambda c: (c["lang"], c["tts_system"])).items()},
        "clips_by_voice": {f"{k[0]}/{k[1]}": v for k, v in by(lambda c: (c["tts_system"], c["tts_voice"])).items()},
        "clips_by_channel": by(lambda c: c["channel"]),
        "clips_with_medium": sum(MEDIUM in c["asr"] for c in clips),
        "waves": max(c["wave"] for c in clips) + 1,
    }


def main(argv: Optional[Sequence[str]] = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0], epilog=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--probe", nargs="+", type=Path,
                    default=[PROBE_DIR / "seed.jsonl", PROBE_DIR / "gen.jsonl"])
    ap.add_argument("--lexicon", type=Path, default=PROBE_DIR / "terms.jsonl")
    ap.add_argument("--n", type=int, default=1000, help="sentences")
    ap.add_argument("--second-share", type=float, default=0.15)
    ap.add_argument("--noisy-share", type=float, default=0.20)
    ap.add_argument("--medium-share", type=float, default=0.5)
    ap.add_argument("--wave", type=int, default=40, help="sentences per wave")
    ap.add_argument("--seed", type=int, default=13)
    ap.add_argument("--workers", type=int, default=8)
    ap.add_argument("--eff-workers", type=float, default=5.0,
                    help="busy ASR workers on average (final run: 4.15 overall, ~5 before its memory-bound tail)")
    ap.add_argument("--report", type=Path, default=SAMPLES / "final80.report.json")
    ap.add_argument("--measure", type=Path, default=SAMPLES / "measure.json")
    ap.add_argument("--out", type=Path, default=None, help="plan JSONL (a .summary.json is written next to it)")
    ap.add_argument("--gpu-system", default=None,
                    help="instead: write the pod plan of this GPU cloning system (qwen3tts-1.7b, chatterbox-mtl)")
    ap.add_argument("--gpu-n", type=int, default=300, help="sentences of the pod plan (a prefix of the CPU order)")
    ap.add_argument("--ref-dir", type=Path, default=None, help="reference voices for --gpu-system")
    args = ap.parse_args(argv)
    if args.gpu_system:
        if not args.ref_dir or not args.out:
            ap.error("--gpu-system needs --ref-dir and --out")
        probes = load_probes(args.probe)
        lex = load_lexicon(args.lexicon)
        ordered = interleave(select(probes, lex, args.n, args.seed), lex, args.seed)
        refs = read_refs(args.ref_dir)
        clips = build_gpu_plan(ordered, args.gpu_system, refs, args.gpu_n, args.medium_share, args.wave)
        args.out.parent.mkdir(parents=True, exist_ok=True)
        with open(args.out, "w", encoding="utf-8") as fh:
            for c in clips:
                fh.write(json.dumps({k: c[k] for k in ("probe_id", "tts_system", "tts_voice", "channel", "asr",
                                                       "wave")}, ensure_ascii=False) + "\n")
        words = Counter()
        for c in clips:
            words[c["lang"]] += c["words"]
        print(json.dumps({"system": args.gpu_system, "clips": len(clips), "refs": len(refs),
                          "by_lang": dict(Counter(c["lang"] for c in clips)),
                          "by_voice": dict(Counter(c["tts_voice"] for c in clips)), "words": dict(words),
                          # Supertonic's seconds per word as a stand-in until the pod measures the system
                          "audio_s_est": round(sum(c["words"] * SEC_PER_WORD[("supertonic3", c["lang"])]
                                                   for c in clips))}))
        return 0

    probes = load_probes(args.probe)
    lex = load_lexicon(args.lexicon)
    chosen = select(probes, lex, args.n, args.seed)
    ordered = interleave(chosen, lex, args.seed)
    clips = build_plan(ordered, args.second_share, args.noisy_share, args.medium_share, args.wave)
    tts_xrt, asr_xrt = run_speeds(args.report, args.measure)
    est = estimate(clips, tts_xrt, asr_xrt, args.workers, args.eff_workers)
    summ = summary(chosen, probes, lex, clips)
    full = {"args": {k: str(v) for k, v in vars(args).items()}, "selection": summ, "estimate": est,
            "speeds": {"tts": tts_xrt, "asr": asr_xrt}}
    print(json.dumps(full, ensure_ascii=False, indent=1))
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        with open(args.out, "w", encoding="utf-8") as fh:
            for c in clips:
                fh.write(json.dumps({k: c[k] for k in ("probe_id", "tts_system", "tts_voice", "channel", "asr",
                                                       "wave")}, ensure_ascii=False) + "\n")
        args.out.with_suffix(".summary.json").write_text(json.dumps(full, ensure_ascii=False, indent=1) + "\n",
                                                         encoding="utf-8")
        print(f"wrote {len(clips)} clips -> {args.out}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
