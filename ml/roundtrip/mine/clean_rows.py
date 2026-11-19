"""Select the round-trip rows the primary mining tables are built from, by documented rules.

Every rule is applied in the order below; a row is dropped by the first rule that matches and the reason is
written to the exclusions file, so nothing is removed by hand.

  R1 piper_ru     Piper rows of ru/mixed sentences (the primary tables leave Piper's two Russian voices out;
                  per-TTS tables keep them). Off with --keep-piper-ru.
  R2 tts_flag     clips listed in --flags (the TTS itself garbled the input, e.g. digits inside Latin names that
                  the voice did not say); the whole clip is dropped (all of its ASR rows).
  R3 empty        empty or whitespace-only hypothesis.
  R4 loop         hallucination loop: the hypothesis repeats one 1-4 word n-gram at least LOOP_REPEATS times in
                  a row (or one non-word character at least CHAR_LOOP times) and the reference does not.
  R5 tts_fail     clip-level TTS failure: the clip has >= MIN_CLIP_MODELS rows and even its best hypothesis has
                  normalised WER >= CLIP_BEST_WER (no recogniser recovered the text, so the audio does not carry
                  it); the whole clip is dropped.
  R6 tts_skip     the TTS skipped words: >= SKIP_RUN consecutive reference words are deleted (no hypothesis word
                  at all at that place) by at least 2/3 of the clip's recognisers (>= MIN_CLIP_MODELS rows);
                  the whole clip is dropped.

  python -m ml.roundtrip.mine.clean_rows full.jsonl --out primary_rows.jsonl \\
      --flags flags_supertonic_digit_names.jsonl --excluded excluded.jsonl --summary clean_summary.json
"""
from __future__ import annotations

import argparse
import difflib
import json
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any, Iterable, Sequence

from .align import raw_wer_counts, raw_words

LOOP_REPEATS = 4
CHAR_LOOP = 5
CLIP_BEST_WER = 0.5
MIN_CLIP_MODELS = 3
SKIP_RUN = 3
SKIP_SHARE = 2 / 3

CLIP_KEY = ("probe_id", "tts_system", "tts_voice", "channel")


def clip_key(row: dict[str, Any]) -> tuple:
    return tuple(row.get(k) for k in CLIP_KEY)


def max_ngram_repeat(words: Sequence[str], max_n: int = 4) -> int:
    """Longest run of one n-gram (n <= max_n) repeated back to back, in repetitions."""
    best = 1 if words else 0
    for n in range(1, max_n + 1):
        for i in range(len(words) - n + 1):
            k, j = 1, i + n
            while j + n <= len(words) and list(words[j:j + n]) == list(words[i:i + n]):
                k += 1
                j += n
            best = max(best, k)
    return best


def max_char_run(text: str) -> int:
    """Longest run of one non-alphanumeric, non-space character (``14::::::::``)."""
    best, prev, run = 0, "", 0
    for ch in text:
        if ch == prev and not ch.isalnum() and not ch.isspace():
            run += 1
        else:
            run = 1
        prev = ch
        if not ch.isalnum() and not ch.isspace():
            best = max(best, run)
    return best


def is_loop(ref: str, hyp: str) -> bool:
    hr, rr = max_ngram_repeat(raw_words(hyp)), max_ngram_repeat(raw_words(ref))
    if hr >= LOOP_REPEATS and hr > rr:
        return True
    return max_char_run(hyp) >= CHAR_LOOP and max_char_run(hyp) > max_char_run(ref)


def deleted_ref_positions(ref: Sequence[str], hyp: Sequence[str]) -> set[int]:
    out: set[int] = set()
    for tag, i1, i2, _j1, _j2 in difflib.SequenceMatcher(None, list(ref), list(hyp), autojunk=False).get_opcodes():
        if tag == "delete":
            out.update(range(i1, i2))
    return out


def skipped_run(rows: Sequence[dict[str, Any]]) -> int:
    """Longest run of reference words deleted by >= SKIP_SHARE of the clip's rows."""
    if len(rows) < MIN_CLIP_MODELS:
        return 0
    ref = raw_words(rows[0]["text"])
    cnt: Counter = Counter()
    for r in rows:
        cnt.update(deleted_ref_positions(ref, raw_words(r["hypothesis"] or "")))
    best = run = 0
    for i in range(len(ref)):
        run = run + 1 if cnt[i] >= SKIP_SHARE * len(rows) else 0
        best = max(best, run)
    return best


