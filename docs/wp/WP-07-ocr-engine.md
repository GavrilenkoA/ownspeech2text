# WP-07 OCR engine + parity with ml/ocr (`:core:ocr`, `:infer:asr` ocr package)

Wave 1. Read first: `docs/wp/README.md`; `ml/ocr/README.md` (the porting spec — every step and constant),
`ml/ocr/config.py`, `ml/ocr/pipeline.py`, `ml/ocr/postprocess.py`, `ml/ocr/golden/` (26 pairs + `rec_keys.txt`);
`docs/research/verify/asr-ocr.md` §7 (runtime decision A7, emulator parity); `docs/ml/CONTEXT_V2.md`;
ARCHITECTURE.md §9.3; DECISIONS ADR-013, ADR-023.

## Goal

The Android OCR produces what the Python reference produces (D11), fast enough to finish during a dictation.

## Owns

`core/ocr/**`; `infer/asr/src/{main,androidTest}/kotlin/app/murmur/infer/asr/ocr/**` (ORT sessions + the
`OcrHandler` that `AsrService` already delegates to); `infer/asr/src/androidTest/assets/ocr/**` (golden PNGs for the
emulator test). The androidTest manifest and `infer/asr/build.gradle.kts` are WP-06's: ask for what you need.

## Contracts

Implement `OcrPipeline.run`, `OcrPipeline.detTargetSize`, `OcrPostProcess.process`, `repairHomoglyphs` (pure,
`core/ocr`), `OcrModels` (`OrtOcrModels`: ORT 1.32 Java API, default CPU EP, `intra_op_num_threads` from
`OcrConfig.threads`, graph optimisation ALL), and the `:asr` side through the seam that exists in your package:
`OcrHandler` (`load(detJson, recJson, cb)`, `recognize(SharedMemory, w, h, excludeJson, cb)`, `unload()`) and
`OcrHandlers.create(context)` (replace `StubOcrHandler` with your handler: SharedMemory → `ScreenImage` → `OcrPipeline`
→ `ScreenText` JSON on your own OCR thread; close the SharedMemory in every path). `OcrConfig` defaults must stay equal to `ml/ocr/config.py`
(`OcrConfigParityTest`; extend it to every field).

## Deliver

1. Detector pre-processing (BGR, resize rule, half-pixel bilinear in float32, normalisation in B,G,R order),
   DB post-processing (8-connected components, bbox score mean, unclip `d = w*h*1.4/(2(w+h))`, clamps, rounding
   half up), exclusion rectangles (≥ 50 % overlap), recogniser crop (no rotation), bucket padding, batch by bucket
   (batch size from config; output must not depend on it), greedy CTC + confidence, dictionary from `inference.yml`
   (`rec_keys.txt` byte-identical), post-processing (NFC, filters, homoglyph repair, geometric de-dup, two-pane
   reading order, textual de-dup, conf rounding half-even to 3 digits).
2. Golden loader for `ml/ocr/golden/NNN.json` (`stages.det_input`, `det_boxes`, `rec`, `ocr`); test helpers to
   compare stage by stage.
3. Timing log (det ms, rec ms per line, lines) for the benchmark (WP-14 reads it through `ScreenText` + a stats
   side channel you define in `infer/asr/.../ocr`).

## Acceptance (checkable here)

- `tools/gw :core:ocr:test` green with `OcrGoldenSuiteTest.postProcessingReproducesGoldenExactly` un-ignored:
  feeding `stages.rec` of all 26 goldens into `OcrPostProcess.process` reproduces `ocr` **exactly** (JSON equality).
- Host JVM end-to-end with ORT Java on Linux x86_64 (add `com.microsoft.onnxruntime:onnxruntime` desktop artifact as
  a **test-only** dependency — request the version from the architect): `OcrPipeline.run` on every golden PNG meets
  the README tolerances (same line count, boxes ±2 px, ≥ 98 % identical texts, CER ≤ 0.5 % on the rest, conf ±0.02).
- x86_64 emulator: instrumented test sends a golden PNG decoded to ARGB through the real `recognizeScreen` binder
  path (SharedMemory) and meets the same tolerances; report det ms at 960 and total ms for the cover and inner
  goldens (the spike measured det 453 ms at 960×864 on a loaded host).
- No XNNPACK, no QNN in this WP (NPU OCR is WP-21's, adopted only after DEVICE_CHECKLIST DC4). Keep the ORT session
  creation in one function (`OrtOcrModels.session(spec, options)`) so that WP-21 can add the QNN EP options there.

## Out of scope

Screenshot capture (WP-02), ctx assembly and budgets (WP-08/WP-04), OCR quality on real Fold7 screens (DC7).

## Build/test

`tools/gw :core:ocr:test :infer:asr:assembleDebugAndroidTest`.
