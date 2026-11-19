# WP-18 Model hub UI + benchmark sheet (`:feature:models`)

Wave 2. Read first: `docs/wp/README.md`; DESIGN.md §0 decision 8, §6.4 (all), §4 (badges, in-use pill, progress
ring, sheets); frames `models*.png`, `model-detail--cover.png`, `bench-*.png`; ARCHITECTURE.md §3 (pieces without a
frame), §8 (catalog, unit selection, download rules, custom models), §10 (benchmark); DECISIONS ADR-016, ADR-029,
ADR-033, ADR-046, ADR-047, ADR-050, ADR-051; the bundled catalog
`core/catalog/src/main/resources/catalog/catalog.json`.

## Goal

The user sees every model as facts from the catalog (parameters, languages, size, where it runs, streaming, licence,
source), downloads / selects / deletes / benchmarks it, pins a compute unit, adds any Hugging Face model — with no
model-specific code (D3).

## Owns

`feature/models/**`.

## Contracts

Consume `ModelsDeps` (catalog repository, installed-models repository, benchmark repository + runner, settings,
custom resolver, device profile flow, `health: UnitHealth`). `ArtifactSelector.candidates/ranked/choose` decides
badges, blockers and the chosen unit; the unit preference is written per **model**: `Settings.unitPreference[entry.id]`.
Provide `ModelsScreen(deps, onOpenModel)` and a detail pane composable.

## Deliver

1. Segmented Speech | Cleanup; purpose line; rows (name, publisher + variant, mono spec line "938M params · 590 MB
   · streaming", unit badges from `artifact.units` with the chosen one marked, language badges, "No Russian" when
   `entry.noRussian`, "In training" for `status = in_training`, "Preview only, not selectable yet" for
   `asr.previewOnly` (no "Use this model", ADR-047), row states of DESIGN 6.4 from `InstallState` incl. Preparing);
   "None" first in Cleanup (`CleanupChoice.None`); storage line from `storage()`. Under Speech, a **"Support models"**
   group (no frame: grouped-list rows from WP-01) lists the VAD and the two OCR models with size, install state,
   Delete and Benchmark (OCR: ms per screenshot).
2. Detail: badges, headline, description, fact table (Parameters, Languages long form, Download size + quant, Runs
   on = candidates with blockers: "NPU not supported yet" for `followUps[npu-port]`, "Needs Snapdragon 8 Elite" for
   `WRONG_SOC`, "Not supported in this version" for `RUNTIME_NOT_SHIPPED`; Streaming; License; Source = HF repo or
   GitHub release, opens the browser; "Failed to load on NPU, using CPU" for `Blocker.UNIT_FAILED`; "Results look wrong
   on <unit>" for an invalid benchmark record), unit picker (no frame; radio rows: Auto / each usable unit →
   `Settings.unitPreference[entry.id]`; a pinned unit whose artifact is not installed shows "Download (N MB)"), the
   "Download the CPU version (N MB)" action when an NPU artifact failed and the CPU artifact is missing, "Use this
   model" / "✓ In use", Delete (confirm when in use; switch to the next downloaded model or None).
3. Benchmark sheet (bottom sheet on cover, 420 dp side sheet on inner): step checklist with timings, progress, Stop,
   results grid per kind (ASR: Faster than real time ×, Load time, Peak memory, Ran on, per-sample; LLM: Writing
   speed, Reading speed, First word, Peak memory, `BenchSummary` sentence; OCR: ms per screenshot), Copy results /
   Done, keep-screen-on while running; results per unit when several were measured.
4. "Add a model" sheet (no frame: sheet + text fields + radio rows from WP-01): repo id, optional revision/file, kind,
   and for LLMs the prompt format (Auto = from the GGUF / Murmur fine-tune / Standard); shows resolve errors
   (unsupported architecture, no GGUF, no sherpa layout); preview of the resulting entry (template, profile, size)
   before saving.
5. Errors: not enough storage banner (`models-storage`), download failed row + notification text, checksum mismatch.

## Acceptance (checkable here)

