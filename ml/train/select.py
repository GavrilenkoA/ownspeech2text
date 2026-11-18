#!/usr/bin/env python3
"""Pick the best run of a sweep from its dev-set GGUF eval and emit its hyperparameters as `--set`
overrides for the next phase (PLAN_V2: LFM3 sweep first, then Qwen3.5 0.8B / 2B with the winner).

Ranking (ties broken left to right), on the overall dev summary of the Q4_0 file = the artifact that ships:
  1. fewest FAILed ship gates (NO_DATA does not count)   2. highest EM   3. lowest N-WER
  4. highest no-op exactness                            5. fewer trainable parameters (cheaper)

  python -m ml.train.select --runs-dir $MURMUR_RUNS --eval Q4_0-dev lfm3-r16 lfm3-r64 ...  [--json out]
prints the winner on line 1 and the overrides on line 2, e.g.
  lfm3-r16-lr5e-4
  method=lora lora.r=16 lora.alpha=32 train.lr=0.0005 train.epochs=3
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

CARRY = (("method",), ("lora", "r"), ("lora", "alpha"), ("lora", "dropout"), ("train", "lr"), ("train", "epochs"))


def key(rep: dict, params: int | None) -> tuple:
    o = rep["summary"]["overall"]
    fails = sum(1 for g in rep["gates"]["scopes"]["overall"] if g["status"] == "FAIL")
    return (fails, -(o.get("em") or 0.0), o.get("nwer") if o.get("nwer") is not None else 9.0,
            -(o.get("noop_exact") or 0.0), params or 0)


def candidates(runs_dir: Path, runs: list[str], eval_name: str) -> list[dict]:
    out = []
    for r in runs:
        rep_p = runs_dir / r / "eval" / eval_name / "report.json"
        cfg_p = runs_dir / r / "config.resolved.json"
        if not rep_p.exists() or not cfg_p.exists():
            continue
        rep = json.loads(rep_p.read_text())
        info_p = runs_dir / r / "train_info.json"
        params = json.loads(info_p.read_text()).get("params_trainable") if info_p.exists() else None
        out.append({"run": r, "key": key(rep, params), "cfg": json.loads(cfg_p.read_text()),
                    "overall": {k: rep["summary"]["overall"].get(k) for k in ("em", "nwer", "noop_exact")},
                    "verdict": rep["gates"]["verdict"]})
    return sorted(out, key=lambda c: c["key"])


def overrides(cfg: dict) -> list[str]:
    out = []
    for path in CARRY:
        node = cfg
        for p in path:
            node = node.get(p) if isinstance(node, dict) else None
        if node is not None:
            out.append(".".join(path) + "=" + str(node))
    return out


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("runs", nargs="+")
    ap.add_argument("--runs-dir", required=True)
    ap.add_argument("--eval", default="Q4_0-dev", help="eval sub-directory name under RUN/eval/")
    ap.add_argument("--json")
    a = ap.parse_args(argv)
    cs = candidates(Path(a.runs_dir), a.runs, a.eval)
    if not cs:
        raise SystemExit(f"no evaluated runs among {a.runs}")
    best = cs[0]
    if a.json:
        Path(a.json).write_text(json.dumps({"winner": best["run"], "overrides": overrides(best["cfg"]),
                                            "ranking": [{k: c[k] for k in ("run", "key", "overall", "verdict")}
                                                        for c in cs]}, indent=1))
    print(best["run"])
    print(" ".join(overrides(best["cfg"])))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
