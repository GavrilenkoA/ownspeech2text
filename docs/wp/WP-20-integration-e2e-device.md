# WP-20 Integration, end-to-end tests, device tooling

Waves 1-2 on the early paths (test target, scripts, the fake-engine end-to-end test, the device checklist); wave 3 for
the real wiring and the real end-to-end test. Read first: `docs/wp/README.md`; ARCHITECTURE.md (all, especially §4, §5,
§6, §16, §18); `docs/DEVICE_CHECKLIST.md`; DECISIONS ADR-001, ADR-010, ADR-016, ADR-022, ADR-048, ADR-052, ADR-056,
ADR-058, ADR-063; `docs/dev/TOOLCHAIN.md` §4 (emulator loop, §4.5 hinge sensor); every WP's report.

## Goal

The complete pipeline (bubble → record → ASR → LLM → insert → history) runs on the x86_64 emulator with CPU engines
and small real models (D6), every platform event the emulator can simulate is tested there, and the single device
pass is one script plus the checklist (D12).

## Owns

Waves 1-2: `testapps/**`, `tools/e2e/**`, `tools/device/murmur_app_smoke.sh`,
`app/src/androidTest/kotlin/app/murmur/e2e/**` (`PrivacyManifestTest` lives there), `docs/DEVICE_CHECKLIST.md`.
Wave 3 adds (taken over from WP-15): `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`,
`app/src/main/kotlin/app/murmur/{AppContainer,MurmurApp}.kt` and other non-shell files, `app/src/{debug,release,test}/**`.

## Deliver

1. Wiring (wave 3): replace every remaining skeleton and fake in `AppContainer` with the wave-1 modules' factories
   (`DbModule`, `ModelsModule`, `DefaultDeviceProfileRepository`, `FileUnitHealthRepository`, `BinderEngines(bindContext)`,
   the orchestrator with all `DictationDeps`, the benchmark runner); `MurmurApp` remains process-aware and calls
   `orchestrator.onAccessibilityLost()` on disconnect (exists); settings `StateFlow` started eagerly; retention and the
   orphan-audio cleanup at start; `DeviceProfileRepository.ensure()` after first unlock / app update; the orchestrator's
   settings observer started.
2. Debug fake mode: `DebugEngines` (file flag) or `Settings.debugFakeEngines`; `AudioCapture` swapped for WP-09's
   `WavFileCapture` when a test WAV is present (`files/debug-capture.wav`); `debugTimeoutScale` and
   `debugIgnoreMemoryGate` applied.
3. `:testapps:target`: EditText (exists), multiline, password, Compose `TextField`, WebView input and
   contenteditable, a `FLAG_SECURE` activity; each logs `TGT text=…` (lift `spikes/platform/target`).
