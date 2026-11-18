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
> * Session 2 (about 1 h 45 min, a day later at most): start the same pod, we push the data, train the
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

| Session | Stage | Minutes | Basis |
|---|---|---:|---|
| 1 | pod start + SSH ready + `push_code.sh` | 4 | [A] |
| 1 | `bootstrap.sh` first run without base weights (uv, Python, llama.cpp downloads, ~3.5 GB of wheels, self-test) | 8 | [A]; self-test 8 s on the dev server [M] |
| 1 | round-trip probe incl. its own setup (owned by `ml/roundtrip`) | 25 | [A] PLAN_V2 says 15-30 min |
| 1 | pull probe outputs, stop | 3 | [A] |
| 2 | start + push code and data | 4 | [A] |
| 2 | `bootstrap.sh` re-check after the container reset + 9.8 GB base weights | 6 | [A] |
| 2 | phase A: 4 LFM3-1.7B LoRA runs (3K examples x ~500 tokens x 3 epochs = 4.5M tokens per run at an assumed 10K tokens/s; the 5-epoch run 1.7x), each + merge + GGUF + Q4_0 dev eval | 49 | [A] |
| 2 | phase B: Qwen3.5-0.8B and 2B LoRA with the phase-A winner, each + export + dev eval | 20 | [A] |
| 2 | finalists: BF16 GGUF dev eval (quantisation loss), Q4_0 test eval, default-prompt eval, cards | 22 | [A] ~0.3 s per example on llama.cpp CUDA |
| 2 | pull results + finalist GGUFs, stop | 4 | [A] |
| | **Total** | **145 (2 h 25 min)** | 2 h 25 min x 0.69 USD/h = **~1.70 USD** |
| optional | full fine-tuning fallback (only if LoRA misses the gates) | +25 | [A] |
| optional | DoRA run in phase A | +15 | [A] |

Hard stop: `ml/pod/run_all.sh` sums the stage times it measured and refuses to start a stage once
`MAX_GPU_HOURS` (default 5) is used. Pod idle time between our commands is not in that sum; Hermes'
billing number is the authoritative one.

If the llama.cpp CUDA 12.8 release build does not see the 5090, `bootstrap.sh` records it and every pod
eval falls back to the CPU build (slower, same numbers); the training stages do not depend on it.

Sequence length (measured on the dev server with the real tokenizers): ~210-240 tokens per example with the
short fine-tune prompt, ~1,180 with the long default prompt (15 % of examples); mean ~370 + OCR screen text
-> the ~500 tokens per example used above; a real OCR chat screen (26 OCR lines) renders to ~390 tokens with the
short prompt, ~530 with the 15 % long-prompt mix [M].

What is measured today (dev server CPU, no GPU here): the whole chain runs end to end on
Qwen3.5-0.8B real weights (LoRA and full fine-tuning: train -> merge -> GGUF -> Q4_0 -> llama.cpp eval);
timings in `docs/ml/TRAINING_RUNBOOK.md` section 4. No GPU number in this file is measured yet.
