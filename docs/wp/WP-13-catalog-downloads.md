# WP-13 Catalog, downloads, storage, device profile, unit health, custom models (`:data:models`, `:core:catalog`)

Wave 1. Read first: `docs/wp/README.md`; ARCHITECTURE.md §7 (`MemoryEstimate`), §8 (all); `core/catalog/src/main/kotlin/.../`
(`CatalogSchema.kt`, `Selection.kt`, `CustomModels.kt`) and `core/catalog/tools/build_catalog.py`;
`docs/research/verify/platform.md` §3 item g, §7 (`DownloadJob.kt`); `docs/research/android-platform.md` §7.4; DESIGN.md
§6.1 steps 5-5b, §6.4 row states and errors; DECISIONS ADR-016, ADR-017, ADR-030, ADR-033, ADR-039, ADR-042, ADR-045,
ADR-046, ADR-047, ADR-050. Code to lift: `spikes/platform/app/src/main/kotlin/app/murmur/spike/DownloadJob.kt`.

## Goal

The hub's data: the effective catalog (bundled + custom), resumable verified downloads that survive process death,
install state with the Preparing step, disk usage, the device profile and per-unit health of this phone, and the
resolver that turns "what the user selected" into load specs with fallbacks.

## Owns

`data/models/**`, `core/catalog/**` (schema, parser, validator, selector, bundled catalog and its generator).

## Contracts

Implement `CatalogRepository` (`DefaultCatalogRepository`), `InstalledModelsRepository`
(`DefaultInstalledModelsRepository`), `ModelResolver` (`DefaultModelResolver(catalog, installed, device, health,
benchmarks)`, used by WP-11), `DeviceProfileRepository` (`DefaultDeviceProfileRepository`: **you are the only owner of
the probe**), `UnitHealthRepository` (`FileUnitHealthRepository`), `CustomModelResolver` (`HfCustomModelResolver`),
`Downloader`, `ArchiveExtractor`, `ModelDownloadJob`. Storage layout `ModelStorage` (ARCHITECTURE 8.3). Consume
`EnginePreparer` (the orchestrator, WP-11) for the Preparing step. Existing and tested: `ArtifactSelector.ranked /
choose / fallbackAfter` (artifact order before preferredUnit), `Blocker.UNIT_FAILED`, `MemoryEstimate`,
`GgufTemplates`, `CatalogValidator` rules (nCtx ≥ prefixTokens + 5,200; memoryMb for every bundled unit; no previewOnly
default).

## Deliver

1. `ModelDownloadJob` (UIDT, `RUN_USER_INITIATED_JOBS`): one artifact per job; for each file Range-resume from the
   `.part`, redirect host allowlist (`CatalogValidator.allowedRedirectHosts`), streamed SHA-256, `fsync`, atomic
   rename; extraction for `tar.bz2`/`zip` (commons-compress) with path-traversal checks, verify every `layout`
   path exists (and for archives with a `dir` role, flatten or resolve it); `installed.json` last; notification
   progress; Wi-Fi only (`setRequiredNetworkType(NETWORK_TYPE_UNMETERED)`); free-space pre-check
   (`peakInstallBytes` + 200 MB) → `Paused(LowStorage)`. Then `InstallState.Preparing(progress)` while
   `EnginePreparer.prepareArtifact(artifact)` runs; a report with `needsCpuArtifact` queues that artifact
   (ARCHITECTURE 8.5).
2. Download semantics (ADR-050, ARCHITECTURE 8.5): packs → one artifact per model (`ArtifactSelector.choose(entry,
   device, Auto)`); an ASR artifact with a batch runtime also queues the default VAD; "Use what's on screen" on queues
   the OCR pair; choosing a model in the hub follows the same rules; a pinned unit whose artifact is not installed is
   offered for download (not an error).
3. `InstallState` flow per artifact from disk + running jobs (survives process death: state from `.part` lengths).
4. `DefaultModelResolver.resolve(settings)` (never throws): device = `DeviceProfileRepository.ensure()`; ASR =
   `settings.asrModel` or `defaults.asr[profile]` (profile from `settings.languages`), rejected if not
   `selectableAsAsr` (previewOnly, ADR-047: fall back to the default); `ArtifactSelector.ranked` over **installed**
   artifacts with the unit preference **of the model** (`settings.unitPreference[modelId]`), the latest **valid**
   benchmark scores (`BenchLookup` returns null for invalid records) and `UnitHealth`; primary = first, `asrFallback`
   = next; VAD = defaults.vad (batch runtime without an installed VAD → `Err(NoVadModel)`); OCR det/rec (null when
   `useScreen` is off or not installed); LLM from `settings.cleanup` (Default → `defaults.llm`, None → null +
   `llmUnavailable = "none"`, not installed → null + `"no_model"`) with `fallback` = next ranked candidate,
   `nCtx`/`prefixTokens` from the catalog, `modelSha256` from the GGUF `FileSpec`, `speed` from the newest LLM
   benchmark record of that (artifact, unit) (invalid → no speed, ADR-071), `memoryMb = MemoryEstimate.mb`; `LoadSpec` with absolute dir + layout +
   `maxWindowSec` + options (`LoadOptions`: `n_ctx`, CPU LLM `use_mmap=0`, threads 4). `Err(NoAsrModel)` when no ASR
   artifact is installed.