- View-model tests on `FakeCatalogRepository` (bundled catalog) + `FakeInstalledModelsRepository` +
  `FakeDeviceProfileRepository` + `FakeUnitHealth` + `FakeBenchmarkRunner` + `FakeCustomModelResolver`: the emulator
  device profile (CPU only) shows Parakeet's NPU artifact as blocked and the CPU artifact as chosen; the Fold7 profile
  (SM8750, NPU+GPU+CPU) chooses NPU; NPU marked failed → CPU chosen with the "Failed to load on NPU" line; pinning CPU
  persists under the model id; an invalid benchmark record shows "Results look wrong" and does not change the chosen
  unit; Nemotron cannot be selected; deleting the in-use model switches; "None" selection sets `CleanupChoice.None`;
  every catalog model renders without a crash (iterate all 27); Support models rows exist for VAD and OCR.
- A source scan test: no `ModelId("…")` / artifact-id string literals in `feature/models/src/main` (D3).
- Compose UI tests on cover and inner: `models`, `models-llm`, `models-storage`, `model-detail`, `bench-run`,
  `bench-result`, `bench-result-llm` screenshots vs `docs/design/screens/` (sample numbers come from
  `FakeBenchmarkRunner`); screenshots of the frameless pieces (unit picker, Add-a-model sheet, Support models) go to
  `.cache/spikes/wp-18/no-frame/` for the design owner.

## Out of scope

Downloads and resolution (WP-13), running benchmarks (WP-14).

## Build/test

`tools/gw :feature:models:testDebugUnitTest :feature:models:assembleDebugAndroidTest`.

## Integration notes (wave-1 integration pass 1; ADR-064..077 in DECISIONS)

Merged on `integration/wave1`: WP-01, 05, 06, 07, 08, 09, 11, 12, 13. Still in work: WP-02, 03, 04, 10, 14, 20 (early paths).
- `PrepareReport` (ADR-066, verification of pass 1): `failed` = marked failed in `UnitHealth` (the unit-failure label of the hub),
  `untested` = tried but the error says nothing about the unit (OutOfMemory, no process, prefix error, decode timeout):
  show no unit label for those; the first real load or a benchmark decides.
- WP-01 (ADR-075): components grow with large text, so never put fixed heights around chips, buttons, segmented
  controls, search fields or list rows; `MurmurNavigationRail` is ≥ 88 dp and widens (lay it out wrap-content);
  `MurmurSheet` on medium+ calls `onDismiss` after its exit animation (remove the sheet there); `AppTileSize.letterDp`
  replaces `letterSp`. Test AVDs run with `animator_duration_scale=0`: a test that observes a Compose animation sets it
  to 1 and restores it (`OverlaysTest.withAnimatorScale`).
- `MurmurError` gained `DownloadPaused` (`dl.paused`, copy "Paused" + Resume) and `MicLost` (`audio.lost`, the
  microphone pill): every exhaustive `when` over it needs both (ADR-073, ADR-074).
- Benchmark rows (ADR-071): show `BenchmarkRepository.latest(id)` (newest record per unit, newest first). An invalid
  newest record shows "Results look wrong on <unit>" and gives that unit no score (the chosen unit may change);
  `latestValid` is only for an optional "last good run" line.
- Install states (ADR-073): `Paused(reason = LowStorage)` = disk full (models-storage banner, DESIGN 6.4);
  `Paused(reason = DownloadPaused)` = user pause or a vanished job (Resume); `Failed(StorageFailed)` = another disk
  error; `Failed(ChecksumMismatch)` = damaged download.
- "Failed to load on NPU, using CPU" never appears for an out-of-memory load (ADR-066: OOM does not mark the unit).
- Streaming ASR (T-one, Nemotron) runs with the VAD when it is installed and then reports speech time; the VAD is
  queued only for batch runtimes (ADR-065). OCR benchmark validity uses `OcrTolerance` with the CER gated on the
  non-identical lines (ADR-077).

## Integration notes (wave-1 integration pass 2; ADR-078..080 in DECISIONS)

Merged on `integration/wave1` in this pass: WP-04, WP-02, WP-14 (in addition to WP-01, 05, 06, 07, 08, 09, 11, 12, 13).
Still in work: WP-03, 10, 15, 16, 17, 20 (early paths).
- Benchmark (ADR-080): `ModelsDeps` gets the real `DefaultBenchmarkRunner`. The bench-run checklist can show a fifth
  row `SAMPLE_MIXED` (ru+en models); `BenchmarkRecord.notes` carries measurement details (`BenchNotes`: thermal
  before/after, wall time, `llm.warmup.ms` / `llm.case.ms` / `llm.synthetic.ms`, per-page OCR ms, `asr.tail.whole_decode`
  = the tail shown is an upper bound). `stop()` also stops a run that has not started yet (it ends with no events).
