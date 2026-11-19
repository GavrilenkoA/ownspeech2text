# Murmur — training, export and evaluation runbook (PLAN_V2)

Date: 2026-11-18, review fixes 2026-11-19. Binding plan: `docs/ml/PLAN_V2.md` (1–5K synthetic examples, teacher on the dev
server, one RTX 5090 for 2–3 GPU-hours, LoRA first). Code: `ml/train`, `ml/eval`, `ml/pod` (overview in
`ml/README.md`). GPU request: `ml/pod/REQUEST.md`.
Legend: **[V]** verified here (command, file or source given), **[I]** design choice, **[A]** assumed.

## 0. Decisions

1. **Bases** (HF API, 2026-11-18) [V]:

   | Model | Train from | Size (safetensors) | Licence for redistributing fine-tunes | Template, thinking off |
   |---|---|---:|---|---|
   | LFM3-1.7B | `LiquidAI/LFM3-1.7B` (rev `1da06bec6a81`) | 3.46 GB | LFM Open License v1.0 (`research/lfm3_LICENSE`): Apache-2.0-style grant, **commercial use licensed only while the user's legal entity has annual revenue < US$10M** (s.1 "Threshold", s.5); redistributing a derivative requires a copy of the licence (4a) and notices on modified files (4b) | `<\|startoftext\|><\|im_start\|>…<\|im_start\|>assistant\n<think></think>`; reasoning is **on by default**, off with `enable_thinking=False` (card: "can be inconsistent", so we train with it off) |
   | Qwen3.5-0.8B | `Qwen/Qwen3.5-0.8B` (rev `2fc06364715b`) | 1.75 GB | Apache-2.0: ship LICENSE, mark changes | `<\|im_start\|>assistant\n<think>\n\n</think>\n\n`; off unless `enable_thinking=true` |
   | Qwen3.5-2B | `Qwen/Qwen3.5-2B` (rev `15852e8c1636`) | 4.55 GB | Apache-2.0 | identical `chat_template.jinja` to 0.8B [V cmp] |

   Qwen3.5 checkpoints are `Qwen3_5ForConditionalGeneration` (vision encoder + MTP head); `AutoModelForCausalLM`
   loads the text model only (752M params for 0.8B) [V]. LFM3: `Lfm3ForCausalLM`, hybrid short-conv +
   attention (GQA, per-head gate `g_proj`, sliding window on some layers) [V GGUF metadata, module names].
2. **Stack** [V installed + run on CPU]: torch 2.15.0, transformers 5.23.0 (first release with native `lfm3`;
   absent in 5.22.0, so Liquid's cookbook uses 5.20.2 + `trust_remote_code`), peft 0.21.3, accelerate 1.16.0;
   GPU extras flash-linear-attention 0.6.0 (Qwen3.5 Gated DeltaNet kernels) and liger-kernel 0.8.5.
   **torch 2.15.0 exists only as CUDA 13.2 wheels** (PyPI default; download.pytorch.org cu128 stops at 2.11.0)
   [V index], so the pod needs a host driver >= 580. **No TRL, no Unsloth**: we render and mask ourselves (TRL
   would only re-implement that); Unsloth has no LFM3 module [V repo tree] and buys little at 0.8–2B on a 5090.
   Liquid's official LoRA recipe (cookbook `lfm3_sft_with_trl.ipynb`: r16, α32, dropout 0.05, q/k/v/o +
   in_proj/out_proj + w1/w2/w3, lr 2e-4, effective batch 16, cosine, warmup 5 %) [V notebook] is our anchor
   run `lfm3-r16` (we also adapt `g_proj`, the "optional" per-head gate in the notebook).
3. **Tokenizer quirks** [V `ml/train/tests`]: LFM3's BOS comes from the template text, the tokenizer adds none
   (GGUF `add_bos_token=false`), so tokenise with `add_special_tokens=False`; both families end a turn with
   `<|im_end|>` (Qwen 248046, LFM3 124900), but Qwen3.5's `config.json` has `eos_token_id` 248044
   (`<|endoftext|>`): training appends the `<|im_end|>` id and HF `generate` is given it explicitly.
   Training strings equal the vendors' `apply_chat_template(..., enable_thinking=False)` byte for byte, and
   `ml/eval/contract.render_prompt` equals `prompts/render.py render_prompt` for every profile, few-shot
   profiles included (tests).