4. End-to-end tests (`app/src/androidTest/kotlin/app/murmur/e2e` + `tools/e2e/run_emulator_e2e.sh`):
   - **fast** (fakes): enable the accessibility service via `settings put secure enabled_accessibility_services
     app.murmur/app.murmur.platform.a11y.MurmurAccessibilityService`, open the target, tap the bubble with
     UiAutomator, wait, tap again → the target logs the fake cleanup text, a history row exists, the bubble went
     Listening → Polishing → Inserted.
   - **platform events during Listening** (fakes, ADR-058), each asserting the documented outcome of ARCHITECTURE 5.2
     and the history row: (a) fold/unfold: `adb shell wm size 1080x2520` / `1968x2184` plus `adb emu sensor set
     hinge-angle0 0` / `180` → dictation continues, bubble keeps its edge, Inserted; (b) rotation `settings put system
     user_rotation 1` → Inserted; (c) `adb emu gsm call 5551234` → Finishing with the audio so far, Inserted (or the
     WP-09-reported limitation); (d) `kill -9 $(pidof app.murmur:asr)` → re-bind + replay → Inserted; a second kill →
     Failed with audio; (e) `kill -9 $(pidof app.murmur:llm)` during Polishing → "Inserted, no cleanup"; (f) disabling
     the service → Failed(AccessibilityOff) with audio; (g) notification start: `cmd statusbar expand-notifications`,
     fire "Start dictation", collapse → the target package (not SystemUI) is in the history row.
   - **real** (CPU engines, wave 3): models via `tools/models/fetch` — **pinned** `gigaam-v3-e2e-rnnt@onnx-int8`,
     `silero-vad-v5@onnx`, both OCR models, `qwen3.5-0.8b@q4_0` (Parakeet CPU only in WP-06's own test, ADR-063) —
     pushed to the app's `noBackupFilesDir/models/<artifact>/` with `installed.json`; WAV capture of a FLEURS ru clip;
     emulator with `-qemu -cpu host`, `debugIgnoreMemoryGate = true`; assert inserted text is non-empty Cyrillic, WER
     of the raw transcript ≤ 20 %, cleanup outcome `accept` (not skipped), guard verdict recorded, history audio file
     exists, `:asr`/`:llm` processes gone 70 s after Done (`pidof`). Run on the inner AVD with 4 GB guest RAM
     (`-memory 4096`) under `tools/heavy`.
   - **privacy**: during the real run, `adb shell dumpsys netstats detail` shows no bytes for the app UID after the
     model push (no network outside downloads, ADR-022); `PrivacyManifestTest` (exists, now in `e2e/`) stays green; no
     log line contains the transcript (grep logcat for the reference words).
5. `tools/device/murmur_app_smoke.sh` for DEVICE_CHECKLIST DC8: refuses to run unless the installed APK has the
   `libQnnHtp.so` and `libggml-htp-v79.so` libraries (built with `-Pmurmur.qnn=true -Pmurmur.htp=true` and the `npu`
   jniLibs; prints the build recipe otherwise); install, enable, download the recommended pack (waits for Preparing),
   prompt the owner to speak the scripted texts (10 s ru, 10 s en, 60 s mixed, "first after 70 s idle, 3 s", "right
   after a prompt edit", forced LLM-on-NPU while `:asr` holds QNN), collect per dictation: ASR/LLM `deviceName`
   (asserts `qnn` / `HTP0`), fallbacks in `UnitHealth`, probe facts, timings from history + `dumpsys meminfo` of the
   three processes + MemAvailable, idle exit check; compact markdown + JSON report in `.cache/device-reports/`.
   `bash -n` and an emulator dry run (`NONINTERACTIVE=1` with WAV capture, CPU build, the NPU asserts reported as
   SKIPPED) must pass.
6. Keep `docs/DEVICE_CHECKLIST.md` (steps DC1-DC10) current with every WP's device notes.

## Acceptance (checkable here)

- `tools/gw assembleDebug testDebugUnitTest` green for the whole project.
- Fast end-to-end test and the seven platform-event tests pass on `murmur_fold7_inner` (and the fast test on
  `murmur_fold7_cover`) — report the instrument output; any event the emulator cannot produce is reported as
  UNVERIFIABLE-HERE with the command output, and added to DC5/DC10.
- Real end-to-end test passes on the inner AVD (report timings: they are emulator numbers).
- Privacy checks above pass; release build (`tools/gw :app:assembleRelease`) contains only arm64-v8a, no
  `libggml-htp-*.so` and no QNN libraries without the flags, and no `:testing` classes (`unzip -l`,
  `dexdump | grep app/murmur/testing` empty).
- `tools/device/murmur_app_smoke.sh` dry run on the emulator exits 0 and prints the report skeleton.

## Out of scope

Feature implementations; the shell and onboarding (WP-15); phone runs (the owner).

## Build/test

`tools/gw assembleDebug testDebugUnitTest :app:assembleDebugAndroidTest :testapps:target:assembleDebug`; emulator via
`tools/heavy` (README rule 4).
