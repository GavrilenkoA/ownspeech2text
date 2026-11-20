# Murmur (working name) — product brief

On-device Android dictation tool, an analog of Wispr Flow. Everything below is the owner's
requirement unless marked `(orchestrator note)`.

## Product

1. **Local models only**: Whisper-like ASR + a small LLM for error fixing and context-aware rewriting.
2. **Target device**: Samsung Galaxy Z Fold7, 12 GB RAM. Support other devices only if it is easy; otherwise skip.
3. **UX**: clean, bright, minimalistic. No sloppy / generic-looking interfaces. Production quality.
4. **Floating bubble** (like Wispr Flow) to start/stop dictation from any app.
5. **Use the Samsung NPU for inference** — for all models (ASR, LLM, OCR).

## Screens — 4 bottom tabs

1. **Dashboard** — useful usage stats, like Wispr Flow has.
2. **History** — the main tab. Dictation history, auto-deleted after 1 week.
3. **Model hub** — the user configures any model here.
4. **Settings** — retention, UI features, post-processing prompts. The default prompt must be very
   good without editing, but the user must be able to improve it.

## Model hub

Audio (ASR) models:
- Whisper, different sizes
- Parakeet (owner wrote "parquet")
- GigaAM ("giga audio")
- T-one
- Qwen3-ASR (https://huggingface.co/Qwen/Qwen3-ASR-0.6B)
- each model shows parameter count + supported languages

LLM post-processing models:
- LFM 2.5 series
- LFM 3 series
- small Qwen 3.5–3.6
- fine-tuned variants: train LFM 3 + Qwen 3.5 (0.8–2B) for this task (LoRA or similar)
- "Q4" (4-bit) inference
- or **None** (no post-processing)

Rules:
- Models are NOT baked into the APK — downloaded from Hugging Face on demand.
- NPU inference for all models.
- Every model has a **Benchmark** button: runs a short benchmark on the phone and shows short stats (tokens/s etc.).
- Models must not hold RAM 24/7 — loaded on demand.

## Processing flow

1. User taps the bubble → dictation starts.
2. 0.5–1 s after start: take a screenshot through the Accessibility API → turn it into text with a
   small multilingual OCR model (no modern LLM needed, a small classic OCR).
3. Also read context from the Accessibility API — what the current app is.
4. Start loading the audio model into RAM.
5. Start loading the LLM into RAM.
6. Process the audio. If the model supports streaming, or the audio can be split into parts without
   losing information, transcribe before the user finishes; otherwise transcribe when the user taps
   the bubble again. (Open question — owner wants to discuss the approach.)
7. When dictation is finished and the full text is ready: SYSTEM REPHRASE PROMPT + context + text → LLM.
   If a valid input field is focused, insert the result through Accessibility **and** copy it to the clipboard.
8. Save output + audio to history.

## Engineering constraints

- (orchestrator note) Likely languages of the owner: Russian + English, incl. code-switching
  (GigaAM and T-one are Russian-first models). Treat ru + en as first-class.
- (orchestrator note) No physical Fold7 is attached to the build server. `adb devices` is empty.
  Anything that can only be validated on the device must be flagged explicitly.
- (orchestrator note) Build server: x86_64 Linux, 16 cores, 121 GB RAM, no GPU, ~85 GB free disk,
  JDK 21, Android SDK at `$ANDROID_HOME`. The server is shared with other
  users and running services — do not disturb anything outside this project.
- A Hermes agent lives on the same server. It holds the owner's Hugging Face Pro token and can start
  RunPod GPU pods (H100 / H200 / 5090). Requests to Hermes go through the orchestrator only.
- Suggested datasets for the cleanup fine-tune (owner is not sure about them):
  - juanquivilla/sotto-transcript-cleanup — 135K English pairs
  - shantanugoel/aawaaz-transcript-cleanup-dataset — 22.8K English pairs
  - GenSEC-LLM/SLT-Task1-Post-ASR-Text-Correction — 241K examples
  - kul-speech-lab/contextual-reasoning-speechllm — ASR correction using title/description/tags
  - gpb11/russian-asr-correction — 1.1K Russian pairs

## Repo layout (this phase)

- `docs/research/` — research reports
- `docs/ml/` — context, prompt, data and training design
- `docs/design/` — design system, prototype, screenshots
- `docs/dev/` — toolchain notes
- `prompts/` — post-processing prompt files
- repo root — Gradle project (owned by the toolchain agent in this phase)
