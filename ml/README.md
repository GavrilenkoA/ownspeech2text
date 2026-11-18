# ml/ — Murmur post-processing models: training, export, evaluation, GPU pod

Owners: `ml/train`, `ml/eval`, `ml/pod` (this file). Data generation lives in `ml/data`, `ml/common`; the
round-trip ASR-noise probe in `ml/roundtrip` (other owners). Binding plan: `docs/ml/PLAN_V2.md`.
End-to-end procedure, run matrix and measured timings: `docs/ml/TRAINING_RUNBOOK.md`. GPU request:
`ml/pod/REQUEST.md`.

## Decisions

1. **One stack for all three bases**: transformers 5.23.0 + PEFT 0.21.3 + plain `Trainer` (no TRL, no
   Unsloth). transformers 5.23 is the first release with native `lfm3` (and has `qwen3_5`); torch 2.15.0 is
   CUDA 13.2-only on GPU, so the pod needs driver >= 580.
2. **Prompt rendered at load time** by `prompts/render.py` through `ml/eval/contract.render_prompt`
   (system prompt name or prompt profile, few-shot profiles included). Training default: `finetuned.v1`;
   15 % rendered with `default.v1`; 15 % with a random target-neutral preferences block
   (`ml/train/preferences_pool.v1.txt`); `meta.preferences` always wins. Byte-equal to the vendors'
   `apply_chat_template(..., enable_thinking=False)` and to `render.render_prompt` (tests).
3. **Loss only on `target + <|im_end|>`**; prompt and completion tokenised separately, as at inference.
   Too-long examples are dropped, never truncated; `max_len: auto` = longest rendered training example
   rounded up to 64 (cap 4096), so the OCR contexts of ctx v2 set the limit, not a guess. Right padding
   (safe for DeltaNet / short-conv layers). Trainer checkpoints are deleted once `final/` exists.
4. **PLAN_V2 run matrix**: LoRA first — an LFM3-1.7B sweep (rank / LR / epochs around Liquid's r16 / 2e-4
   recipe), the winner's hyperparameters carried to Qwen3.5-0.8B and 2B (`ml/train/select.py`), full
   fine-tuning as one fallback config (`full-fallback.yaml`, `FULL=1`). LoRA / DoRA / full share one
   `method:` switch, so the comparison is the same data, prompt mix and eval with one config each.
5. **Eval is implemented once** (`ml/eval`): GGUF through `llama-server` (greedy, `cache_prompt`, no
   penalties: the phone's engine and quantisation), HF checkpoints through `generate`, the teacher through
   the Responses API (B3). Metric definitions in `ml/eval/metrics.py` match `prompts/eval/score.py`
   (parity test). Ship gates of DATA_AND_TRAINING_PLAN 4.3 in `ml/eval/gates.py`.
6. **Quantisation loss is measured** (BF16 GGUF vs Q4_0 GGUF of the same weights, `ml.eval.compare`),
   never assumed; the card copies the measured table. LFM3 fine-tunes lose the vendor's QAD (no float QAD
   checkpoint exists); see the runbook for the mitigation order.
7. **Context v2 = what OCR sees** (`docs/ml/CONTEXT_V2.md`). No ml/train or ml/eval code reads ctx fields
   except `eval/ctxview.py` (v2 `screen.lines`; other shapes only through `render.render_context`).
   Reports have a `ctx:screen` / `ctx:no_screen` / `ctx:legacy` breakdown; context-resolution entities only
   count when the term is in `ctx.screen.lines` (no reward for invented terms).
8. **No teacher on the pod.** The judge (and B3) run on the dev server through `tools/with-teacher`, after
   results are synced; the pod gets only code, data and (for upload) `HF_TOKEN`.

## Layout

