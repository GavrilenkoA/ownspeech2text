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

## Integration notes (wave-1 integration pass 3; ADR-081 in DECISIONS)

Merged on `integration/wave1` in this pass: WP-03 (in addition to WP-01, 02, 04, 05, 06, 07, 08, 09, 11, 12, 13, 14).
Still in work: WP-10, 15, 16, 17, 20 (early paths).
- No WP-03 contract touches the NPU ports.

## Integration notes (wave-1 integration pass 4; ADR-083 in DECISIONS)

Merged on `integration/wave1` in this pass: WP-10 (in addition to WP-01..09, 11, 12, 13, 14).
Still in work: WP-15, 16, 17, 18, 19, 20 (early paths).
- ADR-083 applies to the NPU ports too: an lmkd kill of `:asr` during an NPU load (exit reason low memory) is
  `OutOfMemory(kind)` in the executor, Preparing and the benchmark, never a unit failure; any other death during the
  load still marks the unit (ADR-066).
- `:llm` NPU path (WP-10): `UnitUnavailable(NPU)` on the emulator, then a CPU load in the same process works; the
  `npu` build is `HEXAGON_SDK_DIR=.cache/spikes/llm-npu/work/hexagon-sdk native/llama/build_android.sh npu`.

## Integration notes (wave-1 integration pass 5; ADR-085, ADR-086 in DECISIONS)

Merged on `integration/wave1` in this pass: main 392d313 (stock prompt default.v1) and WP-20's wave-1 part (e2e harness,
`:testapps:target`, DC8 smoke script, release checks). Still in work: WP-15, 16, 17, 18, 19, 21, and WP-20's wave 3.
- ADR-085: every stock catalog LLM uses `promptProfile` default.v1 (catalog 2026-11-20.2): `prefixTokens` 1,047-1,101,
  `nCtx` 7,168, the non-floor `memoryMb` values slightly lower. `GgufTemplates.STOCK_PROFILE` = default.v1. The shared
  `FakeModelResolver` now defaults to default.v1 / `nCtx` 7,168 / `prefixTokens` 1,047: a test that pinned the old fake
  numbers (9,216 / 3,129) or "default.v4" must follow. default.v4 / v4s stay packaged.
- ADR-086: a press holds the `:asr`/`:llm` idle exit for the whole dictation (Done re-arms 60 s; the fake host records
  `idleExitHistory`); `AsrService.probe` lists the OCR runtimes; `:app:assembleRelease` builds again and is part of the gate.
- `AsrService.probe` now lists `ort/ppocr-det` and `ort/ppocr-rec` (ADR-086); a QNN OCR handler (`OcrHandlers.create`)
  must keep them and add the NPU unit only when its session really loads.
- Catalog version (pass-5 verification): wp/wp-21 114fb35 bumped `CATALOG_VERSION` to "2026-11-20.2" independently of
  integration's 2fb59ae (same string, different contents; `catalogVersion` is written into installed-artifact records).
  Before or at the merge: rebase the catalog change onto integration's catalog, rerun `build_catalog.py` with network
  checks on the merged tree, bump to "2026-11-20.3", and check that only WP-21's artifact rows and the version lines
  differ from integration's 2026-11-20.2. Never hand-merge catalog.json.

## Integration notes (wave-2 integration pass 6; ADR-087, ADR-088 in DECISIONS)

Merged on `integration/wave1` in this pass: WP-15..19 (all wave-2 UI). Still in work: WP-21 and WP-20's wave 3.
- Nothing in your owned paths changed. The hub (WP-18) shows what the catalog and `DeviceProfile` say: a QNN OCR or
  GigaAM artifact appears in "Support models" / the Speech list from its catalog row alone, with the unit badge from
  `ComputeUnit`; "NPU (Hexagon)" on Runs on is derived from the SoC part numbers. No UI change is needed for the ports.
- `ModelsDeps.ensureDevice` is required now: the hub waits for the device profile (skeletons) before it offers a
  download, so a probe that lists the NPU unit for OCR only after a real session load (pass-5 note) is what the hub shows.
- Catalog: still 2026-11-20.2 on integration (unchanged in this pass); the pass-5 rule for your catalog merge stands
  (rebuild with network checks, bump to "2026-11-20.3"). WP-18 asks the catalog owner for a dialect-count field
  (OWNER_QUESTIONS 12); if it lands, it is a schema change: not yours unless the owner says so.

