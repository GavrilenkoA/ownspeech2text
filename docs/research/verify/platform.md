# Platform verification: accessibility, overlay, mic, screenshot, insertion, processes, downloads

Role: Android platform engineer. Date: 2026-11-18. Spike: `spikes/platform/` (`:app` = `app.murmur.spike`, `:target` = `app.murmur.spike.target`, the "other app").
Every evidence path below is relative to `spikes/platform/evidence/` unless it says otherwise.

Status labels:
- **VERIFIED**: I ran it, or I inspected the saved output.
- **REFUTED**: same standard, and it contradicts the claim.
- **UNVERIFIABLE-HERE**: something is missing on the server; the reason is stated.
- **UNVERIFIED-ON-DEVICE**: needs the Fold7; `tools/device/platform_smoke.sh` covers it.

Emulators used:
- API 36: `google_apis` x86_64, AVDs `murmur_fold7_cover` (1080×2520 @420) and `murmur_fold7_inner` (1968×2184 @420).
- API 37: `aosp_atd` (`sdk_slim_x86_64 17/CE2A.260420.051.A3`) in `.cache/spikes/platform/avd`, with `-gpu guest`.

---

## 1. Decisions

1. **The design in `docs/research/android-platform.md` works on stock Android 16 and 17**, with the corrections in §4. Each mechanism below was proven on the emulator from a single non-tool accessibility service (`isAccessibilityTool=false`) with **no `SYSTEM_ALERT_WINDOW`**:
   - the bubble (`TYPE_ACCESSIBILITY_OVERLAY`, Compose, drag, rotation, size change);
   - mic start from a bubble tap over another app;
   - window screenshot;
   - context read;
   - the insertion ladder into EditText, Compose, WebView and Chrome;
   - clipboard write;
   - the `:infer` process lifecycle;
   - a UIDT download with resume.
2. **Screenshot for OCR: use `takeScreenshotOfWindow(focused TYPE_APPLICATION window)`, not `takeScreenshot(display)`.**
   - The window capture contains only the app's pixels: no keyboard, no Murmur bubble, no status bar, no nav handle (`ctx2_shots/ime_up_window_552907.png` vs `ime_up_display_554990.png`).
   - Exclusion rectangles are then unnecessary for IME, overlay and bars. Keep them only for the display-capture fallback.
   - The capture is still full-window size: the area under the keyboard comes back as the app's background, not as keyboard pixels.
   - **Minimum interval is 333 ms per display and per window, tracked separately.** It is measured: 330 ms → error 3, 340 ms → OK (`shot_interval.log`). In a second sweep 330 ms passed (`.cache/spikes/platform/device_dryrun2.txt`), so the boundary is 333 ms ± scheduling jitter. A window capture followed by a display capture is never throttled.
   - **`getWindows()` can transiently omit the app window.** In the dry run just after boot, under load, the window list at tap time held only the bubble. The target id was -1 and the window capture failed with error 5 (`.cache/spikes/platform/device_dryrun2.txt`, "screenshot ~400 ms"). Production rule: resolve the target window again at capture time. If it is still missing, use `findFocus(FOCUS_INPUT).windowId`, then the display capture plus exclusion rects.
   - **On a `FLAG_SECURE` window**, the window capture fails with error 6 (`ERROR_TAKE_SCREENSHOT_SECURE_WINDOW`), and the display capture succeeds but is black (centre luma 5). Treat both as "no screen context".
3. **Mic:** start a `microphone` FGS on the bubble tap, then record in the accessibility-service process. The FGS is not needed for legality: recording without it was never silenced in 40 s with another app in front (API 36 and 37). Keep it anyway: it gives the stop control and the A17 audio-focus requirement, and it costs 8–90 ms. The FGS gets while-in-use access through the accessibility binding:
   - `code:UID_VISIBLE` while the bubble is visible;
   - `code:ACTIVITY_STARTER allowWiu:52` with the bubble detached.
4. **Insertion ladder** order: `commitText` (accessibility IME) → `ACTION_PASTE` → `ACTION_SET_TEXT`.
   - All three verified on EditText, Compose `TextField`, WebView input/textarea/contenteditable and Chrome.
   - **Read back with `AccessibilityInputConnection.getSurroundingText`, not the node text.** WebView node text was stale 150 ms after a successful paste (`e_insert_matrix.log`, web-input paste: node `"abc "`, surrounding `"abc Murmur_ok"`, target echo `"abc Murmur_ok"`).
