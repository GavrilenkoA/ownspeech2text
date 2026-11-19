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

## Integration notes (wave-1 integration pass 1; ADR-064..077 in DECISIONS)

Merged on `integration/wave1`: WP-01, 05, 06, 07, 08, 09, 11, 12, 13. Still in work: WP-02, 03, 04, 10, 14, 20 (early paths).
- `MurmurError` gained `DownloadPaused` (`dl.paused`, copy "Paused" + Resume) and `MicLost` (`audio.lost`, the
  microphone pill): every exhaustive `when` over it needs both (ADR-073, ADR-074).
- Wave-3 wiring (if WP-15 did not): the WP-15 notes above apply (DbModule.settings with appScope +
  `RetentionScheduler.start()`, `ModelsModule.downloads(scope = appScope)`, `StatusActionHandler`, `fgsContext` = the
  accessibility service). The resolver already gets the storage folder for Preparing (ADR-067).
- IPC semantics for the e2e assertions (ADR-069): a kill after a call timeout surfaces as `Timeout(stage)`, not
  `ProcessDied`; OCR timeouts never kill `:asr`; a superseded OCR frame is a late OCR (no `screen`).
- Timing criteria on the emulator (ASR as well as LLM) run with `MURMUR_EMU_CPU_HOST=1` (ADR-076).
- Reuse WP-09's host scripts: `platform/audio/scripts/emulator_call_signal.sh <serial>` (ring → CallStarted ≤ 1 s;
  CallStarted now comes on RINGTONE, before the call is answered), `emulator_focus_other_app.sh`,
  `emulator_mic_lost.sh <serial> kill|stall`; WP-13's `data/models` kill/resume script for downloads.
- Proposed e2e cases from WP-11: `kill -9` of `app.murmur:asr` during Finishing of a long dictation → Inserted (one
  death counted once, ADR-069); a Retry e2e: force an ASR OOM, tap Retry → Transcribing → Polishing → Inserted in the
  same field (ADR-064).
- Capture kind: `ScreenImage` has no capture kind yet, so the orchestrator passes `ScreenOutcome.Text(text,
  capture = null)` (size inference, ADR-070) until WP-02 reports window vs display capture.
- DEVICE_CHECKLIST: wave-1 one-line additions are under "Wave-1 additions" there (fold them into the rows and
  `murmur_app_smoke.sh`). Proposed **new** steps that did not fit an existing DCn (decide placement):
  (1) 200 % system font on both displays: every tab grows instead of clipping, nav bar shows only the active label,
  rail widens; with TalkBack every destination and the selected state are announced; side sheet slides out before the
  screen reacts with animations on, instant with "Remove animations" (WP-01). (2) Retention on One UI ("DC11"):
  retention 1 day, phone idle overnight on battery, next morning the previous day's entries and audio are gone without
  opening the app (`dumpsys jobscheduler | grep -A5 app.murmur` before/after), and a time-zone change moves the job
  (WP-12). (3) Orphan sweep: force-stop during an Undo snackbar, reopen, the deleted entry's file is gone and no live
  recording was removed (WP-12). (4) Background download resume: `kill -9` of `:main` (or `am stop-app`) during a
  large download → the job restarts without opening the app, `dl_http … code=206 rangeFrom=…` then `dl_verified`; a
  literal `am kill` leaves the process alive; notification texts for Downloading / Verifying / Preparing / "Not enough
  space" / damaged download (WP-13). (5) T-one dictation with the VAD loaded → Speech events, speechMs > 500, not
  "Didn't catch that" (WP-06). (6) Replace the ASR model during Listening, then stop → fails fast, not after
  ASR_FINAL (WP-06). (7) "Hide 1 h", lock > 1 h, unlock → "Hide 1 h" is back within ≈ 1 min (WP-09). (8) Bluetooth
  headset disconnect mid-dictation → recording continues or `mic_lost cause=…` with audio kept (WP-09). (9) Retry
  after "Couldn't load the speech model" → Transcribing → Polishing → Inserted in the same field; Retry of a 5:00
  entry reads its audio well under 10 s (`storedAudioReadTimeoutMs`) (WP-11). (10) The binder order of
  binderDied / onServiceDisconnected / EPIPE vs a failing `finish()` on real process deaths (WP-11).

## Integration notes (wave-1 integration pass 2; ADR-078..080 in DECISIONS)

