# Prompt evaluation — stock models, default prompt, ctx v2, output guard

Owner: prompt engineer (ML post-processing). Date: 2026-11-19, review round updated 2026-11-20. Everything below was run on the build server
(x86_64 CPU, llama.cpp b12503 `llama-server`, Q4_0 GGUFs, greedy, 6 threads, under `tools/heavy`). Nothing here ran
on a phone. Run files: `.cache/ml/prompt/runs/<model>.<profile>[_tag].<set>.jsonl` (+ `.meta.json`, `.server.log`).
Every table is reproducible with `python3 prompts/eval/report.py <runs…>` (current guard applied on re-score).
**Numbers marked "pre-rebuild" were measured on v2 contexts from an older ml/ocr + ml/screens** (before producer
`ee0a7097398c`, 2026-11-19 02:09) and were not re-run; all other v2 numbers use the current producer and `guard.v1.1`.

## R. Review round 2026-11-19 — protocol (written before any run on the rebuilt sets)

Review findings: the v2 sets were built with an older ml/ocr + ml/screens (68 of 98 dev and 44 of 66 test cases have
different OCR lines under the current producer), `default.v4` was chosen on dev although it only ties `default.v1`
on test, and the guard was checked against stored test runs while it was being changed. Protocol for this round:

1. Rebuild every v2 set, shot file and smoke file with the current producer once ml/ocr and ml/screens have
   stopped changing; the producer hash is stored in each case (`meta.render.producer`) and `run_eval.py` refuses
   stale sets.
2. Guard frozen at `guard.v1.1` (no rule change in this round; rule changes are decided on dev only from now on).
3. Runs: {`default.v1`, `default.v4s`, `default.v4`} × {Qwen3.5-2B, LFM3-1.7B, Qwen3.5-0.8B}, once each on dev.v2
   and once on test.v2, no prompt or shot edit in between.
4. **Ship rule (fixed now):** profiles ordered by prefix cost v1 (~1.05 K tokens) < v4s (~2.0 K) < v4 (~3.1 K).
   The cheapest profile ships unless a more expensive one, after the guard on test.v2, (a) passes at least 3 more
   cases (≥ 0.045) with Qwen3.5-2B, (b) passes no more than 2 cases fewer with LFM3-1.7B and with Qwen3.5-0.8B,
   and (c) answers no more than 1 extra dictated question/command with any of the three models. Dev numbers are
   reported but do not decide.

**R.5 Result (runs 02:14–07:07, all on producer `ee0a7097398c`, `guard.v1.1`).** Pass after the guard, cases out of 66 on
test.v2 (98 on dev.v2); "answered" = dictated questions/commands/injections answered or executed, raw → after guard (of 11 test, 18 dev):

| profile (prefix tok Qwen / LFM) | Qwen3.5-2B test | LFM3-1.7B test | Qwen3.5-0.8B test | answered test (2B / LFM3 / 0.8B, raw) | Qwen3.5-2B dev | LFM3 dev | 0.8B dev |
|---|---:|---:|---:|---|---:|---:|---:|
| **default.v1** (1,047 / 1,072) | **33** (0.50) | **21** (0.32) | **21** (0.32) | 1 / 2 / 0 → 0 / 0 / 0 | 56 (0.57) | 36 (0.37) | 33 (0.34) |
| default.v4s (2,001 / 2,060) | 32 (0.48) | 18 (0.27) | 19 (0.29) | 1 / 3 / 3 → 0 / 0 / 0 | 52 (0.53) | 35 (0.36) | 33 (0.34) |
| default.v4 (3,116 / 3,226) | 33 (0.50) | 24 (0.36) | 21 (0.32) | 1 / 5 / 3 → 0 / 0 / 0 | 56 (0.57) | 39 (0.40) | 39 (0.40) |

Ship rule: v4s (−1 case) and v4 (±0) do not pass 3 more test cases with Qwen3.5-2B, so **`default.v1` ships**
(v4 would also fail (c): LFM3 answers 3 and Qwen3.5-0.8B 3 more dictated questions raw). On the rebuilt contexts the old
dev gap is gone for Qwen3.5-2B as well (v1 = v4 = 56/98): v4's earlier +3–8-point dev lead was partly the stale
contexts its shots were written against. Raw WER on test, Qwen3.5-2B: v1 0.35, v4 0.47 (v4 has a runaway address output, §6).