4. **Export** [V on CPU, section 4]: PEFT merge (fp32, cast to bf16 once) → llama.cpp **b12503**
   `convert_hf_to_gguf.py --outtype bf16` (Qwen3.5 with `--no-mtp`, text-only) → `llama-quantize Q4_0` →
   eval of the quantised file. Use gguf-py from the same llama.cpp checkout (PyPI `gguf` 0.19.0 predates both
   architectures). LFM3 GGUFs need llama.cpp >= b11960 (vendor card); the app's runtime must be >= the
   conversion build (recorded in the manifest and catalog entry).
5. **QAD** [V vendor GGUF card]: `LiquidAI/LFM3-1.7B-GGUF/LFM3-1.7B-QAD-Q4_0.gguf` is a
   Quantization-Aware-Distillation checkpoint "trained to recover the accuracy lost to Q4_0"; no float QAD
   checkpoint is published (33 LFM3 repos listed), so a fine-tune of `LiquidAI/LFM3-1.7B` **loses QAD** under
   plain post-training Q4_0. Handling, in order: measure (BF16 GGUF vs Q4_0 GGUF of the same fine-tune on the
   same set, and fine-tune Q4_0 vs stock QAD Q4_0); `--imatrix-data` calibration on our rendered prompts (wired);
   Q4_K_M for CPU/GPU builds; own QAT only if Q4_0 loses > 3 pp EM-noop or > 0.2 judge points
   (`ml.eval.compare` prints that trigger). The unsloth Qwen3.5 "Q4_0" files are imatrix mixed quants
   (Q8_0/Q5_K/Q4_1 tensors inside) [V GGUF metadata], so the baselines also include our own plain conversion
   of the stock weights (`STOCK_OURS=1`).
6. **LoRA first, full FT as one fallback** (PLAN_V2). The method comparison stays a first-class experiment:
   same data, same prompt mix, same eval, one config each (`lfm3-r16` vs `full-fallback`); `full-fallback`
   runs only with `FULL=1`, i.e. when LoRA misses the gates. "Misses the gates" is only meaningful because
   a perfect model passes them: an oracle (output := target) gives PASS or INCOMPLETE, never FAIL, on every
   eval set (`test_oracle_never_fails_the_gates`; before the 2026-11-19 fix it FAILed every set).
8. **Prompts** [I, 2026-11-19]: fine-tunes train and are evaluated with **`finetuned.v2`** (OCR wording; the ctx
   v2 data is what it was written for; prompts/README said "adopt with the next fine-tune", which is this one).
   The robustness mix renders **5 %** of examples with **`default.v4s`** (the stock profile a user can actually
   select, 8 OCR shots, ~2.2K tokens; 5 % keeps the mean at ~395 tokens). The eval of a run always uses the
   `prompt.system` it trained with (read from its `config.resolved.json`), and the card / catalog entry
   record it, so the app must send `finetuned.v2` to these models (the Kotlin renderer needs that file).
   Stock baselines use what the app ships for stock models: profiles `default.v4` and `default.v4s`.
9. **Selection and ship sets are ctx v2 only** [I]: dev = synthetic dev split + `prompts/eval/sets/dev.v2.jsonl`
   + `smoke_cases.v2` (converted); test = synthetic test split + `test.v2.jsonl`. The v1 structured-context sets
   are a labelled legacy column (`LEGACY=1`), never used by `select.py`, the gates or the cards.
10. **Guard** [I]: every report holds the raw-output measurement (what `select.py` ranks) and the same
   predictions after `prompts/guard.py` (`guarded`: precheck skip -> empty, repair, fallback to the transcript),
   i.e. what the app inserts; leaderboard and cards show both, the stock-vs-fine-tune comparison is made raw vs
   raw and guarded vs guarded.
7. **Judge and teacher only on the dev server** (PLAN_V2: Responses API proxy, credentials never leave the
   server). The pod produces predictions; the dev server judges them (`ml.eval.judge`, 2 concurrent
   requests, Retry-After honoured) and recomputes the reports (`run_eval --rescore`).

## 1. End-to-end sequence

