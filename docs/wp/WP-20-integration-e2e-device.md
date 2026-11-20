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
- `platform/overlay/tools/run_emulator_tests.sh` needs the **inner** AVD (`murmur_fold7_inner`): on the cover AVD
  `survivesPostureChanges` and `survivesSizeAndRotationChangesWithTheSameEdgeAndProportionalY` fail by design (no
  hinge posture, `wm size reset` is 1080). The orchestrator's internal errors are logged as `MurmurDictation
  internal_error <class> <- <cause>… at <frame>` (ADR-082, no message text); an e2e run should fail on any such line.
  A bubble that could not attach logs `bubble_retry_later display=N` and is retried on the display's next change.

## Integration notes (wave-1 integration pass 4; ADR-083 in DECISIONS)

Merged on `integration/wave1` in this pass: WP-10 (in addition to WP-01..09, 11, 12, 13, 14).
Still in work: WP-15, 16, 17, 18, 19, 20 (early paths).
- Wave 3 (`AppContainer` taken over from WP-15): keep `exits = AndroidProcessExits(context)` in `DictationDeps` and
  `DefaultBenchmarkRunner` (ADR-083).
- WP-10 is merged: the WP-14 emulator benchmark entry point (`.cache/spikes/wp-14/BenchmarkOnEmulatorTest.kt`) can now
  be committed under `app/src/androidTest/kotlin/app/murmur/e2e/` (see the pass-2 note). The real e2e run needs the
  x86_64 llama libraries: `tools/heavy nice native/llama/build_android.sh cpu-x86_64` writes
  `.cache/native/jniLibs/llama/x86_64` (clean build ≈ 7 min at load ~36; `-Pmurmur.nativeLibsDir=` to point elsewhere).
- WP-10's own suite: `am instrument -w -e class app.murmur.infer.llm.LlmServiceTest
  app.murmur.infer.llm.test/androidx.test.runner.AndroidJUnitRunner` with the model copied through run-as into
  `no_backup/models/qwen3.5-0.8b@q4_0/model.gguf` (OK 15 tests, ≈ 5 min; LFM3 with `-e artifact lfm3-1.7b@q4_0-qad
  -e timeoutScale 4`, ≈ 8 min).
- DEVICE_CHECKLIST (yours): WP-10 proposes a new step "load LFM3 on the CPU while the system is low on memory and record
  what :main sees"; with ADR-083 the expected result is `OutOfMemory(LLM)` (exit reason `REASON_LOW_MEMORY` read by
  `AndroidProcessExits`), no `UnitHealth` mark, CPU tried again on the next press. Also: an e2e assertion that
  `unit-health.json` stays empty after an lmkd kill of `:llm` during its load, if the emulator can provoke one.
- Pass-4 verification (ADR-084): in that DC step also record `ActivityManager.isLowMemoryKillReportSupported()` on the
  phone (`adb shell dumpsys activity exit-info app.murmur` shows the raw reason/status) and the `MurmurEngines`
  `load_death reason=… status=… lmk_report=… waited_ms=… oom=…` line: `waited_ms` says when the exit record arrived,
  which decides whether the 2 s poll can be shortened (ARCHITECTURE 6.4 row D). Without lmk reports the expected result
  is `lmk_report=false waited_ms=0 oom=false` and a unit mark (the documented fallback).

## Integration notes (wave-1 integration pass 5; ADR-085, ADR-086 in DECISIONS)

Merged on `integration/wave1` in this pass: main 392d313 (stock prompt default.v1) and WP-20's wave-1 part (e2e harness,
`:testapps:target`, DC8 smoke script, release checks). Still in work: WP-15, 16, 17, 18, 19, 21, and WP-20's wave 3.
- ADR-085: every stock catalog LLM uses `promptProfile` default.v1 (catalog 2026-11-20.2): `prefixTokens` 1,047-1,101,
  `nCtx` 7,168, the non-floor `memoryMb` values slightly lower. `GgufTemplates.STOCK_PROFILE` = default.v1. The shared
  `FakeModelResolver` now defaults to default.v1 / `nCtx` 7,168 / `prefixTokens` 1,047: a test that pinned the old fake
  numbers (9,216 / 3,129) or "default.v4" must follow. default.v4 / v4s stay packaged.
- ADR-086: a press holds the `:asr`/`:llm` idle exit for the whole dictation (Done re-arms 60 s; the fake host records
  `idleExitHistory`); `AsrService.probe` lists the OCR runtimes; `:app:assembleRelease` builds again and is part of the gate.
- Your wave-1 part is merged. Of `integ3-wiring.diff`, the Orchestrator idle hold (final form: a finite hold set at the
  press, not 24 h), the AsrService OCR runtime keys and the overlay release dependency are on integration (ADR-086);
  the rest (debug fake engines, WAV capture, timeout scale, status notifier and handler) is your wave-3 work with WP-15's
  `AppContainer`. The DC8 dry run should now exit 0 without any Orchestrator patch: confirm it in wave 3.
- edit10's prefix numbers change with default.v1 (about 1,047 tokens before the edit instead of 3,116); the assertion is
  on the sha, so only the report numbers move. DC2's `llm_npu_smoke.sh` runs default.v1 at n_ctx 7,168.
- Yours in wave 3 (ADR-086 item 5): the insertion ladder fix with a JVM `InsertionLadder` test (after a true but
  unverified `commitText`, read again after a grace period and accept COMMIT_TEXT when the text grew by the insertion;
  never escalate on an unreadable field after a true commit). Also: `FakeAsrEngine` should model a death during
  `loadDelayMs` (today the fake keeps loading through `FakeInferenceHost.crash()`); ask WP-11's JVM test for
  Down → Up(p2) → Crashed within one dispatch (`Orchestrator.watchDeaths` collects a conflated StateFlow), then drop
  the 1 s settle in `PlatformEventsE2eTest.secondAsrDeathFailsWithAudio`.
- Integration gate from now on: `assembleDebug testDebugUnitTest :app:assembleRelease` (your finding).
- DEVICE_CHECKLIST: your rows fold every former "Wave-1 additions" line, so that section was removed at the merge; DC2
  got the default.v1 numbers and an optional `device_prompt_bench.py` line (prompt work).

## Integration notes (wave-2 integration pass 6; ADR-087, ADR-088 in DECISIONS)

Merged on `integration/wave1` in this pass: WP-15, 16, 17, 18, 19 (wave 2 complete; every screen is in the shell, no
placeholder left). Still in work: WP-21 and your wave 3. You now take over WP-15's `app/**` paths (README index).
- `AppContainer` (ADR-087 item 1): WP-15's container with `DebugWiring`/`Replacements`/`FakeData` (src/debug, release
  returns none). Kept and must stay: `fake?.benchmarkRunner ?: DefaultBenchmarkRunner(… specs = resolver.benchSpecs …,
  exits)`, `exits` in `DictationDeps`, `OverlayFeedback`, `onInternalError = internalErrorLine`, `ensureDevice =
  deviceProfile::ensure` in `ModelsDeps`, `appScope` in `HistoryDeps`. `AppContainer.start()` starts retention (real
  data only) and `StatusNotificationController` (status notifier + `MurmurApp.onStatusAction`, which already calls
  `NotificationStart.start`). The single `PermissionMonitor` is `app/…/wiring/AndroidPermissionMonitor.kt`.
- Debug switches (files in `filesDir`, read once when the container is built): `debug-fake-engines` = FakeEngines **plus
  `FakePromptRenderer` and `PassThroughGuard`** with the real data side and the **real resolver** (so the fake engines
  only run once a pack is "installed"); `debug-fake-container` = `seeded|fresh[:fold7|:emulator]` = the whole data side
  in memory (also fake engines). `MurmurTestRunner -e murmurFake seeded|fresh|off` writes/deletes the container flag;
  `run_emulator_e2e.sh fake_mode` now removes it before each suite (pass 6). Decide in wave 3 for the fast suite:
  either the engines-only mode gets a fake resolver (integ3's `fakeResolverOrNull`) or the suite seeds installs.
  ADR-089 (pass-6 verification R1): in the engines-only mode the real `DefaultBenchmarkRunner` runs over FakeEngines,
  but benchmark records and unit health are in memory and the device profile is cached in
  `debug-fake-engines-device-profile.json` (`Replacements.engineRecords`); keep this if you add a fake resolver, and
  assert unit health / benchmark state through `E2e.container`, never by reading the real files. The Settings
  "Use fake engines" switch (`Settings.debugFakeEngines`) has no consumer yet: wire it to the flag file or remove it.
  `EnginesOnlyWiringTest` (shell, `-e murmurFake off`) guards the separation.
- Still yours from `integ3-wiring.diff` (not applied, ADR-087 item 11): `WavFileCapture` when `files/debug-capture.wav`
  exists, with the `CallWatch`/`AndroidAudioModeSource` call injection; `BinderEngines(…, { BinderTimeouts(scale =
  settings.debugTimeoutScale) })`; `debugIgnoreMemoryGate` applied to the real `MemoryGate`. The status notifier hunk is
  superseded by `StatusNotificationController`.
- Shell instrumented tests (`app/src/androidTest/kotlin/app/murmur/shell/**`, WP-15's, stays shell-owned): runner
  `.cache/spikes/integration/wave2/run_shell_tests.sh <serial> all` in the integration tree (dismisses the SystemUI ANR
  dialog; reuse `dismiss_anr` in your e2e runner). All pass on both AVDs. `RealContainerWiringTest` (`-e murmurFake off`)
  now runs the real `DefaultBenchmarkRunner`: a not-installed model fails and stores nothing.
- Sample data: `DebugWiring.seed` writes history entries without audio (the detail shows "Recording not available"
  instead of the player in the frames) and the WP-12 metrics week (Dashboard "Today 184 words", the frame's 1,284 is
  its own sample). If you want shots closer to the frames, seed recordings through `Wp16HistoryFixture.entries(audio)`.
- Dashboard "Typed into the app … most in com.termux": the label comes from the live `AppInfoProvider`, which falls back
  to the package name for an app that is not installed (the emulator has no Termux). History rows show the stored label.
  Consider the stored `AppRef.label` as the fallback (WP-17 code, `DashboardDeps.apps`).
- ADR-088: tone per app now reaches the cleanup (`Cleaner.tone`); DC8's report may add the recorded `cleanup.tone`.
- Device steps proposed by WP-15..19 that did not fit an existing DC row (put them where they belong): History playback
  with music playing / an incoming call / Home (audio focus and ON_STOP pause, WP-16); History on both real displays
  (real launcher icons, insets: emulator inner taskbar 32 dp vs the frame's 20 dp, cover status bar 24 vs 36 dp, Copy
  without a duplicate toast, share sheet) and a real-hinge fold with an entry open (WP-16); Dashboard with real history
  on both displays (launcher icons in "Where you dictate", the range chip, the typing-speed sheet slides out after a
  preset and saves it, WP-17); first visit to Models right after onboarding (skeletons for a moment, then Parakeet on
  NPU, no blind download, WP-18); a real "Failed to load on NPU" → the CPU-version button, then its progress and Pause
  (WP-18); Tone per app and "Hidden in" with a full app list (real icons after a short load, no stutter, WP-19);
  optional: the Settings snackbars on a full disk (WP-19).

## Integration notes (wave-3 integration pass 7; ADR-090 in DECISIONS)

Merged on `integration/wave1` in this pass: main 039a826 (ML data/docs) and your wave 3 (`wp/wp-20w3` cf27470). Still in
work: WP-21. All your wave-3 decisions are accepted as ADR-090: `StoredPrefillSpeeds` as `BinderEngines.prefillTps`,
binder timeouts scaled by `debugTimeoutScale` in debug only, `FakeEnginesSwitch` debug-only (flag file decides the
process mode, the switch applies at the next start), `Replacements.resolver` / `capture`, the ladder's 1,500 ms budget
inside the 2 s DELIVERY timer, `MurmurTestRunner` in both scripts, `AppContainer.start()` start-up work (probe at
`:main` start).
- Integration fix (ADR-090 item 8): `real-log-privacy` failed on the merged tree: `AppTabs.history` searched rows with
  `By.descContains(<start of the transcript>)`, and UiDevice logs every selector under the app's uid. Your runs had the
  same lines; the check missed them because `grep … | grep -q` under `pipefail` reads the SIGPIPE as no match. Now the
  rows are compared in code (`shown`, `scrollToPrivate`) and the check greps a file (`real.logcat-app.txt`); the ANR
  check got the same pipefail fix. Keep dictated text out of selectors and log lines in every new e2e test.
- Integration fix: the switch's sub-line (WP-19's res, en + ru) now says the change applies at the next app start
  (your note to WP-19).
- Still open, not yours alone: WP-11's JVM test for Down → Up(p2) → Crashed within one dispatch (`watchDeaths` collects
  a conflated StateFlow; if Up(p2) is conflated away, `lastUp` is the old pid and the death is ignored) and the
  `FakeAsrEngine` death during `loadDelayMs`; after both, drop the 1 s settle in `secondAsrDeathFailsWithAudio`.
- Not done in wave 3 (optional, pass-6 notes): seeded recordings for the History frames, Dashboard label fallback to the
  stored `AppRef.label`; `tools/emulator/boot.sh` has no guest-memory switch (not needed: the inner AVD already has 4 GB).
- After WP-21 merges: rerun the DC8 dry run and the `real` suite on the inner AVD (the `:asr` probe gains the ort-qnn
  runtime keys and the catalog moves to 2026-11-20.3).

## Integration notes (integration pass 8; ADR-092 in DECISIONS)

Merged on `integration/wave1` in this pass: main 1dd1848 (ML training) and WP-21 (`wp/wp-21` d518381). Nothing is in
work any more. Changes in your files: `tools/e2e/check_release_apk.sh` also fails on `libonnxruntime_providers_qnn.so`
(WP-21's QNN plugin EP) without `-Pmurmur.qnn`, and `--control` requires it with the flag. DEVICE_CHECKLIST: DC4 gained
WP-21's rows (26 golden pages per stage, `det_unit`, GigaAM vs CPU, NPU load ms and memory), DC8 the probe duration and
`probe_retry`, DC9 the `det_unit` of an adopted NPU OCR run, and the legal list the plugin EP. The catalog is
2026-11-20.3. The `real` suite and the DC8 dry run were rerun on the inner AVD in this pass (see the pass-8 notes).