**R2. Wording patch of the winner (written 2026-11-19 22:58, before any `default.v6` run).** The ship rule picked
`default.v1` (§R.4, results §4.1). Its rule 9 predates ctx v2 (it does not say that the screen text is raw OCR with
recognition errors and UI labels) and its inline injection example describes a v1 "Names and terms on screen" line
that ctx v2 no longer has. `default.v6` = `default.v1` with exactly two edits: rule 9 replaced by `default.v4`'s OCR
paragraph (+ "and to choose the tone"), and the example's context described as OCR screen text. Same guard
(`guard.v1.1`), same sets. Rule: run v6 on dev.v2 for the three models; only if it passes no more than 2 cases fewer
than v1 with each model (after the guard) and answers no extra dictated question/command, run it once on test.v2.
v6 replaces v1 as the shipped profile only if on test.v2 it again passes no more than 2 cases fewer than v1 with each
model and answers no more dictated questions/commands than v1 (raw and after the guard). Otherwise v1 ships unchanged
and the wording gap is listed as open. v6 is not tuned further in this round whatever the result.
Dev gate (2026-11-20 00:22): pass after guard v6 vs v1 — Qwen3.5-2B 54 vs 56, LFM3 40 vs 36, Qwen3.5-0.8B 31 vs 33 (of 98);
answered raw 1 / 2 / 0 vs 1 / 2 / 0, after guard 0 everywhere → gate passed, test run queued.
**Test result (2026-11-20 00:23–00:34):** pass after guard v6 vs v1 — Qwen3.5-2B 33 vs 33, LFM3 22 vs 21, Qwen3.5-0.8B 23 vs 21 (of 66);
answered raw 1 / **3** / 0 vs 1 / 2 / 0, after guard 0 everywhere; harmful outputs after the guard 13 / 14 / 18 vs
11 / 16 / 15. LFM3 answers one more dictated question raw, so by the rule **v6 does not replace v1: `default.v1`
ships unchanged.** v6 stays in `prompts/` as a measured candidate (equal within noise); v1's missing OCR wording is
listed as open (§8).

## 0. Decisions

