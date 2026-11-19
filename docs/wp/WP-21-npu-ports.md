# WP-21 NPU ports: ORT QNN plugin EP for OCR and GigaAM (`:infer:asr` `qnnep/` + `ocr/`, catalog)

Wave 3 (needs WP-06, WP-07 and WP-13 landed; their paths named below are handed over to you). Read first:
`docs/wp/README.md`; `docs/research/verify/asr-ocr.md` §2 (QNN EP packaging), §5.1-5.4 (versions, GigaAM), §7.5 (OCR on
the NPU), the scripts it names (`scripts/ocr_qnn.py`, the GigaAM compile); ARCHITECTURE.md §2.1 (licence gates), §9.3,
§17 (Product 5); DECISIONS ADR-005, ADR-006, ADR-013, ADR-048, ADR-059.

## Goal

BRIEF "NPU inference for all models": make the OCR models and GigaAM v3 runnable on the HTP through ONNX Runtime's QNN
plugin EP, proven here on the x86_64 HTP simulator and on the emulator's CPU path, so that the device pass (DC4) only
has to decide whether to switch them on. Off by default; adopted per model only if DC4 shows a clear win at equal
accuracy (ADR-059). VAD and T-one stay CPU (no NPU path: T-one does not compile strictly, asr-ocr 5.4).

## Owns

`infer/asr/src/*/kotlin/app/murmur/infer/asr/qnnep/**` (new), `infer/asr/src/*/kotlin/app/murmur/infer/asr/ocr/**`
(handed over by WP-07), `core/catalog/tools/build_catalog.py` and `core/catalog/src/main/resources/catalog/catalog.json`
(handed over by WP-13), `native/qnn-contexts/**` (host compile scripts), `tools/device/ort_qnn_smoke.sh`.

## Contracts

Same `OcrHandler` and `AsrSession` contracts; new runtime keys `ort-qnn/ppocr-det`, `ort-qnn/ppocr-rec`,
`ort-qnn/gigaam-ctc` (add them to `RuntimeKeys` through the architect: contract change), reported by the `:asr` probe
only when the QNN EP registers. Catalog artifacts for the context binaries with `units: [npu]`, `socs: [SM8750]`,
pinned files and sha256 (where they are hosted is an owner question, see needs).

## Deliver

1. Packaging behind `-Pmurmur.qnn=true` (ADR-048): `com.qualcomm.qti:onnxruntime-android-qnn:2.7.0` (plugin EP,
   `libonnxruntime_providers_qnn.so`, NEEDED liblog/libm/libdl/libc only) next to Maven ORT 1.32.0 and the QNN 2.52
   runtime libs WP-06 packages; one QNN stack per APK (ADR-005). Verify the plugin registers with ORT 1.32's
   `registerExecutionProviderLibrary` API on the host build; record the exact API calls.
2. Host compile scripts (`native/qnn-contexts/`, `tools/heavy`, PyPI `onnxruntime-qnn` 2.8.0 / QAIRT 2.52, from the
   spike): OCR det contexts for 960×416 (cover), 960×864 (inner) and their rotations, rec contexts for width buckets
   320/640/1280; GigaAM v3 e2e CTC fixed to 20 s windows (466 MB fp16). Record sizes, compile times, sha256.
