"""Disfluency probe -> per-ASR-model rendering of hesitations and repeated words.

Input: the round-trip output of ml/data/probe/disfluency_probe.v1.jsonl (32 sentences with "э-э", "эм", "um",
"uh" and an immediate word repeat each), produced with ml/roundtrip's runner (read-only use):

  tools/heavy .cache/ml/roundtrip/venv/bin/python -m ml.roundtrip.run_roundtrip \\
      --probe ml/data/probe/disfluency_probe.v1.jsonl --out .cache/ml/data/disfl_probe --tts cpu \\
      --voices-per-sentence 1 --channels clean --asr all --workers 3 --threads 2
  python -m ml.data.probe.measure_disfluency .cache/ml/data/disfl_probe/roundtrip/disfluency_probe.v1.jsonl

Output: ml/data/probe/disfluency_render.v1.json, {"models": {asr_model: {"hesitation_keep", "repeat_keep",
"n_hesitations", "n_repeats", ...}}}. Channel.disfluency_render reads it.

hesitation_keep = hesitation tokens in the hypothesis / hesitation tokens said (capped at 1).
repeat_keep     = repeats of words of 3+ letters still doubled in the hypothesis / such repeats said (TTS often
                  merges a doubled one- or two-letter word like "к к" into one sound, so short repeats are not used).

Caveat (stated in DATA_CARD): the TTS voice ARTICULATES "э"/"um" as a clear word inside fluent speech. A real
filled pause is longer, quieter and non-lexical, and Whisper-style models trained on subtitles omit those much
more often; the voices also misread them ("эм" as the letter "М", "uh" as "oh"/"our"/"well"). The measured
hesitation rate is therefore only a rough upper bound and hesitation_keep = min(measured, family prior cap).
Longer repeated words are read naturally by TTS, so repeat_keep is used as measured.
"""
from __future__ import annotations

import json
import re
import sys
from collections import defaultdict
from pathlib import Path

from ml.common import text as T

OUT = Path(__file__).resolve().parent / "disfluency_render.v1.json"
# literature/behaviour prior for real filled pauses (upper cap for the TTS-measured rate)
HES_PRIOR_CAP = [("whisper", 0.1), ("distil", 0.1), ("qwen3", 0.15), ("parakeet", 0.35), ("nemotron", 0.35),
                 ("gigaam", 0.6), ("t-one", 0.6)]


def _hes(s: str) -> int:
    return sum(1 for w in T.words(s) if T.norm_word(w) in T.HESITATION or T.norm_word(w) in {"эм", "ум", "uhm"})


def _repeats(s: str, min_len: int = 1) -> list[str]:
    ws = [T.norm_word(w) for w in T.words(s)]
    return [a for a, b in zip(ws, ws[1:]) if a == b and a not in T.HESITATION and len(a) >= min_len]


def measure(rows: list[dict]) -> dict:
    agg = defaultdict(lambda: {"utts": 0, "hes_said": 0, "hes_written": 0, "rep_said": 0, "rep_written": 0,
                               "examples": []})
    for r in rows:
        hyp = r.get("hypothesis") or ""
        a = agg[r["asr_model"]]
        a["utts"] += 1
        hs, hw = _hes(r["text"]), _hes(hyp)
        a["hes_said"] += hs
        a["hes_written"] += min(hs, hw)
        said = _repeats(r["text"], 3)
        a["rep_said"] += len(said)
        got = _repeats(hyp)
        a["rep_written"] += sum(1 for w in said if w in got)
        if len(a["examples"]) < 3:
            a["examples"].append({"said": r["text"], "written": hyp})
    out = {}
    for m, a in sorted(agg.items()):
        hk = a["hes_written"] / a["hes_said"] if a["hes_said"] else None
        rk = a["rep_written"] / a["rep_said"] if a["rep_said"] else None
        cap = next((c for fam, c in HES_PRIOR_CAP if fam in m), 0.6)
        out[m] = {"utterances": a["utts"], "n_hesitations": a["hes_said"], "n_repeats": a["rep_said"],
                  "tts_hesitation_keep": round(hk, 3) if hk is not None else None,
                  "hesitation_keep": round(min(hk, cap), 3) if hk is not None else cap,
                  "hesitation_cap_prior": cap,
                  "repeat_keep": round(rk, 3) if rk is not None else 0.9, "examples": a["examples"]}
    return out


def main(argv=None) -> int:
    argv = argv if argv is not None else sys.argv[1:]
    rows = [json.loads(l) for l in Path(argv[0]).read_text(encoding="utf-8").splitlines() if l.strip()]
    res = measure(rows)
    doc = {"source": "ml/data/probe/disfluency_probe.v1.jsonl -> CPU TTS -> ASR (ml/roundtrip runner)",
           "rows": len(rows), "note": "hesitation_keep = min(TTS-measured rate, family prior cap); see module doc",
           "models": res}
    OUT.write_text(json.dumps(doc, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    for m, v in res.items():
        print(f"{m:52s} hes {v['tts_hesitation_keep']} -> {v['hesitation_keep']} (n={v['n_hesitations']})  "
              f"rep {v['repeat_keep']} (n={v['n_repeats']})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
