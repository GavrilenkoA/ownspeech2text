# WP-06 ASR engine: sherpa-onnx, Silero VAD, segmenter (`:infer:asr`, `native/sherpa`)

Wave 1. Read first: `docs/wp/README.md`; `docs/research/verify/asr-ocr.md` (all — decisions A1-A6, the build, the
segmentation measurements, §5.3 ADSP path); `docs/research/asr-models.md` §6-7 (session interface background);
ARCHITECTURE.md §2.1, §4, §5.2 (Listening/Finishing rows), §6, §7, §8.5 (Preparing); DECISIONS ADR-005, ADR-006,
ADR-008, ADR-014, ADR-024, ADR-025, ADR-048, ADR-050; `core/catalog/src/main/resources/catalog/catalog.json` (ASR +
VAD entries, `layout`, `maxWindowSec`).

## Goal

The `:asr` process transcribes during dictation (D4): native streaming for streaming models, VAD pause-cut
segments decoded as they close for batch models, the open tail at stop — on the NPU through sherpa's QNN path on
SM8750 and on the CPU everywhere (x86_64 emulator included), behind `AsrSession`.

## Owns

`infer/asr/**` except `infer/asr/src/*/kotlin/app/murmur/infer/asr/ocr/**` and `infer/asr/src/androidTest/assets/ocr/**`
(WP-07); `native/sherpa/**`; `core/audio/src/{main,test}/kotlin/app/murmur/core/audio/Segmenter*.kt`;
`tools/device/asr_npu_smoke.sh`.

## Contracts

Implement the `IAsrService` binder (`AsrService`): `probe`, `memory`, `loadAsr`, `startSession` (+ `IAsrSession`),
`unloadAll`. OCR is not yours: `AsrService` already forwards `loadOcr` / `recognizeScreen` / `unloadAll` to WP-07's
`OcrHandler` (`infer/asr/.../ocr/OcrHandler.kt`, a stub until WP-07 lands); do not move that delegation. WP-07 may add
OCR androidTest classes under `infer/asr/src/androidTest/kotlin/app/murmur/infer/asr/ocr/` and assets under
`infer/asr/src/androidTest/assets/ocr/`; the androidTest manifest and `infer/asr/build.gradle.kts` stay yours (add what
WP-07 asks for). Implement `Segmenter` (pure, `core/audio`) and `VadModel` (`SileroVad`). Runtime keys
(`RuntimeKeys`): `sherpa/offline-transducer`, `sherpa/offline-ctc`, `sherpa/offline-whisper`,
`sherpa/offline-qwen3-asr`, `sherpa/online-transducer`, `sherpa/online-t-one`, `ort/silero-vad-v5`.
Unit → provider: NPU = `qnn` (context binaries from `layout`), CPU = `cpu`. A load failure is reported as a
`MurmurError` (`UnitUnavailable(NPU)` when the QNN backend or context cannot be created, `EngineLoadFailed(ASR)`
otherwise); the fallback to another artifact is WP-11's executor (ADR-050), not yours.

## Deliver

1. Native build: `native/sherpa/build-android.sh` (from the spike) producing `libsherpa-onnx-jni.so` for arm64-v8a
   (QNN on, public qnn-include 2.40 headers) and x86_64, linked against `onnxruntime-android-1.32.0.aar`'s
   `libonnxruntime.so`. Install **only** `libsherpa-onnx-jni.so` into `<murmur.nativeLibsDir>/sherpa/<abi>/` (default
   `.cache/native/jniLibs`); the script fails if `libonnxruntime.so` (or the c/cxx api libs) appear there, and keeps the
   `cmp` check of the linked `libonnxruntime.so` against the AAR's. `llvm-readelf -d` of the JNI lib must show NEEDED
   `libonnxruntime.so` only from ORT. Pin sherpa to v2.0.1 (`af2d8d81`) with sha256 of the source archive. Record build
   times; heavy steps via `tools/heavy`, `-j6`.
2. Vendor sherpa's Kotlin API (`com.k2fsa.sherpa.onnx.*`, Apache-2.0 header kept) under
   `infer/asr/src/main/kotlin/com/k2fsa/sherpa/onnx/`, only the classes you use.
3. Runtime adapters (one engine thread): `SegmentingSession` (Silero VAD v5 via ORT Java API or sherpa `Vad` —
   pick one, both are verified to give identical segments — + `Segmenter` + `OfflineRecognizer`), `StreamingSession`
   (`OnlineRecognizer`, endpointing off, `finish` = tail padding + `inputFinished`). Audio buffered from the first
   sample until the model is loaded. Language hint for HINT models. T-one at 8 kHz (resample).
