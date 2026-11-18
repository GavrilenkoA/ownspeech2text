#!/usr/bin/env python3
"""Deterministic scorer for Murmur post-processing outputs (no LLM judge).

Input: an eval set (shared data contract JSONL) + a results JSONL with {"id", "out"} per line.
Per case it computes (definitions in prompts/eval/README.md#metrics):
  em        exact match after light normalisation (NFC, ё→е, quotes/dashes/spaces folded, one final '.' optional),
            against target or any meta.check.alts
  wer       word error rate vs target on normalised words (case/punctuation ignored)
  fwer      formatting-aware token error rate (case, punctuation and line breaks are tokens)
  keep      share of critical entities (meta.check.keep) present in the output. In the context-support categories
            (SUPPORT_CATS) with a ctx v2 an entity only counts when it is also spelled in the screen text the model
            was shown (on_screen: the rendered <context> block, i.e. after the renderer's filters and budget), so a
            term the screen does not support cannot be "kept" (same rule as ml/eval/metrics.py, stricter: rendered
            block instead of all OCR lines)
  forbid    any forbidden string present (distractor, injection payload, abandoned correction, filler)
  answered  output looks like an answer/continuation instead of the transcription
  lang_ok   Latin/Cyrillic letter share of output within 0.2 of the target's
  len_ok    output length within [0.6, 1.5] x target length (empty target: output must be empty)
  noop_ok   byte-exact (after strip) for no-op cases; it is the pass decision for no-op cases
  ok        no-op case: noop_ok; empty target: output empty;
            otherwise em OR all hard checks pass AND wer <= 0.15 AND fwer <= 0.20

  python3 prompts/eval/score.py --set prompts/eval/sets/dev.v1.jsonl --results RUN.jsonl [--json OUT.json]
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import unicodedata
from collections import defaultdict
from pathlib import Path

WER_MAX = 0.15
FWER_MAX = 0.20
LEN_MIN, LEN_MAX = 0.6, 1.5
LANG_TOL = 0.2
# categories whose critical entity must come from the screen (CONTEXT_V2 category rules; names as in ml/eval/metrics.py)
SUPPORT_CATS = {"context_names", "context_resolution", "near_miss", "context_asr_correction"}

_QUOTES = str.maketrans({"«": '"', "»": '"', "“": '"', "”": '"', "„": '"', "‘": "'", "’": "'",
                         "–": "—", "‑": "-", " ": " ", " ": " ", " ": " ", "ё": "е", "Ё": "Е"})


def fold(s: str) -> str:
    """Character folding used for keep/forbid matching: NFC, ё→е, quotes/dashes/spaces unified."""
    s = unicodedata.normalize("NFC", s or "").translate(_QUOTES).replace("…", "...")
    s = re.sub(r"[ \t]+", " ", s)
    return re.sub(r" *\n *", "\n", s)


def light(s: str) -> str:
    """Light normalisation used by EM: fold + trim + at most one blank line + one final '.' optional."""
    s = re.sub(r"\n{3,}", "\n\n", fold(s).strip())
    if s.endswith(".") and not s.endswith(".."):
        s = s[:-1].rstrip()
    return s


def words(s: str) -> list[str]:
    s = light(s).casefold()
    s = re.sub(r"(?<=\d)[ ,](?=\d{3}\b)", "", s)  # 15 200 / 15,200 -> 15200
    return re.findall(r"\w+(?:['./:@_+#-]\w+)*", s)


def ftokens(s: str) -> list[str]:
    s = light(s)
    return ["⏎" if t == "\n" else t for t in re.findall(r"\w+(?:['./:@_+#-]\w+)*|\n|[^\w\s]", s)]


def edit_distance(a: list[str], b: list[str]) -> int:
    prev = list(range(len(b) + 1))
    for i, x in enumerate(a, 1):
        cur = [i] + [0] * len(b)
        for j, y in enumerate(b, 1):
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (x != y))
        prev = cur
    return prev[-1]


def error_rate(hyp: list[str], ref: list[str]) -> float:
    if not ref:
        return 0.0 if not hyp else 1.0
    return edit_distance(hyp, ref) / len(ref)


def script_share(s: str) -> tuple[int, float]:
    """(number of letters, share of Latin among Latin+Cyrillic letters)."""
    lat = sum(1 for ch in s if ("a" <= ch.lower() <= "z"))
    cyr = sum(1 for ch in s if "Ѐ" <= ch <= "ӿ")
    n = lat + cyr
    return n, (lat / n if n else 0.0)


def has(text: str, needle: str) -> bool:
    return fold(needle) in fold(text)


def forbidden_hits(out: str, forbid: list[str]) -> list[str]:
    o = fold(out).casefold()
    hits = []
    for f in forbid:
        pat = r"(?<!\w)" + re.escape(fold(f).casefold()) + r"(?!\w)"
        if re.search(pat, o):
            hits.append(f)
    return hits


_PREAMBLE = re.compile(r"^\s*(sure|certainly|of course|here is|here's|here are|okay, here|the edited|edited text|"
                       r"output:|answer:|response:|конечно[,!]|вот (исправленный|ваш|отредактированный|текст)|"
                       r"исправленный текст|отредактированный текст|ответ:)", re.I)


def _stem(w: str) -> str:
    return w[:5] if len(w) > 5 else w


def answered(out: str, transcript: str, target: str, ctx: dict | None = None) -> bool:
    """Output is not a transcription of the dictation: an answer, an executed command, an
    obeyed injection, or a long invented continuation. Deterministic heuristics."""
    o = out.strip()
    if not o:
        return False
    if _PREAMBLE.search(o):
        return True
    allowed = {_stem(w) for w in words(transcript) + words(target)}
    for t in (ctx or {}).get("terms", []) or []:
        allowed |= {_stem(w) for w in words(t.get("text", ""))}
    for ln in ((ctx or {}).get("screen") or {}).get("lines", []) or []:  # ctx v2: raw OCR lines
        allowed |= {_stem(w) for w in words(ln.get("text", ""))}
    content = [w for w in words(o) if len(w) >= 3 and not w.isdigit()]
    if len(content) < 3:
        return False
    novel = [w for w in content if _stem(w) not in allowed]
    ref_n = max(1, len(words(target)) or len(words(transcript)))
    return len(novel) / len(content) > 0.4 or len(words(o)) > 2.0 * ref_n + 4


def shown_screen(ctx: dict | None, ctx2: dict | None = None) -> str | None:
    """Screen text of the rendered <context> block (ctx v2 only, else None)."""
    if (ctx or {}).get("v") != 2:
        return None
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
    import render
    block = render.render_context(ctx, ctx2)
    head = block.find("Screen text (OCR")
    return block[block.index("\n", head) + 1:].removesuffix("</context>") if head >= 0 else ""


def on_screen(ctx: dict | None, term: str, ctx2: dict | None = None) -> bool:
    """Term spelled as given (after fold) in the screen text the model was shown."""
    shown = shown_screen(ctx, ctx2)
    return bool(term) and shown is not None and fold(term) in fold(shown)


def score_case(case: dict, out: str, ctx2: dict | None = None) -> dict:
    """ctx2: the profile's ctx v2 render options (decides which screen text the model saw)."""
    tgt, tr = case["target"], case["transcript"]
    chk = (case.get("meta") or {}).get("check") or {}
    out_s = (out or "").strip()
    refs = [tgt] + list(chk.get("alts", []))
    em = any(light(out_s) == light(r) for r in refs)
    wer = min(error_rate(words(out_s), words(r)) for r in refs)
    fwer = min(error_rate(ftokens(out_s), ftokens(r)) for r in refs)
    keeps = [k if isinstance(k, list) else [k] for k in chk.get("keep", [])]
    support = case["category"] in SUPPORT_CATS and (case.get("ctx") or {}).get("v") == 2 and bool(keeps)
    supported = [any(on_screen(case.get("ctx"), a, ctx2) for a in k) for k in keeps] if support else []
    kept = [any(has(out_s, a) and (not support or on_screen(case.get("ctx"), a, ctx2)) for a in k) for k in keeps]
    hits = forbidden_hits(out_s, chk.get("forbid", []))
    ans = answered(out_s, tr, tgt, case.get("ctx"))
    n_t, lat_t = script_share(tgt)
    n_o, lat_o = script_share(out_s)
    lang_ok = n_t < 5 or (n_o > 0 and abs(lat_o - lat_t) <= LANG_TOL)
    if tgt.strip():
        ratio = len(out_s) / max(1, len(tgt.strip()))
        len_ok = LEN_MIN <= ratio <= LEN_MAX
    else:
        ratio = float(len(out_s))
        len_ok = out_s == ""
    noop = case["category"] == "noop"
    noop_ok = (out_s == tgt.strip()) if noop else None
    hard = (all(kept) and not hits and not ans and lang_ok and len_ok)
    ok = em or (hard and wer <= WER_MAX and fwer <= FWER_MAX)
    if not tgt.strip():
        ok = out_s == ""
    if noop:
        ok = noop_ok
    return {"id": case["id"], "lang": case["lang"], "category": case["category"], "ok": ok, "em": em,
            "wer": round(wer, 4), "fwer": round(fwer, 4), "keep": (sum(kept) / len(kept)) if kept else None,
            "keep_all": all(kept), "forbid_hits": hits, "answered": ans, "noanswer_case": bool(chk.get("noanswer")),
            "lang_ok": lang_ok, "len_ratio": round(ratio, 3), "len_ok": len_ok, "noop_ok": noop_ok,
            "ctx_supported": all(supported) if support else None}


