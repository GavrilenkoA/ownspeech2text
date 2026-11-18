# WP-13 Catalog, downloads, storage, custom models (`:data:models`, `:core:catalog`)

Wave 1. Read first: `docs/wp/README.md`; ARCHITECTURE.md §8 (all); `core/catalog/src/main/kotlin/.../CatalogSchema.kt`
and `core/catalog/tools/build_catalog.py`; `docs/research/verify/platform.md` §3 item g, §7 (`DownloadJob.kt`);
`docs/research/android-platform.md` §7.4; DESIGN.md §6.1 steps 5-5b, §6.4 row states and errors; DECISIONS
ADR-016, ADR-017, ADR-030, ADR-033, ADR-039, ADR-042. Code to lift:
`spikes/platform/app/src/main/kotlin/app/murmur/spike/DownloadJob.kt`.

## Goal

The hub's data: the effective catalog (bundled + custom), resumable verified downloads that survive process death,
install state, disk usage, and the resolver that turns "what the user selected" into load specs for this phone.

## Owns

`data/models/**`, `core/catalog/**` (schema, parser, validator, selector, bundled catalog and its generator).

## Contracts

Implement `CatalogRepository` (`DefaultCatalogRepository`), `InstalledModelsRepository`
(`DefaultInstalledModelsRepository`), `ModelResolver` (`DefaultModelResolver`, used by WP-11),
`CustomModelResolver` (`HfCustomModelResolver`), `Downloader`, `ArchiveExtractor`, `ModelDownloadJob`.
Storage layout `ModelStorage` (ARCHITECTURE 8.3). Device profile cache (probe results from both processes through
`InferenceHost.probe()`; store in DataStore via WP-12's settings or a small file of your own).

## Deliver

1. `ModelDownloadJob` (UIDT, `RUN_USER_INITIATED_JOBS`): one artifact per job; for each file Range-resume from the
   `.part`, redirect host allowlist (`CatalogValidator.allowedRedirectHosts`), streamed SHA-256, `fsync`, atomic
   rename; extraction for `tar.bz2`/`zip` (commons-compress) with path-traversal checks, verify every `layout`
   path exists (and for archives with a `dir` role, flatten or resolve it); `installed.json` last; notification
   progress; Wi-Fi only (`setRequiredNetworkType(NETWORK_TYPE_UNMETERED)`); free-space pre-check
   (`peakInstallBytes` + 200 MB) → `Paused(LowStorage)`.
2. `InstallState` flow per artifact from disk + running jobs (survives process death: state from `.part` lengths).
3. `DefaultModelResolver.resolve(settings)`: ASR = `settings.asrModel` or `defaults.asr[profile]` (profile from
   `settings.languages`), choose the artifact with `ArtifactSelector` among **installed** artifacts using the device
   profile, the unit preference and benchmark scores (WP-12 repo); VAD = defaults.vad; OCR det/rec (skip when
   `useScreen` is off); LLM from `settings.cleanup` (Default → `defaults.llm`, None → null); `LoadSpec` with absolute
   dir + layout + `maxWindowSec` + runtime options (CPU LLM `use_mmap=0`, threads 4). Errors: `NoAsrModel` when no
   ASR artifact is installed.
4. Custom models (D3): HF API `GET /api/models/<repo>/revision/<rev>` → full sha; `POST …/paths-info/<sha>` → sizes +
   LFS sha256 (small non-LFS files: download and hash); `SherpaLayout.detect` for ASR, GGUF selection for LLMs
   (prefer `Q4_0`), template choice; validated entry stored in `custom_model` (WP-12's DAO) and merged into the
   catalog flow.
5. Catalog generator: keep `core/catalog/tools/build_catalog.py` the only way the bundled catalog changes; add the
   fine-tune artifacts when the ML team publishes GGUFs (status → available); add whisper tiny/base SM8750 binaries
   if wanted (GitHub digests available).

## Acceptance (checkable here)

- `tools/gw :core:catalog:test` green (`BundledCatalogTest` + your selector tables: pinned unit unavailable,
  benchmark override, wrong SoC, runtime not shipped, installed-only selection).
- `python3 -I core/catalog/tools/build_catalog.py` reproduces the committed `catalog.json` byte-for-byte (network
  available) — or `--offline` re-shapes identically (say which ran).
- Instrumented on the emulator (network on): download `silero-vad-v5@onnx` (2.3 MB, GitHub) and
  `pp-ocrv6-tiny-det@onnx` (1.8 MB, HF) through the real job: state flow Queued → Downloading → Verifying →
  Installed; kill the app process mid-download of `t-one@onnx` (144 MB) with `adb shell am kill app.murmur` → the
  job resumes with HTTP 206 from the `.part` offset (log line) and the sha256 matches; a corrupted `.part`
  (truncate + flip a byte) → `ChecksumMismatch`, file deleted; a tar.bz2 path-traversal fixture is rejected
  (unit test with a crafted archive).
- `DefaultModelResolver` unit tests with fake install states and the emulator device profile → CPU artifacts.
- Custom model: resolve `csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8` (no revision) → entry with the
  transducer layout and pinned sha; resolve `unsloth/Qwen3.5-0.8B-GGUF` → Q4_0 GGUF artifact. Both validate.

## Out of scope

Hub UI (WP-18), benchmark (WP-14), HF mirror of GitHub assets (owner, ADR-016).

## Build/test

`tools/gw :core:catalog:test :data:models:testDebugUnitTest :data:models:assembleDebugAndroidTest`.