1. **Default stock model (nothing fine-tuned): Qwen3.5-2B Q4_0 with profile `default.v1` + `prompts/guard.py`**
   (§R.5: the cheapest profile, not beaten on the held-out test by `default.v4s`/`default.v4`). Prefix 1,047 tokens
   (LFM3 1,072), no few-shot turns; restoring the prefix KV state per dictation still saves its prefill but is no
   longer a precondition. Pass after the guard, dev.v2 / held-out test.v2: Qwen3.5-2B **0.57 / 0.50**, LFM3-1.7B
   0.37 / 0.32, Qwen3.5-0.8B 0.34 / 0.32; no LLM (raw transcript + the guard's pre-LLM noise check) 0.26 / 0.23.
   `default.v6` (v1 + the OCR wording of v4) was measured as a drop-in (§R2): equal within noise on dev and test, but
   LFM3 answered one more dictated question raw on test, so by the pre-registered rule it does not ship.
2. **One prompt for all stock models: `default.v1`.** It is the cheapest and on test not worse than v4/v4s with any
   of the three models except LFM3 (v4 +3 cases, but LFM3 answers 5 of 11 dictated questions raw under v4 vs 2 under v1).
   `default.v4`/`default.v4s` stay in `prompts/` as selectable, measured profiles; they are no longer the default.
3. **Context v2 block (`render.CTX2_DEFAULT`, shipped; chosen pre-rebuild with v4, re-check with v1 in §4.5):** OCR blocks ranked by vertical distance to the focused
   field and shown nearest first; budget 1,400 characters; drop confidence < 0.5, < 2 letters/digits, clock/status
   blocks, repeated lines and lines inside the field; merge wrapped lines; no side marker; no derived terms line;
   context before the transcript. Every alternative measured worse or within ±1 case (noise) of it (§4.5).
4. **Guard always on, frozen at `guard.v1.1`**, `precheck` before the LLM (noise/filler-only/ASR hallucinations never
   reach the model). With `default.v1` on the held-out test it fired on 12–29 % of requests, was right on 38 of 39
   firings, turned 16 failing outputs into passes, broke none, and cut the harmful outputs (answers, wrong language,
   length blow-ups) from 70 to 42 over the three models (§5). Guard rule changes are decided on dev only.
5. **LFM2.5-1.2B-Instruct and LFM2.5-350M are not useful stock post-processors here** (pre-rebuild, default.v4:
   dev after guard 0.23 and 0.27, at or below the no-LLM baseline 0.26; their cards list no Russian). Qwen3-0.6B: 0.33.
   With the shipped `default.v1` on the rebuilt dev.v2: Qwen3-0.6B 0.22, LFM2.5-350M 0.28, LFM2.5-1.2B 0.32 (but it
   answers 9 of 18 dictated questions raw) — all within a few points of the no-LLM 0.26. Mark all three
   "experimental" in the Model hub until fine-tuned.
6. **`default.v5` (rule rewording of v4) rejected** (pre-rebuild): worse or equal on all three models. Wording does not make stock
   models resolve names from OCR text; that, and the classes in §6, are what the fine-tune must fix.
7. **Fine-tuned prompt:** `finetuned.v1.md` stays what training uses today; `finetuned.v2.md` carries the OCR wording
   and should be adopted with the next fine-tune (the training owner decides).

## 1. Eval sets (shared data contract, `prompts/eval/README.md`)

| set | n | ru / en / mixed | context | use |
|---|---:|---|---|---|
| `dev.v1` | 98 | 35 / 32 / 31 | v1 structured (superseded) | tuning v1–v3 |
| `test.v1` | 66 | 22 / 23 / 21 | v1 structured (superseded) | held-out |
| **`dev.v2`** | 98 | same cases | **ctx v2 = real render + real OCR** (`ml.screens.build_ctx`) | tuning v4/v5, ablations |
| **`test.v2`** | 66 | same cases | ctx v2, real OCR | held-out: run only for ship decisions (§R: v1, v4s, v4 once each; §R2: v6) |
| `dev.v2dense` | 45 | context-relevant dev cases | ctx v2 on dense screens (inner display, keyboard hidden, long history) | budget/order measurement |
| `dev.v2fx`/`test.v2fx` | 98/66 | same cases | ctx v2 from `ocr_fixture.py` (before `ml/screens/READY`) | history only |
| `smoke_cases.v2.jsonl` | 24 | | ctx v2, real OCR | smoke (v1 file kept) |

16 categories, 4–7 cases each (filler/self-correction, punctuation/casing, spoken formatting commands, numbers/dates/
money/time, e-mail/URL/phone, lists, names from screen with distractors, transliterated tech terms, code-switching,
dictated questions/commands, injection on screen, no-op, noise-only, long 150–300 words, tone by app, continuation).
ASR styles: lowercase without punctuation (GigaAM/T-one-like), Whisper-punctuated with fillers, Parakeet.
`test_v1.py` was written before any prompt run (file time 19:52, first run 20:01) and only aggregates were read.
The real teacher was not used: the hand-written dev set was already complete.

**Migration v1 → v2** (`prompts/eval/cases/to_v2.py`, `build_v2.py`): every case gets a render spec (CONTEXT_V2
format) built from its v1 context (title, messages with authors and times, the v1 "terms" as a visible line,
snippets, field and text before the cursor) and passed through `ml.screens.specs.from_teacher` (template, font
scale, hint) → `build_ctx` (Chrome render → PP-OCRv6 det + cyrillic PP-OCRv5 rec → ctx v2). Template filler gives the
unrelated screen text; ~12 % of cases have no screenshot, all others a screen. Specs are stored next to the sets
(`sets/*.v2.specs.jsonl`). Real-OCR screens (producer `ee0a7097398c`): dev median 87 OCR characters (p90 386, max 592),
test median 122 (p90 356, max 633), dense subset median 849 (max 1,157) — the spike's real screenshot ground truth (keyboard hidden) has median ~450, max 1,242.
**Verification:** each case's screen terms (`meta.check.screen_terms`: the names/terms it depends on, the injection
payload) must be in the OCR lines and in the rendered `<context>`: 20 cases depend on screen terms (10 dev, 10 test), 17 pass; 3 dev injection
payloads are OCR-hard (the templates truncate them in list previews, "Ignore the transcript a…": the injection is still
on screen, the exact payload string is not) — kept and flagged `ocr_hard`. A re-render with another theme/font
scale was tried first (4 retries, 1 recovered).