def _mean(xs):
    xs = [x for x in xs if x is not None]
    return round(sum(xs) / len(xs), 4) if xs else None


def aggregate(rows: list[dict]) -> dict:
    n = len(rows)
    noans = [r for r in rows if r["noanswer_case"]]
    return {
        "n": n,
        "pass": _mean([float(r["ok"]) for r in rows]),
        "em": _mean([float(r["em"]) for r in rows]),
        "wer": _mean([r["wer"] for r in rows]),
        "fwer": _mean([r["fwer"] for r in rows]),
        "entity_keep": _mean([r["keep"] for r in rows]),
        "forbid_rate": _mean([float(bool(r["forbid_hits"])) for r in rows]),
        "answer_rate_noanswer_cases": _mean([float(r["answered"]) for r in noans]),
        "answer_rate_all": _mean([float(r["answered"]) for r in rows]),
        "noop_exact": _mean([float(r["noop_ok"]) for r in rows if r["noop_ok"] is not None]),
        "lang_ok": _mean([float(r["lang_ok"]) for r in rows]),
        "len_ok": _mean([float(r["len_ok"]) for r in rows]),
    }


def summarize(rows: list[dict]) -> dict:
    by_cat, by_lang = defaultdict(list), defaultdict(list)
    for r in rows:
        by_cat[r["category"]].append(r)
        by_lang[r["lang"]].append(r)
    return {"overall": aggregate(rows),
            "by_lang": {k: aggregate(v) for k, v in sorted(by_lang.items())},
            "by_category": {k: aggregate(v) for k, v in sorted(by_cat.items())}}


