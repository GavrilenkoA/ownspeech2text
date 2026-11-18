# WP-18 Model hub UI + benchmark sheet (`:feature:models`)

Wave 2. Read first: `docs/wp/README.md`; DESIGN.md §0 decision 8, §6.4 (all), §4 (badges, in-use pill, progress
ring, sheets); frames `models*.png`, `model-detail--cover.png`, `bench-*.png`; ARCHITECTURE.md §8 (catalog, unit
selection, custom models), §10 (benchmark); DECISIONS ADR-016, ADR-029, ADR-033; the bundled catalog
`core/catalog/src/main/resources/catalog/catalog.json`.

## Goal

The user sees every model as facts from the catalog (parameters, languages, size, where it runs, streaming, licence,
source), downloads / selects / deletes / benchmarks it, pins a compute unit, adds any Hugging Face model — with no
model-specific code (D3).

## Owns

`feature/models/**`.

## Contracts

Consume `ModelsDeps` (catalog repository, installed-models repository, benchmark repository + runner, settings,
custom resolver, device profile flow). `ArtifactSelector.candidates/choose` decides badges and the chosen unit.
Provide `ModelsScreen(deps, onOpenModel)` and a detail pane composable.

## Deliver

1. Segmented Speech | Cleanup; purpose line; rows (name, publisher + variant, mono spec line "938M params · 590 MB
   · streaming", unit badges from `artifact.units` with the chosen one marked, language badges, "No Russian" when
   `entry.noRussian`, "In training" for `status = in_training`, row states of DESIGN 6.4 from `InstallState`);
   "None" first in Cleanup (`CleanupChoice.None`); storage line from `storage()`.
2. Detail: badges, headline, description, fact table (Parameters, Languages long form, Download size + quant, Runs
   on = candidates with blockers: "NPU not supported yet" for `followUps[npu-port]`, "Needs Snapdragon 8 Elite" for
   `WRONG_SOC`, "Not supported in this version" for `RUNTIME_NOT_SHIPPED`; Streaming; License; Source = HF repo or
   GitHub release, opens the browser), unit picker (Auto / each usable unit → `Settings.unitPreference`), "Use this
   model" / "✓ In use", Delete (confirm when in use; switch to the next downloaded model or None).
3. Benchmark sheet (bottom sheet on cover, 420 dp side sheet on inner): step checklist with timings, progress, Stop,
   results grid per kind (ASR: Faster than real time ×, Load time, Peak memory, Ran on, per-sample; LLM: Writing
   speed, Reading speed, First word, Peak memory, `BenchSummary` sentence; OCR: ms per screenshot), Copy results /
   Done, keep-screen-on while running; results per unit when several were measured.
4. "Add a model" sheet: repo id, optional revision/file, kind; shows resolve errors; preview of the resulting entry
   before saving.
5. Errors: not enough storage banner (`models-storage`), download failed row + notification text, checksum mismatch.

## Acceptance (checkable here)

- View-model tests on `FakeCatalogRepository` (bundled catalog) + `FakeInstalledModelsRepository`: the emulator
  device profile (CPU only) shows Parakeet's NPU artifact as blocked and the CPU artifact as chosen; the Fold7
  profile (SM8750, NPU+GPU+CPU) chooses NPU; pinning CPU persists; deleting the in-use model switches; "None"
  selection sets `CleanupChoice.None`; every catalog model renders without a crash (iterate all 27).
- A source scan test: no `ModelId("…")` / artifact-id string literals in `feature/models/src/main` (D3).
- Compose UI tests on cover and inner: `models`, `models-llm`, `models-storage`, `model-detail`, `bench-run`,
  `bench-result`, `bench-result-llm` screenshots vs `docs/design/screens/` (sample numbers come from the fake runner).

## Out of scope

Downloads and resolution (WP-13), running benchmarks (WP-14).

## Build/test

`tools/gw :feature:models:testDebugUnitTest :feature:models:assembleDebugAndroidTest`.