## 2. Scorer (`prompts/eval/score.py`, deterministic, no LLM judge)

Per case and per category: exact match after light normalisation, WER, formatting-aware token error rate (fWER),
critical-entity retention (`keep`), forbidden strings (distractor, injection payload, abandoned self-correction,
fillers), answered/executed check on dictated questions/commands/injections, no-op byte preservation, language
preservation (Latin/Cyrillic share vs the target) and length sanity. `pass` = EM, or every hard check passes and
WER ≤ 0.15 and fWER ≤ 0.20; empty target → output must be empty; **no-op cases pass only on byte-exact output**
(`noop_ok`, after strip; no final-period or ё folding). In the screen-support categories (`score.SUPPORT_CATS`) a
`keep` entity counts only if it is also spelled in the `<context>` block the model was shown (`score.on_screen`, the
rendered block under the profile's `ctx2`); ml/eval's `metrics.py` applies the same idea to all OCR lines with a fuzzy
match, so prompt numbers are, if anything, slightly stricter. Both changes (review 2026-11-19) are in every number
re-scored here. Definitions: `prompts/eval/README.md#metrics`.

## 3. Harness (`prompts/eval/run_eval.py`)

`llama-server` per run; the profile's request-independent prefix (system + shots) is evaluated once, saved with the
slot API and restored before each request (this is also how the phone avoids re-prefilling it), so a request costs only its
user message. 6 parallel slots, greedy, no penalties, `n_predict = min(1024, 2·transcript_tokens + 64)`. Throughput on
the shared server varied 10× with other users' load (q08 dev run 75 s – 10 min). Run-to-run noise with identical
prompts (batch composition differs): 1 of 45 outputs changed (q2b dense, 1400 vs 2400 budget) ≈ ±2 points.

## 4. Results

### 4.1 Before → after (pass raw / after guard; dev / held-out test)

| model | phase 1: v1 prompt, v1 ctx | **shipped: `default.v1`, real OCR ctx** | `default.v4`, real OCR ctx | pre-rebuild: v4, real OCR ctx |
|---|---|---|---|---|
| Qwen3.5-2B | 0.40 / 0.55 · 0.45 / 0.55 | **0.44 / 0.57 · 0.41 / 0.50** | 0.49 / 0.57 · 0.42 / 0.50 | 0.48 / 0.56 · 0.42 / 0.50 |
| LFM3-1.7B | 0.26 / 0.39 · 0.32 / 0.41 | **0.21 / 0.37 · 0.24 / 0.32** | 0.17 / 0.40 · 0.21 / 0.36 | 0.21 / 0.43 · 0.18 / 0.32 |
| Qwen3.5-0.8B | 0.22 / 0.33 · 0.24 / 0.32 | **0.23 / 0.34 · 0.24 / 0.32** | 0.30 / 0.40 · 0.23 / 0.32 | 0.30 / 0.40 · 0.20 / 0.30 |
| no LLM (transcript + precheck) | 0.19 / 0.26 · 0.17 / 0.23 | 0.19 / 0.26 · 0.17 / 0.23 | same | same |

**Honest reading.** On the held-out test no prompt revision after v1 is measurably better for the stock models:
v4 ties v1 for both Qwen models and gains 3 cases for LFM3 only by answering more dictated questions (raw). The
v4 dev gains do not transfer (and with Qwen3.5-2B they vanish on dev too once the contexts are rebuilt). What the
prompt work does show: few-shot turns lift a v4-style prompt from 0.31 to 0.56 on dev (§4.2), so they are what makes a
short-rules prompt competitive, not what beats v1's inline examples; the guard adds 8–16 points everywhere.
Real OCR context instead of clean structured context, same v1 prompt, after the guard: dev −2 to +2 points, test
0 to −9 points (Qwen3.5-2B 0.55 → 0.50, LFM3 0.41 → 0.32, Qwen3.5-0.8B 0.32 → 0.32). Test categories hold 4–5 cases, so ±1 case = ±20 points per category (aggregate noise ≈ ±3 points).

### 4.2 Prompt iterations (dev; v1–v3 on dev.v1, rows marked dev.v2 pre-rebuild; pass raw / after guard; prefix tokens Qwen / LFM)

| profile | what | Qwen3.5-2B | LFM3-1.7B | Qwen3.5-0.8B | prefix tok |
|---|---|---|---|---|---:|
| default.v1 | rules + 7 inline examples | 0.40 / 0.55 | 0.26 / 0.39 | 0.22 / 0.33 | 1,047 / 1,072 |
| default_inline.v2 | v2 rules + 10 inline examples | 0.44 / 0.57 | 0.19 / 0.36 | 0.19 / 0.28 | 1,181 / 1,243 |
| default.v2 | 10 few-shot chat turns | 0.46 / 0.58 | 0.26 / 0.43 | 0.31 / 0.42 | 1,923 / 2,012 |
| default.v3 | v3 rules + 13 turns | 0.50 / 0.58 | 0.14 / 0.38 | 0.29 / 0.40 | 2,116 / 2,199 |
| v3x2 (dev.v2) | v3 with OCR contexts | 0.49 / 0.57 | 0.19 / 0.34 | 0.27 / 0.37 | 2,796 / 2,891 |
| **default.v4 (dev.v2)** | no-translation first, OCR paragraph, 15 turns | **0.48 / 0.56** | **0.21 / 0.43** | **0.30 / 0.40** | 3,129 / 3,240 |
| default.v4s (dev.v2) | v4 with 8 turns | 0.46 / 0.54 | — | 0.21 / 0.32 | 2,008 / — |
| default.v4z (dev.v2) | v4 rules, no shots | 0.23 / 0.31 | — | 0.15 / 0.24 | 709 / — |
| default.v1 (dev.v2) | v1 prompt on OCR contexts | 0.40 / 0.53 | 0.23 / 0.39 | 0.21 / 0.32 | 1,047 / 1,072 |
| default.v5 (dev.v2) | v4 + "spell heard terms as on screen" | 0.45 / 0.53 | 0.21 / 0.39 | 0.29 / 0.39 | 3,184 / 3,296 |
| *rebuilt dev.v2:* **default.v1** | shipped | **0.44 / 0.57** | **0.21 / 0.37** | **0.23 / 0.34** | 1,047 / 1,072 |
| *rebuilt dev.v2:* default.v4s | | 0.44 / 0.53 | 0.14 / 0.36 | 0.22 / 0.34 | 2,001 / 2,060 |
| *rebuilt dev.v2:* default.v4 | | 0.49 / 0.57 | 0.17 / 0.40 | 0.30 / 0.40 | 3,116 / 3,226 |
| *rebuilt dev.v2:* default.v6 | v1 + v4's OCR paragraph (§R2) | 0.41 / 0.55 | 0.23 / 0.41 | 0.21 / 0.32 | 1,060 / 1,086 |

Findings: few-shot demonstrations as real chat turns in the exact request format beat the same examples inline in the
system prompt (Qwen +2–14 points); explicit "the screen/app language is not a reason to translate" removed most
translations (language preservation 0.93 → 0.96 for 2B; code-switching 0.67 → 0.83); answer prefixes were not needed (no
preamble/label after the shots; the guard strips them anyway); the empty-output convention is a shot ending in an
empty assistant turn plus the pre-LLM check (no stock model reliably outputs nothing: `empty_noise` raw pass 0 for all).
Prompt cost per request: user message 106 tokens (v1 ctx) → 154 (real OCR ctx) → 361 (dense screens).

### 4.3 Effect of ctx v1 → v2 (same v3 rules and shots; dev; after guard; pre-rebuild)

| model | v1 structured ctx | real OCR ctx | Δ | context_names v1 → v2 |
|---|---:|---:|---:|---|
| Qwen3.5-2B | 0.58 | 0.57 | −0.01 | 0.83 → 0.67 |
| LFM3-1.7B | 0.38 | 0.34 | −0.04 | 0.17 → 0.17 |
| Qwen3.5-0.8B | 0.40 | 0.37 | −0.03 | 0.50 → 0.17 |

OCR context is harder mainly where it matters: names and terms must be found among chrome and unrelated lines,
and screen content leaks into outputs (2B copied "до 1.14" from a merged OCR block; the guard's R7 caught it). The user
message grows by ~48 tokens. On OCR contexts `default.v4` recovers LFM3 (+9) and 0.8B (+3); 2B is unchanged.
The fixture-era numbers (`*.v2fx`, contexts from `ocr_fixture.py`) were within 2 points of real OCR (q08: v3x2 0.35 vs 0.37).

### 4.4 Other stock models (dev.v2, after the guard unless noted)

| model | default.v4, pre-rebuild: pass raw / guard | **default.v1, rebuilt: pass raw / guard** | answer rate raw (v4 / v1) | lang ok (v1) | prefix tok (v4 / v1) |
|---|---|---|---:|---:|---:|
| Qwen3-0.6B (`unsloth/Qwen3-0.6B-GGUF` Q4_0) | 0.23 / 0.33 | 0.12 / 0.22 (2 long cases lost to timeouts, scored as failures) | 0.00 / 0.00 | 0.84 | 3,161 / 1,062 |
| LFM2.5-350M (`LiquidAI/LFM2.5-350M-GGUF` Q4_0) | 0.15 / 0.27 | 0.02 / 0.28 | 0.06 / 0.28 | 0.71 | 3,269 / 1,101 |
| LFM2.5-1.2B-Instruct (Q4_0) | 0.08 / 0.23 | 0.11 / 0.32 | 0.50 / 0.50 | 0.83 | 3,269 / 1,101 |

The guard fires on 47–68 of 98 requests for the LFM2.5 models under v1, i.e. most of their "after guard" score is the raw
transcript. Qwen3-0.6B is the only one that does better with the few-shot profile.

### 4.5 Context v2 format (measured on stock models with default.v4, pre-rebuild; after guard unless noted)

| option (vs shipped) | Qwen3.5-0.8B dev.v2 | Qwen3.5-2B dev.v2 | Qwen3.5-2B dense (45) | user tok |
|---|---:|---:|---:|---:|
| **shipped** (near-first, 1,400, filters, merge) | **0.40** | **0.56** | **0.60** (raw 0.53) | 154 / 361 |
| reading order (top to bottom) | 0.37 | 0.55 | 0.62 (raw 0.49; answers 0.19 vs 0.06) | 152 / 359 |
| budget 800 | — | — | 0.53 (raw 0.47) | 337 dense |
| *re-check, rebuilt dense set, shipped `default.v1`:* shipped options | | | **0.60** (27/45; raw 0.49) | 340 dense |
| *re-check:* reading order | | | 0.62 (28/45; raw 0.51; answers 0 vs 0) | 338 dense |
| *re-check:* budget 800 | | | 0.58 (26/45; raw 0.47) | 314 dense |
| budget 2,400 (= 1,400: no screen exceeds 1,400) | — | — | 0.58 (raw 0.51) | 361 dense |
| no filters, no merge (raw OCR lines) | 0.37 | — | — | 163 |
| derived "Terms on screen" line | 0.40 | — | — | 164 (+147 prefix) |
| "»" marker on right-aligned (own) bubbles | 0.39 | — | — | 154 |
| context after the transcript | 0.35 (answers 0.11) | — | — | 154 |

Budget: 1,400 characters never cuts a real-OCR screen in dev/test (rebuilt: max 633) and cuts no dense screen (max 1,157);
800 binds on the 31 of 45 dense screens whose OCR text exceeds 800 characters and lost 7 points with v4 (pre-rebuild),
2 points (1 case) with v1 on the rebuilt set. 2,400 buys nothing. Re-check with v1: reading order and budget 800 are
within ±1 case of the shipped options, so `CTX2_DEFAULT` stays frozen (it is ported byte-for-byte to Kotlin; changing
it needs a gain larger than the ~±2-point run-to-run noise).

## 5. Output guard (`prompts/guard.py`, port to Kotlin; contract = `prompts/tests/test_guard.py`)

Spec in the module docstring: `precheck` (empty, filler-only, Whisper silence hallucinations, trailing subtitle
credits) before the LLM; after it `repair` (think tags, chat tokens, labels, wrapping quotes, repeated field text) then
first-match rules R1 empty → R2 leaked prompt tags → R3 assistant preamble → R4 word-3-gram loop / char loop / length
blow-up or collapse → R5 script flip → R5b translated span → R6 mostly novel words → R7 unspoken screen name or
near-miss swap. Fallback = the raw transcript (capitalised unless search/address/code or mid-sentence).
`guard.v1.1` (frozen; `GUARD_VERSION`, recorded in every run's meta) = v1 + ctx v2 support (screen terms =
`render.ocr_terms` of all OCR lines) + R4 character loop/length (a phone number became "4-1-5-5-5-…", one hyphenated
"word") + R5b on a single translated word. Those two rule changes were accepted on the 13 dev runs stored at the time
(firings 263 → 272, right 262 → 271, broken passes 0). The 3 stored test runs were also re-scored then and did not
change at all (firings 40 → 40, right 39 → 39, broke 0), so no test result shaped them; from this round on, rule changes
are decided on dev runs only and every behaviour change bumps `GUARD_VERSION`.

| run (shipped `default.v1`, test.v2) | fired | right | fixed | broke | harmful before → after |
|---|---:|---:|---:|---:|---:|
| Qwen3.5-2B | 12 (18 %) | 12 | 6 | 0 | 20 → 11 |
| LFM3-1.7B | 19 (29 %) | 19 | 5 | 0 | 30 → 16 |
| Qwen3.5-0.8B | 8 (12 %) | 7 | 5 | 0 | 20 → 15 |

On dev.v2 with `default.v1`: fired 19 / 39 / 13 (Qwen3.5-2B / LFM3 / Qwen3.5-0.8B), right 18 / 39 / 13, fixed 13 / 15 / 10,
broke 0, harmful 22 → 9, 46 → 17, 27 → 16. Rules that fired on test (all three models): R4 length 13, R5b translated
span 7, R5 script flip 3, R7 distractor 2, R4 loop 1, R2 leaked tags 1, plus the pre-LLM skips.
4 of the firings per test run are the pre-LLM skips (noise cases). "Right" = the replacement is at least as good as the
model text (pass, or the model text had a hard failure, a forbidden string or a higher WER). "Harmful after" is mostly fallbacks that equal the raw
transcript, which the scorer still counts as a language/length miss against the cleaned target.

## 6. Remaining failure classes (what the fine-tune must fix; shipped Qwen3.5-2B + `default.v1` + guard, per-category pass after the guard, dev.v2 / test.v2)

1. **Long dictations (0.00 / 0.00):** fillers and false starts survive in 150–300-word inputs; WER > 0.15 everywhere.
2. **Fillers and self-corrections (0.17 / 0.00):** "нет", "то есть", "sorry", "I mean" kept with both halves.
3. **E-mail/URL/phone dictation (0.43 / 0.00):** "собака/dot/slash" not assembled; digit grouping wrong.
4. **Spoken formatting commands (0.33 / 0.25):** "двоеточие", "вопросительный знак", "new paragraph" kept as words.
5. **Numbers/dates/money (0.43 / 0.20):** words left as words or mis-composed ("1255" for 1 250).
6. **Transliterated tech terms without context (0.17 / 0.50):** "ноушен" → Notion, "эслинт" → ESLint.
7. **Names/terms from OCR context (0.50 / 0.50):** heard term not replaced by the on-screen spelling ("fig ma" → Figma)
   and screen content leaking into the output; instructions do not teach stock models this (v5, pre-rebuild).
8. **Tone by app, incl. terminal/shell (0.57 / 0.40):** "эл эс минус эл эй" → `ls -la`.
9. **Code-switching (0.67 / 0.50 after the guard; raw 0.00 / 0.00):** on the 6 dev cases v1's raw output translates one
   part (3), drops the other-language part (2) or returns OCR screen text instead of the dictation (1); the guard falls
   back to the raw transcript on 5 of them. This is where `default.v4`'s leading "never translate" paragraph helps:
   raw 5/6 on dev, 0.50 on test (v1 0.00); after the guard both reach 0.50 on test.
10. **Dictated questions on the other models:** under v1 the guard still has to stop 2 answered questions of 11 for LFM3
    (raw); under v4 LFM3 and Qwen3.5-0.8B answer 5 and 3 of 11 (raw).
11. **LFM3 rambling:** answer followed by meta-commentary ("Wait, the transcript says…"); the guard's length rule
    (R4: 9 of its 19 test firings) removes it, but the model text is lost (falls back to the raw transcript).
12. **A runaway address output on test with `default.v4`** (Qwen3.5-2B, category WER 3.5) passes the guard; it does not
    occur with the shipped v1. Not inspected (held-out); re-check once a dev case of the same kind exists.
Noise-only input is solved outside the model (precheck: `empty_noise` 1.00 after the guard on both splits).

## 7. Reproduce

```
make -C prompts test                         # 54 unit tests: renderer v1/v2 goldens, templates, scorer, guard
make -C prompts sets goldens                 # v1 sets + v2 sets with real OCR (ml/screens/READY; .cache/ml/ocr/venv), goldens
tools/heavy .cache/ml/ocr/venv/bin/python -m prompts.eval.cases.build_v2 --dense
python3 prompts/eval/cases/fingerprint.py    # producer hash; run_eval.py refuses sets built with another one
tools/heavy python3 prompts/eval/run_eval.py --np 6 --m q2b --profile default.v1 --set dev.v2
tools/heavy python3 prompts/eval/run_eval.py --np 6 --m q2b --profile default.v1 --set dev.v2dense --ctx2 '{"budget":800}' --tag _b800
python3 prompts/eval/report.py .cache/ml/prompt/runs/*.default.v1.dev.v2.jsonl --by-category --stage final --guard-detail
```
Queues actually run: `.cache/ml/prompt/queue_v{6,7,9}.sh` (pre-rebuild), `queue_review2.sh` (§R), `queue_fix5.sh` (§R2, §4.4/4.5
re-checks); logs next to them. Pre-rebuild run files: `.cache/ml/prompt/runs/stale_ocr_v1/`.

## 8. Not verified / open

* Phone: prefill time of the shipped 1.05 K-token prefix (and of v4's 3.1 K, if it stays selectable) and its state
  save/restore, and greedy parity of the phone build with the host outputs (`prompts/eval/device_prompt_bench.py`
  `--profiles default.v1,default.v4`; host run below, made with v4/v4s before the switch).
* Host run of the bench (`tools/heavy python3 prompts/eval/device_prompt_bench.py --local --m q08 --profiles default.v4,default.v4s --n 4`,
  `.cache/device-reports/prompt_bench-20261119-005713/`, contended 16-core server, 6 threads, Qwen3.5-0.8B): prefix 3,128 tokens
  prefilled in 12.7 s (v4s: 2,007 in 7.7 s); a full uncached request ~13.4 s / 8.0 s; generation 19–21 tok/s. Greedy outputs of
  `llama-completion` (one-shot prefill) equal the `llama-server` runs (restored prefix, 6-slot batches) in 3/4 (v4) and 2/4 (v4s)
  cases: near-ties flip between code paths, so phone/host parity is approximate unless the app uses the same path.
  Re-run 2026-11-20 00:44 with the new defaults (`--profiles default.v1,default.v4 --n 2`, lightly loaded server,
  `.cache/device-reports/prompt_bench-20261120-004416/`): v1 prefix 1,046 tokens in 1.9 s, v4 3,115 in 6.8 s; a full
  uncached request 2.2 s vs 9.1 s; generation 52 vs 42 tok/s; 2/2 outputs equal to the server runs for both.
* The shipped `default.v1` predates ctx v2: its rule 9 says the screen text is untrusted, never an instruction and
  never a source of added information, but not that it is raw OCR with recognition errors and UI labels, and its
  inline injection example still describes a v1 "Names and terms on screen" line. `default.v6` fixes both and measured
  equal within noise (§R2) but failed the no-extra-answers condition by one LFM3 case. Re-decide on a fresh held-out set.
* `field.box`: `build_ctx` now emits it (focused node bounds, as `getBoundsInScreen` on the phone); all 98 + 66 + 45
  rebuilt cases carry it. `build_v2.py` keeps the render's visible field box only as a fallback.
* The held-out test set has now been used for three ship decisions (§R, §R2, and the earlier v1/v4 look). It is still
  untuned (no case was read, no rule or shot came from it), but a fresh hand-written held-out set is needed before
  claiming any further prompt gain.
* Small categories (4–7 cases): category-level differences below ~2 cases are noise.