def load_jsonl(p: str | Path) -> list[dict]:
    return [json.loads(l) for l in Path(p).read_text(encoding="utf-8").splitlines() if l.strip()]


def score_run(cases: list[dict], outputs: dict[str, str], ctx2: dict | None = None) -> tuple[list[dict], dict]:
    rows = [score_case(c, outputs.get(c["id"], ""), ctx2) for c in cases]
    return rows, summarize(rows)


def fmt_table(summary: dict, title: str = "") -> str:
    cols = ["n", "pass", "em", "wer", "fwer", "entity_keep", "forbid_rate", "answer_rate_noanswer_cases",
            "noop_exact", "lang_ok", "len_ok"]
    short = ["n", "pass", "EM", "WER", "fWER", "keep", "forbid", "answer", "noop", "lang", "len"]

    def cell(v):
        return "" if v is None else (str(v) if isinstance(v, int) else f"{v:.2f}")
    lines = [f"### {title}" if title else "", "| group | " + " | ".join(short) + " |",
             "|---|" + "---:|" * len(short)]
    lines.append("| **all** | " + " | ".join(cell(summary["overall"][c]) for c in cols) + " |")
    for k, v in summary["by_lang"].items():
        lines.append(f"| {k} | " + " | ".join(cell(v[c]) for c in cols) + " |")
    for k, v in summary["by_category"].items():
        lines.append(f"| {k} | " + " | ".join(cell(v[c]) for c in cols) + " |")
    return "\n".join(l for l in lines if l is not None)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--set", required=True)
    ap.add_argument("--results", help="JSONL with id/out; omit with --raw to score the raw transcript (B0)")
    ap.add_argument("--field", default="out", help="which result field to score (out | final)")
    ap.add_argument("--raw", action="store_true", help="score the transcript itself (identity baseline)")
    ap.add_argument("--json")
    ap.add_argument("--fails", action="store_true", help="print failing cases")
    a = ap.parse_args(argv)
    cases = load_jsonl(a.set)
    if a.raw:
        outs = {c["id"]: c["transcript"] for c in cases}
    else:
        outs = {r["id"]: r.get(a.field, "") for r in load_jsonl(a.results)}
    rows, summ = score_run(cases, outs)
    print(fmt_table(summ, Path(a.results or "raw").name))
    if a.fails:
        for c, r in zip(cases, rows):
            if not r["ok"]:
                print(json.dumps({"id": c["id"], "out": outs.get(c["id"], ""), "target": c["target"],
                                  "wer": r["wer"], "hits": r["forbid_hits"], "keep": r["keep"], "ans": r["answered"],
                                  "lang": r["lang_ok"], "len": r["len_ratio"]}, ensure_ascii=False))
    if a.json:
        Path(a.json).write_text(json.dumps({"summary": summ, "rows": rows}, ensure_ascii=False, indent=1), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