Merged on `integration/wave1` in this pass: WP-04, WP-02, WP-14 (in addition to WP-01, 05, 06, 07, 08, 09, 11, 12, 13).
Still in work: WP-03, 10, 15, 16, 17, 20 (early paths).
- Capture kind is now reported (ADR-079): `ScreenImage.kind` (WINDOW / DISPLAY) from `ScreenshotChain`, passed as
  `ScreenOutcome.Text(text, capture)`; `A11ySnapshot.windowBoundsInScreen` is filled. The pass-1 note about
  `capture = null` is obsolete. Shared fakes: `FakeTargetReader.captureKind` (default WINDOW),
  `SimpleContextAssembler.lastScreen`, `FakeWorld.context`.
- Notification start e2e (case g): the action goes WP-09 `StatusActionReceiver` → `StatusActionHandler` (MurmurApp)
  → `NotificationStart.start(context)`; WP-02's own receiver is gone (ADR-079). `ShadeTest` calls
  `NotificationStart.start` directly, so case (g) is the only test of the whole chain: send the real broadcast.
  `FakePermissionMonitor.onRefresh` (pass-2 verification) says what a re-read finds; preflight re-reads before refusing. WP-02's emulator suite:
  `platform/a11y/tools/run_emulator_tests.sh <serial>` (also installs `:testapps:target`; file-install vs adb-install
  restricted-settings runs).
- Wave-3 wiring: `AppContainer.benchmarkRunner` exists (ADR-080). The WP-14 emulator benchmark entry point
  (`.cache/spikes/wp-14/BenchmarkOnEmulatorTest.kt`) needs WP-10's real `:llm`; commit it under
  `app/src/androidTest/kotlin/app/murmur/e2e/` once WP-10 is merged (it copies models into the test APK's files with
  run-as and needs `useLegacyPackaging` for the native libraries).
- WP-04's ART runner `core/prompt/tools/run_on_art.sh <serial>` (≈ 2 min, expected `OK (36 tests)`) proves the prompt
  and guard ports on ART/ICU; add it to the device pass (see DEVICE_CHECKLIST DC8 addition).
- DEVICE_CHECKLIST: this pass's device notes (WP-02, 04, 14) were added as one-line additions to DC5, DC8, DC9 and
  DC10 under "Wave-1 additions"; fold them into the rows with the others.

## Integration notes (wave-1 integration pass 3; ADR-081 in DECISIONS)

Merged on `integration/wave1` in this pass: WP-03 (in addition to WP-01, 02, 04, 05, 06, 07, 08, 09, 11, 12, 13, 14).
Still in work: WP-10, 15, 16, 17, 20 (early paths).
- The bubble is merged (ADR-081). For the fast e2e test: the Ready circle is one accessibility node with the
  description "Start dictation. Double-tap to talk, double-tap again to finish" (ru: values-ru), test tag `bubble`;
  the Listening pill's node is "Finish dictation". Each face has one node ≥ 48 dp except Error (reason + action).
- UiAutomation against overlay windows (WP-03 note): after a programmatic move or a snap, wait until the accessibility
  window list shows the overlay window at the target point before injecting a press; WindowManager lags the app's
  `updateViewLayout` on a loaded emulator. `platform/overlay/tools/run_emulator_tests.sh <serial>` is WP-03's suite
  (installs `:testapps:target`, drives the hinge posture requests, pulls screenshots to `.cache/spikes/wp-03/`).
- Platform event (a) fold/unfold: the emulator posture test proves only that the bubble survives (the headless emulator
  has no display change); re-placement on the real hinge (same edge, proportional y or the stored position) is DC10.
  Assert in (a) that the bubble still exists and the dictation ends Inserted; leave the edge check to DC10.
- Dragging a working pill only moves it (no hide target); dropping the idle bubble on the hide target writes
  `Settings.hiddenUntilEpochMs` (+1 h) and shows the snackbar. A drag test may assert both.
- Wave 3: `AppContainer.feedback` is `OverlayFeedback` already; keep it when taking over `AppContainer` from WP-15.
  DEVICE_CHECKLIST: WP-03's three proposed new steps (drag a Listening pill, haptics per setting, Small size with
  TalkBack) are listed in the integration report for you to place; its DC5 / DC10 notes were added as one-liners.
