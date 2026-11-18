# WP-02 Accessibility service, screen capture, insertion (`:platform:a11y`)

Wave 1. Read first: `docs/wp/README.md`; `docs/research/verify/platform.md` (all; it is the evidence you build on);
ARCHITECTURE.md §4.1, §4.4 (accessibility rows), §5.2 rows 3, 13, 19, 52, §9.1, §9.2, §11, §14; `docs/ml/CONTEXT_V2.md`;
DESIGN.md §5.2 (when the bubble shows), §6.1 `ob-restricted`; DECISIONS ADR-021, ADR-027, ADR-054, ADR-056.
Code to lift: `spikes/platform/app/src/main/kotlin/app/murmur/spike/{SpikeA11yService,ContextV2}.kt` (functions
`targetWindow()`, `focusedEditable()`, `ctxV2()`, `shotOnce()`, `tryMethod()/insert()`, `setClip()`, `SpikeIme`).
Do not ship its exported broadcast receiver.

## Goal

The accessibility service that knows the target field, takes the window screenshot, inserts the text and feeds the
bubble's visibility policy, behind the `TargetReader` / `Inserter` ports of `:core:dictation`.

## Owns

`platform/a11y/**`.

## Contracts

Implement: `TargetReader` (`snapshot()`, `snapshotAfterShade(timeoutMs)`, `screenshot()`, `current()`), `Inserter`
(`insert()`, `copyToClipboard()`) from `core/dictation/.../Ports.kt`; produce `A11ySnapshot` / `FieldSnapshot`
(`core/context/.../Snapshot.kt`). `RestrictedSettingsProbe` (exists, tested: `likelyBlocked(returnedFromA11ySettings,
connectedNow, packageSource)`, `enabledButDead`).
Consume: `A11yServiceHost` (implemented by `MurmurApp`: `onServiceState(false)` also calls
`DictationOrchestrator.onAccessibilityLost()`), `OverlayHost` + `VisibilityInput` from `:platform:overlay` (WP-03),
`DictationOrchestrator.bubble/levels/onInput` (WP-11), `FieldKinds` / `BuiltInDenylist` (WP-08).
Already present: `MurmurAccessibilityService` skeleton, `A11yBridge` + `BridgedTargetReader/BridgedInserter`,
`res/xml/murmur_accessibility.xml` (spike-verified config), manifest with `<queries>`.

## Deliver

1. Service lifecycle: `onServiceConnected` → attach one `OverlayHost` per display (`DisplayListener` for added /
   changed / removed displays, fold/unfold), accessibility IME (`onCreateInputMethod`, API 33) for `commitText` and
   `getSurroundingText`; `onUnbind` / `onDestroy` → detach, `A11yBridge.service = null`, `host.onServiceState(false)`
   (the orchestrator turns a running dictation into `ExternalStop(A11Y_OFF)`).
2. Focus tracking (`a11y-io` HandlerThread, never the main thread for node reads): from
   `TYPE_VIEW_FOCUSED`/`WINDOWS_CHANGED`/`WINDOW_STATE_CHANGED` compute `VisibilityInput` (editable focused, field
   allowed = `FieldKinds.bubbleAllowed`, app hidden = built-in denylist ∪ Settings.hiddenInPackages ∪ own package,
   hidden-until, show always, idle since, tuck-in, TalkBack on) and push to the overlay; blur debounce 300 ms.
3. `snapshot()`: focused `TYPE_APPLICATION` window (never `rootInActiveWindow` at tap time), package, label
   (PackageManager with `<queries>`, fallback window title), window title, display id, focused editable with BFS
   fallback (Compose/WebView), `EditorInfo` from the accessibility IME, `before_cursor` =
   `getSurroundingText(1000,0,0)` cut at the selection start (never for passwords, "" when the hint shows), bounds,
   exclusion rects (IME, own overlay, system bars) — all within 150 ms.
4. `snapshotAfterShade(timeoutMs)` (ADR-056, NOTIFICATION start): wait for the first `WINDOWS_CHANGED` after which a
   focused `TYPE_APPLICATION` window of a package other than SystemUI exists (or return at once if one already is),
   at most `timeoutMs`, then `snapshot()`; null if none.
