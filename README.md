# Murmur

On-device voice dictation for Android, built for the Galaxy Z Fold7.

Tap a floating bubble, speak, tap again. Murmur transcribes the speech, cleans it up
(punctuation, filler words, self-corrections, the tone of the app you are in, words it reads
from the screen) and types the result into the field you were in. Nothing leaves the phone:
no network traffic, no audio or dictated text in the system log.

## Pipeline

1. **Audio** — the bubble records, silence detection cuts segments in pauses.
2. **Speech** — a local ASR engine decodes each closed segment (arm64 CPU or NPU).
3. **Context** — the accessibility tree of the target app, plus OCR of the screen when the
   tree is thin, assembled under a privacy level (full / app only / terms only / none).
4. **Cleanup** — a small language model rewrites the transcript with the context, following a
   prompt profile (default.v1, default.v4, finetuned.v1).
5. **Insertion** — text into the focused field through the accessibility service, with a
   clipboard fallback when the app refuses.

Each stage runs in its own process and unit, with a memory and time budget; a failed unit falls
back instead of blocking the dictation.

## Features

- Floating bubble over any app, fold-aware layouts for cover and inner screens
- History with audio playback and Retry, Dashboard with dictation and latency numbers
- Model hub: download, benchmark, and choose speech, cleanup, VAD and OCR models
- Settings: prompt editor, tone per app, privacy level, debug diagnostics
- Onboarding that walks through overlay, accessibility and battery permissions

## Models

Everything runs locally and models are downloaded on first use from the catalog
(`core/catalog/src/main/resources/catalog/catalog.json`).

- **Speech** — Parakeet TDT 0.6B, GigaAM v3, Whisper, Nemotron 3.5, T-one, Qwen3-ASR
- **Cleanup** — Qwen3.5 0.8B / 2B, Qwen3 0.6B / 1.7B, LFM3 1.7B, LFM2.5 350M / 1.2B / 2.6B
- **Vision** — PP-OCRv6 detector, PP-OCRv5 Cyrillic recognizer; Silero VAD

Fine-tuned cleanup models live at **[huggingface.co/satoshiNakomoroReal/murmur-cleanup](https://huggingface.co/satoshiNakomoroReal/murmur-cleanup)**.
Training data and pipeline are in `ml/`; no weights are published yet.

## Status

Builds and passes 1,056 JVM tests; verified end to end on an Android emulator with the real
speech and cleanup models on CPU (1.0–1.4 s from end of speech to inserted text). Not yet
verified on the phone: NPU paths, the real microphone, One UI behaviour, real apps, battery.

`docs/STATUS.md` has the full picture, `docs/DEVICE_CHECKLIST.md` the on-device steps,
`docs/DECISIONS.md` the 93 architecture decision records.

## Build

Requirements: JDK 21, Android SDK 37.2 (`compileSdk`), build-tools 37.0.0.

```bash
export ANDROID_HOME=$HOME/.local/share/android-sdk
./gradlew assembleDebug testDebugUnitTest
```

Prebuilt native libraries (llama.cpp, sherpa-onnx, QNN probe) are produced by the scripts in
`native/` and are not committed; without them the Gradle build still succeeds and the app
reports the engine runtime as missing. Model weights are never committed.

The release build excludes Qualcomm NPU libraries; device builds can opt in with
`-Pmurmur.qnn=true -Pmurmur.htp=true` under the licences named in `docs/DECISIONS.md`.

## Docs

`docs/BRIEF.md` (product and scope), `docs/ARCHITECTURE.md`, `docs/DESIGN.md` in
`docs/design/`, `docs/ml/` (data card, training runbook, prompt evaluation).

## Privacy

Audio, transcripts, screen context and history stay in app-private storage. The app has no
analytics and no server component; `core/benchmark` and the tools under `tools/` are the only
things that write files, and only locally.

## License

No license has been chosen yet, so all rights are reserved for now. Third-party models
downloaded by the app keep their own licenses, listed per entry in the catalog.
