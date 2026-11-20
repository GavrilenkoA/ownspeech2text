#!/usr/bin/env python3
"""Stock baselines and the fine-tune-vs-stock comparison.

The stock numbers to beat are the prompt-eval runs of docs/ml/PROMPT_EVAL.md (vendor Q4_0 GGUFs, profile
`default.v1` + `prompts/guard.py`, on prompts/eval/sets/dev.v2 and test.v2). `build` re-scores those stored
runs through the SAME code a fine-tune's report uses (ml/eval/scoring.py: prompts/eval/score.py pass flag
+ ml/eval/metrics.py, raw and after the guard) and freezes the result in ml/eval/baselines/, so the pod
(which has no run files) compares against identical definitions:

  python -m ml.eval.baseline build [--runs .cache/ml/prompt/runs] [--out ml/eval/baselines/stock.default.v1.json]
  python -m ml.eval.baseline table RUNS_OR_EVAL_DIR... [--out comparison.md]

`compare(report)` (called by run_eval for every report) returns, for each prompt eval set present in the
report's `by_set`, the run's pass rate next to the three stock models', raw vs raw and guarded vs guarded.
`table` writes one markdown table over all reports found (fine-tunes and stock reports made by
ml/pod/after_pod.sh on the dataset's own dev/test), per set, so every set has its stock reference.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

from . import contract

BASE_DIR = contract.REPO / "ml/eval/baselines"
DEFAULT_FILE = BASE_DIR / "stock.default.v1.json"
# prompt-eval model keys (prompts/eval/models.json) -> display names; order = the app's default first
MODELS = {"q2b": "Qwen3.5-2B", "lfm3": "LFM3-1.7B", "q08": "Qwen3.5-0.8B"}
SETS = ("dev.v2", "test.v2")
KEEP = ("n", "pass", "em", "nwer", "nwer_raw", "answer_rate", "injection_success", "noop_exact", "entity_recall",
        "distractor_insertion", "empty_ok", "wrong_language")


def load(path: Path = DEFAULT_FILE) -> dict | None:
    return json.loads(path.read_text(encoding="utf-8")) if path.exists() else None


def _pick(o: dict) -> dict:
    return {k: o.get(k) for k in KEEP}


def build(runs: Path, out: Path, profile: str = "default.v1") -> dict:
    from . import metrics, scoring
    res = {"profile": profile, "guard_version": scoring.app_guard.GUARD_VERSION,
           "scorer": "ml/eval/scoring.py (pass = prompts/eval/score.py score_case on the rendered context)",
           "source": "vendor Q4_0 GGUFs, llama.cpp b12503 CPU, greedy (docs/ml/PROMPT_EVAL.md section R.5)",
           "models": {}}
    for key, name in MODELS.items():
        for st in SETS:
            run = runs / f"{key}.{profile}.{st}.jsonl"
            if not run.exists():
                print(f"missing {run}", file=sys.stderr)
                continue
            meta = json.loads(run.with_suffix(".meta.json").read_text(encoding="utf-8"))
            set_path = contract.PROMPTS / "eval/sets" / f"{st}.jsonl"
            data = contract.load(set_path)
            got = {}
            for line in run.read_text(encoding="utf-8").splitlines():
                if line.strip():
                    r = json.loads(line)
                    if "error" not in r:
                        got[r["id"]] = {"out": r["out"], "raw_out": r["out"]}
            lost = [ex["id"] for ex in data if ex["id"] not in got]
            for i in lost:  # harness failure = failed case (as prompts/eval/report.py), never a smaller denominator
                got[i] = {"out": "", "raw_out": ""}
            rows, grows, _ = scoring.score_all(data, got, profile)
            raw, gd = metrics.aggregate(rows), metrics.aggregate(grows)
            res["models"].setdefault(name, {})[st] = {
                "raw": _pick(raw), "guarded": _pick(gd), "lost": len(lost),
                "pass_cases": {"raw": sum(r["pass"] for r in rows), "guarded": sum(r["pass"] for r in grows)},
                "run_file": str(run.relative_to(contract.REPO)) if run.is_relative_to(contract.REPO) else str(run),
                "run_sha256": hashlib.sha256(run.read_bytes()).hexdigest()[:16],
                "producer": meta.get("producer"), "model_file": meta.get("model"),
                "prompt_eval_pass": {"raw": (meta.get("score_raw") or {}).get("overall", {}).get("pass"),
                                     "guarded": (meta.get("score_guarded") or {}).get("overall", {}).get("pass")}}
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(res, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    return res


def compare(report: dict, base: dict | None = None) -> dict | None:
    """Per prompt eval set in the report: this run vs every stock model (pass, EM, N-WER, answer rate)."""
    base = base if base is not None else load()
    if not base:
        return None
    by_set = (report.get("summary") or {}).get("by_set") or {}
    gby_set = ((report.get("guarded") or {}).get("summary") or {}).get("by_set") or {}
    out = {"profile": base["profile"], "guard_version": base.get("guard_version"), "sets": {}}
    for st in SETS:
        if st not in by_set:
            continue
        this = {"raw": _pick(by_set[st]), "guarded": _pick(gby_set.get(st, {}))}
        stock = {m: {"raw": v[st]["raw"], "guarded": v[st]["guarded"]} for m, v in base["models"].items() if st in v}
        best = max(stock.items(), key=lambda kv: kv[1]["guarded"]["pass"] or 0) if stock else None
        out["sets"][st] = {"this": this, "stock": stock, "best_stock": best[0] if best else None,
                           "delta_pass_guarded_vs_best": (round(this["guarded"]["pass"] - best[1]["guarded"]["pass"], 4)
                                                          if best and this["guarded"].get("pass") is not None else None),
                           "delta_pass_raw_vs_best": (round(this["raw"]["pass"] - best[1]["raw"]["pass"], 4)
                                                      if best and this["raw"].get("pass") is not None else None)}
    return out if out["sets"] else None


def _f(v) -> str:
    return "" if v is None else (str(v) if isinstance(v, int) else f"{v:.3f}")


def to_markdown(cmp: dict, label: str = "this run") -> str:
    lines = [f"## Versus the stock models (profile {cmp['profile']} + guard {cmp.get('guard_version')}; "
             "pass = prompts/eval/score.py)", "",
             "| set | model | pass raw | pass guarded | EM guarded | N-WER guarded | answered guarded | inject guarded |",
             "|---|---|---:|---:|---:|---:|---:|---:|"]
    for st, s in cmp["sets"].items():
        rows = [(f"**{label}**", s["this"])] + list(s["stock"].items())
        for name, v in rows:
            g = v["guarded"]
            lines.append(f"| {st} | {name} | {_f(v['raw'].get('pass'))} | {_f(g.get('pass'))} | {_f(g.get('em'))} | "
                         f"{_f(g.get('nwer'))} | {_f(g.get('answer_rate'))} | {_f(g.get('injection_success'))} |")
        lines.append(f"| {st} | delta vs best stock ({s['best_stock']}) | {_f(s['delta_pass_raw_vs_best'])} | "
                     f"{_f(s['delta_pass_guarded_vs_best'])} | | | | |")
    return "\n".join(lines)


def table(dirs: list[str]) -> str:
    """One row per report.json found (fine-tune evals and stock evals), one column block per set."""
    reps = []
    for d in dirs:
        for p in sorted(Path(d).rglob("report.json")):
            r = json.loads(p.read_text(encoding="utf-8"))
            if "legacy" in (r["info"].get("label") or ""):
                continue
            reps.append((r["info"].get("label") or p.parent.name, r["info"].get("system"), r))
    sets = sorted({s for _, _, r in reps for s in (r["summary"].get("by_set") or {})})
    base = load()
    lines = []
    for st in sets:
        lines += [f"### {st}", "", "| model / run | prompt | n | pass raw | pass guarded | EM guarded | N-WER guarded | "
                  "answered guarded |", "|---|---|---:|---:|---:|---:|---:|---:|"]
        if base and st in SETS:
            for m, v in base["models"].items():
                if st in v:
                    g, rw = v[st]["guarded"], v[st]["raw"]
                    lines.append(f"| stock {m} (frozen, PROMPT_EVAL) | {base['profile']} | {g['n']} | {_f(rw['pass'])} | "
                                 f"{_f(g['pass'])} | {_f(g['em'])} | {_f(g['nwer'])} | {_f(g['answer_rate'])} |")
        for label, system, r in sorted(reps, key=lambda x: -((((x[2].get("guarded") or {}).get("summary") or {})
                                                              .get("by_set") or {}).get(st, {}).get("pass") or 0)):
            s = (r["summary"].get("by_set") or {}).get(st)
            g = (((r.get("guarded") or {}).get("summary") or {}).get("by_set") or {}).get(st)
            if not s or not g:
                continue
            lines.append(f"| {label} | {system} | {s['n']} | {_f(s.get('pass'))} | {_f(g.get('pass'))} | {_f(g.get('em'))} | "
                         f"{_f(g.get('nwer'))} | {_f(g.get('answer_rate'))} |")
        lines.append("")
    return "\n".join(lines)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    b = sub.add_parser("build")
    b.add_argument("--runs", default=str(contract.REPO / ".cache/ml/prompt/runs"))
    b.add_argument("--out", default=str(DEFAULT_FILE))
    b.add_argument("--profile", default="default.v1")
    t = sub.add_parser("table")
    t.add_argument("dirs", nargs="+")
    t.add_argument("--out")
    a = ap.parse_args(argv)
    if a.cmd == "build":
        res = build(Path(a.runs), Path(a.out), a.profile)
        for m, v in res["models"].items():
            for st, x in v.items():
                print(f"{m:14s} {st:8s} pass raw {x['raw']['pass']:.3f} guarded {x['guarded']['pass']:.3f} "
                      f"({x['pass_cases']['guarded']}/{x['guarded']['n']}; prompt-eval meta {x['prompt_eval_pass']}) "
                      f"lost {x['lost']}")
        return 0
    md = table(a.dirs)
    print(md)
    if a.out:
        Path(a.out).write_text(md + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
