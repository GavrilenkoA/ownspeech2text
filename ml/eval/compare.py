#!/usr/bin/env python3
"""Compare eval reports (e.g. BF16 GGUF vs Q4_0 GGUF of the same fine-tune = quantisation loss,
or stock model vs fine-tune). Prints a Markdown table of overall + per-language deltas and the
quantisation-loss check from DATA_AND_TRAINING_PLAN.md 6.1 (try QAT if Q4 loses > 3 pp EM-noop
or > 0.2 judge points).

  python -m ml.eval.compare REF/report.json CAND/report.json [--json out.json]
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

KEYS = ["exact", "em", "nwer", "entity_recall", "distractor_insertion", "noop_exact", "answer_rate",
        "injection_success", "empty_ok", "wrong_language", "drift_proxy", "judge_mean", "latency_ms_p50"]
HIGHER_BETTER = {"exact", "em", "entity_recall", "noop_exact", "empty_ok", "judge_mean"}
QAT_TRIGGER = {"noop_exact": 0.03, "judge_mean": 0.2}


def delta(ref: dict, cand: dict) -> dict:
    out = {}
    for k in KEYS:
        a, b = ref.get(k), cand.get(k)
        out[k] = {"ref": a, "cand": b, "delta": None if a is None or b is None else round(b - a, 4)}
    return out


def guarded_view(rep: dict) -> dict | None:
    """The report's after-guard measurement (ml/eval/run_eval.py) shaped as a report, or None."""
    g = rep.get("guarded")
    if not g:
        return None
    return {"info": {**rep["info"], "backend": rep["info"]["backend"] + "+guard"}, "summary": g["summary"],
            "gates": g["gates"]}


def compare(ref: dict, cand: dict) -> dict:
    scopes = {"overall": (ref["summary"]["overall"], cand["summary"]["overall"])}
    for lang in sorted(set(ref["summary"]["by_lang"]) & set(cand["summary"]["by_lang"])):
        scopes[lang] = (ref["summary"]["by_lang"][lang], cand["summary"]["by_lang"][lang])
    res = {s: delta(a, b) for s, (a, b) in scopes.items()}
    o = res["overall"]
    loss = {k: (o[k]["ref"] - o[k]["cand"]) if o[k]["delta"] is not None else None for k in QAT_TRIGGER}
    trig = [k for k, v in loss.items() if v is not None and v > QAT_TRIGGER[k]]
    return {"ref": ref["info"], "cand": cand["info"], "scopes": res, "loss": loss, "qat_recommended": bool(trig),
            "qat_triggers": trig}


def to_markdown(c: dict) -> str:
    lines = [f"Reference: `{Path(c['ref']['model']).name}` ({c['ref']['backend']}); "
             f"candidate: `{Path(c['cand']['model']).name}` ({c['cand']['backend']})", "",
             "| scope | metric | ref | cand | delta |", "|---|---|---:|---:|---:|"]
    for s, ms in c["scopes"].items():
        for k, v in ms.items():
            if v["ref"] is None and v["cand"] is None:
                continue
            f = (lambda x: "" if x is None else f"{x:.4g}")
            lines.append(f"| {s} | {k} | {f(v['ref'])} | {f(v['cand'])} | {f(v['delta'])} |")
    lines += ["", f"QAT/QAD recommended: **{c['qat_recommended']}** (triggers: {c['qat_triggers'] or 'none'}; "
              f"thresholds {QAT_TRIGGER})"]
    return "\n".join(lines)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("ref")
    ap.add_argument("cand")
    ap.add_argument("--json")
    ap.add_argument("--md")
    a = ap.parse_args(argv)
    c = compare(json.loads(Path(a.ref).read_text()), json.loads(Path(a.cand).read_text()))
    md = to_markdown(c)
    print(md)
    if a.json:
        Path(a.json).write_text(json.dumps(c, ensure_ascii=False, indent=1))
    if a.md:
        Path(a.md).write_text(md + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
