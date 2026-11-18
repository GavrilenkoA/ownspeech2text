"""ASR channel adapter: clean spoken text -> simulated raw hypothesis of one ASR model.

Two back ends behind one interface, recorded in meta.noise.source:
  mined:<dir>  ml/roundtrip/mine/noise.py (NoiseModel) with error tables mined from the real TTS -> ASR probe
               (owned by ml/roundtrip; read-only here). Used when --mined DIR (or $MURMUR_MINED_DIR) has
               profiles.json + confusions.jsonl + term_manglings.jsonl.
  builtin      ml/data/synth/asr_noise.py, hand-set priors per engine (fallback while mined tables are absent).

The teacher writes `spoken` with numbers in words. Engines whose mined profile prints digits get the
numbers digitised first (at the profile's digit rate); noise.py then applies the model's own style,
term manglings, confusions, deletions and hallucinations.
"""
from __future__ import annotations

import os
import random
import re
from pathlib import Path

from ml.common import text as T
from ml.data.synth import asr_noise as AN

BUILTIN_LANGS = {"whisper": ("ru", "en", "mixed"), "parakeet_v3": ("ru", "en", "mixed"),
                 "nemotron_3_5": ("ru", "en", "mixed"), "gigaam_v3_e2e": ("ru", "mixed"), "t_one": ("ru",),
                 "qwen3_asr": ("ru", "en", "mixed")}
RU_ONLY_HINTS = ("gigaam", "t-one", "t_one")


class Channel:
    def __init__(self, mined_dir: str | os.PathLike | None = None):
        mined_dir = mined_dir or os.environ.get("MURMUR_MINED_DIR") or None
        self.nm = None
        if mined_dir and (Path(mined_dir) / "profiles.json").exists():
            from ml.roundtrip.mine.noise import NoiseModel  # read-only dependency on the round-trip team
            self.nm = NoiseModel.load(mined_dir)
            self.source = "mined:" + Path(mined_dir).name
        elif mined_dir:
            raise FileNotFoundError(f"no profiles.json in {mined_dir}")
        else:
            self.source = "builtin"

    # ------------------------------------------------------------------ models
    def models(self, lang: str) -> list[str]:
        if self.nm is None:
            return [m for m, ls in BUILTIN_LANGS.items() if lang in ls]
        out = []
        for m in self.nm.models():
            langs = set((self.nm.profiles[m].get("langs") or {}).keys())
            if langs:
                ok = lang in langs or (lang == "mixed" and "ru" in langs) or (lang == "ru" and "mixed" in langs)
            else:
                ok = not (lang == "en" and any(h in m for h in RU_ONLY_HINTS))
            if lang == "en" and any(h in m for h in RU_ONLY_HINTS):
                ok = False
            if ok:
                out.append(m)
        return out

    def pick_model(self, lang: str, r: random.Random, prefer: str | None = None) -> str:
        ms = self.models(lang)
        if prefer:
            hit = [m for m in ms if prefer in m]
            if hit:
                return hit[0]
        return r.choice(ms)

    def _digit_rate(self, model: str) -> float:
        if self.nm is None:
            return AN.PROFILES[model]["digits"]
        d = self.nm.profiles[model].get("digits") or {}
        if d.get("label") == "digits":
            return float(d.get("hyp_digit_rate") or 0.9) if d.get("hyp_digit_rate") else 0.9
        return 0.0

    def is_lowercase_engine(self, model: str) -> bool:
        if self.nm is None:
            return not AN.PROFILES[model]["case"]
        return ((self.nm.profiles[model].get("casing") or {}).get("label") == "lowercase")

    # ------------------------------------------------------------------ run
    def transcribe(self, spoken: str, target: str, lang: str, model: str, terms: list[dict], seed: int,
                   severity: float) -> tuple[str, list[str]]:
        """terms: [{"text", "category"}] Latin terms present in `spoken` (the channel may mangle them)."""
        r = random.Random(seed)
        if self.nm is None:
            ents = [{"text": t["text"], "kind": "tech"} for t in terms]
            # punctuation and casing are projected from the punctuated spoken text (what the audio supports),
            # not from the edited target
            out = AN.noise(_strip_punct(spoken), spoken, lang, model, min(1.0, severity / 2), r, entities=ents)
            return out.hyp, [_fmt_op(o) for o in out.ops]
        text = spoken
        ev = []
        if r.random() < self._digit_rate(model):
            toks = text.split()
            dig = AN._digitise(toks, "en" if lang == "en" else "ru", r)
            if dig != toks:
                text = " ".join(dig)
                ev.append("digits")
        sim = self.nm.simulate(text, model, [{"term": t["text"], "category": t.get("category", "")} for t in terms],
                               "mixed" if lang == "mixed" else lang, seed=seed, severity=severity)
        return sim.hypothesis, ev + list(sim.events)


def _strip_punct(s: str) -> str:
    return " ".join(re.sub(r"^[^\w]+|[^\w%]+$", "", w) for w in s.split() if re.sub(r"\W", "", w))


def _fmt_op(o: dict) -> str:
    return ":".join(str(o.get(k)) for k in ("op", "from", "to") if o.get(k) is not None)