- **Correction to the pass-1 note above:** the OCR *benchmark* validity is `OcrGoldenMatch` (ADR-080): over the run at
  most ceil(2 % of the lines) differing texts and CER ≤ 0.5 % over all characters; `OcrTolerance` (ADR-077) is the
  parity test only. One rounding character on NPU no longer shows "Results look wrong".
- One score implementation: `core:benchmark` `BenchScores` (newest record per (artifact, unit)); the resolver uses it.

## Integration notes (wave-1 integration pass 3; ADR-081 in DECISIONS)

Merged on `integration/wave1` in this pass: WP-03 (in addition to WP-01, 02, 04, 05, 06, 07, 08, 09, 11, 12, 13, 14).
Still in work: WP-10, 15, 16, 17, 20 (early paths).
- No WP-03 contract changes the model hub. The bubble's "Download a speech model first" error opens the hub through
  `Effect.Open(OpenTarget)` (unchanged).

## Integration notes (wave-1 integration pass 4; ADR-083 in DECISIONS)

Merged on `integration/wave1` in this pass: WP-10 (in addition to WP-01..09, 11, 12, 13, 14).
Still in work: WP-15, 16, 17, 18, 19, 20 (early paths).
- ADR-083: a process death during a load whose exit record says low memory is `OutOfMemory(kind)`: `PrepareReport.untested`
  (not `failed`), and the benchmark's `UnitFailed` with `engine.oom.*` no longer marks `UnitHealth` (it used to mark any
  load error). Show such a unit as "not tested, low memory", never as a unit failure.
- Catalog 2026-11-19.1 (re-measured default.v4 `prefixTokens`, network-verified); `nCtx` is unchanged. Pass-4 verification:
  catalog 2026-11-20.1 raises the CPU `memoryMb` of LFM3 1.7B to 1,480 and Qwen3.5 0.8B to 880 (WP-10's emulator peak RSS,
  rounded up; ADR-084); a hub row that shows memory reads it from the catalog, so no text pins the old 1,310 / 770.
- `:llm` probe facts now include `native.build` and `cpu.variant` (emulator: `libggml-cpu-haswell.so`); `htp.arch` only on
  the phone. Without the native libraries (integration debug APKs) the probe reports the LLM runtime missing.

## Integration notes (wave-1 integration pass 5; ADR-085, ADR-086 in DECISIONS)

Merged on `integration/wave1` in this pass: main 392d313 (stock prompt default.v1) and WP-20's wave-1 part (e2e harness,
`:testapps:target`, DC8 smoke script, release checks). Still in work: WP-15, 16, 17, 18, 19, 21, and WP-20's wave 3.
- ADR-085: every stock catalog LLM uses `promptProfile` default.v1 (catalog 2026-11-20.2): `prefixTokens` 1,047-1,101,
  `nCtx` 7,168, the non-floor `memoryMb` values slightly lower. `GgufTemplates.STOCK_PROFILE` = default.v1. The shared
  `FakeModelResolver` now defaults to default.v1 / `nCtx` 7,168 / `prefixTokens` 1,047: a test that pinned the old fake
  numbers (9,216 / 3,129) or "default.v4" must follow. default.v4 / v4s stay packaged.
- ADR-086: a press holds the `:asr`/`:llm` idle exit for the whole dictation (Done re-arms 60 s; the fake host records
  `idleExitHistory`); `AsrService.probe` lists the OCR runtimes; `:app:assembleRelease` builds again and is part of the gate.
- A unit marked failed (`UnitHealth`, a death or error during a load, ADR-066) gets a "Try again" action that runs that
  unit's benchmark: the benchmark re-tests a failed unit and a valid record clears the mark (ADR-080, ADR-086). Without
  it one crash during a load disables the unit until the next app version.
- Catalog 2026-11-20.2 (ADR-085): stock LLM `nCtx` 7,168, `prefixTokens` about 1.05-1.1k, NPU/GPU `memoryMb` 24 MB lower
  (Qwen3 224 MB lower); read everything from the catalog, no pinned numbers.
