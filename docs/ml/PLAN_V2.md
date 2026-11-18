# ML plan v2 — owner-directed (2026-11-18)

Supersedes the data-volume, teacher and GPU-budget parts of `DATA_AND_TRAINING_PLAN.md`.
Everything else there (categories, context rendering, eval gates) still applies.

Nothing here trains an ASR or TTS model. They run in inference only, as a source of realistic
recognition errors for the cleanup LLM's training inputs.

## Method

**A. Round-trip probe (small, real).** 1–2K sentences dense with hard IT/AI vocabulary
(Russian, English, mixed) → several TTS systems and voices → every ASR model the hub ships.
Diversity of TTS and ASR is the point: we want each recogniser's real output style and its real
manglings of terms. Tooling lives in `ml/roundtrip/`. The run starts with a 200-utterance
throughput measurement so pod time is sized from a number, not a guess.

**B. Error mining.** From the probe pairs: a style profile per ASR model (casing, punctuation,
digits or words), word confusion tables, and a catalogue of term manglings
(`Kubernetes` → `кубернетис`, `ClickHouse` → `клик хаус`).

**C. Fully synthetic set, 1–5K examples.** A teacher writes scenario + screen content + intended
text; speech noise is applied from the mined tables. **The context of an example is what OCR sees**
(owner, 2026-11-18): the screen content is rendered as a phone screenshot, the app's own OCR models
and post-processing run on it, and that output plus the app name is the context — see
`CONTEXT_V2.md`. Start at ~3K, scale only if eval says the model is data-limited. Must include:

- different contexts (messenger, mail, work chat, notes, terminal, browser; with and without screen text);
- **context-resolution cases**: a hard word the ASR garbles, recoverable only from the context;
- their **counterparts**: the same garbled word with no supporting context, where the right answer
  is to leave the transcript's word alone (otherwise the model learns to invent terms);
- near-miss distractors in context, no-op inputs, spoken commands, numbers and dates,
  noise-only input, dictated questions and on-screen injection text.

**D. LoRA experiments.** LFM3-1.7B first, then Qwen3.5 0.8B and 2B. Gates: the dev/test sets and
the 24-case smoke in `prompts/eval/`. Full fine-tuning only if LoRA misses the gates.

## Teacher

Hermes' local codex model `gpt-6.2-sol` through an OpenAI-compatible endpoint. Scripts read
`MURMUR_TEACHER_BASE_URL`, `MURMUR_TEACHER_API_KEY`, `MURMUR_TEACHER_MODEL` from the environment;
no key is ever written to the repo or printed.

Verified 2026-11-18 (owner approved use of the local proxy and of the HF token):

- Endpoint `http://127.0.0.1:18899/v1`, **Responses API only** — `POST /v1/chat/completions` is 404.
- Working request shape: `{"model", "instructions": <system text>, "input": [{"type": "message",
  "role": "user", "content": [{"type": "input_text", "text": ...}]}], "store": false}`; streaming
  optional. The answer is in `output[].content[].text` of the item with `type: "message"`.
  A bare string `input` with `max_output_tokens` and no `instructions` is rejected (400).
- `MURMUR_TEACHER_API=responses` selects this shape; `tools/with-teacher <cmd>` exports all teacher
  variables and `HF_TOKEN` for one command without printing them.
- No concurrency cap at the broker, but upstream quotas are shared with other users: start with one
  worker, raise slowly, honor `Retry-After` on 429. Reasoning effort defaults to low; keep it there
  for bulk generation.
- Credentials stay on this server: nothing is copied to the GPU pod except the HF token as a pod
  secret when a model upload needs it.

## GPU budget

One RTX 5090. Target 2–3 GPU-hours total (probe ~15 min, LoRA runs minutes each, plus pod setup
and model downloads). Hard cap 5 hours without asking the owner. Stop the pod between stages.
