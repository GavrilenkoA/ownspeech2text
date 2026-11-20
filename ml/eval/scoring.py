"""Scoring of a set of predictions, shared by ml/eval/run_eval.py and ml/eval/baseline.py.

Every row carries three measurements of the same output:
  * ml/eval/metrics.py (EM, N-WER, entity recall, gates, ...; screen terms checked on all OCR lines,
    fuzzy for Russian inflection),
  * `pass` = the prompt-eval pass flag of prompts/eval/score.py `score_case` (screen terms checked on
    the rendered <context> block under the profile's ctx2, `score.on_screen`), i.e. the number the stock
    baselines in docs/ml/PROMPT_EVAL.md are quoted in, so a fine-tune is compared on the same scorer;
  * the same two after prompts/guard.py (precheck skip -> empty, repair, fallback to the transcript).
`set` = the stem of the data file a row came from (dev.v2, test.v2, dev, test, ...): reports break every
metric down by it, because the stock baselines exist per prompt eval set, not for a merged set.
"""
from __future__ import annotations

import importlib.util
from pathlib import Path

from . import contract, metrics

import guard as app_guard  # noqa: E402  prompts/guard.py (contract puts prompts/ on sys.path)

_spec = importlib.util.spec_from_file_location("murmur_prompt_score", contract.PROMPTS / "eval" / "score.py")
prompt_score = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(prompt_score)


def set_name(path: str | Path) -> str:
    """'prompts/eval/sets/dev.v2.jsonl' -> 'dev.v2'; '$MURMUR_DATA/dev.jsonl' -> 'dev'."""
    n = Path(path).name
    return n[: -len(".jsonl")] if n.endswith(".jsonl") else n


def set_map(paths: list[str]) -> dict[str, str]:
    """id -> set name, from the files as given (ids are unique across files: contract.load checks)."""
    out = {}
    for p in paths:
        for ex in contract.iter_jsonl(p):
            out.setdefault(ex["id"], set_name(p))
    return out


def ctx2_of(system: str) -> dict | None:
    """ctx v2 render options of the prompt profile the model was run with (None = shipped default)."""
    try:
        return contract.render.load_profile(system).get("ctx2")
    except Exception:  # noqa: BLE001  messages backend / unknown name: the default options
        return None


def score_all(data: list[dict], done: dict[str, dict], system: str, sets: dict[str, str] | None = None,
              judged: dict | None = None) -> tuple[list[dict], list[dict], dict[str, int]]:
    """(raw rows, guarded rows, guard reason counts) for predictions `done` = {id: {"out", "raw_out"?, ...}}."""
    judged = judged or {}
    ctx2 = ctx2_of(system)
    rows, grows, reasons = [], [], {}
    for ex in data:
        p = done[ex["id"]]
        extra = {k: p.get(k) for k in ("latency_ms", "prompt_tokens", "completion_tokens", "prefill_ms", "decode_ms")}
        tag = {"set": (sets or {}).get(ex["id"], "all")}
        r = metrics.score_example(ex, p["out"])
        r.update(extra, **tag, **{"pass": bool(prompt_score.score_case(ex, p["out"], ctx2)["ok"])})
        if ex["id"] in judged:
            r.update({k: judged[ex["id"]][k] for k in ("judge_score", "judge_drift")})
        rows.append(r)
        v = app_guard.guard(ex["transcript"], p.get("raw_out") or p["out"], ex.get("ctx"))
        reasons[v.reason] = reasons.get(v.reason, 0) + 1
        gr = metrics.score_example(ex, v.text)
        gr.update(extra, **tag, guard_action=v.action, guard_reason=v.reason,
                  **{"pass": bool(prompt_score.score_case(ex, v.text, ctx2)["ok"])})
        if ex["id"] in judged and v.text == p["out"]:
            gr.update({k: judged[ex["id"]][k] for k in ("judge_score", "judge_drift")})
        grows.append(gr)
    return rows, grows, reasons