3. `qnnep/QnnEpSessions`: create ORT sessions with the QNN EP and the EPContext models; CPU fallback disabled; one
   session per shape; `OrtOcrModels.session(spec, options)` (WP-07's single factory) gains the QNN option for
   `ort-qnn/*` artifacts; the detector picks the context by input shape.
4. GigaAM CTC on NPU: the 64-bin mel front end in Kotlin (port the spike's reference; parity test against the Python
   front end on the FLEURS clips), 20 s windows fed by WP-06's `Segmenter`, greedy CTC with GigaAM's vocabulary,
   behind `AsrSession` (a new adapter in `qnnep/`).
5. `tools/device/ort_qnn_smoke.sh` for DC4: pushes the contexts, runs det/rec/GigaAM on QNN vs CPU on the phone,
   prints a compact table (ms, argmax agreement, WER for GigaAM).

## Acceptance (checkable here)

- Host, x86_64 HTP simulator (`libQnnHtp.so` x86 from QAIRT 2.52): OCR det/rec contexts reproduce the spike's parity
  (argmax agreement 1.0 on the recogniser, det binary map 1.0 at the 0.2 threshold) on all 26 `ml/ocr/golden` pages;
  the full OCR pipeline with simulated QNN outputs meets the ml/ocr README tolerances. GigaAM CTC context: WER on the
  FLEURS ru clips within 1 pp of ORT CPU (simulator; report run time).
- Kotlin mel front end: max |Δ| vs the Python reference ≤ 1e-4 on the clips (JVM test).
- Emulator: the `ort-qnn/*` runtimes are absent from the probe (`units=[CPU]`), the hub shows the NPU artifacts as
  "Needs Snapdragon 8 Elite" / not available, the CPU path is unchanged (WP-07's and WP-06's tests stay green).
- `tools/device/ort_qnn_smoke.sh`: `bash -n`, and a dry run that prints the table skeleton without a device.
- Catalog validates (`BundledCatalogTest`), every new file pinned with sha256.

## Out of scope

Deciding adoption (owner after DC4), the sherpa QNN path (WP-06), T-one and VAD on the NPU (no path).

## Needs from the owner

Where the compiled context binaries are hosted: an owner HF repo (needs the HF token, via the orchestrator/Hermes)
or on-device compilation with `libQnnHtpPrepare.so` (+89.6 MB APK). Licence reading for the QNN EP plugin (same as
QNN runtime, ADR-048).

## Build/test

`tools/heavy native/qnn-contexts/compile.sh`; `tools/gw :infer:asr:testDebugUnitTest :infer:asr:assembleDebugAndroidTest :core:catalog:test`.

## Integration notes (wave-1 integration pass 1; ADR-064..077 in DECISIONS)

Merged on `integration/wave1`: WP-01, 05, 06, 07, 08, 09, 11, 12, 13. Still in work: WP-02, 03, 04, 10, 14, 20 (early paths).
- OCR in `:asr` is `OrtOcrHandler` (`StubOcrHandler` is gone): newest frame wins (`OcrFailed("superseded")`),
  `OutOfMemory(OCR_DET/OCR_REC)` by stage, `OcrFailed("exclude_json")` for a bad payload (ADR-069, ADR-077).
  `OcrRunControl` is checked only between model calls; for long QNN calls wire `RunOptions.setTerminate` into
  `OrtOcrModels`.
- The host pipeline is byte-exact with `ml/ocr` on x86_64 (26/26 goldens): a phone or QNN difference comes from the
  kernels or the EP, not the port. Parity gate = `OcrTolerance` with the CER on the non-identical lines.
- Catalog: `CatalogValidator.validateEntry`, `RuntimeKeys.implemented/isBatchAsr`, `DownloadPlanner` exist (WP-13);
  the resolver folds an artifact's `layout.dir` into `LoadSpec.dir` (ADR-073), so new NPU tarballs that extract into a
  sub-folder need only `layout.dir`. Benchmark selection uses the newest record per (artifact, unit) (ADR-071).

## Integration notes (wave-1 integration pass 2; ADR-078..080 in DECISIONS)

Merged on `integration/wave1` in this pass: WP-04, WP-02, WP-14 (in addition to WP-01, 05, 06, 07, 08, 09, 11, 12, 13).
Still in work: WP-03, 10, 15, 16, 17, 20 (early paths).
- OCR benchmark validity on NPU (ADR-080): one rounding character over the 43 bundled lines is still valid; record
  the differing line count and CER of an NPU `ocr_text` result at DC9 (it tells whether the gate is right).
  The parity gate (`OcrTolerance`, ADR-077) is unchanged.
- `ScreenImage` gained `kind` (CaptureKind, :main only; the IPC frame carries pixels and exclusion rects) (ADR-079).