## Integration notes (wave-3 integration pass 7; ADR-090 in DECISIONS)

Merged on `integration/wave1` in this pass: WP-20's wave 3 (final app wiring) and main's ML data commits. Still in work:
WP-21 (you). Nothing in your owned paths changed, and `wp/wp-21` (6eb1dba) still merges without conflicts with the new
tree (`git merge-tree`); the overlap files of pass 6 (`catalog.json`, `build_catalog.py`, `AsrService.kt`,
`RuntimeKeys.kt`) were not touched in this pass. What changed around you:
- **Probe moment (ADR-090 item 6):** `AppContainer.start()` calls `deviceProfile.ensure()` at every `:main` start, so the
  probe of a new app version runs right after app start (`:asr` and `:llm` start for a few seconds and idle out), not in
  the first press. A probe that lists the QNN OCR / GigaAM runtime keys must not need a loaded model; a unit that only
  shows after a real session load stays "unknown" until then (pass-5 note unchanged).
- **Binder timeouts (ADR-090 item 2, ADR-091):** read per call; debug builds scale them by `debugTimeoutScale` but never
  below 1, release by 1. An NPU load or context compile on the phone has the plain 15 s `loadAsr` / `loadOcr` budget,
  also under DC8's tmo10 (scale 0.1): if a cold QNN context build takes longer, say so in your report (do not
  lengthen it inside `:asr`).
- **E2e and DC8 dry run:** `tools/e2e/run_emulator_e2e.sh` (suites harness, wiring, fast, matrix, events, events-real,
  real, bench) runs the app's tests under `app.murmur.shell.MurmurTestRunner`; your own `:infer:asr` instrumented tests
  keep `AndroidJUnitRunner`. Your merge pass reruns `real` on the inner AVD and `NONINTERACTIVE=1
  tools/device/murmur_app_smoke.sh`; NPU rows are SKIPPED on the emulator, DC4 covers them on the phone.
- Catalog: still 2026-11-20.2 on integration; the pass-5 rule for your catalog merge stands (rebuild with network checks,
  bump to "2026-11-20.3").

## Integration notes (integration pass 8; ADR-092 in DECISIONS)

Merged on `integration/wave1` in this pass: main 1dd1848 (ML training) and your branch (`wp/wp-21` d518381). Every
wave-1..3 package is now merged. All your contract, cross-package and dependency changes are accepted as ADR-092
(runtime keys and probe, `AsrEngineHost`/`AsrService`, `OcrConfigParityTest`, `deviceName` values, the two Qualcomm AARs
as literal coordinates inside the `-Pmurmur.qnn` block, the desktop ORT test jar).
- Catalog: the conflict was the `generated` line only; `CATALOG_VERSION` is now "2026-11-20.3", rebuilt by
  `build_catalog.py` with network checks; against integration's .2 only your three NPU rows and the version lines differ.
- The NPU artifacts stay on the placeholder host and unselectable (`RUNTIME_NOT_SHIPPED` in every build without the
  flag; the hub copy "Not supported in this version" is kept). Hosting, licence and adoption are OWNER_QUESTIONS 17-19.
- `OcrStats` gets no unit field now (your note to WP-14); the `det_unit` log field is enough while NPU OCR is off.
- Integration fixes around you: `DeviceProfileProber` retries a `cpuOnly` fallback after 5 min (pass-7 R3: a slow
  first probe with the QNN EP registration inside the 2 s PROBE call must not keep `:main` on CPU only);
  `check_release_apk.sh` also checks `libonnxruntime_providers_qnn.so` (absent without the flag, present with it in
  `--control`). DC4/DC8/DC9 carry your device notes as one-liners. Pass-8 verification (ADR-093): the retry now runs
  on its own outside dictations (5 min, doubling to 60 min), a PROBE reply with `ProcessProbe.FACT_ERROR` (your slow
  QNN EP registration hitting the 2 s PROBE call) counts as a failed probe instead of a profile without `:asr`
  runtimes, and the placeholder host stays until OWNER_QUESTIONS 17 with a catalog test guarding it.
- The `*SimulatorTest` suites stay outside the gate (they need the x86_64 HTP simulator and the contexts outside the
  repo, about 1 h each); keep `.cache/spikes/wp-21/ctx` until the owner hosts the files.
