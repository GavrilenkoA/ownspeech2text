# WP-06 ASR engine: sherpa-onnx, Silero VAD, segmenter (`:infer:asr`, `native/sherpa`)

Wave 1. Read first: `docs/wp/README.md`; `docs/research/verify/asr-ocr.md` (all — decisions A1-A6, the build, the
segmentation measurements); `docs/research/asr-models.md` §6-7 (session interface background); ARCHITECTURE.md §4,
§5.2 (Listening/Finishing rows), §6, §7; DECISIONS ADR-005, ADR-006, ADR-008, ADR-014, ADR-024, ADR-025;
`core/catalog/src/main/resources/catalog/catalog.json` (ASR + VAD entries, `layout`, `maxWindowSec`).

## Goal

The `:asr` process transcribes during dictation (D4): native streaming for streaming models, VAD pause-cut
segments decoded as they close for batch models, the open tail at stop — on the NPU through sherpa's QNN path on
SM8750 and on the CPU everywhere (x86_64 emulator included), behind `AsrSession`.

## Owns

`infer/asr/**` except `infer/asr/src/*/kotlin/app/murmur/infer/asr/ocr/**` (WP-07); `native/sherpa/**`;
`core/audio/src/{main,test}/kotlin/app/murmur/core/audio/Segmenter*.kt`; `tools/device/asr_npu_smoke.sh`.

## Contracts

Implement the `IAsrService` binder (`AsrService`): `probe`, `memory`, `loadAsr`, `startSession` (+ `IAsrSession`),
`unloadAll`; WP-07 implements `loadOcr`/`recognizeScreen` in the same service (agree on a small `OcrHandler` seam in
`AsrService` on day 1). Implement `Segmenter` (pure, `core/audio`) and `VadModel` (`SileroVad`). Runtime keys
(`RuntimeKeys`): `sherpa/offline-transducer`, `sherpa/offline-ctc`, `sherpa/offline-whisper`,
`sherpa/offline-qwen3-asr`, `sherpa/online-transducer`, `sherpa/online-t-one`, `ort/silero-vad-v5`.
Unit → provider: NPU = `qnn` (context binaries from `layout`), CPU = `cpu`.

## Deliver

1. Native build: `native/sherpa/build-android.sh` (from the spike) producing `libsherpa-onnx-jni.so` for arm64-v8a
   (QNN on, public qnn-include 2.40 headers) and x86_64, linked against `onnxruntime-android-1.32.0.aar`'s
   `libonnxruntime.so`, installed into `<murmur.nativeLibsDir>/sherpa/<abi>/` (default `.cache/native/jniLibs`).
   Pin sherpa to v2.0.1 (`af2d8d81`) with sha256 of the source archive. Record build times; heavy steps via
   `tools/heavy`, `-j6`. Prove `cmp` identity of the packaged `libonnxruntime.so` with the AAR's.
2. Vendor sherpa's Kotlin API (`com.k2fsa.sherpa.onnx.*`, Apache-2.0 header kept) under
   `infer/asr/src/main/kotlin/com/k2fsa/sherpa/onnx/`, only the classes you use.
3. Runtime adapters (one engine thread): `SegmentingSession` (Silero VAD v5 via ORT Java API or sherpa `Vad` —
   pick one, both are verified to give identical segments — + `Segmenter` + `OfflineRecognizer`), `StreamingSession`
   (`OnlineRecognizer`, endpointing off, `finish` = tail padding + `inputFinished`). Audio buffered from the first
   sample until the model is loaded. Language hint for HINT models. T-one at 8 kHz (resample).
4. `Segmenter` exactly per `SegmenterConfig` (pause ≥ 0.5 s, ≥ 0.15 s once the segment ≥ 14 s, 0.3 s pre/post-roll,
   hard max = window − 0.5 s choosing the lowest-VAD frame in the last 2 s, drop < 250 ms of speech) + `speechMs`.
5. Probe: implemented runtime keys; units that initialise (`cpu` always; `npu` only if the QNN HTP backend creates a
   context on this device; on the emulator: CPU only), facts (QNN build id, sherpa version).
6. Memory: `memory()` = VmHWM/VmRSS from `/proc/self/status` + `Debug.getMemoryInfo()` + system availMem.
7. QNN packaging behind `-Pmurmur.qnn=true` (off until the owner confirms the licence): add
   `com.qualcomm.qti:qnn-runtime:2.52.0` (architect adds it to the catalog on request), keep only `libQnnHtp.so`,
   `libQnnSystem.so`, `libQnnHtpV79Stub.so`, `libQnnHtpV79Skel.so`; set `ADSP_LIBRARY_PATH` to the real lib dir.
8. Keep `tools/device/asr_npu_smoke.sh` working against your build (it is the phone test for this WP).

## Acceptance (checkable here)

- JVM: `SegmenterTest` with synthetic probability sequences covering every rule (pause cut, soft cut after 14 s,
  forced cut at hard max on the lowest frame, pre/post-roll sample math, min speech, flush).
- Host parity (optional but recommended): your Segmenter replayed on the spike's logged VAD probabilities
  (`.cache/spikes/asr-ocr/logs/seg_*.json`) produces the same cut points as `scripts/seg_stream.py`.
- x86_64 emulator, real models (download with catalog URLs into `.cache/models/`, check sha256, push to the app's
  files dir with `run-as`): instrumented test in `infer/asr/src/androidTest` binds `AsrService` (`:asr`), loads
  `gigaam-v3-e2e-rnnt@onnx-int8` and `parakeet-tdt-0.6b-v3@onnx-int8` on CPU + `silero-vad-v5`, streams a FLEURS
  ru clip and an en clip (or the spike's eval clips) in 20 ms chunks in real time, and asserts: ≥ 2 `Committed`
  segments before `finish()`, final text WER ≤ 15 % vs the reference (WER from `core:benchmark`), `finish()` returns
  within 3 s of EOF, `Transcript.unit == CPU`. Report RTF and peak RSS.
- Streaming: `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` emits `Partial` events while audio flows.
- Buffering: start the session before `loadAsr` completes; no samples lost (sample count in = count decoded).
- `unloadAll()` + unbind → `pidof app.murmur:asr` empty within 2 s.
- NPU: UNVERIFIED-ON-DEVICE (DEVICE_CHECKLIST D3); the probe on the emulator reports `units=[CPU]`.

## Out of scope

OCR (WP-07), binder client side (WP-05), choosing models (WP-13), Parakeet timestamp merge for forced cuts
(known open item: forced cuts are a last resort; record the limitation).

## Build/test

`tools/heavy native/sherpa/build-android.sh all <outdir>`; `tools/gw :core:audio:test :infer:asr:assembleDebugAndroidTest :app:assembleDebug`.