5. **Focused field (CONTEXT_V2 `field`):** take `EditorInfo` from the accessibility IME and the node from `findFocus(FOCUS_INPUT)`, with a BFS fallback. Compose and WebView need the fallback: `findFocus` returns the host `View`/`WebView`.
   - `before_cursor` = `getSurroundingText(1000, 0, 0)` cut at `selectionStart`, measured at 0–8 ms.
   - Password comes from `node.isPassword || inputType` variation.
   - Code: `app/.../ContextV2.kt` + `SpikeA11yService.ctxV2()`.
6. **The manifest needs `<queries>` for `MAIN/LAUNCHER`** (no `QUERY_ALL_PACKAGES`). Without it, `getApplicationInfo(<foreground pkg>)` throws `NameNotFoundException` for every user-installed app, so `app.label` is missing (`ctx2_label_without_queries.log` vs `ctx2_label_with_queries.log`). The launcher (HOME) stays invisible even with it; fall back to `AccessibilityWindowInfo.getTitle()`.
7. **minSdk 34, target/compile 37.** Reasons in §5.
8. **Two processes, as designed:** `:main` (service, overlay, mic, UI, downloads) and `:infer` (native models).
   - A native SIGSEGV in `:infer` left the service and the bubble alive. `:infer` was recreated in about 2 s on rebind.
   - A death of `:main` takes the bubble away for **11 s**: `ActivityManager: Scheduling restart of crashed service .../.SpikeA11yService in 11000ms` (`g_uidt_resume.log`). Nothing that can crash natively may run in `:main`.
9. **Enabling the service.**
   - An adb install (`packageSource=1`, `initiatingPackageName=com.android.shell`) is **not** ECM-guarded on AOSP: the normal "full control" dialog appears (`enable_01_after_toggle.png`).
   - A file-manager install (`packageSource=3`) gets "App was denied access" (`h_restricted_setting_file_install.png`) until App info → ⋮ → **Allow restricted settings** (`h_allow_restricted_settings_menu.png`).
   - For automation: `adb shell settings put secure enabled_accessibility_services app.murmur/<service>` works even with the restricted-settings app-op set to `ignore`.

---

## 2. Facts: device and platform (primary sources where they exist)

