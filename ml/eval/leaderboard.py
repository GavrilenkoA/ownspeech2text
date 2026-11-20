#!/usr/bin/env python3
"""One table over every eval report under the given directories (stock baselines, run matrix).
Raw-output metrics plus the after-guard (prompts/guard.py) pass, EM, N-WER and verdict (`pass` =
prompts/eval/score.py over the whole report; per-set numbers next to the stock models: ml.eval.baseline table). Reports whose
label contains "legacy" (v1 structured-context sets) are listed in a separate table: they are kept
for continuity only and take no part in selection or gates.

  python -m ml.eval.leaderboard EVAL_DIR RUNS_DIR [--out leaderboard.md]
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

KEYS = [("pass", "pass"), ("em", "EM"), ("nwer", "N-WER"), ("nwer_raw", "raw"), ("noop_exact", "noop"), ("answer_rate", "answer"),
        ("injection_success", "inject"), ("entity_recall", "ent"), ("distractor_insertion", "distr"),
        ("empty_ok", "empty"), ("wrong_language", "lang"), ("judge_mean", "judge"), ("latency_ms_p50", "p50ms")]


def rows(dirs: list[str]) -> list[dict]:
    out = []
    for d in dirs:
        for p in sorted(Path(d).rglob("report.json")):
            r = json.loads(p.read_text())
            if (r.get("info", {}).get("label") or "").endswith("smoke") or p.parent.name.endswith("-smoke"):
                continue
            g = r.get("guarded") or {}
            go = (g.get("summary") or {}).get("overall") or {}
            out.append({"label": r["info"].get("label") or p.parent.name, "file": Path(r["info"]["model"]).name,
                        "system": r["info"]["system"], "verdict": r["gates"]["verdict"],
                        "bytes": r["info"].get("model_bytes"), **r["summary"]["overall"],
                        "g_em": go.get("em"), "g_nwer": go.get("nwer"), "g_pass": go.get("pass"),
                        "g_verdict": (g.get("gates") or {}).get("verdict", "")})
    return out


def table(rs: list[dict]) -> str:
    f = (lambda v: "" if v is None else (str(v) if isinstance(v, int) else f"{v:.3g}"))
    lines = ["| run | prompt | MB | " + " | ".join(s for _, s in KEYS) + " | gates | guard pass | guard EM | guard N-WER | guard gates |",
             "|---|---|---:|" + "---:|" * len(KEYS) + "---|---:|---:|---:|---|"]
    for r in sorted(rs, key=lambda r: (r["nwer"] if r["nwer"] is not None else 9)):
        mb = f"{r['bytes'] / 1e6:.0f}" if r.get("bytes") else ""
        lines.append(f"| {r['label']} | {r['system']} | {mb} | " + " | ".join(f(r.get(k)) for k, _ in KEYS)
                     + f" | {r['verdict']} | {f(r.get('g_pass'))} | {f(r.get('g_em'))} | {f(r.get('g_nwer'))} | {r.get('g_verdict', '')} |")
    return "\n".join(lines)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("dirs", nargs="+")
    ap.add_argument("--out")
    a = ap.parse_args(argv)
    rs = rows(a.dirs)
    cur = [r for r in rs if "legacy" not in r["label"]]
    leg = [r for r in rs if "legacy" in r["label"]]
    md = table(cur) + ("\n\n### Legacy v1-context sets (not used for selection or gates)\n\n" + table(leg) if leg else "")
    print(md)
    if a.out:
        Path(a.out).write_text(md + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