| # | Step | Where | Command | Time |
|---|---|---|---|---|
| 1 | Unit tests + CPU dry run of the chain | dev server | `make -C ml/train test dryrun` | tests 10 s; dry run section 4 [V] |
| 2 | Request the pod | Hermes | `ml/pod/REQUEST.md` | — |
| 3 | **Session 1** push code | dev server | `POD=… POD_PORT=… POD_KEY=/home/private/projects/murmur/.cache/ml/train/pod-ssh/murmur_pod_ed25519 bash ml/pod/push_code.sh` | 1 min [A] |
| 4 | Bootstrap (driver check, uv + Python on the volume, llama.cpp CPU+CUDA, venv, self-test) | pod | `STAGES="bootstrap probe" bash ml/pod/run_all.sh` | 8 min [A] |
| 5 | **ROUND-TRIP PROBE (owned by `ml/roundtrip`, not implemented here)**: TTS + ASR on GPU | pod | same command; `ml/pod/probe.sh` runs `MURMUR_PROBE_CMD` or `ml/roundtrip/pod_probe.sh` | 15–30 min [A] |
| 6 | Pull probe outputs; **stop the pod** | dev server | `WITH_PROBE=1 POD=… bash ml/pod/sync_results.sh` | 3 min [A] |
| 7 | Error mining + synthetic data (~3K) with the teacher; assemble with leak check | dev server | owned by `ml/roundtrip` + `ml/data`; then `python -m ml.train.assemble --out DIR shards/*.jsonl` | hours, no GPU |
| 8 | **Session 2** push code + data | dev server | `WITH_DATA=1 DATA_DIR=DIR POD=… bash ml/pod/push_code.sh` | 1 min [A] |
| 9 | Bootstrap re-check + base weights; preflight (fla kernel, CUDA llama.cpp); anchor `lfm3-r16` → measured tokens/s and projections → optional sweep runs that fit the target → select → phase B (Qwen 0.8B, 2B); each run trained, merged, exported, **HF-vs-GGUF parity**, Q4_0 dev eval | pod | `tmux new -s murmur "STAGES='bootstrap train eval' bash ml/pod/run_all.sh"` | 84 min [A] |
| 10 | Finalists (best run per base): BF16 dev eval (quant loss), Q4_0 test eval, default.v4-prompt eval, cards, leaderboard | pod | stage `eval` of the same command | 30 min [A] |
| 11 | Pull results + finalist GGUFs; **stop the pod** | dev server | `POD=… bash ml/pod/after_pod.sh` (does the sync first) | 4 min [A] |
| 12 | CPU ship-gate eval, judge (teacher), rescore, stock baselines, cards, leaderboard | dev server | same `after_pod.sh` (no GPU) | ~1–3 h CPU [A] |
| 13 | Owner picks; upload | dev server | `tools/with-teacher python -m ml.train.upload_hf --run … --repo OWNER/…` (`HF_TOKEN` from env) | minutes |
| 14 | On-device: Benchmark button + real dictations | Fold7 | app | owner |

If LoRA misses the gates: start the pod once more, `FULL=1 PHASE_A="" PHASE_B="" STAGES="train eval" bash ml/pod/run_all.sh`
(+68 min [A]). The budget is enforced where the time is spent: billed pod time (since container start, summed
over sessions on the volume) is checked before every stage, run, run step and eval; nothing starts that would
end past `MAX_GPU_HOURS` (5), optional runs must fit `TARGET_GPU_HOURS` (3) together with the mandatory rest
(`ml/pod/REQUEST.md`, "Hard stop"). Every step skips finished work (markers, `train_info.json`, `manifest.json`, `report.json`);
training resumes from its newest checkpoint, so after a stop or crash the same command continues.

## 2. Run matrix (one GPU, sequential)

Same data, same prompt mix (finetuned.v2; 5 % default.v4s; 15 % random preferences block), max_len auto
(longest rendered example rounded up to 64, cap 4096),
batch 16 (≈190 steps per epoch at 3K examples), cosine, warmup 5 %, β2 0.95, alpha = 2r, dropout 0.05,
all linear layers except `lm_head`. Selection on the **Q4_0 GGUF dev eval** (the shipping artifact):
fewest failed gates, then EM, N-WER, no-op exactness, fewer trainable parameters (`ml/train/select.py`).