5. `screenshot()`: ARCHITECTURE 9.1 exactly (window capture → retry once after ≥ 340 ms on error 3 → re-resolve on
   error 5 → `findFocus(FOCUS_INPUT).windowId` → display capture + exclusion rects; error 6 or a black frame →
   `Err(SecureWindow)`). HardwareBuffer → `ARGB_8888` `ScreenImage`; no PNG; record the call time.
6. `insert()`: ladder `commitText` → `ACTION_PASTE` → `ACTION_SET_TEXT` (+`ACTION_SET_SELECTION`); after each,
   verify with `getSurroundingText` (not node text); return the first verified `InsertMethod`. `copyToClipboard`:
   `ClipData` with `EXTRA_IS_SENSITIVE=false`, never read the clipboard.
7. Restricted settings (ADR-054): record when the user is sent to the accessibility settings screen and when they
   return (`returnedFromA11ySettings`), plus `getInstallSourceInfo().packageSource`; expose both through a small
   `A11yStatus` object that WP-15's `PermissionMonitor` reads (enabled in `Settings.Secure`, connected now, ever
   connected, restricted likely).
8. Haptics are WP-03's (`Feedback` from the overlay view). Notification "Start dictation" belongs to WP-09's status
   notification; you forward its intent to `UserInput.NotificationStart`.

## Acceptance (checkable here)

- JVM/Android unit tests for every pure helper (window choice from a fake window list incl. the shade case,
  before_cursor cut, exclusion rect math, visibility input building, ladder ordering with a fake node wrapper);
  `RestrictedSettingsProbeTest` (exists) kept green.
- Instrumented tests on `murmur_fold7_inner` (API 36) with `:testapps:target` (WP-20 owns it; ask for extra field
  types — EditText, multiline, password, Compose TextField, WebView — or add a target activity of your own under
  `platform/a11y/src/androidTest`): enable the service with
  `adb shell settings put secure enabled_accessibility_services app.murmur/app.murmur.platform.a11y.MurmurAccessibilityService`
  and `adb shell settings put secure accessibility_enabled 1`, then assert:
  - `snapshot()` returns the target package, label, field kind/hint, `before_cursor` = "Line one" for a multiline
    field with the caret after it, no `before_cursor` for a password field;
  - `snapshotAfterShade(1000)`: `adb shell cmd statusbar expand-notifications`, call it, `adb shell cmd statusbar
    collapse` → returns the target app, not `com.android.systemui`;
  - `screenshot()` returns a frame of the window size without keyboard pixels (compare the IME rect region with
    the app background colour), error 6 path on a `FLAG_SECURE` activity → `SecureWindow`;
  - two screenshots 200 ms apart: the second waits/retries and succeeds (rate limit handling);
  - `insert()` lands in EditText, Compose and WebView fields, verified by the target's own `TGT text=` log line;
  - after force-stop of the target app, `insert()` returns `Failed(TargetChanged or NoEditableField)`;
  - disabling the service (`settings put secure enabled_accessibility_services ''`) calls `onServiceState(false)`
    within 2 s (logged).
- Restricted settings on the emulator: install the debug APK from a file session (`adb push app-debug.apk
  /data/local/tmp/ && adb shell pm install -i com.android.documentsui /data/local/tmp/app-debug.apk` or a
  `PackageInstaller` session with `PACKAGE_SOURCE_LOCAL_FILE`; report the command that produced packageSource 3),
  open the accessibility settings, return → `A11yStatus.restrictedLikely == true`; with an `adb install` (source
  unspecified) → false. Report what the emulator shows when toggling (dialog or not).
- Visibility: focusing a password field hides the bubble within 300 ms, also with "Show the bubble: Always"
  (assert the `VisibilityInput` sequence).
- Every result line in the report quotes the logcat/test output.

## Out of scope

Bubble drawing, drag, placement (WP-03); ctx v2 assembly and privacy decisions (WP-08); deciding what to insert
(WP-11); `PermissionMonitor` implementation (WP-15 reads your `A11yStatus`). One UI behaviour (Samsung Keyboard,
the real restricted-settings dialog) is DEVICE_CHECKLIST DC5/DC6/DC10.

## Build/test

`tools/gw :platform:a11y:testDebugUnitTest :platform:a11y:assembleDebugAndroidTest :app:assembleDebug`; install
the app APK and your test APK on your emulator (README rule 11).