| Fact | Value | Status / source |
|---|---|---|
| Fold7 at launch | Android 16, One UI 8.0; Snapdragon 8 Elite for Galaxy; 12 GB RAM (16 GB on 1 TB) | Samsung UK comparison page (fetched by engineer #2, saved in transcript) |
| Fold7 today | One UI 9 / Android 17 (API 37). Stable rollout started late Sept 2026 (Korea), US carriers early Oct 2026. | Secondary only: 9to5Google 2026-10-05, ghacks, droid-life. No Samsung newsroom post found. |
| Next 12 months | One UI 9.x maintenance releases. The next major (Android 18) is not expected before late 2027 [inference from the A16 → A17 cadence]. Samsung's policy is 7 OS generations / 7 years. | Policy is secondary only (SamMobile); the Samsung policy page was not found. Plan for API 37, and test on the API 37 emulator before every release. |
| Cover display | 6.5", 2520×1080 px | Samsung UK + India pages |
| Inner display | 8.0", 2184×1968 px | same |
| Default density, dp | Unknown for the real phone. With the AVDs' 420 dpi: cover = 411×960 dp (`sw411`); inner = 750×832 dp (`sw750`), from `config_changed` in `a_display_matrix.log`. | UNVERIFIED-ON-DEVICE (`wm density` in the device script) |
| A13+ restricted settings (ECM) | Sideloaded apps cannot enable accessibility until App info → More → Allow restricted settings | Google Help 12623953. VERIFIED on the API 36 emulator for a file install; adb install not guarded (§1.9). |
| A14 typed FGS + WIU | A `microphone` FGS needs `RECORD_AUDIO` at creation. A background-started FGS gets no mic access unless an exemption applies. "Standard FGS: services started while the app is visible or granted background activity launch capability are granted WIU access." | developer.android.com fgs/restrictions-bg-start and A17 bg-audio page. The accessibility binding grants BAL capability: VERIFIED (`code:ACTIVITY_STARTER allowWiu:52`, API 36 and 37). |
| A15 overlay FGS exemption | "If your app targets Android 15 or higher, it must have the SYSTEM_ALERT_WINDOW permission *and* the app must currently have a visible overlay window." Not relevant: Murmur uses no SAW. | developer.android.com |
| A16 `accessibilityDataSensitive` | Views marked sensitive (and every `setFilterTouchesWhenObscured(true)` view) are hidden from non-tool services. Setting `isAccessibilityTool=true` falsely gets the app rejected by Play and blocked by Play Protect. | developer.android.com blog, 2025-12-11 |
| A17 Advanced Protection | "Restricts accessibility services to verified accessibility tools"; blocks unknown-source installs; Play Protect forced on. Murmur cannot run under AAPM. `AdvancedProtectionManager.isAdvancedProtectionEnabled()` (API 36) was callable from the spike with only the manifest permission; it returned `false`. | Google Help 16339980; call VERIFIED (`main_status` `"aapm":"false"`) |
| A17 background audio | Playback, **audio focus** and volume need a visible activity or a non-`SHORT_SERVICE` FGS. When targeting 37, a background app needs an FGS **with WIU**. Recording is not in the affected list. | developer.android.com/about/versions/17/changes/bg-audio |
| A17 memory limiter | `ApplicationExitInfo` `REASON_OTHER` with `"MemoryLimiter:AnonSwap"`; `am memory-limiter status`. Absent on the emulator (`Unknown command: memory-limiter`, `.cache/spikes/platform/device_dryrun.txt`). | developer.android.com behavior-changes-all |
| Clipboard | A background write from the service works: the target pasted `clip_from_service_42`. A read is denied: `ClipboardService: Denying clipboard access to app.murmur.spike, application is not in focus` (`e_clipboard_write.log`). | VERIFIED on API 36; the write also on API 37 (`api37/run2`) |
| Play Protect enhanced fraud protection | Blocks internet-sideloaded apps requesting accessibility in some countries | Secondary only. adb install sidesteps it [inference]. |

---

## 3. What was proven on the emulator (spike items a–h + CONTEXT_V2)

| # | Item | Result | Evidence |
|---|---|---|---|
| a | Compose bubble as `TYPE_ACCESSIBILITY_OVERLAY` (2032) over another app, no SAW, draggable | VERIFIED API 36 and 37 (`ty=ACCESSIBILITY_OVERLAY`) | `a_bubble_over_target_app.png`, `a_bubble_after_drag.png`, `api37/run2/summary.txt` |
| a | Survives rotation 0/90/180/270 and a cover → inner → cover size change; re-clamped to the same edge and proportional y | VERIFIED. The 180° flip arrives only through `DisplayListener.onDisplayChanged`, not `onConfigurationChanged`. | `a_display_matrix.log`, `a_display_rot*.png`, `a_display_inner_*.png` |
| a | Real fold/unfold (same logical display or a second one; BLAST stall with a GONE root) | UNVERIFIED-ON-DEVICE. `wm size` only simulates the metrics. | device script "fold/unfold events" |
| b | FGS `microphone` started from a bubble tap with another app in front | VERIFIED. 8–90 ms tap → `fgs_started`; first buffer 67–209 ms; `isClientSilenced=false`; `types=0x80`. | `b_mic_fgs_bubble_tap.log`, `b_fgs_dumpsys_bubble_visible.txt` (`code:UID_VISIBLE`), `api37/run2/summary.txt` |
| b | FGS started with the bubble removed (binding only) | VERIFIED: allowed with WIU (`code:ACTIVITY_STARTER allowWiu:52`) | `b_fgs_dumpsys_bubble_detached.txt`, `api37/run2` |
| b | Recording without FGS from the service while the launcher is in front | VERIFIED: not silenced for 40 s (API 36), 6 s (API 37) | `b_mic_a11y_nofgs_40s_launcher.log`, `api37/run2/summary.txt` |
| b | Control: an app without the service records, then goes to the background | VERIFIED: `isClientSilenced` flips to true after about 7 s (API 36 and 37). The detector works, and the service is what prevents silencing. | `b_mic_control_activity_to_background_service_off.log` |
| b | Non-zero real audio samples | UNVERIFIABLE-HERE. The host has no audio stack. A private PulseAudio server (`murmur-pulse` container, `module-sine-source`) works from the host (libpulse read peak 16383), but the emulator either does not connect (`PULSE_SERVER`) or crashes at audio init (`QEMU_PA_SERVER`; crashpad dumps in `/tmp/android-private/emu-crash-37.2.13.db`). Samples on the emulator are near zero (peak 8). | `b_mic_tone_injection_attempt.log`; DEVICE line in needsFromOwner |
| c | Screenshot about 400 ms after the tap | VERIFIED: `msSinceTap` 404–432 ms; API call 6–70 ms; capture + ARGB copy + PNG 260–1155 ms (PNG dominates; skip PNG in production) | `b_mic_fgs_bubble_tap.log`, `shot_interval.log` (`shot_pipeline`) |
| c | Rate limit | VERIFIED: 333 ms per display and per window, independent (§1.2) | `shot_interval.log` (44 pairs), `.cache/spikes/platform/device_dryrun2.txt` |
| c | Target window missing from `getWindows()` right after a cold start under load | Observed once: window capture → error 5 (§1.2) | `.cache/spikes/platform/device_dryrun2.txt` |
| c | `FLAG_SECURE` | VERIFIED: window → error 6; display → OK but black. API 37: same error codes. | `c_shot_suite_flag_secure.log`, `api37/run2/c_shot_suite_SecureActivity.log` |
| c | API 37 screenshot content and timing | UNVERIFIABLE-HERE. On the API 37 ATD image every pixel readback (`adb screencap` and our Bitmap copy) aborts in `mapper.ranchu` (`Assertion failed: !rcEnc->featureInfo()->hasReadColorBufferDma`) and takes down system_server; frames are black. This is an emulator gralloc bug, not Android 17. | `api37/run2/tombstones.txt` |
| d | Foreground app, focused editable, on-screen text from the tree | VERIFIED. Target = the focused `TYPE_APPLICATION` window. `rootInActiveWindow` is wrong at tap time. Tree walk ≤ 150 ms. | `b_mic_*` `context` lines |
| CTX2 | `app` / `field{kind,hint,before_cursor,password}` / IME and overlay bounds | VERIFIED on EditText (empty+hint, prefilled, multiline caret 8 → `"Line one"`, password → no `before_cursor`), Compose, WebView input and contenteditable. IME window `input_method` bounds `[0,1343,1968,2184]`; own overlay `[0,600,168,768]`; status bar `[0,0,1968,63]`; 7–115 ms total. | `ctx2_*.log`, `.cache/spikes/platform/ctx2_run.txt` |
| e | Insertion ladder × surfaces | VERIFIED: commit/paste/settext all land, with independent read-back by the target app (TGT log), in EditText, Compose, WebView ×3, Chrome page + contenteditable | `e_insert_matrix.log`, `e_insert_chrome.log` |
| e | Clipboard from the service | VERIFIED (write OK, read denied, §2) | `e_clipboard_write.log` |
| f | `:infer` bound on demand, idle unbind → `killProcess` | VERIFIED: 512 MB native load, PSS 557 815 KB, then gone after idle; service alive. API 37 the same. | `f_infer_idle_kill.log`, `api37/run2/summary.txt` |
| f | Native and Java crash in `:infer` | VERIFIED: `infer_disconnected` → rebind → `infer_created`; `pong connected:true overlays:1` | `f_infer_native_crash.log`, `f_infer_java_crash_rerun.log` |
| g | UIDT from a visible activity and **from the background service** | VERIFIED: `RESULT_SUCCESS` both ways (API 36 and 37) | `g_uidt_schedule_from_service.log`, `api37/run2` |
| g | Resume after the process is killed | VERIFIED: 206 from byte 90 203 631 via `us.aws.cdn.hf.co`; SHA-256 matches; atomic rename | `g_uidt_resume.log` |
| h | Enable flow and force-stop | VERIFIED: force-stop removes the service from the enabled set; adb `settings put` re-enables; ECM behaviour as in §1.9 | `h_*.log`, `h_*.png`, `enable_0*.png` |

---

## 4. Verification of `docs/research/android-platform.md`

| Claim (section) | Status | Evidence |
|---|---|---|
| All §2–§8 APIs exist with the stated levels (takeScreenshotOfWindow 34, attachAccessibilityOverlayToDisplay/Window 34, getInputMethod/onCreateInputMethod 33, AccessibilityInputConnection.commitText/getSurroundingText 33, FLAG_INPUT_METHOD_EDITOR 33, TYPE_ACCESSIBILITY_OVERLAY 22, setCanPlayMoveAnimation 34, FOREGROUND_SERVICE_MICROPHONE 34, JobInfo.setUserInitiated 34, JobService.setNotification/updateTransferredNetworkBytes 34, AdvancedProtectionManager 36, QUERY_ADVANCED_PROTECTION_MODE 36, ClipDescription.EXTRA_IS_SENSITIVE 33, getCpuHeadroom 36) | VERIFIED | `api_check_android-37.2.txt` (`tools/api_check.py` on `platforms/android-37.2/data/api-versions.xml`). The spike compiles at compileSdk 37.2, minSdk 34, lint 0 errors (`.cache/spikes/platform/build_lint.log`). |
| `AudioRecord.registerAudioRecordingCallback` | VERIFIED: inherited from `AudioRecordingMonitor` (API 29). The checker lists it under the interface. | used in `Recorder.kt` |
| `ProfilingTrigger.TRIGGER_TYPE_ANOMALY` | VERIFIED: since 37.0 | api check |
| `TRIM_MEMORY_RUNNING_*` / `COMPLETE` are no longer delivered | Consistent: deprecated since 35 in the SDK | api check |
| §0.3 / §3.1 the visible overlay gives `REASON_UID_VISIBLE` for the mic FGS | VERIFIED (`code:UID_VISIBLE`) | `b_fgs_dumpsys_bubble_visible.txt` |
| §4.1.2 the binding gives the process WIU; Ramblr records with no FGS | VERIFIED: not silenced for 40 s without FGS | `b_mic_a11y_nofgs_40s_launcher.log` |
| §4.1.3 the binding is a second FGS-start path | VERIFIED (`ACTIVITY_STARTER allowWiu:52` with the bubble detached) | `b_fgs_dumpsys_bubble_detached.txt` |
| §4.1.4 A17 audio-focus rule; recording not affected | VERIFIED from the primary doc. Not exercised on the emulator. | bg-audio page |
| §2.2 do not trust `rootInActiveWindow` at tap time; use the focused `TYPE_APPLICATION` | VERIFIED: windows at tap list the bubble first | `b_mic_fgs_bubble_tap.log` context |
| §2.3 the IME signal covers Compose/WebView where `findFocus` returns no editable | VERIFIED: `findFocus` → `android.view.View` (Compose) or `WebView`, while `EditorInfo` is correct. The editable node also exists via BFS. | `ctx2_compose_prefill.log`, `ctx2_webview_*.log` |
| §2.3 `getSurroundingText` returns real chars, never the hint | VERIFIED: empty field with hint → `""`; the node returned the hint text with `isShowingHintText=true` | `ctx2_plain_empty.log` |
| §2.4 `takeScreenshotOfWindow` excludes overlay and keyboard | VERIFIED: also excludes the status bar and the nav handle | `ctx2_shots/` |
| §2.4 rate limit 333 ms, per display and per window | VERIFIED. Correction: the two limits are independent; window then display at 0 ms both succeed. Retry after ≥ 340 ms, not 350 ms (350 also works). | `shot_interval.log` |
| §2.4 secure window → error 6; display capture black | VERIFIED | `c_shot_suite_flag_secure.log` |
| §2.4 a full inner frame is about 17 MB | VERIFIED: 1968×2184 ARGB_8888 = 17.2 MB | `ctx2_shots` dimensions |
| §2.5 ladder A/B/C all viable; WebView `ACTION_PASTE` "reports success but inserts nothing" | Partly REFUTED on stock WebView/Chrome: paste inserted every time (target echo). The node text is just stale, so verify via `getSurroundingText`. The Ramblr case may be app-specific. | `e_insert_matrix.log`, `e_insert_chrome.log` |
| §2.5 SET_TEXT moves the cursor to the end; fix with SET_SELECTION | VERIFIED: both called, final text correct | `e_insert_matrix.log` |
| §3.2 Compose in the overlay with manual owners | VERIFIED: works; `OverlayHost.kt` | `a_*` |
| §3.5 a 180° rotation gives no config change; use `DisplayListener` | VERIFIED | `a_display_matrix.log` (rot1 → rot2: `display_listener` reclamp before the config change) |
| §5.1 adb install guarded? `[U]` | Resolved: **not guarded on AOSP 36** (packageSource=1, installer null, initiator shell). A file install (packageSource=3) is guarded. Samsung: UNVERIFIED-ON-DEVICE. | `enable_01_after_toggle.png`, `h_restricted_setting_file_install.png` |
| §5.1 detect restricted settings with `unsafeCheckOpNoThrow("android:access_restricted_settings")` | **REFUTED on API 36**: it throws `SecurityException: verifyIncomingOp: uid … does not have any of {MANAGE_APPOPS, GET_APP_OPS_STATS, MANAGE_APP_OPS_MODES}`. `OPSTR_ACCESS_RESTRICTED_SETTINGS` is also not in the public SDK. Detect instead: "service enabled in settings, but `onServiceConnected` never ran" + install source from `getInstallSourceInfo().packageSource`. | `b_mic_control_*.log` (`restricted_op_check_failed`), api check |
| §5.1 user steps App info → ⋮/More → Allow restricted settings, shown after the dialog once | VERIFIED on AOSP 36 (menu entry present after the denial) | `h_allow_restricted_settings_menu.png` |
| §5.3 `isAdvancedProtectionEnabled` needs `QUERY_ADVANCED_PROTECTION_MODE`, protection level `[U]` | VERIFIED callable by a normal app that declares it (granted at install) | `main_status` `"aapm":"false"` |
| §5.7 force-stop disables the service | VERIFIED | `h_force_stop_and_adb_enable.log` |
| §5.7 a crash does not disable it; restart with back-off | VERIFIED: restart scheduled in 11 000 ms | `g_uidt_resume.log` |
| §6 background clipboard write allowed, read denied | VERIFIED | `e_clipboard_write.log` |
| §7.4 UIDT scheduled from the visible Model Hub | VERIFIED. Also works from the background service. Docs say "visible"; the binding's process state is enough on AOSP 36/37. Samsung: UNVERIFIED-ON-DEVICE. | `g_*` |
| §7.4 Range resume against the HF CDN, 206, `X-Linked-*` | VERIFIED: 206 resume and SHA-256. HF headers not re-checked here. | `g_uidt_resume.log` |
| §8.1 `:infer` idle kill returns memory; native crash isolated | VERIFIED (CPU heap). NPU/dmabuf release: UNVERIFIED-ON-DEVICE. | `f_*` |
| §13 "kvm not accessible, emulator unaccelerated" | Outdated: the emulator runs with `-accel on` through `sg kvm` (`tools/emulator/boot.sh`) | boot logs |
| §1 Fold7 runs One UI 9 / A17; displays 2184×1968 and 2520×1080 | Displays VERIFIED (Samsung pages). One UI 9 rollout: secondary sources only. | §2 |
| §0.2 `isAccessibilityTool=true` is rejected by Play / blocked by Play Protect for non-assistive apps | VERIFIED (primary blog) | §2 |
| §4.1.6 CDD concurrency rule for the accessibility VOICE_RECOGNITION client | Not re-checked; needs the device for a real concurrent-recorder test | — |
| §3.2 never `View.GONE` the overlay root (fold BLAST stall) | UNVERIFIED-ON-DEVICE; the spike follows the rule (`alpha + FLAG_NOT_TOUCHABLE`) | `OverlayHost.kt` |

---

## 5. minSdk: 34

- Every API the design needs is at most 34. That covers the window screenshot, accessibility overlays on display/window, the typed microphone FGS, UIDT, the accessibility IME (33) and `setCanPlayMoveAnimation`.
- The 36+ calls are `AdvancedProtectionManager` and `SystemHealthManager.get{Cpu,Gpu}Headroom`. They need two `SDK_INT` guards. Lint `NewApi` flags them at minSdk 34; the spike shows the pattern.
- minSdk 36 buys nothing on the Fold7, which is already on 37. It only excludes Android 14/15 phones at zero saving.
- Testing: API 36 `google_apis` is the reliable emulator here. API 37 ATD works for everything except pixel readback (§3 c). A `google_apis` API 37 image (about 4 GB) would close that gap when disk allows.

## 6. Process layout constraints (platform side)

1. `:main` holds the accessibility service, overlay, `AudioRecord` + `RecordingFgs`, UI, Room/DataStore and `ModelDownloadJob`.
   - No native model code, ever: a `:main` crash hides the bubble for 11 s, and repeated crashes back off further.
   - Force-stop disables the service until the user re-enables it.
2. `:infer` uses `android:process=":infer"`, bound with `BIND_AUTO_CREATE | BIND_IMPORTANT` from the service context.
   - Unbind after idle. `onUnbind` frees runtimes, then `Process.killProcess(myPid)`.
   - The crash handler in `:infer` must call `killProcess` quietly (no crash dialog; `f_infer_crash_dialog.png` shows the dialog otherwise).
   - The client treats `onServiceDisconnected` / `DeadObjectException` as "retry once".
3. Screenshot pixels: capture in `:main`, downscale to the OCR input, then hand to `:infer` by `SharedMemory`. Never send the 17 MB full frame or PNG-encode on the dictation path (the PNG cost is 0.26–1.2 s on the emulator).
4. The UIDT job runs in `:main`. Progress is the `.part` length on disk, so it survives process death.

## 7. Code to lift as-is from `spikes/platform/app/src/main/kotlin/app/murmur/spike/`

| File | What | Notes |
|---|---|---|
| `OverlayHost.kt` | Compose-in-accessibility-overlay host: lifecycle, saved-state and VM owners; `TYPE_ACCESSIBILITY_OVERLAY` LayoutParams; drag via `updateViewLayout`; edge snap; proportional re-clamp on config/display change; hide via alpha + `FLAG_NOT_TOUCHABLE` | One instance per displayId |
| `ContextV2.kt` | CONTEXT_V2 `field.kind` from inputType/imeOptions, password detection, window list with bounds, exclusion rects, `before_cursor` from the node | Pure functions |
| `SpikeA11yService.kt`: `targetWindow()`, `focusedEditable()`, `ctxV2()`, `shotOnce()`, `tryMethod()`/`insert()`, `setClip()`, `SpikeIme` | Target-window choice, focus with BFS fallback, v2 context, screenshot to `ARGB_8888`, the insertion ladder with `getSurroundingText` read-back | Drop the broadcast `cmd` receiver (test-only, exported) |
| `Recorder.kt` | 16 kHz mono PCM16 `VOICE_RECOGNITION` on a `URGENT_AUDIO` thread, `AudioRecordingCallback` + `isClientSilenced`, first-buffer latency | Stream to file/pipe instead of counting |
| `RecordingFgs.kt` | `startForeground(…, FOREGROUND_SERVICE_TYPE_MICROPHONE)` with `FOREGROUND_SERVICE_DEFERRED` and a failure callback | — |
| `InferService.kt` (`InferService` + `InferClient`) | On-demand bind, idle unbind, self-kill, crash isolation | Replace Messenger with AIDL |
| `DownloadJob.kt` | UIDT job: Range resume from `.part`, SHA-256, atomic rename, `setNotification(DETACH)`, `updateTransferredNetworkBytes` | Add per-attempt re-resolve via huggingface.co, and fsync |
| `app/src/main/AndroidManifest.xml` | Permission set without SAW; `<queries>` LAUNCHER; service declarations | — |
| `res/xml/spike_accessibility.xml` | Same as the research doc's §2.1 | — |

## 8. Reproduce

- Build: `cd spikes/platform && ../../tools/heavy ./gradlew --no-daemon --max-workers=6 assembleDebug`.
- Emulator, API 36: `MURMUR_EMU_EXTRA="-no-audio" tools/heavy spikes/platform/tools/boot_audio.sh murmur_fold7_inner 5560 8560`. Add `-read-only` when another instance of the same AVD runs.
- Emulator, API 37: `ANDROID_AVD_HOME=.cache/spikes/platform/avd MURMUR_EMU_GPU=guest …boot_audio.sh murmur_api37_atd …`.
- Runners in `spikes/platform/tools/`:
  - `insert_matrix.sh`, `chrome_matrix.sh`, `mic_matrix.sh`, `display_matrix.sh`;
  - `ctx_v2_probe.sh` (CONTEXT_V2 + interval sweep);
  - `run_api37.sh` (`API37_RUN=<subdir>`).
- Phone: `tools/device/platform_smoke.sh` (interactive; `NONINTERACTIVE=1` for an emulator dry run). It prints a markdown report to `.cache/device/`.
