# WP-02 Accessibility service, screen capture, insertion (`:platform:a11y`)

Wave 1. Read first: `docs/wp/README.md`; `docs/research/verify/platform.md` (all; it is the evidence you build on);
ARCHITECTURE.md §4.1, §9.1, §9.2, §11, §14; `docs/ml/CONTEXT_V2.md`; DESIGN.md §5.2 (when the bubble shows).
Code to lift: `spikes/platform/app/src/main/kotlin/app/murmur/spike/{SpikeA11yService,ContextV2}.kt` (functions
`targetWindow()`, `focusedEditable()`, `ctxV2()`, `shotOnce()`, `tryMethod()/insert()`, `setClip()`, `SpikeIme`).
Do not ship its exported broadcast receiver.

## Goal

The accessibility service that knows the target field, takes the window screenshot, inserts the text and feeds the
bubble's visibility policy, behind the `TargetReader` / `Inserter` ports of `:core:dictation`.

## Owns

`platform/a11y/**`.

## Contracts

Implement: `TargetReader` (`snapshot()`, `screenshot()`, `current()`), `Inserter` (`insert()`, `copyToClipboard()`)
from `core/dictation/.../Ports.kt`; produce `A11ySnapshot` / `FieldSnapshot` (`core/context/.../Snapshot.kt`).
Consume: `A11yServiceHost` (implemented by `MurmurApp`), `OverlayHost` + `VisibilityInput` from `:platform:overlay`
(WP-03), `DictationOrchestrator.bubble/levels/onInput` (WP-11), `FieldKinds` (WP-08).
Already present: `MurmurAccessibilityService` skeleton, `A11yBridge` + `BridgedTargetReader/BridgedInserter`,
`RestrictedSettingsProbe`, `res/xml/murmur_accessibility.xml` (spike-verified config), manifest with `<queries>`.

## Deliver

1. Service lifecycle: `onServiceConnected` → attach one `OverlayHost` per display (`DisplayListener` for added /
   changed / removed displays, fold/unfold), accessibility IME (`onCreateInputMethod`, API 33) for `commitText` and
   `getSurroundingText`; `onUnbind` → detach, `A11yBridge.service = null`.
2. Focus tracking (`a11y-io` HandlerThread, never the main thread for node reads): from
   `TYPE_VIEW_FOCUSED`/`WINDOWS_CHANGED`/`WINDOW_STATE_CHANGED` compute `VisibilityInput` (editable focused, field
   allowed = `FieldKinds.bubbleAllowed`, app hidden = built-in denylist ∪ Settings.hiddenInPackages ∪ own package,
   hidden-until, show always, idle since, tuck-in, TalkBack on) and push to the overlay; blur debounce 300 ms.
3. `snapshot()`: focused `TYPE_APPLICATION` window (never `rootInActiveWindow` at tap time), package, label
   (PackageManager with `<queries>`, fallback window title), window title, display id, focused editable with BFS
   fallback (Compose/WebView), `EditorInfo` from the accessibility IME, `before_cursor` =
   `getSurroundingText(1000,0,0)` cut at the selection start (never for passwords, "" when the hint shows), bounds,
   exclusion rects (IME, own overlay, system bars) — all within 150 ms.
4. `screenshot()`: ARCHITECTURE 9.1 exactly (window capture → retry once after ≥ 340 ms on error 3 → re-resolve on
   error 5 → `findFocus(FOCUS_INPUT).windowId` → display capture + exclusion rects; error 6 or a black frame →
   `Err(SecureWindow)`). HardwareBuffer → `ARGB_8888` `ScreenImage`; no PNG; record the call time.
5. `insert()`: ladder `commitText` → `ACTION_PASTE` → `ACTION_SET_TEXT` (+`ACTION_SET_SELECTION`); after each,
   verify with `getSurroundingText` (not node text); return the first verified `InsertMethod`. `copyToClipboard`:
   `ClipData` with `EXTRA_IS_SENSITIVE=false`, never read the clipboard.
6. "The bubble is off" signal: `A11yServiceHost.onServiceState`; `RestrictedSettingsProbe` inputs (enabled in
   `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` but never connected; `getInstallSourceInfo().packageSource`).
7. Haptics are WP-03's (`Feedback` from the overlay view). Notification "Start dictation" action belongs to WP-09's
   FGS notification; you forward its intent to `UserInput.NotificationStart`.

## Acceptance (checkable here)

- JVM/Android unit tests for every pure helper (window choice from a fake window list, before_cursor cut, exclusion
  rect math, visibility input building, ladder ordering with a fake node wrapper).
- Instrumented tests on `murmur_fold7_inner` (API 36) with `:testapps:target` (WP-20 owns it; ask for extra field
  types — EditText, multiline, password, Compose TextField, WebView — or add a target activity of your own under
  `platform/a11y/src/androidTest`): enable the service with
  `adb shell settings put secure enabled_accessibility_services app.murmur/app.murmur.platform.a11y.MurmurAccessibilityService`
  and `adb shell settings put secure accessibility_enabled 1`, then assert:
  - `snapshot()` returns the target package, label, field kind/hint, `before_cursor` = "Line one" for a multiline
    field with the caret after it, no `before_cursor` for a password field;
  - `screenshot()` returns a frame of the window size without keyboard pixels (compare the IME rect region with
    the app background colour), error 6 path on a `FLAG_SECURE` activity → `SecureWindow`;
  - two screenshots 200 ms apart: the second waits/retries and succeeds (rate limit handling);
  - `insert()` lands in EditText, Compose and WebView fields, verified by the target's own `TGT text=` log line;
  - after force-stop of the target app, `insert()` returns `Failed(TargetChanged or NoEditableField)`.
- Visibility: focusing a password field hides the bubble within 300 ms (assert `VisibilityInput` sequence).
- Every result line in the report quotes the logcat/test output.

## Out of scope

Bubble drawing, drag, placement (WP-03); ctx v2 assembly and privacy decisions (WP-08); deciding what to insert
(WP-11). One UI behaviour (Samsung Keyboard, restricted settings dialog) is DEVICE_CHECKLIST D5/D6/D10.

## Build/test

`tools/gw :platform:a11y:testDebugUnitTest :platform:a11y:assembleDebugAndroidTest :app:assembleDebug`; install
the app APK and your test APK on your emulator (README rule 11).