5. `DefaultDeviceProfileRepository`: bind both hosts, `probe()`, merge into `DeviceProfile` (+ `nativeBuilds` from the
   `native.build` facts), cache per app version in noBackupFilesDir; `ensure()` never throws (probe failure →
   `DeviceProfile.cpuOnly`, not cached across processes); `reprobe()`.
6. `FileUnitHealthRepository`: JSON in noBackupFilesDir, reset when `appVersion` changes; `clear` is called by the
   benchmark after a valid record.
7. Custom models (D3, ADR-046): HF API `GET /api/models/<repo>/revision/<rev>` → full sha; `POST …/paths-info/<sha>` →
   sizes + LFS sha256 (small non-LFS files: download and hash); `SherpaLayout.detect` for ASR; for GGUF: prefer `Q4_0`,
   read the header with an HTTP Range request (first 4-16 MB) for `general.architecture` → `GgufTemplates.templateFor`
   (reject others with a clear error), `general.name` + `murmur.prompt_profile` → `GgufTemplates.profileFor` (the
   request's `promptProfile` wins), nCtx `GgufTemplates.DEFAULT_CUSTOM_NCTX` until Preparing measures the prefix
   (then `nCtxFor(PrefixInfo.tokens)`), memory by `MemoryEstimate`; validated entry stored in `custom_model` (WP-12's
   DAO) and merged into the catalog flow.
8. Catalog generator: keep `core/catalog/tools/build_catalog.py` the only way the bundled catalog changes (it now
   carries `PREFIX_TOKENS`, the nCtx rule, KV growth in `memoryMb`, K-quant NPU units, the memory fallback and the
   fine-tune entries); add the fine-tune artifacts when the ML team publishes GGUFs (status → available); add whisper
   tiny/base SM8750 binaries if wanted (GitHub digests available).

## Acceptance (checkable here)

- `tools/gw :core:catalog:test` green (`BundledCatalogTest`, 13 tests, + your selector tables: pinned unit
  unavailable, pinned unit not installed, benchmark override, invalid benchmark ignored, wrong SoC, runtime not shipped,
  unit failed, installed-only selection, fallback order).
- `python3 -I core/catalog/tools/build_catalog.py` reproduces the committed `catalog.json` byte-for-byte (network
  available) — or `--offline` re-shapes identically (say which ran).
- Instrumented on the emulator (network on): download `silero-vad-v5@onnx` (2.3 MB, GitHub) and
  `pp-ocrv6-tiny-det@onnx` (1.8 MB, HF) through the real job: state flow Queued → Downloading → Verifying → Preparing
  (with a fake preparer) → Installed; kill the app process mid-download of `t-one@onnx` (144 MB) with
  `adb shell am kill app.murmur` → the job resumes with HTTP 206 from the `.part` offset (log line) and the sha256
  matches; a corrupted `.part` (truncate + flip a byte) → `ChecksumMismatch`, file deleted; a tar.bz2 path-traversal
  fixture is rejected (unit test with a crafted archive); a preparer report with `needsCpuArtifact` queues it.
- `DefaultModelResolver` unit tests with fake install states: emulator profile → CPU artifacts, no fallback; Fold7
  profile with both Parakeet artifacts installed → NPU primary + CPU fallback; LLM fallback = same artifact on GPU;
  failed NPU in `UnitHealth` → CPU primary; previewOnly selected → default ASR; batch ASR without VAD → `NoVadModel`;
  probe failure → CPU-only profile, no exception.
- Custom model: resolve `csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8` (no revision) → entry with the
  transducer layout and pinned sha; resolve `unsloth/Qwen3.5-0.8B-GGUF` → Q4_0 artifact, template `qwen3.5`, profile
  `default.v4` (header read over HTTP Range; report the bytes fetched); a repo with an unsupported architecture → clear
  error. All validate.

## Out of scope

Hub UI (WP-18), benchmark (WP-14), preparing engines (WP-11's `EnginePreparer`), HF mirror of GitHub assets (owner,
ADR-016), NPU OCR/GigaAM artifacts (WP-21).

## Build/test

`tools/gw :core:catalog:test :data:models:testDebugUnitTest :data:models:assembleDebugAndroidTest`.