def load_flags(path: str | Path | None) -> set[tuple]:
    if not path:
        return set()
    with open(path, encoding="utf-8") as fh:
        return {clip_key(json.loads(line)) for line in fh if line.strip()}


def clean(rows: Iterable[dict[str, Any]], flags: set[tuple] = frozenset(), keep_piper_ru: bool = False
          ) -> tuple[list[dict[str, Any]], list[dict[str, Any]], dict[str, Any]]:
    """(kept rows in input order, excluded [{rule, key, asr_model, detail}], summary)."""
    rows = list(rows)
    reason: dict[int, tuple[str, str]] = {}
    for i, r in enumerate(rows):
        hyp = r.get("hypothesis")
        if not keep_piper_ru and r.get("tts_system") == "piper" and r.get("lang") != "en":
            reason[i] = ("R1_piper_ru", "")
        elif clip_key(r) in flags:
            reason[i] = ("R2_tts_flag", "")
        elif hyp is None or not str(hyp).strip():
            reason[i] = ("R3_empty", "")
        elif is_loop(r["text"], hyp):
            reason[i] = ("R4_loop", hyp[:200])
    clips: dict[tuple, list[int]] = defaultdict(list)
    for i, r in enumerate(rows):
        if i not in reason:
            clips[clip_key(r)].append(i)
    for key, idx in clips.items():
        group = [rows[i] for i in idx]
        if len(group) >= MIN_CLIP_MODELS:
            best = min(raw_wer_counts(r["text"], r["hypothesis"]).rate for r in group)
            if best >= CLIP_BEST_WER:
                for i in idx:
                    reason[i] = ("R5_tts_fail", f"best WER {best:.2f}")
                continue
            run = skipped_run(group)
            if run >= SKIP_RUN:
                for i in idx:
                    reason[i] = ("R6_tts_skip", f"{run} words skipped")
    kept = [r for i, r in enumerate(rows) if i not in reason]
    excluded = [{"rule": reason[i][0], "key": list(clip_key(rows[i])), "asr_model": rows[i].get("asr_model"),
                 "detail": reason[i][1]} for i in sorted(reason)]
    by_rule = Counter(e["rule"] for e in excluded)
    summary = {"rows_in": len(rows), "rows_kept": len(kept), "excluded_by_rule": dict(sorted(by_rule.items())),
               "clips_kept": len({clip_key(r) for r in kept}),
               "rules": {"LOOP_REPEATS": LOOP_REPEATS, "CHAR_LOOP": CHAR_LOOP, "CLIP_BEST_WER": CLIP_BEST_WER,
                         "MIN_CLIP_MODELS": MIN_CLIP_MODELS, "SKIP_RUN": SKIP_RUN, "SKIP_SHARE": round(SKIP_SHARE, 3),
                         "keep_piper_ru": keep_piper_ru, "flags": len(flags)}}
    return kept, excluded, summary


def main(argv: Sequence[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("rows", help="round-trip rows (interface 2 JSONL)")
    ap.add_argument("--out", required=True, help="kept rows JSONL")
    ap.add_argument("--flags", default=None, help="JSONL of clips to drop (probe_id, tts_system, tts_voice, channel)")
    ap.add_argument("--excluded", default=None, help="write the dropped rows' rule and key here")
    ap.add_argument("--summary", default=None, help="write the counts per rule here (JSON)")
    ap.add_argument("--keep-piper-ru", action="store_true", help="do not apply R1")
    a = ap.parse_args(argv)
    with open(a.rows, encoding="utf-8") as fh:
        lines = [ln for ln in fh if ln.strip()]
    rows = [json.loads(ln) for ln in lines]
    kept, excluded, summary = clean(rows, load_flags(a.flags), a.keep_piper_ru)
    kept_ids = {id(r) for r in kept}
    with open(a.out, "w", encoding="utf-8") as fh:
        for ln, r in zip(lines, rows):
            if id(r) in kept_ids:
                fh.write(ln if ln.endswith("\n") else ln + "\n")
    if a.excluded:
        with open(a.excluded, "w", encoding="utf-8") as fh:
            for e in excluded:
                fh.write(json.dumps(e, ensure_ascii=False) + "\n")
    if a.summary:
        Path(a.summary).write_text(json.dumps(summary, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
