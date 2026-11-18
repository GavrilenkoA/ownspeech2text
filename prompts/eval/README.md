# Prompt evaluation (dev/test sets, scorer, harness)

Results and decisions: `docs/ml/PROMPT_EVAL.md`. Everything here is stdlib Python 3.11+, no network.

| File | What |
|---|---|
| `cases/test_v1.py` | **Held-out test set** (66 cases), written before any prompt tuning. Only aggregate numbers may be looked at. |
| `cases/dev_v1.py` | Dev set (98 cases) for tuning. |
| `cases/shots_v2.py` | Few-shot demonstrations used by profile `default.v2` (disjoint from dev/test). |
| `cases/build.py` | Validates the cases and writes `sets/{dev,test}.v1.jsonl` and `../shots/default.v2.jsonl` (`make -C prompts sets`). |
| `sets/*.jsonl` | Generated sets in the shared data contract (below). Do not edit by hand. `dev.v1`/`test.v1` (structured ctx v1) are **superseded** by `dev.v2`/`test.v2` (same cases, ctx v2 = OCR lines); kept for the v1→v2 comparison. |
| `sets/{dev,test}.v2.specs.jsonl` | Render spec per case (`{"id", "spec"}`, CONTEXT_V2 spec format + fixture keys). Re-run `make sets` to regenerate the v2 contexts from them. |
| `cases/to_v2.py`, `cases/build_v2.py` | v1 case → render spec (title/messages/terms/snippets/field + neutral filler history, ~12 % without screenshot, ~25 % "dense" screens) → ctx v2 via `ml.screens.build_ctx` when `ml/screens/READY` exists, else `ocr_fixture.py`; verifies that each case's `meta.check.screen_terms` is in the OCR lines **and** in the rendered `<context>`. Also writes `smoke_cases.v2.jsonl` and the v2 few-shot files. |
| `ocr_fixture.py` | Stand-in for `ml.screens.build_ctx` (no image, no OCR model): lays the spec out like real OCR output of a phone screenshot (calibrated on the spike's real screenshot ground truth) with low-confidence icon junk and rare character errors; never corrupts `spec["protect"]` strings. `meta.ctx_source` says which producer made a set. |
| `cases/shots_v4.py` | Few-shot turns of `default.v4`/`default.v4s`/`default.v5` (v3 shots + 2). |
| `sets/dev.v2dense.jsonl` | 45 context-relevant dev cases re-rendered on dense screens (inner display, keyboard hidden, long history): the budget/order measurement (`build_v2 --dense`). |
| `device_prompt_bench.py` | Prefix/request prefill cost per profile and greedy parity with the host runs, on the phone over adb or on the host with `--local`. |
| `score.py` | Deterministic scorer (no LLM judge). `--raw` scores the transcript itself (identity baseline). |
| `run_eval.py` | Harness: llama-server, greedy, cached system prefix, N parallel slots; writes raw + guarded outputs. |
| `report.py` | Re-scores stored runs (raw and after `prompts/guard.py`), guard statistics, per-category tables. |
| `sweep.sh`, `models.json` | Sequential model × profile × set sweeps; local GGUF paths of the stock models. |
| `run_smoke.py`, `smoke_cases.v1.jsonl` | Phase-1 smoke harness (kept for history; superseded). |

## Data contract (shared with training/data pipelines)

One JSON object per line: `id`, `lang` (`ru`/`en`/`mixed`), `category`, `source` (`manual` here), `split`
(`dev`/`test`; shots `train`), `ctx` (ContextPacket accepted by `render.user_message`), `transcript`, `target`
(exact expected output, `""` allowed), `meta`. Eval-only checks live in `meta.check`:

| key | meaning |
|---|---|
| `keep` | critical entities that must appear verbatim (names, numbers, terms); an inner list = alternatives |
| `forbid` | must not appear (word-boundary, case-insensitive): distractor names, injection payloads, the abandoned half of a self-correction, fillers, translated words |
| `alts` | other outputs that count as exact match |
| `noanswer` | dictated question / command / injection: the output must stay a transcription |
| `screen_terms` | (v2 sets) strings the case depends on that must be visible on the screen and inside the rendered `<context>` (checked by `build_v2.py`, not by the scorer) |

Categories (16): `filler_selfcorr`, `punct_case`, `spoken_format`, `numbers`, `address` (e-mail/URL/phone/handle),
`list`, `context_names` (incl. distractor-only screens), `translit_terms`, `code_switch`, `no_answer`, `injection`,
`noop`, `empty_noise`, `long` (150–300 words), `tone` (terminal, search, e-mail, chat), `continuation`.
ASR styles in `meta.asr_style`: `lower` (GigaAM / T-one: lowercase, no punctuation, numbers as words, English
terms in Cyrillic), `whisper` (punctuated, may keep fillers and hallucinate tails), `parakeet`.

## Target conventions

* Minimal edit: the speaker's words and order; fillers, stutters and the abandoned half of self-corrections removed.
* Digits for clock times (`14:45`), dates (`23 декабря`, `March 3rd`), money, percentages, measurements, versions,
  phones and numbers > 10; small counts stay words. Russian thousands with a space (`15 200`), `25 %` or `25%`
  (both accepted via `keep` alternatives).
* Products/technologies in their usual Latin spelling (`Kubernetes`, `Jira`); Russian jargon stays Cyrillic
  (`запушил`, `пул-реквест`, `стейджинг` accepted as spoken).
* Code-switching kept as spoken: English stays English, Russian stays Russian.
* Messaging: a single short sentence may end without a period (EM ignores one final period anyway).
* Search fields: no final period, ordinary words lowercase. Address fields: the bare address. Terminal: the command.
* Continuation: only the new text; lowercase if the field text ends mid-sentence. No leading space (the app joins).
* Noise-only / subtitle credits: empty string.

## Metrics (`score.py`)

| metric | definition |
|---|---|
| `pass` | EM, or all hard checks pass and WER ≤ 0.15 and fWER ≤ 0.20; empty target: output must be empty; `noop`: EM required |
| `EM` | equality after light normalisation (NFC, ё→е, quotes/dashes/spaces unified, ≤ 1 blank line, one final `.` optional) against target or `alts` |
| `WER` | word error rate vs target, lowercase, punctuation ignored, `15 200`→`15200` |
| `fWER` | token error rate with case, punctuation and line breaks as tokens (formatting quality) |
| `keep` | share of `keep` entities present |
| `forbid` | share of cases with any `forbid` hit |
| `answer` | on `noanswer` cases: output starts with an assistant preamble, or > 40 % of its content words are absent from transcript+target+screen terms, or it is > 2× the target length + 4 words |
| `noop` | byte-exact (after strip) on `noop` cases |
| `lang` | Latin share of letters within 0.2 of the target's (catches translation of a code-switched part) |
| `len` | output chars / target chars in [0.6, 1.5] |

## Running

```
make -C prompts test                                   # unit tests (renderer v1+v2 goldens, chat templates, scorer, guard)
make -C prompts eval M=q2b PROFILE=default.v4 SET=dev.v2    # current prompt on the OCR-context dev set
tools/heavy python3 prompts/eval/run_eval.py --m q2b --profile default.v4 --set dev.v2 \
    --ctx2 '{"order":"reading"}' --tag _reading         # ctx v2 option ablation (overrides the profile's ctx2)
make -C prompts sets                                   # rebuild + validate sets
make -C prompts eval M=q2b PROFILE=default.v2 SET=dev  # one run (tools/heavy, 6 threads, 3 slots)
make -C prompts sweep MODELS="q2b lfm3 q08" PROFILES="default.v1 default.v2" SET=dev
make -C prompts report SET=dev
```

Prefix caching: the profile's request-independent prefix (`render.cache_prefix`) is pre-filled once, saved with
`/slots/0?action=save` and restored into the slot before every request, so a request costs only its own user
message (~80–150 tokens, ~250–600 for long dictations). This also works for the hybrid recurrent models
(Qwen3.5, LFM), whose cache cannot be truncated. Decoding: temperature 0, top-k 1, no repetition/presence penalty,
`n_predict = min(1024, 2·transcript_tokens + 64)`, stop at `<|im_end|>`. With 3 parallel slots batch composition
varies between runs, so a rerun can differ in a few greedy outputs (measured: see PROMPT_EVAL.md).