4. `Segmenter` exactly per `SegmenterConfig` (pause ≥ 0.5 s, ≥ 0.15 s once the segment ≥ 14 s, 0.3 s pre/post-roll,
   hard max = window − 0.5 s choosing the lowest-VAD frame in the last 2 s, drop < 250 ms of speech) + `speechMs`.
5. Probe: implemented runtime keys; units that initialise (`cpu` always; `npu` only if the QNN HTP backend creates a
   context on this device; on the emulator: CPU only), facts (`native.build` = "sherpa-onnx 2.0.1 af2d8d81 + ORT
   1.32.0 [+ QNN 2.52]", QNN build id, HTP arch).
6. Memory: `memory()` = VmHWM/VmRSS from `/proc/self/status` + `Debug.getMemoryInfo()` + system availMem.
7. QNN packaging behind `-Pmurmur.qnn=true` (ADR-048; off until the owner confirms the licence): add
   `com.qualcomm.qti:qnn-runtime:2.52.0` (architect adds it to the catalog on request), keep only `libQnnHtp.so`,
   `libQnnSystem.so`, `libQnnHtpV79Stub.so`, `libQnnHtpV79Skel.so`. Before the first QNN recognizer call
   `OfflineRecognizer.prependAdspLibraryPath(applicationInfo.nativeLibraryDir)` (sherpa 2.0.1 API, VERIFIED in
   `offline-recognizer.cc:731`): it keeps the vendor DSP paths. Where a full string is needed (the smoke script), use
   exactly `<lib dir>;/vendor/lib64/rfs/dsp/snap;/vendor/dsp/cdsp;/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/vendor/dsp`
   (the Samsung `snap` path matters for QNN, llm spike §3.4).
8. Keep `tools/device/asr_npu_smoke.sh` working against your build and switch its `ADSP_LIBRARY_PATH` to the string
   above (it is the phone test for this WP, DC3).

## Acceptance (checkable here)

- JVM: `SegmenterTest` with synthetic probability sequences covering every rule (pause cut, soft cut after 14 s,
  forced cut at hard max on the lowest frame, pre/post-roll sample math, min speech, flush).
- Host parity (optional but recommended): your Segmenter replayed on the spike's logged VAD probabilities
  (`.cache/spikes/asr-ocr/logs/seg_*.json`) produces the same cut points as `scripts/seg_stream.py`.
- Native: the install dir contains only `libsherpa-onnx-jni.so` per ABI (`ls`), the script refuses a dir with
  `libonnxruntime.so` (negative test), `:app:assembleDebug` packages exactly one `libonnxruntime.so` per ABI
  (`unzip -l`).
- x86_64 emulator, real models (`tools/models/fetch gigaam-v3-e2e-rnnt@onnx-int8 parakeet-tdt-0.6b-v3@onnx-int8
  silero-vad-v5@onnx`, pushed to the app's files dir with `run-as`): instrumented test in `infer/asr/src/androidTest`
  binds `AsrService` (`:asr`), loads each ASR model on CPU + `silero-vad-v5`, streams a FLEURS ru clip and an en clip
  (or the spike's eval clips) in 20 ms chunks in real time, and asserts: ≥ 2 `Committed` segments before `finish()`,
  final text WER ≤ 15 % vs the reference (WER from `core:benchmark`), `finish()` returns within 3 s of EOF,
  `Transcript.unit == CPU`. Report RTF and peak RSS. (Parakeet CPU needs 1.3 GB: run it on an AVD with 4 GB, alone.)
- Streaming: `nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms` emits `Partial` events while audio flows (kept for the
  future preview; not selectable in v1, ADR-047).
- Buffering: start the session before `loadAsr` completes; no samples lost (sample count in = count decoded).
- Load failure: a `LoadSpec` with unit NPU on the emulator returns `UnitUnavailable(NPU)` within 2 s (no crash).
- `unloadAll()` + unbind → `pidof app.murmur:asr` empty within 2 s.
- NPU: UNVERIFIED-ON-DEVICE (DC3, DC8); the probe on the emulator reports `units=[CPU]`.

## Out of scope

OCR (WP-07), binder client side (WP-05), choosing models and fallbacks (WP-13, WP-11), Parakeet timestamp merge for
forced cuts (known open item: forced cuts are a last resort; record the limitation), GigaAM NPU (WP-21).

## Build/test

`tools/heavy native/sherpa/build-android.sh all <outdir>`; `tools/gw :core:audio:test :infer:asr:assembleDebugAndroidTest :app:assembleDebug`.