| Path | What |
|---|---|
| `eval/contract.py` | shared data contract: validate/load/write JSONL, `render_prompt()`, `render_messages()` |
| `eval/metrics.py` | all metric definitions (pure Python, unit-tested) |
| `eval/ctxview.py` | the only reader of ctx internals (screen lines, context source) |
| `eval/v2_cases.py`, `eval/cases/v2_screens.v1.jsonl` | hand-written cases with a real ctx v2: spec -> `ml.screens.build_ctx` -> contract JSONL, CONTEXT_V2 rules checked on OCR lines (OCR venv, `tools/heavy`) |
| `eval/tests/fixtures/ctx_v2.sample.jsonl` | 5 hand-written ctx v2 examples (stand-in until `ml/screens` build_ctx data exists) |
| `eval/gates.py` | ship gates as data + PASS / FAIL / NO_DATA evaluator |
| `eval/backends.py` | llama-server (GGUF), HF, teacher (Responses API) backends |
| `eval/run_eval.py` | CLI: model x JSONL -> predictions, scores, report.json/.md (resumable; `--rescore`) |
| `eval/judge.py` | LLM judge (score 1-5 + drift) via the dev-server teacher; feeds 2 gates; CLI over eval dirs |
| `eval/compare.py`, `eval/leaderboard.py` | quantisation loss / baseline deltas; one table over all reports |
| `eval/smoke_to_contract.py` | `prompts/eval/smoke_cases.v1.jsonl` -> contract |
| `eval/stub_teacher.py` | deterministic Responses/chat stub (tests; 429 + Retry-After simulation) |
| `train/sft.py` | LoRA / DoRA / full SFT (`method:`), resumable |
| `train/render_data.py` | prompt mixing, tokenisation, loss mask, resampling |
| `train/configs/*.yaml` | PLAN_V2 run matrix + CPU dry run (`extends:`, `${ENV:-default}`, `--set a.b=c`) |
| `train/select.py` | best run of a sweep on the dev Q4_0 eval -> `--set` overrides for the next phase |
| `train/merge.py` | adapter -> merged bf16 HF checkpoint |
| `train/export.py` | HF -> GGUF BF16 -> Q4_0 / Q4_K_M (+ imatrix), manifest with sha256 + llama.cpp build |
| `train/card.py`, `train/upload_hf.py` | model card + catalog entry; upload with `HF_TOKEN` from env |
| `train/assemble.py` | generator shards -> train/dev/test with leak check |
| `train/tiny_chain.py` | whole chain on a tiny random model of a family (LFM3 / Qwen3.5-2B code paths in minutes) |
| `pod/run_all.sh` | pod entry point: `STAGES="bootstrap probe"` (session 1), `"bootstrap train eval"` (session 2), budget guard |
| `pod/bootstrap.sh`, `pod/env.sh` | clean CUDA image -> ready env, everything persistent on the volume |
| `pod/probe.sh` | marked hook for the round-trip probe (owned by `ml/roundtrip`) |
| `pod/run_one.sh`, `pod/train_matrix.sh` | one run end to end; phase A -> select -> phase B (-> fallback) |
| `pod/export_eval.sh` | finalists: quant loss, test eval, default-prompt eval, cards, leaderboard |
| `pod/push_code.sh`, `pod/sync_results.sh`, `pod/after_pod.sh` | dev server side: push code/data, pull results, CPU ship eval + judge |

## Quick start (CPU, this server)

```bash
make -C ml/train venv test          # venv in .cache/ml/train/venv, unit tests
make -C ml/train dryrun-lora        # real Qwen3.5-0.8B: 3 steps -> merge -> GGUF -> Q4_0 -> eval (smoke cases)
make -C ml/train dryrun-full SET="--set train.save_steps=1000"
make -C ml/train tiny FAMILY=lfm3 TOK=.cache/ml/train/models/LFM3-1.7B-tok    # LFM3 code path, random weights
PYTHONPATH=. .cache/ml/train/venv/bin/python -m ml.eval.run_eval --model .cache/ml/llm/Qwen3.5-2B-Q4_0.gguf \
    --data prompts/eval/sets/dev.v1.jsonl --out .cache/ml/train/eval/q2-stock-default --system default.v2
```
Heavy commands go through `tools/heavy` (the Makefile does it). Threads: `OMP_NUM_THREADS` / `--threads` (default 6).
