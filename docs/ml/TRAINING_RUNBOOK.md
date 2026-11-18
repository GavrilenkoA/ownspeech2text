# Murmur — training, export and evaluation runbook (PLAN_V2)

Date: 2026-11-18. Binding plan: `docs/ml/PLAN_V2.md` (1–5K synthetic examples, teacher on the dev
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
   runs only with `FULL=1`, i.e. when LoRA misses the gates.
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
| 9 | Bootstrap re-check + base weights, phase A (LFM3 sweep) → select → phase B (Qwen 0.8B, 2B), each run trained, merged, exported, Q4_0 dev eval | pod | `tmux new -s murmur "STAGES='bootstrap train eval' bash ml/pod/run_all.sh"` | 75 min [A] |
| 10 | Finalists (best run per base): BF16 dev eval (quant loss), Q4_0 test eval, default-prompt eval, cards, leaderboard | pod | stage `eval` of the same command | 22 min [A] |
| 11 | Pull results + finalist GGUFs; **stop the pod** | dev server | `POD=… bash ml/pod/after_pod.sh` (does the sync first) | 4 min [A] |
| 12 | CPU ship-gate eval, judge (teacher), rescore, stock baselines, cards, leaderboard | dev server | same `after_pod.sh` (no GPU) | ~1–3 h CPU [A] |
| 13 | Owner picks; upload | dev server | `tools/with-teacher python -m ml.train.upload_hf --run … --repo OWNER/…` (`HF_TOKEN` from env) | minutes |
| 14 | On-device: Benchmark button + real dictations | Fold7 | app | owner |

If LoRA misses the gates: start the pod once more, `FULL=1 PHASE_A="" PHASE_B="" STAGES="train eval" bash ml/pod/run_all.sh`
(+25 min [A]). `run_all.sh` refuses to start a stage once the measured stage time reaches `MAX_GPU_HOURS`
(default 5). Every step skips finished work (markers, `train_info.json`, `manifest.json`, `report.json`);
training resumes from its newest checkpoint, so after a stop or crash the same command continues.

## 2. Run matrix (one GPU, sequential)

Same data, same prompt mix (finetuned.v1; 15 % default.v1; 15 % random preferences block), max_len auto
(longest rendered example rounded up to 64, cap 4096),
batch 16 (≈190 steps per epoch at 3K examples), cosine, warmup 5 %, β2 0.95, alpha = 2r, dropout 0.05,
all linear layers except `lm_head`. Selection on the **Q4_0 GGUF dev eval** (the shipping artifact):
fewest failed gates, then EM, N-WER, no-op exactness, fewer trainable parameters (`ml/train/select.py`).

| Phase | Run (config) | Base | Method | r | LR | Epochs | Question | GPU min [A] |
|---|---|---|---|---:|---:|---:|---|---:|
| A | `lfm3-r16` | LFM3-1.7B | LoRA | 16 | 2e-4 | 3 | anchor = Liquid's recipe | 11 |
| A | `lfm3-r64` | LFM3-1.7B | LoRA | 64 | 2e-4 | 3 | capacity | 11 |
| A | `lfm3-r16-lr5e-4` | LFM3-1.7B | LoRA | 16 | 5e-4 | 3 | under-training at ~570 steps? | 11 |
| A | `lfm3-r16-e5` | LFM3-1.7B | LoRA | 16 | 2e-4 | 5 | more passes over a small set | 16 |
| A opt. | `lfm3-dora-r16` (`DORA=1`) | LFM3-1.7B | DoRA | 16 | 2e-4 | 3 | DoRA vs LoRA | 15 |
| B | `q08-lora` + winner's r/α/lr/epochs | Qwen3.5-0.8B | LoRA | ← A | ← A | ← A | speed option | 7 |
| B | `q2-lora` + winner's r/α/lr/epochs | Qwen3.5-2B | LoRA | ← A | ← A | ← A | quality option | 13 |
| fallback | `full-fallback` (`FULL=1`) | LFM3-1.7B (embeddings frozen) | full FT | — | 1e-5 | 3 | does full FT fix what LoRA misses? | 25 |

Per-run minutes = train + merge + GGUF + Q4_0 dev eval, assuming 3K examples × ~500 tokens and ~10K
training tokens/s on the 5090 [A]; `ml/pod/run_one.sh` records every stage in `logs/timings.jsonl`, which
replaces these numbers after session 2. Data-limited check (PLAN_V2 "scale only if eval says so"): if
`lfm3-r16-e5` beats `lfm3-r16` while dev loss still falls, the set is too small for the task → generate more
(up to 5K) rather than add epochs.

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
`ctx:no_screen`, `ctx:legacy` = v1 structured context). In `context_resolution`, `context_names`, `near_miss`
an entity only counts as kept when it is also spelled in `ctx.screen.lines` (`resolved`,
`ctx_resolution_rate`), so restoring a term the screen does not show is never rewarded; items whose gold
term is missing from the OCR lines are counted (`ctx_unsupported_items`) and fail the "Eval-set integrity"
gate; a `context_counterpart` output that contains the unsupported term counts as a distractor insertion.
Ship gates (`ml/eval/gates.py` = DATA_AND_TRAINING_PLAN 4.3 + empty output >= 90 % + eval-set integrity),
overall and per language, PASS / FAIL / NO_DATA; a non-GGUF report is flagged "not a ship measurement". The ship
measurement is the finalist's Q4_0 file on the CPU llama.cpp build (`after_pod.sh`, `Q4_0-test-cpu`).

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
* **Teacher backend + judge** (Responses API through `tools/with-teacher`): 3 smoke cases answered and judged
  (`.cache/ml/train/eval/teacher-smoke3/judge.jsonl`, 6 requests).