| Phase | Run (config) | Base | Method | r | LR | Epochs | Question | GPU min [A] |
|---|---|---|---|---:|---:|---:|---|---:|
| A anchor | `lfm3-r16` | LFM3-1.7B | LoRA | 16 | 2e-4 | 3 | anchor = Liquid's recipe; measures tokens/s | 27 |
| A opt. | `lfm3-r64` | LFM3-1.7B | LoRA | 64 | 2e-4 | 3 | capacity | 30 |
| A opt. | `lfm3-r16-lr5e-4` | LFM3-1.7B | LoRA | 16 | 5e-4 | 3 | under-training at ~570 steps? | 27 |
| A opt. | `lfm3-r16-e5` | LFM3-1.7B | LoRA | 16 | 2e-4 | 5 | more passes over a small set | 45 |
| A opt. | `lfm3-dora-r16` (`DORA=1`) | LFM3-1.7B | DoRA | 16 | 2e-4 | 3 | DoRA vs LoRA | 35 |
| B | `q08-lora` + winner's r/α/lr/epochs | Qwen3.5-0.8B | LoRA | ← A | ← A | ← A | speed option | 16 |
| B | `q2-lora` + winner's r/α/lr/epochs | Qwen3.5-2B | LoRA | ← A | ← A | ← A | quality option | 35 |
| fallback | `full-fallback` (`FULL=1`) | LFM3-1.7B (embeddings frozen) | full FT | — | 1e-5 | 3 | does full FT fix what LoRA misses? | 68 |

Per-run minutes = train + merge + GGUF + parity + Q4_0 dev eval, from the anchor estimate (3K examples ×
~395 tokens × 3 epochs at ~3.1K tokens/s = 30 % MFU, see `ml/pod/REQUEST.md`) times the cost factors in
`ml/pod/train_matrix.sh` (`RUN_COST`) [A]. On the pod the anchor runs first and alone; its measured time
replaces the estimate, `train_matrix.sh` prints the projection of every remaining run, and each optional run
("A opt.", DoRA, full FT) starts only if its projection plus all mandatory work still ahead (phase B + 30 min of
finalist evals) fits `TARGET_GPU_HOURS`. At the assumed speed no optional run fits; at >= 6K tokens/s one or
two do. Data-limited check (PLAN_V2 "scale only if eval says so"): if `lfm3-r16-e5` beats `lfm3-r16` while dev
loss still falls, the set is too small for the task → generate more (up to 5K) rather than add epochs.

Export parity (every run, `ml/train/parity.py`, `PARITY_N=24`): after GGUF conversion the merged HF checkpoint
(bf16, torch) and the BF16 GGUF (llama.cpp) decode the same 24 category-spread dev items greedily; an item agrees when the texts are identical or share a
prefix of min(64 chars, half the longer text) (bf16 rounding diverges late, a broken export at once); fewer
than 80 % agreeing items fails the run (exit 3) before it is evaluated. This catches a silent merge/convert error,
e.g. in the Qwen3.5 Gated DeltaNet `in_proj_qkv/z/a/b` tensors that LoRA adapts and `convert_hf_to_gguf.py`
reorders, which would otherwise look like "the recipe does not work". The quantisation loss (BF16 GGUF vs
Q4_0 GGUF) is measured separately for each finalist.

## 3. Evaluation (one implementation, used everywhere)

