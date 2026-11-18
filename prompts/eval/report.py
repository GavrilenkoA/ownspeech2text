#!/usr/bin/env python3
"""Re-score stored runs (raw model output and output after prompts/guard.py) and print summary tables.

  python3 prompts/eval/report.py .cache/ml/prompt/runs/*.dev.jsonl [--by-category] [--guard-detail]

Guard bookkeeping per run (definitions in docs/ml/PROMPT_EVAL.md):
  fired        guard returned fallback/empty instead of the model text
  right        fired and the replacement is at least as good: final is ok, or model output had a hard
               failure (answered / wrong language / length / forbidden string) or a higher WER than the replacement
  harmful      output with a hard failure (answered, wrong language, length off) — the outputs a user
               must never get; reported before and after the guard
  fixed/broke  cases whose pass flag the guard flipped fail->pass / pass->fail (broke lists id:rule)
  pre_llm_skips  of the fired ones, how many precheck() would have skipped before calling the LLM
"""
from __future__ import annotations

import argparse
import json
import sys
from collections import Counter
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
sys.path.insert(0, str(HERE))
import guard  # noqa: E402
import score  # noqa: E402


def harmful(r: dict) -> bool:
    return r["answered"] or not r["lang_ok"] or not r["len_ok"]


def analyse(run_path: Path) -> dict:
    meta_p = run_path.with_suffix(".meta.json")
    meta = json.loads(meta_p.read_text(encoding="utf-8")) if meta_p.exists() else {}
    set_name = meta.get("set") or ("test.v1.jsonl" if ".test." in run_path.name else "dev.v1.jsonl")
    cases = score.load_jsonl(HERE / "sets" / set_name)
    res = {r["id"]: r for r in score.load_jsonl(run_path)}
    cases = [c for c in cases if c["id"] in res]
    ctx2 = meta.get("ctx2")
    if ctx2 is None and "+{" in (meta.get("profile") or ""):  # older runs: ablation options only in the profile name
        ctx2 = json.loads(meta["profile"][meta["profile"].index("+{") + 1:])
    raw_rows, final_rows, reasons, fired, right = [], [], Counter(), 0, 0
    fixed, broke, pre = [], [], 0
    for c in cases:
        out = res[c["id"]]["out"]
        v = guard.guard(c["transcript"], out, c["ctx"])
        rr, fr = score.score_case(c, out, ctx2), score.score_case(c, v.text, ctx2)
        raw_rows.append(rr)
        final_rows.append(fr)
        if v.action != "accept":
            fired += 1
            reasons[v.reason] += 1
            pre += v.action == "empty"
            if fr["ok"] or harmful(rr) or rr["forbid_hits"] or fr["wer"] <= rr["wer"]:
                right += 1
            if fr["ok"] and not rr["ok"]:
                fixed.append(c["id"])
            if rr["ok"] and not fr["ok"]:
                broke.append(f"{c['id']}:{v.reason}")
    n = max(1, len(cases))
    return {"run": run_path.name, "meta": meta, "n": len(cases),
            "raw": score.summarize(raw_rows), "final": score.summarize(final_rows),
            "guard": {"fired": fired, "right": right, "reasons": dict(reasons),
                      "harmful_before": sum(map(harmful, raw_rows)), "harmful_after": sum(map(harmful, final_rows)),
                      "fire_rate": round(fired / n, 3), "pre_llm_skips": pre,
                      "fixed": len(fixed), "broke": broke},
            "rows": {"raw": raw_rows, "final": final_rows}}


def line(a: dict) -> str:
    m, r, f, g = a["meta"], a["raw"]["overall"], a["final"]["overall"], a["guard"]
    return (f"| {a['run'].removesuffix('.jsonl')} | {a['n']} | {r['pass']:.2f} | {f['pass']:.2f} | {r['em']:.2f} | "
            f"{r['wer']:.3f} | {f['wer']:.3f} | {r['entity_keep']:.2f} | {r['answer_rate_noanswer_cases']:.2f} | "
            f"{(r['noop_exact'] or 0):.2f} | {r['lang_ok']:.2f} | {g['fired']}/{g['right']} | "
            f"{g['harmful_before']}→{g['harmful_after']} | {m.get('prefix_tokens', '')} | {m.get('mean_user_tokens', '')} | "
            f"{m.get('mean_gen_tokens', '')} |")


HEAD = ("| run | n | pass | pass+guard | EM | WER | WER+guard | keep | answer | noop | lang | guard fired/right | "
        "harmful before→after | prefix tok | user tok | gen tok |\n|---|" + "---:|" * 15)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("runs", nargs="+")
    ap.add_argument("--by-category", action="store_true")
    ap.add_argument("--guard-detail", action="store_true")
    ap.add_argument("--json")
    a = ap.parse_args(argv)
    res = [analyse(Path(p)) for p in a.runs if not p.endswith((".meta.json", ".server.log"))]
    print(HEAD)
    for x in res:
        print(line(x))
    if a.by_category:
        cats = sorted(res[0]["raw"]["by_category"])
        print("\n| category | " + " | ".join(x["run"].removesuffix(".jsonl") for x in res) + " |")
        print("|---|" + "---:|" * len(res))
        for c in cats + ["ru", "en", "mixed"]:
            key = "by_lang" if c in ("ru", "en", "mixed") else "by_category"
            print(f"| {c} | " + " | ".join(f"{x['raw'][key].get(c, {}).get('pass', 0):.2f}" for x in res) + " |")
    if a.guard_detail:
        for x in res:
            print(x["run"], json.dumps(x["guard"], ensure_ascii=False))
    if a.json:
        Path(a.json).write_text(json.dumps([{k: v for k, v in x.items() if k != "rows"} for x in res],
                                           ensure_ascii=False, indent=1), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
