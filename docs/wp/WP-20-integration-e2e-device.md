# WP-20 Integration, end-to-end tests, device tooling

Wave 3 (start the test target, scripts and the fake-engine end-to-end test in wave 1/2; wire real modules as they
land). Read first: `docs/wp/README.md`; ARCHITECTURE.md (all, especially §4, §5, §6, §16, §18); `docs/DEVICE_CHECKLIST.md`;
DECISIONS ADR-001, ADR-010, ADR-016, ADR-022; `docs/dev/TOOLCHAIN.md` §4 (emulator loop); every WP's report.

## Goal

The complete pipeline (bubble → record → ASR → LLM → insert → history) runs on the x86_64 emulator with CPU engines
and small real models (D6), and the single device pass is one script plus the checklist (D12).

## Owns

`app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/kotlin/app/murmur/{AppContainer,MurmurApp}.kt`,
`app/src/{debug,release,test,androidTest}/**`, `testapps/**`, `tools/e2e/**`, `tools/device/murmur_app_smoke.sh`,
`docs/DEVICE_CHECKLIST.md`.

## Deliver

1. Wiring: replace every skeleton in `AppContainer` with the WPs' module factories as they land (`DbModule`,
   `ModelsModule`, …); build all `XxxDeps` for WP-15; `MurmurApp` remains process-aware; `ServiceConnector` gets the
   accessibility-service context when connected; settings `StateFlow` started eagerly; retention at start; device
   profile probe after first unlock / app update.
2. Debug fake mode: `DebugEngines` (file flag) or `Settings.debugFakeEngines`; also swap `AudioCapture` for WP-09's
   `WavFileCapture` when a test WAV is present (`files/debug-capture.wav`).
3. `:testapps:target`: EditText (exists), multiline, password, Compose `TextField`, WebView input and
   contenteditable, a `FLAG_SECURE` activity; each logs `TGT text=…` (lift `spikes/platform/target`).
4. End-to-end tests (`app/src/androidTest` + `tools/e2e/run_emulator_e2e.sh`):
   - **fast** (fakes): enable the accessibility service via `settings put secure enabled_accessibility_services
     app.murmur/app.murmur.platform.a11y.MurmurAccessibilityService`, open the target, tap the bubble with
     UiAutomator, wait, tap again → the target logs the fake cleanup text, a history row exists, the bubble went
     Listening → Polishing → Inserted.
   - **real** (CPU engines): models from the catalog URLs into `.cache/models/` (gigaam-v3-e2e-rnnt or parakeet CPU,
     silero-vad-v5, both OCR models, qwen3.5-0.8b Q4_0), pushed to the app's `noBackupFilesDir/models/<artifact>/`
     with `installed.json`; WAV capture of a FLEURS ru clip; assert inserted text is non-empty Cyrillic, WER of the
     raw transcript ≤ 20 %, guard verdict recorded, history audio file exists, `:asr`/`:llm` processes gone 70 s
     after Done (`pidof`). Run on the inner AVD with 4 GB guest RAM (`-memory 4096`) under `tools/heavy`.
   - **privacy**: during the real run, `adb shell dumpsys netstats detail` shows no bytes for the app UID after the
     model push (no network outside downloads, ADR-022); `PrivacyManifestTest` (exists) stays green; no log line
     contains the transcript (grep logcat for the reference words).
5. `tools/device/murmur_app_smoke.sh` for DEVICE_CHECKLIST D8: install, enable, download the recommended pack,
   prompt the owner to speak 3 scripted texts, collect timings from history + `dumpsys meminfo` of the three
   processes + MemAvailable, idle exit check; compact markdown + JSON report in `.cache/device-reports/`.
   `bash -n` and an emulator dry run (`NONINTERACTIVE=1` with WAV capture) must pass.
6. Keep `docs/DEVICE_CHECKLIST.md` current with every WP's device notes.

## Acceptance (checkable here)

- `tools/gw assembleDebug testDebugUnitTest` green for the whole project.
- Fast end-to-end test passes on `murmur_fold7_cover` and `murmur_fold7_inner` (report the instrument output).
- Real end-to-end test passes on the inner AVD (report timings: they are emulator numbers).
- Privacy checks above pass; release build (`tools/gw :app:assembleRelease`) contains only arm64-v8a and no
  `:testing` classes (`unzip -l`, `dexdump | grep app/murmur/testing` empty).
- `tools/device/murmur_app_smoke.sh` dry run on the emulator exits 0 and prints the report skeleton.

## Out of scope

Feature implementations; phone runs (the owner).

## Build/test

`tools/gw assembleDebug testDebugUnitTest :app:assembleDebugAndroidTest :testapps:target:assembleDebug`; emulator via
`tools/heavy` (README rule 4).