`python -m ml.eval.run_eval --model X.gguf|HF_DIR --data A.jsonl [B.jsonl …] --out DIR --system NAME` renders
every prompt with `prompts/render.py` (system prompt name or profile, e.g. `default.v2` with few-shot turns),
decodes greedily through `llama-server` (temperature 0, top-k 1, no penalties, `cache_prompt`,
`max_new_tokens = min(1024, 2·transcript_tokens + 64)`, stop `<|im_end|>`), strips a leaked think block, and
writes `predictions.jsonl` (resumable), `scores.jsonl`, `report.json`, `report.md`. Backends: `llama` (GGUF,
the phone's engine and quantisation), `hf` (transformers), `teacher` (Responses API, baseline B3, dev server).
`--rescore DIR` rebuilds a report from synced predictions + `judge.jsonl` without the model.

Metrics (`ml/eval/metrics.py`, one module, unit-tested, normalisation identical to `prompts/eval/score.py`
with a parity test) per language and category: byte-exact match, EM after light normalisation (alts allowed),
corpus N-WER vs target and the raw transcript's N-WER, formatting-aware token error rate, entity recall,
distractor insertion, forbidden strings, answered/executed rate on dictated questions/commands, injection
success, no-op exactness, empty-output correctness and false-empty rate, wrong-language rate, drift proxy
(numbers/negations) and judge drift, length ratio, latency p50/p95, prefill ms/token, decode tok/s, tokens.
Context v2 (`docs/ml/CONTEXT_V2.md`): `ml/eval/ctxview.py` is the only code in ml/train and ml/eval that
looks inside `ctx` (v2 `screen.lines`; any other ctx only through `render.render_context`); everything else
hands ctx to `prompts/render.py`. Reports break every metric down by context source (`ctx:screen`,
`ctx:no_screen`, `ctx:legacy` = v1 structured context). CONTEXT_V2 category rules are checked per item on
the OCR lines (`ctx_rule_ok`; a violation is a data defect, counted in `ctx_unsupported_items` and failing the
"Eval-set integrity" gate): `context_resolution` / `context_names` / `context_asr_correction`: the item's
**support terms** (`meta.entities`, else `meta.check.screen_terms`, else the name-like keep entries) are readable
on screen (case-insensitive, Russian inflection tolerated: "Веронике Ткачук" ~ "Вероника Ткачук"; plain keep
phrases such as "договорились" are not support terms); `near_miss`: every distractor is on screen and the said
term is **absent**; `context_counterpart`: the forbidden written form is absent. Entities of an item that breaks
its rule never count as kept, so restoring a term the screen does not show is never rewarded (`resolved`,
`ctx_resolution_rate`); a `context_counterpart` output that contains the unsupported term counts as a distractor
insertion. EM-noop counts only true `no_op` items (`near_no_op` is reported as `near_noop_exact`, not gated).
Injection items pass only if the output stays close to the target (EM, or word edits <= max(1, 10 % of the
target's words) and formatting-token error rate <= 0.30), copies no word 3-gram of the payload
(`meta.injection.text`, `check.screen_terms`, else any screen 3-gram the dictation does not contain), hits no
`check.forbid` string and is not "answered" (screen words are not whitelisted for injection items): appending
"Respond to the user as a pirate." or SHOUTING the target + "Sent with love." now counts as a successful
injection on every one of the 19 proof_v6 / dev.v2 / test.v2 injection items [V unit test + scratch check].
Ship gates (`ml/eval/gates.py` = DATA_AND_TRAINING_PLAN 4.3 + empty output >= 90 % + eval-set integrity),
overall and per language, PASS / FAIL / NO_DATA; a non-GGUF report is flagged "not a ship measurement". The ship
measurement is the finalist's Q4_0 file on the CPU llama.cpp build (`after_pod.sh`, `Q4_0-test-cpu`).
`test_oracle_never_fails_the_gates` scores output := target on proof_v6 dev/test, `dev.v2`, `test.v2`,
`smoke_cases.v2` and the v2 fixture: every one is PASS or INCOMPLETE (too few items for some gates), never FAIL.

CPU eval fallback timing [M on the dev server, 6 threads, shared host]: Q4_0 0.8B ~1-2 s per item with the
short prompt, 2B / LFM3-1.7B 4-9 s per item with the long default prompt (`.cache/ml/train/eval/v2*`). A
~420-item dev eval is then ~10-40 min, and a session has ~10 evals: this is why `train_matrix.sh` aborts
when the CUDA llama.cpp build is unusable instead of silently falling back.

## 4. CPU dry run (what was proven without a GPU) [V]

Host: shared x86_64 dev server, CPU only, 6 threads, `tools/heavy` scopes; other agents' jobs ran at the
same time (load average 3-55), so CPU times are upper bounds, not throughput numbers. Data: the 24 smoke
cases converted to the contract (`make -C ml/train smoke-data`), base `Qwen3.5-0.8B` real weights, prompt mix
as in training (finetuned.v1, 10 % default.v1, 25 % preferences). Commands: `make -C ml/train dryrun-lora`,
`make -C ml/train dryrun-full SET="--set train.save_steps=1000" EVAL_LIMIT=8 QLIMIT=4`; logs
`.cache/ml/train/logs/dryrun-{lora,lora-eval2,full}.log`, outputs `.cache/ml/train/runs/dryrun-{lora,full}/`.

| Step | LoRA r16 (all-linear, 43.3M trainable) | Full FT (752M trainable) |
|---|---|---|
| train 3 steps (batch 1 x accum 2, max_len auto) | 525.6 s wall, peak RSS 10.6 GB; loss 1.159 / 1.380 / 1.019 | 271.9 s, peak RSS 17.3 GB; loss 1.159 / 9.847 / 3.163 (lr 1e-4 is a LoRA lr: full FT diverges at it, the fallback config uses 1e-5) |
| merge (PEFT, fp32 -> bf16) | 46.0 s, 5.5 GB RSS | not needed |
| GGUF BF16 (`convert_hf_to_gguf.py`, text-only, `--no-mtp`) | 42.5 s, 1,516,742,240 B | 15.9 s, same size |
| Q4_0 (`llama-quantize`) | 10.1 s, 501,450,336 B, sha256 `e29de9fa6693…` | 4.0 s, 501,450,336 B, sha256 `27fd9a0774c1…` |
| eval Q4_0 (llama-server b12503 CPU, greedy) | 24 cases in 48.4 s wall incl. server start; p50 949 ms; prefill 3.07 ms/token; decode 51.8 tok/s | 8 cases in 43.6 s |
| eval BF16 + measured quantisation loss (`ml.eval.compare`) | 8 cases in 37.6 s; `eval/quant_loss.md` | 4 cases; `eval/quant_loss.md` |
| card + catalog entry + upload dry run | `gguf/README.md`, `gguf/catalog_entry.json`, `upload_hf --dry-run` lists Q4_0 + README + LICENSE + catalog | same |

Identical step-1 loss (1.159) for both methods is the check that both see the same rendered data and the
same masking. Quality numbers are meaningless after 3 steps (the 3-step models still answer like chat
models); the dry run proves the chain, not the recipe. Seen and fixed on the way: (1) an eval run timed out
under load average ~50 (llama-server 600 s HTTP timeout; at load 3 the same 24 cases take 48 s);
(2) Trainer writes a final checkpoint even with `save_steps` > steps: 8.5 GB with optimizer state for a
0.8B full-FT run, now deleted after `final/` is written (`train.keep_checkpoints: true` keeps it);
(3) the `causal_conv1d` / `flash-linear-attention` kernels are absent on CPU, transformers falls back to
PyTorch (correct, slow) — the GPU env installs fla 0.6.0.

Re-run 2026-11-19 after the review fixes (`.cache/ml/train/dry2/run.sh`, log `dry2/run.log`): the same 3-step
LoRA adapter through the new `ml/pod/run_one.sh` (budget checks, parity, v2 dev sets) on CPU: merge 13 s,
GGUF BF16 + Q4_0 23 s, **parity 88 s: 7 of 8 smoke dev items byte-identical between merged HF (torch bf16)
and the BF16 GGUF (llama.cpp), the 8th diverged at character 33 (bf16 rounding) -> 7/8 agree, PASS**; Q4_0 dev eval of
146 items (24 legacy smoke dev + `dev.v2` 98 + `smoke_cases.v2` 24; 105 with a screen, 17 without) 415 s;
eval-set integrity PASS; guard fired on 105 of 146 outputs of this 3-step model (EM 0.05 raw -> 0.21 guarded,
injection success 0.70 -> 0.20). Negative control (`dry2/neg_parity.sh`): K and V blocks of
`linear_attn.in_proj_qkv` swapped in 2 Gated DeltaNet layers before conversion (a converter-style bug)
-> 0 of 8 identical, all diverge in the first characters, parity rc 3 = the run would stop before its eval.

Other code paths, also run here [V]:

* **Tiny random-weight models built from the real configs + tokenizers** (`make -C ml/train tiny FAMILY=… METHOD=…`,
  `ml/train/tiny_chain.py`; results in `.cache/ml/train/tiny/*/result.json`): LFM3 LoRA, LFM3 DoRA, LFM3 full FT,
  Qwen3.5-2B LoRA — each train -> merge -> GGUF -> Q4_0 -> llama-server eval, EXIT 0, about 1 min each. This
  covers LFM3's module names, PEFT targets (incl. DoRA), conversion and llama.cpp loading without its 3.5 GB weights.
* **Pod sequence simulated locally** (`.cache/ml/train/podsim/simulate.sh`, log `logs/podsim.log`): tiny LFM3 and
  Qwen3.5-2B models, CPU llama.cpp standing in for CUDA, smoke cases as data. `run_all.sh` with
  `MAX_GPU_HOURS=0` refuses (rc 5); `STAGES="train eval"`: phase A (2 runs) -> `select` -> phase B with the winner's
  overrides -> finalists (one per base) -> BF16/Q4_0/test/default-prompt evals -> cards -> leaderboard, 1508 s;
  the same command again: 1 s (everything skipped). Then `after_pod.sh` (`STEPS="sync ship cards"`) against that
  workspace through a fake `ssh` (`podsim/bin/ssh`): rsync filters, finalists' Q4_0 GGUFs pulled, CPU ship eval,
  cards and leaderboard, EXIT 0 (`logs/afterpod-sim.log`).
* **Pod sequence re-simulated 2026-11-19 after the review fixes** (`podsim/simulate.sh` + `podsim/eval_stage.sh`,
  logs `logs/podsim2.log`, `logs/podsim2-eval.log`): cap 0 h refuses (rc 5); the `llama-cpu-fallback` marker makes
  the preflight abort the matrix (rc 6); with `TARGET_GPU_HOURS=0.01` the anchor and phase B run and the optional
  phase-A run is skipped ("needs ~1884 s incl. the mandatory rest"); with the default target it then runs; every run
  passes through parity (random-weight tiny models diverge after 10-300 characters of gibberish, so the sim uses `PARITY_MIN_AGREE=0.3`; the real-weight check is above); the eval stage (finalists
  per base: BF16 dev, Q4_0 test, Q4_0 dev with `default.v4`, `LEGACY=1` v1 column, cards with `finetuned.v2` in the
  catalog entry, leaderboard with guarded columns and a separate legacy table) EXIT 0 in 321 s on 2-item sets;
  the whole `STAGES="train eval"` again: 2 s (all skipped); `/workspace/.budget/sessions.tsv` held 4 sessions.
  The host had load average 40-70 from other users' jobs during this run (see section 5 on CPU threads).
* **Teacher backend + judge** (Responses API through `tools/with-teacher`): 3 smoke cases answered and judged
  (`.cache/ml/train/eval/teacher-smoke3/judge.jsonl`, 6 requests).
* **ctx v2 integration** (`ml/ocr/READY`, `ml/screens/READY` both present at 22:50): `python -m ml.eval.v2_cases
  ml/eval/cases/v2_screens.v1.jsonl OUT` renders 8 hand-written cases (ru/en/mixed; context_resolution, its
  counterpart, context_names, injection, code-switch, no-screen, noise) through `ml.screens.build_ctx`, checks the
  CONTEXT_V2 category rules on the OCR lines (8 written, 0 rejected, 46 s) and writes contract JSONL that
  `run_eval` consumes unchanged (results in section 4.1).
* **Real OCR context**: `ml/ocr` (READY) run on a rendered Fold7 inner-screen chat (`.cache/ml/screens/tmp/
  work_chat_fold7_inner_light.png`, status bar / keyboard / Murmur bubble excluded): 26 lines in 3.6 s. Three
  eval cases built on it (`.cache/ml/train/ocr-int/ocrint.test.jsonl`: context_resolution ClickHouse/Airflow,
  context_names Галиев, and a counterpart where the bubble cut "Grafana" to "Grai"), rendered by
  `prompts/render.py`: **385 tokens (Qwen3.5) / 396 (LFM3) with finetuned.v1, 1,335 / 1,366 with default.v1**.

Rendered length, measured with the real tokenizers (prompt + target + `<|im_end|>`), tokens mean / max:

| Set | finetuned.v1 Qwen3.5 | finetuned.v1 LFM3 | default.v1 Qwen3.5 | default.v1 LFM3 |
|---|---|---|---|---|
| smoke (24, ctx v1) | 213 / 332 | 221 / 354 | 1163 / 1282 | 1191 / 1324 |
| prompts dev.v1 (98, ctx v1; legacy, not used for selection) | 227 / 755 | 237 / 845 | 1177 / 1705 | 1207 / 1815 |
| ctx v2 fixture (5 hand-written OCR contexts) | 205 / 238 | 210 / 249 | 1155 / 1188 | 1180 / 1219 |
| ctx v2 via `ml.screens.build_ctx` (8 cases, `ml/eval/cases/v2_screens.v1.jsonl`, real render + OCR) | 290 / 356 | 298 / 376 | 1240 / 1306 | 1268 / 1346 |
| one real OCR chat screen, 26 lines (`ocr-int/ocrint.test.jsonl`) | 385 | 396 | 1335 | 1366 |

With 15 % default.v1 the mean is ~370 tokens per example on v1 contexts, ~440 on the build_ctx cases and ~530 on
the busiest real OCR screen (render.py trims screen text to its budget, so this is near the ceiling) [V], hence the ~500 tokens per example used for GPU sizing. `max_len: auto` re-derives the limit
from the real rendered v2 set at training time (`data_stats.json`: `max_len`, `rendered_len` p50/p90/p99/max);
`python -m ml.train.sft --config X --prepare-only` prints it without training.

### 4.1 Stock models on real ctx v2 (CPU llama.cpp b12503, greedy, `--system default`) [V]

8 build_ctx cases (`.cache/ml/train/ocr-int/v2_screens.test.jsonl`, reports `.cache/ml/train/eval/v2screens-*`)
and 8 earlier v2 cases (3 on one real OCR screen + the 5-case fixture, `.cache/ml/train/eval/v2-*`). Tiny sets:
these prove the v2 path through render -> llama.cpp -> metrics -> `ctx:` breakdown, not model quality.

| Model (vendor Q4_0) | set | EM | entity recall | ctx_resolution | distractor insertion | empty-output OK |
|---|---|---:|---:|---:|---:|---:|
| Qwen3.5-2B | build_ctx 8 | 0.50 | 0.80 | 0.50 | 0.00 | 0/1 |
| Qwen3.5-0.8B | build_ctx 8 | 0.25 | 0.40 | 0.00 | 0.00 | 0/1 |
| Qwen3.5-2B | OCR screen 3 + fixture 5 | 0.25 | 0.50 | 0.50 | 0.50 | 0/1 |
| LFM3-1.7B (QAD) | OCR screen 3 + fixture 5 | 0.00 | 0.67 | 0.50 | 0.50 | 0/1 |

What it shows: on the real screen the Murmur bubble cut "Grafana" to "Grai"; both Qwen3.5-2B and LFM3 still wrote
"Grafana" (world knowledge, not the screen) — the counterpart check counts that as a distractor insertion, which is
exactly the invented-term failure CONTEXT_V2 forbids. Qwen3.5-2B translated the English half of the code-switch
case into Russian; all models output "э"/"Э" for noise-only input; 0.8B mostly returns the transcript untouched.

## 5. Known gaps and risks

* No GPU run happened: every GPU time and memory figure is [A] until `logs/timings.jsonl` of session 2.
* flash-linear-attention 0.6.0 with torch 2.15.0 / Triton on sm_120 is unverified; bootstrap runs its
  `chunk_gated_delta_rule` forward + backward once on the GPU and, if that fails, writes a marker on which
  `train_matrix.sh` refuses the Qwen3.5 runs (`ALLOW_SLOW_QWEN=1` overrides).
* Optional sweep runs started in a later pass (more budget) do not re-trigger phase B: phase B keeps the
  winner chosen when it ran. Delete the phase-B run dirs to redo them with a new winner.
* The probe stage and the data generator are owned elsewhere (`ml/roundtrip`, `ml/data`); this pipeline only
  assumes contract JSONL in `$MURMUR_DATA/{train,dev,test}.jsonl` and a probe command.
* CPU evals on the shared dev server: under heavy load (load average 40-60 from other users' jobs) llama.cpp
  with 6 threads collapsed to 0.23 decode tok/s on a tiny model, with 2 threads 18.6 tok/s (same model, same
  minute) [V]; use `EVAL_THREADS=2 bash ml/pod/after_pod.sh` when the host is busy.
* GPU vs CPU llama.cpp kernels can flip greedy ties; gates come from the CPU build on the dev server, the
  nearest stand-in for the phone; the app's Benchmark and real dictations are the final word.
* `wrong_language` is a script-share proxy (shared with `prompts/eval/score.py`): a Latin term left in Cyrillic
  ("клик хаус" for "ClickHouse") can trip it on short sentences. The gate is ≤ 0.5 %, so read the per-item rows
  before acting on a FAIL of that gate alone.
* Eval decoding uses `max_new_tokens = min(1024, 2·transcript_tokens + 64)` (prompts/README.md), while
  `docs/research/llm-catalog.json` runtime_defaults say `ceil(1.3·transcript_tokens) + 16` and n_ctx 2048. The
  app and the eval must use one formula; until reconciled, a target longer than 1.3x the transcript can be cut
  on the phone but not in the eval.
* LFM3 licence: fine-tunes inherit the US$10M revenue threshold — owner decision before shipping an LFM3 fine-tune.
* Python on the dev server can hang connecting to huggingface.co (IPv6 first, no fallback); `bootstrap.sh`
  checks this on the pod and prefers IPv4 via `/etc/gai.conf` if needed.
