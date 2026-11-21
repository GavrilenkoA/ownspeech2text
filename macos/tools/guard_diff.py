#!/usr/bin/env python3
"""Differential test: Swift OutputGuard (murmur-cli guard) vs the reference prompts/guard.py.

Cases = every ctx v2 row of prompts/eval/sets/{dev,test}.v2*.jsonl x a set of output perturbations
(target, transcript, quoted, preamble, label, think block, loop, translation-like, truncation, empty, ...).
Usage: python3 tools/guard_diff.py .build/release/murmur-cli
"""
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "prompts"))
import guard  # noqa: E402

cli = sys.argv[1]
rows = []
for f in sorted((ROOT / "prompts/eval/sets").glob("*.v2*.jsonl")):
    if f.name.endswith(".specs.jsonl"):
        continue
    for line in f.read_text(encoding="utf-8").splitlines():
        if line.strip():
            r = json.loads(line)
            if (r.get("ctx") or {}).get("v") == 2:
                rows.append(r)


def outputs(r):
    t, g = r["transcript"], r.get("target") or ""
    words = g.split()
    yield g
    yield t
    yield f"«{g}»"
    yield f'"{g}"'
    yield "Конечно! " + g
    yield "Sure, here is the edited text: " + g
    yield "Результат: " + g
    yield "<think>hmm</think>\n" + g
    yield g + "<|im_end|>"
    yield (g + " ") * 5
    yield " ".join(words[: max(1, len(words) // 4)])
    yield g.upper()
    yield ""
    yield "```\n" + g + "\n```"
    yield g + " Также я добавил пару слов от себя про погоду и встречу завтра утром."
    yield "<context>" + g
    yield g.replace("а", "a").replace("о", "o")


cases = [{"transcript": r["transcript"], "output": o, "ctx": r["ctx"]} for r in rows for o in outputs(r)]
inp = "\n".join(json.dumps(c, ensure_ascii=False) for c in cases) + "\n"
res = subprocess.run([cli, "guard"], input=inp, capture_output=True, text=True, check=True).stdout.splitlines()
assert len(res) == len(cases), (len(res), len(cases))
bad = 0
for c, line in zip(cases, res):
    got = json.loads(line)
    v = guard.guard(c["transcript"], c["output"], c["ctx"])
    want = {"text": v.text, "action": v.action, "reason": v.reason}
    if got != want:
        bad += 1
        if bad <= 15:
            print("MISMATCH", json.dumps({"transcript": c["transcript"], "output": c["output"][:120]}, ensure_ascii=False))
            print("   want", want, "\n   got ", got)
print(f"guard diff: {len(cases) - bad}/{len(cases)} identical ({len(rows)} ctx v2 rows), {bad} mismatches")
sys.exit(1 if bad else 0)
