"""Turn shared-contract examples into tokenised SFT features at load time.

The prompt is rendered by ml.eval.contract.render_prompt -> prompts/render.py, never stored.
Per example (deterministic in (seed, id)):
  * system prompt / profile: `default_system` (default.v1, the shipped stock-model profile, ADR-085) with
    probability default_share, else `system` (finetuned.v2); any prompts/profiles/<name> works;
  * preferences: meta.preferences if the example has them (its target depends on them), else with
    probability preferences_share a random target-neutral entry of the preferences pool;
  * loss only on the completion = target tokens + end-of-turn token (<|im_end|>); an empty target
    trains "output nothing". Prompt and completion are tokenised separately, exactly like inference
    (llama.cpp tokenises the prompt alone and the model then emits tokens).
Examples longer than max_len are dropped, never truncated (a truncated target teaches truncation).
max_len "auto" (or "auto:<cap>", default cap 4096) is derived from the really rendered examples:
the longest one rounded up to a multiple of 64, capped. OCR contexts (ctx v2) are longer and
noisier than v1, so a fixed number chosen on v1 data would silently drop or over-pad them.
max_len is only a drop filter and the bound of the longest batch: batches are padded to their own
longest example (ml/train/sft.py Collator) and grouped by length, so the few long stock-profile
examples do not pad the rest (`padding_stats` measures it; data_stats.json records it per run).
"""
from __future__ import annotations

import hashlib
import random
from collections import Counter
from dataclasses import dataclass
from pathlib import Path

from ml.eval import contract

IGNORE = -100


@dataclass
class PromptMix:
    system: str = "finetuned.v2"
    default_system: str = "default.v1"
    default_share: float = 0.05
    preferences_share: float = 0.15
    preferences_pool: str | None = None
    seed: int = 1234

    def pool(self) -> list[str]:
        if not self.preferences_pool:
            return []
        txt = Path(self.preferences_pool).read_text(encoding="utf-8")
        lines = [l for l in txt.splitlines() if not l.startswith("#")]
        return [e.strip() for e in "\n".join(lines).split("\n---\n") if e.strip()]


def _u(seed: int, key: str, salt: str) -> float:
    h = hashlib.sha256(f"{seed}:{salt}:{key}".encode()).digest()
    return int.from_bytes(h[:8], "big") / 2 ** 64


def choose_prompt(ex: dict, mix: PromptMix, pool: list[str]) -> tuple[str, str | None]:
    system = mix.default_system if _u(mix.seed, ex["id"], "sys") < mix.default_share else mix.system
    prefs = (ex.get("meta") or {}).get("preferences")
    if not prefs and pool and _u(mix.seed, ex["id"], "pref") < mix.preferences_share:
        prefs = pool[int(_u(mix.seed, ex["id"], "pick") * len(pool))]
    return system, prefs


def features(ex: dict, tok, template: str, system: str, prefs: str | None, eos_id: int) -> dict:
    prompt = contract.render_prompt(ex, system, template, prefs)
    p = tok(prompt, add_special_tokens=False)["input_ids"]
    c = (tok(ex["target"], add_special_tokens=False)["input_ids"] if ex["target"] else []) + [eos_id]
    return {"input_ids": p + c, "labels": [IGNORE] * len(p) + c, "n_prompt": len(p), "n_completion": len(c),
            "system": system, "has_prefs": bool(prefs)}


def expand(rows: list[dict], weight: float, upsample: dict[str, float], seed: int) -> list[dict]:
    """Deterministic resampling: weight 0.5 keeps a random half, 2.0 duplicates; per-category
    upsample multiplies on top (e.g. {"noop": 2.0})."""
    rng = random.Random(seed)
    out = []
    for ex in rows:
        m = weight * upsample.get(ex["category"], 1.0)
        k = int(m) + (1 if rng.random() < m - int(m) else 0)
        out.extend([ex] * k)
    return out


AUTO_CAP = 4096


def resolve_max_len(spec, lengths: list[int]) -> int:
    """int -> itself; "auto" / "auto:<cap>" -> max(lengths) rounded up to 64, at most cap."""
    if isinstance(spec, int) or (isinstance(spec, str) and spec.strip().isdigit()):
        return int(spec)
    s = str(spec).strip()
    if not s.startswith("auto"):
        raise ValueError(f"max_len must be an int or 'auto[:cap]', got {spec!r}")
    cap = int(s.split(":", 1)[1]) if ":" in s else AUTO_CAP
    longest = max(lengths, default=64)
    return min(cap, -(-longest // 64) * 64)


def length_stats(lengths: list[int]) -> dict:
    xs = sorted(lengths)
    if not xs:
        return {}
    q = lambda f: xs[min(len(xs) - 1, int(f * len(xs)))]  # noqa: E731
    return {"p50": q(0.5), "p90": q(0.9), "p99": q(0.99), "max": xs[-1], "mean": round(sum(xs) / len(xs), 1)}


def padding_stats(lengths: list[int], order: list[int], batch: int, multiple_of: int = 8) -> dict:
    """Real vs padded tokens when `order` (sampler output) is cut into batches of `batch` and each batch
    is right-padded to its longest member rounded up to `multiple_of` (the Collator's rule)."""
    real = padded = 0
    for i in range(0, len(order), batch):
        b = [lengths[j] for j in order[i:i + batch]]
        if not b:
            continue
        n = -(-max(b) // multiple_of) * multiple_of
        real += sum(b)
        padded += n * len(b)
    return {"real_tokens": real, "padded_tokens": padded, "efficiency": round(real / padded, 4) if padded else None}


def build(sources: list[dict], split: str, tok, template: str, mix: PromptMix, max_len,
          upsample: dict[str, float] | None = None) -> tuple[list[dict], dict]:
    """sources: [{"path": ..., "weight": 1.0}]. max_len: int or "auto[:cap]". Returns (features, stats);
    stats["max_len"] is the resolved value (pass it to the dev split so both use the same limit)."""
    pool = mix.pool()
    eos_id = tok.convert_tokens_to_ids(contract.TEMPLATES[template])
    exs = []
    for i, s in enumerate(sources):
        rows = contract.load(s["path"], split=split)
        w = float(s.get("weight", 1.0)) if split == "train" else 1.0
        exs += expand(rows, w, (upsample or {}) if split == "train" else {}, mix.seed + i)
    rendered = []
    for ex in exs:
        system, prefs = choose_prompt(ex, mix, pool)
        rendered.append((ex, system, prefs, features(ex, tok, template, system, prefs, eos_id)))
    all_lens = [len(f["input_ids"]) for *_, f in rendered]
    max_len = resolve_max_len(max_len, all_lens)
    feats, dropped = [], 0
    stats = Counter()
    for ex, system, prefs, f in rendered:
        if len(f["input_ids"]) > max_len:
            dropped += 1
            continue
        f["id"], f["lang"], f["category"] = ex["id"], ex["lang"], ex["category"]
        feats.append(f)
        stats[f"system={system}"] += 1
        stats["with_preferences"] += f["has_prefs"]
        stats[f"lang={ex['lang']}"] += 1
    n_tok = sum(len(f["input_ids"]) for f in feats)
    n_lab = sum(f["n_completion"] for f in feats)
    info = {"split": split, "examples": len(feats), "dropped_too_long": dropped, "tokens": n_tok,
            "completion_tokens": n_lab, "max_len_seen": max((len(f["input_ids"]) for f in feats), default=0),
            "max_len": max_len, "rendered_len": length_stats(all_lens),
            **dict(stats)}
    return feats, info