* **Real OCR context**: `ml/ocr` (READY) run on a rendered Fold7 inner-screen chat (`.cache/ml/screens/tmp/
  work_chat_fold7_inner_light.png`, status bar / keyboard / Murmur bubble excluded): 26 lines in 3.6 s. Three
  eval cases built on it (`.cache/ml/train/ocr-int/ocrint.test.jsonl`: context_resolution ClickHouse/Airflow,
  context_names Галиев, and a counterpart where the bubble cut "Grafana" to "Grai"), rendered by
  `prompts/render.py`: **385 tokens (Qwen3.5) / 396 (LFM3) with finetuned.v1, 1,335 / 1,366 with default.v1**.

Rendered length, measured with the real tokenizers (prompt + target + `<|im_end|>`), tokens mean / max:

| Set | finetuned.v1 Qwen3.5 | finetuned.v1 LFM3 | default.v1 Qwen3.5 | default.v1 LFM3 |
|---|---|---|---|---|
| smoke (24, ctx v1) | 213 / 332 | 221 / 354 | 1163 / 1282 | 1191 / 1324 |
| prompts dev.v1 (98, ctx v1) | 227 / 755 | 237 / 845 | 1177 / 1705 | 1207 / 1815 |
| ctx v2 fixture (5 hand-written OCR contexts) | 205 / 238 | 210 / 249 | 1155 / 1188 | 1180 / 1219 |

With 15 % default.v1 the mean is ~370 tokens per example on v1 contexts and ~530 on the real OCR screen above
(0.85 x 390 + 0.15 x 1,350) [V], hence the ~500 tokens per example used for GPU sizing. `max_len: auto` re-derives the limit
from the real rendered v2 set at training time (`data_stats.json`: `max_len`, `rendered_len` p50/p90/p99/max);
`python -m ml.train.sft --config X --prepare-only` prints it without training.

## 5. Known gaps and risks

* No GPU run happened: every GPU time and memory figure is [A] until `logs/timings.jsonl` of session 2.
* flash-linear-attention 0.6.0 with torch 2.15.0 / Triton on sm_120 is unverified; without it Qwen3.5 trains
  on the PyTorch fallback (correct, slower: on CPU it dominated the step time). Bootstrap prints a warning.
* The probe stage and the data generator are owned elsewhere (`ml/roundtrip`, `ml/data`); this pipeline only
  assumes contract JSONL in `$MURMUR_DATA/{train,dev,test}.jsonl` and a probe command.
* GPU vs CPU llama.cpp kernels can flip greedy ties; gates come from the CPU build on the dev server, the
  nearest stand-in for the phone; the app's Benchmark and real dictations are the final word.
* LFM3 licence: fine-tunes inherit the US$10M revenue threshold — owner decision before shipping an LFM3 fine-tune.
* Python on the dev server can hang connecting to huggingface.co (IPv6 first, no fallback); `bootstrap.sh`
  checks this on the pod and prefers IPv4 via `/etc/gai.conf` if needed.
