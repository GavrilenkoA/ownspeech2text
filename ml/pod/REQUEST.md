# GPU pod request (for the owner's Hermes agent)

Binding plan: `docs/ml/PLAN_V2.md`. Sequence and commands: `docs/ml/TRAINING_RUNBOOK.md`.
Legend: **[M]** measured (source given), **[A]** assumed, to be re-measured from
`/workspace/logs/timings.jsonl` after the first session.

## The request (copy as is)

> Please start **one RunPod pod with 1x RTX 5090 (32 GB)**, community cloud (about 0.69 USD/h), for
> Murmur model training. Target 2-3 GPU-hours in total, hard cap 5 GPU-hours. The pod is used in **two
> sessions** and must be **stopped (not terminated)** between them and after the second one; the
> volume must survive until the orchestrator says the results are pulled.
>
> * GPU: 1x RTX 5090 32 GB. The host driver must be **>= 580 (CUDA 13.x)**: our torch 2.15.0 exists only
>   as CUDA 13.2 wheels. In the RunPod filter: CUDA version 13.0 or newer.
> * Image: `runpod/pytorch` (any current Ubuntu 22.04/24.04 tag) or `nvidia/cuda:13.0.x-runtime-ubuntu24.04`;
>   we install our own Python environment onto the volume, so the image's torch version does not matter.
> * Container disk: **30 GB** (wiped on stop; holds only apt packages and temp files).
> * Volume: **120 GB at /workspace** (persists across stops).
> * Ports: **SSH only** (TCP 22, exposed). No HTTP ports; nothing on the pod listens publicly.
> * SSH: add the public key in **`/home/private/projects/murmur/.cache/ml/train/pod-ssh/murmur_pod_ed25519.pub`**
>   on the dev server (a dedicated key, created 2026-11-18, fingerprint comment `murmur-pod`;
>   `ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAICToVEta4rbX5bWcYjWxRnT3KXilXBT4E3442wEaoTsH murmur-pod`),
>   and return `user@host` and the SSH port.
> * Secrets: **HF_TOKEN** (write access to the owner's account) only for the final model upload, as a
>   pod secret/env var, and only in session 2. No other credentials on the pod. No teacher or LLM API
>   keys on the pod.
> * Session 1 (about 40 min): start, give us SSH, we run bootstrap + the round-trip probe, then stop.
> * Session 2 (about 2 h, a day later at most): start the same pod, we push the data, train the
>   run matrix, export and evaluate, pull the results, then stop.
> * Please report the actual GPU-hours and cost at each stop.

## Sizing

| Item | Size | Source |
|---|---:|---|
| Base weights `LiquidAI/LFM3-1.7B` + `Qwen/Qwen3.5-0.8B` + `Qwen/Qwen3.5-2B` | 3.48 + 1.77 + 4.57 = 9.8 GB | [M] HF API `?blobs=true`, 2026-11-18 |
| Training venv (torch 2.15.0 CUDA 13.2 + transformers/peft + fla) | ~8 GB | [A] CPU venv is 1.1 GB [M]; CUDA wheels are larger |
| llama.cpp b12503 source + CPU build + CUDA 12.8 build + cudart | ~1.5 GB | [A] CPU build 58 MB, source 196 MB [M] |
| Per LoRA run: adapter + 1 checkpoint, BF16 GGUF, Q4_0 GGUF (merged HF copy deleted) | LFM3 ~4.6 GB, Q2B ~5.2 GB, Q0.8B ~2.1 GB | [A] from model sizes; vendor Q4_0: LFM3 1.05 GB, Q2B 1.21 GB, Q0.8B 0.51 GB [M] |
| Run matrix: 4 LFM3 + Q0.8B + Q2B (+ DoRA, + full-FT fallback ~8 GB) | ~26 GB (~40 GB with both options) | [A] |
| Round-trip probe (TTS + ASR models, audio) | ~30 GB | [A] owned by `ml/roundtrip`, to be confirmed by that team |
| Headroom | ~30 GB | |
| **Volume total** | **120 GB** | |

## Time budget (sums to <= 3 h)

Throughput assumption, made explicit: LoRA on LFM3-1.7B with gradient checkpointing costs about
6N FLOP per token (forward 2N + recompute 2N + activation gradients 2N; frozen base weights need no
weight gradients) = 1.0e10 FLOP/token. The 5090 does ~105 dense BF16 TFLOP/s with FP32 accumulation
(PyTorch default); small-batch padded SFT typically reaches 20-35 % MFU, so we plan with **30 % =
~3.1K tokens/s** [A] (the previous plan's 10K tokens/s would need ~95 % MFU). Tokens per 3-epoch run:
3K examples x ~395 tokens (measured mean on the proof_v6 synthetic set with the 5 % default.v4s mix [M])
x 3 = 3.6M -> ~19 min of training. The anchor run prints its measured tokens/s; every later run is
projected from it and only started if it fits (below).

| Session | Stage | Minutes | Basis |
|---|---|---:|---|
| 1 | pod start + SSH ready + `push_code.sh` | 4 | [A] |
| 1 | `bootstrap.sh` first run without base weights (uv, Python, llama.cpp downloads, ~3.5 GB of wheels, fla kernel test, self-test) | 8 | [A]; self-test 21 s on the dev server [M] |
| 1 | round-trip probe incl. its own setup (owned by `ml/roundtrip`) | 25 | [A] PLAN_V2 says 15-30 min |
| 1 | pull probe outputs, stop | 3 | [A] |
| 2 | start + push code and data | 4 | [A] |
| 2 | `bootstrap.sh` re-check after the container reset + 9.8 GB base weights | 6 | [A] |
| 2 | anchor `lfm3-r16`: train ~19 + merge 1 + GGUF/Q4_0 2 + HF-vs-GGUF parity 2 + Q4_0 dev eval (~420 items) 3 | 27 | [A]; merge 13 s, export 23 s, parity see runbook 4 on CPU [M] |
| 2 | phase A extras (`lfm3-r64`, `lfm3-r16-lr5e-4`, `lfm3-r16-e5`): **optional**, each starts only if its projection + all mandatory work still ahead fits the 3 h target; at 3.1K tokens/s none fits, at >= 6K tokens/s one or two do | 0 | [A] decided on the pod from the anchor's measured time |
| 2 | phase B: `q08-lora` (~0.6x anchor) + `q2-lora` (~1.3x anchor) with the phase-A winner | 51 | [A] cost factors in `train_matrix.sh` |
| 2 | finalists (one per base): BF16 GGUF dev eval (quantisation loss), Q4_0 test eval, Q4_0 dev eval with the stock default.v4 prompt, cards | 30 | [A] ~0.3 s per example on llama.cpp CUDA |
| 2 | pull results + finalist GGUFs, stop | 4 | [A] |
| | **Total** | **162 (2 h 42 min)** | 2 h 42 min x 0.69 USD/h = **~1.86 USD** |
| optional | full fine-tuning fallback (`FULL=1`, only if LoRA misses the gates; same budget rule as phase A extras) | +68 | [A] 2.5x anchor |
| optional | DoRA run in phase A (`DORA=1`) | +35 | [A] 1.3x anchor |

Worst case we plan for: training at 15 % MFU (half the assumed speed) makes session 2 ~3 h and the total
~3 h 40 min, still under the 5 h cap; the optional runs are then skipped automatically.

Hard stop, enforced where the time is spent (`ml/pod/env.sh budget_check`): billed time = for every session,
now minus the container start (elapsed time of PID 1, so pod setup and idle time count), plus 5 min per
session for provisioning, persisted on the volume (`/workspace/.budget/sessions.tsv`). It is checked before
every stage, every run of the matrix (with the run's projected duration), every step of a run (train,
export, parity, eval) and every finalist eval; nothing starts that would end past `MAX_GPU_HOURS` (default
5), optional runs must fit `TARGET_GPU_HOURS` (default 3) together with the mandatory rest. A step that is
already running is not killed (a half-trained run is worth less than the minutes it would save). Hermes'
billing number stays the authoritative one.

Preflight before the matrix (`train_matrix.sh`): it **aborts** if flash-linear-attention failed bootstrap's
CUDA kernel test while Qwen3.5 runs are planned (the PyTorch fallback would multiply Qwen step times), or if
the llama.cpp CUDA build does not see the GPU (CPU evals: ~3-6 s per item measured on the dev server's CPU ->
~25-40 min per 420-item eval, ~10 evals per session = the whole budget). `ALLOW_SLOW_QWEN=1` /
`ALLOW_CPU_EVAL=1` override after a conscious decision (e.g. with `PHASE_B=""` or a smaller `EVAL_DEV`).

If the llama.cpp CUDA 12.8 release build does not see the 5090, `bootstrap.sh` records it (marker
`llama-cpu-fallback`) and the preflight above stops the matrix before any training starts.

Sequence length (measured on the dev server with the real tokenizers, proof_v6 synthetic ctx-v2 train set,
74 examples): prompt + target with `finetuned.v2` 301 (Qwen3.5) / 315 (LFM3) tokens mean, 510 / 548 max; with the
`default.v4s` profile (stock prompt + 8 OCR shots) 2,186 / 2,254 mean; the 5 % mix gives ~395 tokens per example
[M]. `max_len: auto` resolves to ~2.5K on such data (the longest default.v4s example), length-grouped batches keep
the padding small.

What is measured today (dev server CPU, no GPU here): the whole chain runs end to end on
Qwen3.5-0.8B real weights (LoRA and full fine-tuning: train -> merge -> GGUF -> Q4_0 -> llama.cpp eval);
timings in `docs/ml/TRAINING_RUNBOOK.md` section 4. No GPU number in this file is measured yet.
