# Murmur: Android platform mechanisms

Author role: senior Android engineer. Research date: 2026-11-18.
Target: sideloaded APK on a Samsung Galaxy Z Fold7 running One UI 9 (Android 17, API 37).
Scope: everything outside the ML runtimes. That covers the accessibility service, the bubble, microphone, sideload restrictions, clipboard, storage and downloads, the process and memory model, prior art, layout, the manifest, the state machine and the test matrix.

How claims are marked:

- **[V]**: verified this session from a primary source. The link sits next to the claim. "AOSP" means the `main` branch source at android.googlesource.com, read on 2026-11-18.
- **[I]**: my inference from verified facts.
- **[U]**: unverified. Do not rely on it without checking.
- **[DEV]**: can only be confirmed on the physical Fold7.

---

## 0. Decisions and recommendation

1. **One accessibility service is the backbone.** It shows the bubble, captures the mic, reads context, takes the screenshot and inserts text. Android has no other API that detects focused fields in other apps and inserts into them. Wispr Flow says the same in its [Android help](https://docs.wisprflow.ai/articles/7669452251-accessibility-permission-on-android) [V].
2. **Do not set `isAccessibilityTool="true"`.** Google states that a non-assistive app setting it will be rejected by Play and blocked by Play Protect on devices ([Android Developers blog, Dec 2025](https://developer.android.com/blog/posts/enhancing-android-security-stop-malware-from-snooping-on-your-app-data)) [V]. The consequences are accepted:
   - Murmur cannot run under Android Advanced Protection.
   - Murmur cannot see views marked `accessibilityDataSensitive`, which banking apps and password-type views use.
3. **The bubble is a `TYPE_ACCESSIBILITY_OVERLAY` window. Do not request `SYSTEM_ALERT_WINDOW`.**
   - This window type needs no overlay permission and draws above the IME.
   - It counts as a "trusted" window for touch pass-through [V].
   - While visible, it makes Murmur's UID count as foreground in AOSP [V, AOSP]. That is what legitimises the mic start.
   - Requesting SAW would add a second ECM-restricted grant for no benefit, because SAW is itself on the ECM-protected list [V, AOSP].
4. **Microphone.**
   - Record with `AudioRecord`, `AudioSource.VOICE_RECOGNITION`, 16 kHz mono PCM16, in the accessibility-service process.
   - At the bubble tap, while the bubble is visible, start a short-lived foreground service of type `microphone`. Stop it when recording stops.
   - The accessibility binding already gives the process while-in-use capabilities [V, AOSP]. Ramblr records with no FGS at all [V, source]. The FGS is still worth it for four reasons:
     - it is the documented, policy-compliant path;
     - Android 17's background-audio hardening needs it if we request audio focus [V];
     - Samsung's guarantee is conditional on FGS policy compliance [V];
     - it gives the user a Stop control.
5. **Insertion ladder** (section 3.5):
   1. `InputMethod.AccessibilityInputConnection.commitText` (API 33+).
   2. `ACTION_PASTE` (the clipboard already holds the result, because the brief requires the copy).
   3. `ACTION_SET_TEXT` with a composed string plus `ACTION_SET_SELECTION`, only on non-rich fields.
   4. Clipboard only, with a "Copied, tap to paste" bubble.

   Every write is verified by reading the field back.
6. **Two processes.**
   - `:main` (default) holds the accessibility service, UI, database and downloads. It never loads a model.
   - `:infer` holds the ASR, LLM and OCR native runtimes behind a bound AIDL service. After an idle timeout it unloads everything and kills itself.

   This is the only way to *guarantee* that native and NPU memory goes back to the system. It also keeps a native crash from killing the accessibility service. Force-stop disables the service outright [V, AOSP]. A crash makes the system restart the process; it does not disable the service.
7. **Model downloads.**
   - Run as a JobScheduler **user-initiated data transfer (UIDT)** job, scheduled from the visible Model Hub screen [V].
   - Use OkHttp with `Range` resume against `https://huggingface.co/<repo>/resolve/<commitSha>/<file>`.
   - Verify SHA-256 against `lfs.oid` from `/api/models/<repo>/tree/<sha>` (equal to `X-Linked-Etag`) [V].
   - Use an optional HF Bearer token. OkHttp drops it on the cross-host CDN redirect [V].
8. **Storage.**
   - History goes in Room (SQLite), with audio as Ogg/Opus (about 24 kbit/s, mono, 16 kHz) through `MediaCodec` and `MediaMuxer.MUXER_OUTPUT_OGG`. The Opus encoder exists on Android 10+ [V].
   - Retention runs after every dictation, at service connect, and in a daily WorkManager job.
   - Settings go in DataStore.
   - The HF token is AES-GCM encrypted with an Android Keystore key. Do not use Jetpack `security-crypto`, which is deprecated in favour of direct Keystore use [V].
9. **Install via adb (or Android Studio) on the owner's phone.**
   - adb installs are exempt from developer verification [V].
   - A file-manager or browser install is always ECM-guarded ("restricted settings") [V, AOSP].
   - Whether an adb install is guarded depends on the recorded installer [U]. Ship the "Allow restricted settings" guide regardless.
10. **minSdk 34, targetSdk/compileSdk 37.** API 34 brings `takeScreenshotOfWindow`, `attachAccessibilityOverlayTo*`, typed FGS and UIDT, and the target is a single device. Expanding below 34 is cheap only down to 33, where the accessibility `InputMethod` appears.

---

## 1. Platform baseline

| Item | Value | Source |
|---|---|---|
| Current Android | Android 17, API 37. Stable since June 2026 (date from a third-party report). The SDK on the build server already has `platforms/android-37.0` and `android-37.2`. | [behavior changes, all apps](https://developer.android.com/about/versions/17/behavior-changes-all) [V]. Release date [U, secondary]. |
| Fold7 OS | One UI 9 (Android 17). Stable rollout began late September 2026 in Korea and reached the US in early October. | [Android Authority](https://androidauthority.com/samsung-one-ui-9-galaxy-s25-fold-7-flip-7-stable-3716021), [9to5Google](https://9to5google.com/2026/10/05/samsung-android-17-galaxy-s25-fold-7-us-rollout/) [V, secondary] |
| Fold7 hardware | Snapdragon 8 Elite for Galaxy. 12 GB RAM (16 GB on the 1 TB model). 8.0" main display at 2184×1968 and 6.5" cover display at 2520×1080. Launched on One UI 8 / Android 16. | [GSMArena](https://m.gsmarena.com/samsung_galaxy_z_fold7-13826.php), [Google Fi spec page](https://fi.google.com/about/phones/samsung-galaxy-z-fold7-specs) [V, secondary]. "Samsung NPU" on this phone is therefore Qualcomm Hexagon [I]. |
| Android 17 changes that matter to Murmur | App memory limits ("MemoryLimiter:AnonSwap"); background-audio hardening; Safer native DCL (`System.load()` files must be read-only); large-screen orientation opt-out removed; `setContentCaptureEnabled` deprecated | [all apps](https://developer.android.com/about/versions/17/behavior-changes-all), [target 37](https://developer.android.com/about/versions/17/behavior-changes-17) [V] |
| Android 17 security | Advanced Protection blocks non-tool accessibility services and sideloading. `AdvancedProtectionManager` was added in API 36. | [Android 17 features](https://developer.android.com/about/versions/17/features), [Android Help](https://support.google.com/android/answer/16339980) [V] |

---

## 2. Accessibility service

### 2.1 Configuration (all attributes verified in [R.styleable](https://developer.android.com/reference/android/R.styleable) and [AccessibilityServiceInfo](https://developer.android.com/reference/android/accessibilityservice/AccessibilityServiceInfo))

```xml
<!-- res/xml/murmur_accessibility.xml -->
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeViewFocused|typeWindowStateChanged|typeWindowsChanged|typeViewTextSelectionChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagRetrieveInteractiveWindows|flagInputMethodEditor|flagReportViewIds"
    android:canRetrieveWindowContent="true"
    android:canTakeScreenshot="true"
    android:isAccessibilityTool="false"
    android:notificationTimeout="100"
    android:description="@string/a11y_description"
    android:summary="@string/a11y_summary"
    android:intro="@string/a11y_intro"
    android:settingsActivity="app.murmur.MainActivity" />
```

| Setting | Why |
|---|---|
| `flagInputMethodEditor` (`FLAG_INPUT_METHOD_EDITOR`, API 33, 0x8000) | Turns the service into "an input method editor with a subset of input method editor capabilities: get the InputConnection and get text selection change notifications" [V]. Without it `getInputMethod()` returns null [V]. |
| `flagRetrieveInteractiveWindows` | Needed for `getWindows()`, `getWindowsOnAllDisplays()` and for `findFocus()` to search all windows. Otherwise "the search will be performed only in the active window" [V]. That matters in split-screen. |
| `canTakeScreenshot` | Required by `takeScreenshot` and `takeScreenshotOfWindow` [V]. |
| Event types | Keep the set minimal. Every event costs an IPC, and with `canRetrieveWindowContent` enabled, apps such as Chrome, WebView and Flutter build accessibility trees [I]. Do not subscribe to `typeWindowContentChanged`. |
| No `packageNames` filter | Dictation must work in every app. |

### 2.2 Current app and node tree

- **Foreground app.**
  - Track `TYPE_WINDOW_STATE_CHANGED` and `TYPE_WINDOWS_CHANGED`.
  - On demand, call `getWindows()` and take the `AccessibilityWindowInfo` with `isFocused()` (input focus) and `getType() == TYPE_APPLICATION`. Its `getRoot().packageName` is the app.
  - `getDisplayId()` (API 30) tells displays apart [V].
  - Do **not** trust `getRootInActiveWindow()` at tap time. The docs define the active window as "the one that the user is currently touching or the window with input focus, if the user is not touching any window" [V]. During a bubble tap the touched window is our own overlay [I].
- **Tree reads.** Use `getRootInActiveWindow(int prefetchingStrategy)` / `AccessibilityWindowInfo.getRoot(int)` with `FLAG_PREFETCH_DESCENDANTS_BREADTH_FIRST` [V] for the context dump.
  - Bound the walk by time (about 150 ms) and by node count (about 2 000).
  - Run it off the main thread. The node API is a synchronous binder call.
  - Use `setCacheEnabled`, `clearCache` and `clearCachedSubtree` (API 33) [V] if stale reads appear.
- **Context to collect:** the package and app label; window title; the visible text in reading order; the focused field's hint, label and text before/after the cursor; and the app category. The `AppCategoryClassifier` package map from intent-voice is worth porting (section 9.3).

### 2.3 Detecting the focused editable field

Two signals are better than one. Both stay live.

1. **IME session signal (primary).** With `FLAG_INPUT_METHOD_EDITOR`, override `onCreateInputMethod()` and return a custom `InputMethod` [V]. Its callbacks are:
   - `onStartInput(EditorInfo attribute, boolean restarting)`, documented as "text input has started in an editor" [V]. `EditorInfo` gives `packageName`, `inputType`, `imeOptions`, `hintText` and `fieldId`.
   - `onFinishInput()`.
   - `onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)` [V].
   - `getCurrentInputStarted()` and `getCurrentInputEditorInfo()` [V].

   This is the same lifecycle a keyboard sees. It covers Compose `BasicTextField`, Chromium/WebView inputs and Termux (Ramblr evidence, §9.2), all of which may expose no editable node.
2. **Node signal (secondary, needed for node-based writes).** Use `findFocus(AccessibilityNodeInfo.FOCUS_INPUT)` across windows [V], or cache the source of the last `TYPE_VIEW_FOCUSED` event. The node gives:
   - `isEditable()`, `isPassword()`, `getInputType()`;
   - `getText()`, `getHintText()`, `isShowingHintText()`;
   - `getTextSelectionStart()` / `getTextSelectionEnd()`. These return -1 when there is no cursor; equal values mean a caret [V];
   - `getMaxTextLength()`, `isMultiLine()`.
   - Caveat: `findFocus` cannot see focus inside a `SurfaceView` embedded hierarchy [V].
3. **Snapshot the target at tap-down.** Record the package, `fieldId`, window id, node and selection the moment the bubble is pressed. Use it later to decide whether the user moved away.
4. **Text and selection read.** Prefer `AccessibilityInputConnection.getSurroundingText(before, after, 0)` [V]. It returns real characters and the selection, and never hint text. It is a slow IPC: "Assume this method consumes a lot of time" [V], and it returns null on timeout. Fall back to the node text, with placeholder heuristics: Ramblr treats `isShowingHintText()`, blank text and selection -1/-1 as an empty field.

### 2.4 Screen capture

| API | Level | What you get | Notes |
|---|---|---|---|
| `takeScreenshot(int displayId, Executor, TakeScreenshotCallback)` | 30 | The whole logical display, including our own overlay and the IME | Fails with `ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY` for private virtual displays [V, AOSP]. |
| `takeScreenshotOfWindow(int accessibilityWindowId, Executor, TakeScreenshotCallback)` | 34 | One window. Docs: use it "if your target window might be visually underneath an accessibility overlay … to capture the window contents without the screenshot being covered by the overlay" [V]. | **Recommended.** Capture the focused app window: no bubble, no keyboard pixels, and the right pane in split-screen. |

- **Rate limit.** In AOSP, `ACCESSIBILITY_TAKE_SCREENSHOT_REQUEST_INTERVAL_TIMES_MS = 333`. A request within 333 ms of the previous one fails with `ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT` [V, AOSP `AccessibilityService.java` and `AbstractAccessibilityServiceConnection.java`]. The limit is tracked per display for `takeScreenshot` and per window id for `takeScreenshotOfWindow` [V, AOSP]. One capture per dictation is far inside it. Retry once after 350 ms if the result is `INTERVAL_TIME_SHORT`.
- **Secure windows.** `ERROR_TAKE_SCREENSHOT_SECURE_WINDOW` (6) applies to window capture of a `FLAG_SECURE` window [V]. With display capture, secure layers come out black: AOSP calls `captureDisplay(displayId, null, …)`, which uses default capture args [I]. Treat both cases as "no OCR context" and do not retry.
- **Result handling.** `ScreenshotResult.getHardwareBuffer()`, `getColorSpace()`, `getTimestamp()` [V], then `Bitmap.wrapHardwareBuffer(...)` [V].
  1. Copy the result to a software `ARGB_8888` bitmap, or better, downscale straight to the OCR input size.
  2. Close the `HardwareBuffer` immediately. A full inner-screen frame is about 2184×1968×4 ≈ 17 MB [I].
  3. Send pixels to `:infer` through `SharedMemory` (API 27) [V] or a `ParcelFileDescriptor`. Never put them in a Parcel byte array.
- **Timing.** The brief says 0.5–1 s after start. Capture at about 300–500 ms: early enough that the user has not scrolled, late enough not to compete with `AudioRecord` startup on the main thread [I]. Skip the capture if:
  - the focused field is a password field;
  - the app is on the exclusion list;
  - `isAccessibilityDataSensitive()` nodes dominate.
- **Alternative.** MediaProjection needs a per-session consent dialog and an FGS of type `mediaProjection`. It is worse in every way for this use case [I].

### 2.5 Putting text into the field

| # | Method | API | Pros | Cons |
|---|---|---|---|---|
| A | `getInputMethod().getCurrentInputConnection().commitText(text, 1, null)` | 33 | The keyboard path. Inserts at the cursor and replaces the selection [V]. Preserves rich text, spans and the app's undo. Works where no editable node exists: WebView/Chromium, Compose, Termux [V, Ramblr source]. No clipboard involved. | Returns `void`, so no success signal; verify by read-back. The connection exists only between `onStartInput` and `onFinishInput` [V]. If the user's keyboard has composing text, `commitText` "commits the contents of the currently composing text" first [V], so interaction with Samsung Keyboard composition needs testing [DEV]. |
| B | `node.performAction(ACTION_PASTE)` | 18 | Inserts at the cursor and keeps formatting. Murmur writes the clipboard anyway, so no extra side effect. | Some nodes report `true` but insert nothing. Chromium WebView containers do this [V, Ramblr `WebViewInjectionRules.kt`]. Some apps show a paste-confirmation or rich-paste UI. The clipboard preview toast appears on Android 13+ [V]. |
| C | `node.performAction(ACTION_SET_TEXT, Bundle{ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE})` | 21 | Works without an IME session. Returns a boolean. | Replaces the **whole** field, so you must compose before + text + after from a node read that may be truncated, newline-flattened (rich WebView editors such as Outlook and Gmail) or a hint. Loses formatting and undo. "This action will also put the cursor at the end of text" [V], so fix it with `ACTION_SET_SELECTION`. |
| D | `dispatchGesture` / key events | — | — | Not viable for text. |
| E | Clipboard only plus user paste | — | Always works | Manual step |

Recommended ladder, run on the main thread with a 1.5 s total budget:

```
precheck: target is password / excluded / sensitive  → E (do not even copy for password: discard)
clipboard.setPrimaryClip(result)            // brief: always copy
if a11yIme.inputStarted && editorInfo.packageName == target.package && !isPassword(editorInfo.inputType):
    A; verify (onUpdateSelection moved by len, or getSurroundingText contains text) → done
node = refreshed focused editable node in target window
if node != null:
    if node supports ACTION_PASTE and !isNonFieldWebViewNode(node): B; verify → done
    if !isLossyWholeField(node) [editable && inWebView && multiLine && nonEmpty]: C + SET_SELECTION; verify → done
E: show "Copied, tap the field and paste" bubble (tappable to re-copy)
```

- **Verification.** About 120 ms after the write, call `node.refresh()` [V] and check that the text contains the insertion and the selection moved. For path A, the `onUpdateSelection` callback is the cheaper signal.
- **Spacing.** Apply intent-voice's `SmartSpacing` against the text before and after the cursor, read just before inserting (§9.3).
- **Empty-scan retry.** If no candidate is found, rescan once after 200 ms. Tapping can transiently steal focus (Ramblr #5) [V, source].
- **Moved-away rule.** If the focused editor's package differs from the tap-down snapshot, do not insert. Copy to the clipboard and notify. Wispr Flow does the same ([help](https://docs.wisprflow.ai/articles/5090227041-dictating-in-notification-reply-boxes-and-browser-address-bars)) [V]. Ramblr hit wrong-app delivery as bug #284 [V, source].
- Ramblr uses C first and A only for Termux. Placing A first is my judgement [I]: it is the only lossless write path. It must be validated on the Fold7 with Samsung Keyboard and Gboard across about 20 top apps before it is fixed as the default [DEV]. Keep the order configurable per package.

### 2.6 Special surfaces

| Surface | Behaviour | Plan |
|---|---|---|
| WebView / Chrome | Inputs appear as `android.widget.EditText` under a `WebView` node. Focus may sit on the container, and `ACTION_PASTE` there "reports success but inserts nothing" [V, Ramblr]. Select-all is misreported as `0/1` [V, Ramblr]. | Path A first. Never pick a non-field WebView node. Treat a multi-line, non-empty WebView field as lossy for C. |
| Rich editors (Outlook, Gmail body) | Node text is newline-flattened [V, Ramblr `RichTextDestinationRules.kt`] | A or B only, never C |
| Compose apps | Compose text fields publish an IME input connection. Semantics nodes also support SET_TEXT. | A works [I] [DEV] |
| Terminal (Termux) | No editable node, rejects `ACTION_PASTE`, but `onCheckIsTextEditor()` is true, so `commitText` works [V, Ramblr `A11yInputCommit.kt`] | A |
| Games, custom GL/Canvas | Usually no nodes and often no InputConnection [I] | Clipboard only. Hide the bubble unless an IME session is active. |
| Password fields | `isPassword()`, or `inputType` variations `TYPE_TEXT_VARIATION_PASSWORD`, `TYPE_TEXT_VARIATION_VISIBLE_PASSWORD`, `TYPE_TEXT_VARIATION_WEB_PASSWORD`, `TYPE_NUMBER_VARIATION_PASSWORD` | Hide the bubble. If focus lands on a password field mid-dictation, discard the result: no insert, no clipboard, no history (Ramblr behaviour). |
| `accessibilityDataSensitive` views (Android 16+; set automatically by `setFilterTouchesWhenObscured`) | "any app … that hasn't explicitly declared itself as a legitimate accessibility tool … is denied access to that view" [V] | Expect banking and payment screens to be invisible to Murmur. Clipboard only. |
| Notification inline reply, URL bars | Work, but may bypass password heuristics. Chrome's omnibox sometimes rejects text [V, Wispr Flow help]. | Allow. Never use URL-bar text as context. |

---

## 3. The floating bubble

### 3.1 Window type

| Option | Permission | Z-order | Touch | Verdict |
|---|---|---|---|---|
| `TYPE_ACCESSIBILITY_OVERLAY` (2032, API 22) via the service's `WindowManager` | None beyond the enabled service [V] | Above apps **and the IME**. Also above the lock screen and AOD, so you must hide it there [V, Ramblr `OverlayVisibility.kt`]. | A "trusted window" for the Android 12 untrusted-touch rules [V] | **Use** |
| `TYPE_APPLICATION_OVERLAY` (2038) | `SYSTEM_ALERT_WINDOW`, an ECM-restricted app-op for sideloaded apps [V, AOSP] | "above all activity windows … but below critical system windows like the status bar or IME". "The system may change the position, size, or visibility of these windows at anytime" [V]. | Not trusted [V] | Avoid. Wispr Flow uses SAW ([help](https://docs.wisprflow.ai/articles/8858845757-setup-wispr-flow-on-android-android-settings)) [V]; we do not need it. |
| `attachAccessibilityOverlayToDisplay(displayId, SurfaceControl)` / `…ToWindow(...)` with `SurfaceControlViewHost` (API 34) | None | Display or window anchored | Needs your own SurfaceControl plumbing | Keep as a fallback. Useful later for a pill anchored to the field [V]. |

Why the visible overlay matters for the mic: AOSP's `WindowState.onSurfaceShownChanged` counts any visible window of type ≥ `FIRST_SYSTEM_WINDOW`, except toasts and private presentations, as a "non-app visible window" for the owner UID. `ActivityTaskManagerService.hasActiveVisibleWindow(uid)` / `isUidForeground` then return true. That makes `ActiveServices.shouldAllowFgsWhileInUsePermissionLocked` grant `REASON_UID_VISIBLE` [V, AOSP]. So a `microphone` FGS started from a tap on the visible bubble gets while-in-use access. Not yet confirmed on Samsung's build [DEV].

### 3.2 Hosting Jetpack Compose in the overlay

`AccessibilityService` extends `Service`, not `LifecycleService`, so the owners must be supplied by hand.

```kotlin
class OverlayHost(private val service: AccessibilityService) :
    LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
  private val lifecycleRegistry = LifecycleRegistry(this)
  private val savedState = SavedStateRegistryController.create(this)
  override val lifecycle: Lifecycle get() = lifecycleRegistry
  override val savedStateRegistry get() = savedState.savedStateRegistry
  override val viewModelStore = ViewModelStore()

  private val wm = service.getSystemService(WindowManager::class.java)
  private val view = ComposeView(service).apply {
    setViewTreeLifecycleOwner(this@OverlayHost)          // androidx.lifecycle
    setViewTreeSavedStateRegistryOwner(this@OverlayHost) // androidx.savedstate
    setViewTreeViewModelStoreOwner(this@OverlayHost)
    setContent { MurmurBubble(/* state flow from controller */) }
  }
  val lp = WindowManager.LayoutParams(
      sizePx, sizePx,
      WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
      FLAG_NOT_FOCUSABLE or FLAG_LAYOUT_NO_LIMITS,   // never steal IME focus from the target field
      PixelFormat.TRANSLUCENT).apply {
        gravity = Gravity.TOP or Gravity.START
        setCanPlayMoveAnimation(false)                 // API 34: drag without WM move animation
      }
  fun attach() { savedState.performRestore(null); lifecycleRegistry.currentState = Lifecycle.State.RESUMED
                 runCatching { wm.addView(view, lp) } }   // BadTokenException if service just died
  fun detach() { lifecycleRegistry.currentState = Lifecycle.State.DESTROYED; runCatching { wm.removeView(view) } }
}
```

- Verified APIs: `View.setViewTreeLifecycleOwner` and `setViewTreeViewModelStoreOwner` ([androidx.lifecycle](https://developer.android.com/reference/kotlin/androidx/lifecycle/package-summary)); `setViewTreeSavedStateRegistryOwner` ([androidx.savedstate](https://developer.android.com/reference/kotlin/androidx/savedstate/package-summary)); `setCanPlayMoveAnimation` (API 34) and `FLAG_NOT_FOCUSABLE` ([LayoutParams](https://developer.android.com/reference/android/view/WindowManager.LayoutParams)).
- Size the window to the bubble, not full screen, so touches elsewhere reach the app. Draw the expanded "listening" pill and the result toast in a **second** small window, so the bubble window never resizes mid-gesture.
- `addView` can throw `BadTokenException` if the service connection is torn down mid-connect. Wrap it (Ramblr #260) [V, source].
- **Do not hide by setting the root to `View.GONE`.** Ramblr found on a Pixel Fold that a GONE root in an accessibility overlay window stalls the fold-triggered display switch for about 2 s. The cause is the BLAST sync timeout. Their fix keeps the root VISIBLE with `alpha = 0` plus `FLAG_NOT_TOUCHABLE`, or removes the window [V, Ramblr source comments; the root cause is theirs, not re-verified]. Apply the same rule on the Fold7 [DEV].

### 3.3 Touch and drag

- Use Compose `pointerInput` with `detectTapGestures(onPress = …)` plus `detectDragGestures`. While dragging, update `lp.x`/`lp.y` and call `wm.updateViewLayout(view, lp)`, coalesced to at most one call per frame.
- **Start the mic on press, not on release** (§4.6). Classify the gesture as drag after a touch-slop and about 150 ms. If it turns into a drag or long-press, cancel the started capture and drop the buffer.
- When released, snap to the nearest vertical edge and persist x and y. Keep the bubble out of the IME rectangle: read the keyboard window's `getBoundsInScreen` from `getWindows()` (`TYPE_INPUT_METHOD`) [V].
- Make edge "peek" a setting. It needs `FLAG_LAYOUT_NO_LIMITS`, because WindowManager otherwise clamps the window on-screen [V, Ramblr comment].
- Give haptic feedback with `view.performHapticFeedback(...)` on recording start, confirmed by the first audio buffer, and on stop.

### 3.4 Show it only when it makes sense

The bubble is visible iff all of these hold:

- the service is connected;
- the keyguard is not locked (`KeyguardManager.isKeyguardLocked()`);
- the screen is on: `Display.STATE_ON`, because Samsung AOD reports `DOZE`/`DOZE_SUSPEND` [V, Ramblr];
- the foreground package is not excluded;
- Murmur's own activity is not in front;
- the user has not snoozed the bubble;
- **either** an IME session is active on a non-password editor (`onStartInput` … `onFinishInput`), **or** a `TYPE_INPUT_METHOD` window is present, **or** a dictation or result is in flight. In those last states the bubble must stay, so the user can stop or tap-to-copy.

Wispr Flow uses the same rule: the bubble "appears as the keyboard opens and hides as it starts closing … unless a dictation is starting or running" ([help](https://docs.wisprflow.ai/articles/5002934560-why-is-the-wispr-bar-is-not-appearing-or-disappearing)) [V]. Re-evaluate on these triggers:

- `onStartInput` / `onFinishInput`;
- `TYPE_WINDOWS_CHANGED`;
- `ACTION_SCREEN_ON`, `ACTION_SCREEN_OFF` and `ACTION_USER_PRESENT`, through a runtime receiver;
- `DisplayManager.DisplayListener`;
- `onConfigurationChanged`.

### 3.5 Fold, unfold, rotation, multi-window, cover display

- **Fold and unfold.** On Samsung and Pixel foldables the inner and cover panels are normally the same logical `DEFAULT_DISPLAY`, switching physical panels, so the service sees `onConfigurationChanged` with new bounds [I]. Ramblr confirmed it "fires reliably on a Pixel Fold fold/unfold" [V, source]. Verify on the Fold7 [DEV].
  - On change: recompute bounds with `WindowManager.getCurrentWindowMetrics()` / `getMaximumWindowMetrics()`.
  - Keep the bubble on the same edge, at the same proportional y. Ramblr's `snappedXForScreenChange` is a correct reference.
  - Re-clamp inside the insets: `getInsetsIgnoringVisibility(systemBars | displayCutout)`.
- **180° rotation** changes no size, so no configuration change arrives. Use `DisplayListener.onDisplayChanged` and compare `display.rotation` (Ramblr #352) [V, source].
- **Multi-window** (Fold7 split view and pop-up view). There are several `TYPE_APPLICATION` windows; the target is the one with `isFocused()`. Capture that window id for the screenshot and the insertion. The IME follows the focused window. Pop-up (freeform) windows move, so do not anchor the bubble to them.
- **Cover display while folded.** Same rules. The smaller width needs a smaller default bubble and edge margin. If the cover screen turns out to be a separate logical display on One UI 9, use `getWindowsOnAllDisplays()` and attach a window per display. Design `OverlayHost` per `displayId` from day one [I] [DEV].
- **Flex mode (half-folded)** needs no special handling. It is just a size and posture change [I].

---

## 4. Microphone

### 4.1 Legality chain, current Android

1. **Runtime permission.** `RECORD_AUDIO` is a while-in-use runtime permission [V]. Request it from `MainActivity` during onboarding, never from the bubble: a dialog from an overlay is ugly and may be blocked.
2. **Process capability.** AOSP binds every enabled accessibility service with `BIND_AUTO_CREATE | BIND_FOREGROUND_SERVICE_WHILE_AWAKE | BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS | BIND_INCLUDE_CAPABILITIES` [V, AOSP `AccessibilityServiceConnection.java` L162–165]. `OomAdjuster` passes the client's capabilities to the bound process when `BIND_INCLUDE_CAPABILITIES` is set, and system_server is persistent [V, AOSP]. Ramblr's ADR-0002 reaches the same conclusion and records from the accessibility service with no FGS [V, source]. So the process may capture audio while the service is bound.
3. **FGS start legality (Android 14+).** `startForegroundService` of type `microphone` needs `FOREGROUND_SERVICE_MICROPHONE` (normal) and `RECORD_AUDIO` [V]. It must be started while the app counts as visible, or under an exemption [V, FGS docs]. The visible accessibility overlay supplies `REASON_UID_VISIBLE` (§3.1) [V, AOSP]. The persistent system binding with `BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS` is a second path (`shouldAllowFgsWhileInUsePermissionByBindingsLocked`) [V, AOSP].
4. **Android 17 background-audio hardening.** Audio playback, **audio focus requests** and volume APIs fail outside a valid lifecycle: "must have a visible activity or must be running a foreground service that is not of type `SHORT_SERVICE`". For apps targeting 37, "the foreground service must have while-in-use (WIU) capabilities". A failing focus request returns `AUDIOFOCUS_REQUEST_FAILED` [V, [bg-audio](https://developer.android.com/about/versions/17/changes/bg-audio)]. Recording itself is not on the affected list [V]. Test with `adb shell cmd audio set-enable-hardening throw` [V].
5. **Decision.** Start `RecordingFgs` (type `microphone`) in the press handler, before `AudioRecord.startRecording()`, in the same process. Stop it as soon as capture ends. Processing then continues on the accessibility binding's priority, with a bounded partial wake lock so screen-off decode is not throttled (the Ramblr pattern).
   - Android 12+ may defer an FGS notification by up to 10 s ([behavior changes 12](https://developer.android.com/about/versions/12/behavior-changes-all)) [V]. Short dictations may therefore never show it.
   - `Notification.Builder.setForegroundServiceBehavior(FOREGROUND_SERVICE_DEFERRED|IMMEDIATE)` (API 31) controls this [V]. Which characteristics force immediate display (action buttons, for example) is [U].
   - Recommendation: `DEFERRED`, plus a Stop action in the notification.
6. **Concurrency.** The CDD requires that devices "MUST allow concurrent access to microphone by an accessibility service capturing with AudioSource.VOICE_RECOGNITION and at least one application capturing with any AudioSource" [V, [CDD 16 §5.4](https://source.android.com/docs/compatibility/16/android-16-cdd)]. The platform guide adds that with the service's UI on top, both receive audio [V, [sharing audio input](https://developer.android.com/media/platform/sharing-audio-input)]. During a voice call (`MODE_IN_CALL`/`MODE_IN_COMMUNICATION`), "The app can capture audio if it is an accessibility service" [V].
   - Always register `AudioRecord.registerAudioRecordingCallback()` before start and watch `AudioRecordingConfiguration.isClientSilenced()` [V].
   - Port intent-voice's digital-silence detector (§9.3) to report "mic blocked by privacy toggle or another app".

### 4.2 Source and format for ASR

| Choice | Value | Why |
|---|---|---|
| Source | `MediaRecorder.AudioSource.VOICE_RECOGNITION` | CDD: devices "MUST, by default, disable any noise reduction" and "any automatic gain control" for this source, with a flat ±3 dB response at 100 Hz–4 kHz [V, CDD 16 §5.4.x]. That is what ASR models are trained on. It also gets the accessibility concurrency privilege above. |
| Fallback | `MIC` (Ramblr uses it [V, source]), then `UNPROCESSED` if `AudioManager.getProperty(PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)` is true [V] | Some OEM HALs ship a hot or noisy `VOICE_RECOGNITION` path [U]. Expose it as an advanced setting with an A/B benchmark. |
| Rate / format | 16 000 Hz, mono, `ENCODING_PCM_16BIT` (or `PCM_FLOAT`), through `AudioRecord.Builder` | Whisper, Parakeet, GigaAM, T-one and Qwen3-ASR all take 16 kHz [I]. The CDD guarantees 44.1/48 kHz capture for `VOICE_RECOGNITION` [V]. 16 kHz is resampled by the framework; benchmark one 48 kHz capture plus our own resampler as an alternative [DEV]. |
| Buffer | `max(getMinBufferSize*2, 500 ms)`, read in 20–50 ms chunks on a dedicated thread at `THREAD_PRIORITY_URGENT_AUDIO` | Matches intent-voice and Ramblr [V, source]. |
| Gain | Software peak-normalise before ASR. Keep the raw signal in history. | AGC is off on this source [V]. |
| Privacy | `AudioRecord.Builder.setPrivacySensitive(true)` stops other privileged apps from listening concurrently [V] | Optional. Default false. |

### 4.3 Bluetooth headsets

- Classic BT headsets need the SCO link: `AudioManager.setCommunicationDevice(device)` with a device from `getAvailableCommunicationDevices()`, cleared with `clearCommunicationDevice()`. These replace `startBluetoothSco` [V, [AudioManager](https://developer.android.com/reference/android/media/AudioManager)]. Priority goes to whichever app controls `setMode(MODE_IN_COMMUNICATION)` [V]. That needs `MODIFY_AUDIO_SETTINGS` (normal) [V] and makes Murmur look like a call to other apps [I].
- LE Audio headsets (Galaxy Buds on Android 13+): find an input with `AudioDeviceInfo.TYPE_BLE_HEADSET` and call `AudioRecord.setPreferredDevice(device)` [V, [BLE audio recording](https://developer.android.com/develop/connectivity/bluetooth/ble-audio/audio-recording)].
- Recommendation: **default to the built-in mic** even when a headset is connected. Offer "Use headset microphone" as a setting.
  - When on: use LE Audio via `setPreferredDevice`, or SCO via `setCommunicationDevice` without `MODE_IN_COMMUNICATION` first, with `MODE_IN_COMMUNICATION` as a fallback.
  - Show a "connecting headset mic…" state. Record from the built-in mic until `AudioRecordingCallback` reports the BT device, then switch.
  - SCO is 8/16 kHz narrowband/wideband, which hurts ASR [I].
  - Route setup latency and the Galaxy Buds behaviour need device testing [DEV].

### 4.4 Audio focus

Optional setting "Pause media while dictating", default on. Request `AudioFocusRequest` with `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE` and `AudioAttributes(USAGE_ASSISTANT, CONTENT_TYPE_SPEECH)` **after** the mic FGS is running; Android 17 needs this order (§4.1.4). Abandon focus at stop. If the request fails, record anyway.

### 4.5 Do not clip the first word

There is no true pre-roll: the mic cannot legally or ethically be open before the tap, and the privacy indicator would show. Reduce tap-to-capture latency instead:

1. Start on `ACTION_DOWN` (§3.3). A tap is about 80–150 ms, so this alone saves roughly one tap duration [I].
2. On the press thread, do nothing before `startRecording()` except `startForegroundService`:
   - no Binder calls to `:infer`;
   - no tree walk;
   - no screenshot;
   - no audio-focus request, which can trigger other apps' fade-outs.
3. Keep one `AudioRecord` construction path warm. Build the `AudioFormat` and buffer size once at service connect. Do not hold an `AudioRecord` instance open.
4. Show "listening", with the haptic, only after the first non-zero buffer arrives. Measure the latency from press to first buffer and log it to the benchmark screen. Until then show "starting" (a pulsing outline). Users learn to wait for the confirmation.
5. Never drop the initial buffers, and never trim leading silence before ASR. If VAD is used for chunking, give each segment about 300 ms of pre-roll (Ramblr #196, `SpeechSegmenter.kt`) [V, source].

---

## 5. Restrictions for sideloaded apps

### 5.1 Restricted settings (Enhanced Confirmation Mode)

- **What it guards** (AOSP `EnhancedConfirmationService.PROTECTED_SETTINGS`) [V, AOSP]:
  - app-ops `BIND_ACCESSIBILITY_SERVICE`, `ACCESS_NOTIFICATIONS`, **`SYSTEM_ALERT_WINDOW`**, `GET_USAGE_STATS` and `LOADER_USAGE_STATS`;
  - device admin;
  - the SMS permissions;
  - the dialer and SMS roles.

  Murmur touches only accessibility. Overlay would be a second guarded setting, which is another reason to avoid SAW.
- **Who is guarded** (`isPackageEcmGuarded`) [V, AOSP]:
  - Always guarded: an install with `PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE` or `PACKAGE_SOURCE_DOWNLOADED_FILE` (file manager, browser download).
  - Otherwise guarded unless the immediate installer is preinstalled or allowlisted.
  - The allowlist is device sysconfig, presumably Play Store and Galaxy Store [I].
- **adb.** Whether `adb install` counts as guarded depends on the installer package recorded for adb sessions. That was not verified [U]. Plan for the dialog.
- **Detection in-app.** `AppOpsManager.unsafeCheckOpNoThrow("android:access_restricted_settings", uid, pkg) != MODE_ALLOWED` (Ramblr `RestrictedSettingsCheck.kt`) [V, source]. The op string matches AOSP `OPSTR_ACCESS_RESTRICTED_SETTINGS` [V, AOSP].
- **User steps.** Google calls it Settings → Apps → *app* → **More** → **Allow restricted settings** ([Android Help](https://support.google.com/android/answer/12623953)) [V]. The option appears only after the user has hit the "Restricted setting" dialog once [V, multiple secondary sources]. On One UI it is under the ⋮ menu on App info [U, secondary].

### 5.2 Android developer verification

- Enforcement began on **2026-09-30**. It covers only *participating stores* in Brazil, Indonesia, Singapore and Thailand. Global rollout follows in 2027 [V, [developer-verification](https://developer.android.com/developer-verification)].
- Direct sideloads are not yet affected anywhere [V, [FAQ](https://developer.android.com/developer-verification/guides/faq)].
- **ADB installs are exempt and will stay so**: "you are free to install apps without verification with ADB" [V]. After 2027, non-adb installs of unregistered apps need the "advanced flow". That is a one-time setup: Developer options, a coaching check, a reboot, a 24 h wait, biometric confirmation, then enable for 7 days or indefinitely [V]. Updates of unregistered apps also need the advanced flow or adb [V].
- Option: a free **limited distribution** account covers up to 20 devices with no ID [V]. Worth registering `app.murmur` early so future updates are not blocked [I].

### 5.3 Android Advanced Protection (AAPM)

- When on, it blocks installs from unknown sources (and updates of them), keeps Play Protect on, and "Restricts accessibility services to verified accessibility tools" [V, [Android Help](https://support.google.com/android/answer/16339980)].
- Press reports say the accessibility restriction landed in Android 17. It greys out non-tool services ("Restricted by Advanced Protection") and revokes existing grants [V, secondary: [securityaffairs](https://securityaffairs.com/189497/security/advanced-protection-mode-in-android-17-prevents-apps-from-misusing-accessibility-services.html), [Android Authority](https://www.androidauthority.com/android-17-beta-2-advanced-protection-mode-accessibility-apps-3648860)].
- **Murmur is incompatible with AAPM by design.** Detect it with `AdvancedProtectionManager.isAdvancedProtectionEnabled()` (API 36). That call needs `QUERY_ADVANCED_PROTECTION_MODE`, whose protection level was not verified [U]. Explain the conflict in onboarding.

### 5.4 Samsung layers

| Feature | Effect | Steps |
|---|---|---|
| **Auto Blocker** (on by default on devices that shipped with One UI 6.1.1+, so the Fold7 too) | Blocks installs from outside Galaxy Store and Play, blocks "unauthorized commands" over USB, blocks malicious images in messaging apps [V, [Samsung support](https://www.samsung.com/us/support/answer/ANS10003636/), [9to5Google](https://9to5google.com/2024/07/23/samsung-galaxy-sideloading-android-apps/)] | Settings → Security and privacy → Auto Blocker → Off (needs authentication). One UI 8.5+ offers re-enabling after 30 min [V, secondary]. Disable it before adb (USB commands) and before any APK install. Re-enabling afterwards does not uninstall Murmur [I]. |
| **One UI 9 "high-risk app" blocking** | "when new high-risk apps are detected, it now warns users, blocks execution and installation, and recommends deletion through security policy updates" (Samsung press release, quoted by [Android Authority](https://www.androidauthority.com/samsung-one-ui-9-manage-unknown-apps-3666922/) and [Gizchina](https://www.gizchina.com/samsung/one-ui-9-beta-blocks-high-risk-apk-sideloading)) [V, secondary] | Unknown criteria. Reports mention permission profile and origin [U]. Keep the permission set minimal: no SMS, no notification listener, no SAW, no `QUERY_ALL_PACKAGES` (§10). Sign every build with one stable release key. |
| **Settings → Security and privacy → More security settings → Manage unknown apps** (One UI 9) | Lists sideloaded apps for the user to review [V, secondary] | Informational only |
| **Accessibility list location** | Samsung lists third-party services under Accessibility → **Installed apps** [V, [Wispr Flow help](https://docs.wisprflow.ai/articles/8858845757-setup-wispr-flow-on-android-android-settings)] | Deep-link with `Settings.ACTION_ACCESSIBILITY_SETTINGS` |

### 5.5 Play Protect enhanced fraud protection

Play Protect blocks *internet-sideloaded* installs of apps that request RECEIVE_SMS, READ_SMS, notification listener or **Accessibility**. It is active in Singapore, Brazil, Hong Kong, India, Kenya, Nigeria, the Philippines, South Africa, Thailand, Vietnam and Malaysia [V, secondary; the list may be out of date]. It matters only if the owner is in one of those regions. adb avoids it [I].

### 5.6 Exact onboarding steps (Fold7, One UI 9)

1. Settings → Security and privacy → **Auto Blocker → Off**.
2. Install with `adb install -r murmur.apk`. If adb is blocked, the fallback is to copy the APK and install it from My Files; then expect step 4b.
3. Open Murmur → grant **Microphone** ("While using the app") and **Notifications**.
4. Murmur → "Enable dictation" deep-links to Accessibility → Installed apps → Murmur → On.
   - 4b. If "Restricted setting" appears: Settings → Apps → Murmur → ⋮ → **Allow restricted settings** → authenticate → repeat step 4.
5. Battery (§5.7): App info → Battery → **Unrestricted**. Then Murmur deep-links to Samsung **Never sleeping apps** and the user adds Murmur.
6. Optionally re-enable Auto Blocker.
7. Optionally, for gated models, Model Hub → paste the HF token.

### 5.7 Samsung battery management and how to survive it

- **Sleeping and deep sleeping.** Samsung's [developer page](https://developer.samsung.com/mobile/app-management.html) [V] says:
  - "Sleeping" applies to apps unused for about 3 days that also hurt system health. Job, Alarm and FGS are restricted for them.
  - "Deep sleeping" applies after about 16 days; such apps "only become active when the user opens them".
  - Exceptions live at Settings → Device care → Battery → Background usage limits.
  - Deep link: `Intent("com.samsung.android.sm.ACTION_OPEN_CHECKABLE_LISTACTIVITY").setPackage("com.samsung.android.lool").putExtra("activity_type", 2)` for "never sleeping apps" [V].
  - "Since One UI 6.0, foreground services of apps targeting Android 14 will be guaranteed to work as intended so long as they are developed according to Android's new foreground service API policy" [V].
- **Force stop disables the accessibility service.** AOSP `AccessibilityManagerService.onPackagesForceStoppedLocked` removes the package's services from the enabled set [V, AOSP]. Wispr Flow: "After a force-stop, Android blocks automatic restart" [V]. Mitigations:
  - On every `MainActivity.onResume`, detect "service enabled in settings but not connected".
  - Post a recovery notification from a daily WorkManager check (Ramblr's `ServiceRecoveryWorker`).
  - Never instruct users to force-stop.
- **A crash does not disable the service.** On `binderDied`, the system marks the service crashed and re-binds it when the process restarts (`initializeService` clears the crashed state) [V, AOSP]. Repeated crashes back off [I]. This is the main reason native inference runs in `:infer` (§8).
- **Battery optimisation.** Request `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` with the `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission (normal) [V]. Play restricts this permission; sideloading is unaffected [I]. Also add Murmur to Never sleeping. While enabled, the accessibility binding keeps the process at bound-FGS importance [V, AOSP].

---

## 6. Clipboard from the background

- **Writes from the background are allowed.** AOSP `ClipboardService.clipboardAccessAllowed`: `case OP_WRITE_CLIPBOARD: // Writing is allowed without focus. allowed = true` [V, AOSP]. Reads need input focus or the default-IME role [V, AOSP; [Android 10 privacy](https://developer.android.com/about/versions/10/privacy/changes)].
- **Do not try to restore the user's previous clip.** The overlay is `FLAG_NOT_FOCUSABLE`, so Murmur cannot read the previous clip [I].
- Call `setPrimaryClip(ClipData.newPlainText("Murmur", text))` on the main thread. Port intent-voice's `AndroidClipboardWriter`, which hops to the main looper with a 1 s latch, as an OEM safety measure.
- On Android 13+ the system shows its own copy confirmation, so do not add a toast [V, [copy-paste guide](https://developer.android.com/develop/ui/views/touch-and-input/copy-paste)]. `ClipDescription.EXTRA_IS_SENSITIVE` hides the preview [V]. Offer "Hide dictations from clipboard preview" as a setting, default off.
- Clipboard history in Samsung Keyboard and Gboard will retain dictations [I]. Mention it in privacy settings.

---

## 7. Storage

### 7.1 History and audio

| Codec | Bitrate (mono 16 kHz) | 1 h of speech | Heavy week (1 h/day) | Notes |
|---|---|---|---|---|
| PCM WAV | 256 kbit/s | 115 MB | 806 MB | Fine as a temp file during processing only |
| AAC-LC in .m4a | about 32 kbit/s | about 14 MB | about 100 MB | Universally playable. MediaCodec plus `MUXER_OUTPUT_MPEG_4`. |
| **Opus in .ogg** | **about 24 kbit/s** | **about 11 MB** | **about 76 MB** | Opus encoder on Android 10+, decoder on 5.0+, Ogg container [V, [supported formats](https://developer.android.com/media/platform/supported-formats)]. `MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG` [V]. **Recommended.** |

Sizes are arithmetic from the bitrate [I].

- **Pipeline.** Record to `cacheDir/rec_<id>.pcm`, streaming rather than accumulating a `ByteArray` (the Ramblr pattern). After delivery, encode to `filesDir/history/<yyyy-MM-dd>/<id>.ogg` on a low-priority dispatcher, then delete the PCM.
- **History row (Room):**
  - id, timestamps;
  - package and app label;
  - field kind;
  - raw transcript, final text;
  - ASR and LLM model ids and revisions;
  - durations, latencies (record → first buffer, ASR, LLM, insert);
  - insertion method and result;
  - audio path, audio bytes;
  - error code.

  Dashboard stats come from aggregate queries on this table.
- Re-transcribing from Opus is fine for ASR [I].

### 7.2 Retention (7 days)

1. `DELETE FROM history WHERE created_at < now-7d`, then delete the files.
2. Then sweep orphan files: date directories older than 8 days, and any `.pcm` in cache older than 1 h.
3. Run it in three places:
   - at accessibility `onServiceConnected`;
   - after each history insert, debounced to at most once an hour;
   - in a daily `PeriodicWorkRequest`.

   The first two do not depend on WorkManager surviving Samsung's sleeping-app rules.

### 7.3 Settings and secrets

- Use Proto or Preferences **DataStore** for settings. Store default prompts as assets copied from `prompts/`; store user overrides as rows or files with a "reset to default" action.
- Encrypt the **HF token** with AES-256-GCM under a non-exportable Android Keystore key. intent-voice's `KeystoreSecretCipher` already does this (§9.3).
- Do not use `androidx.security:security-crypto`. Its "Deprecated all APIs in favour of existing platform APIs and direct use of Android Keystore" note is in the [release notes](https://developer.android.com/jetpack/androidx/releases/security) [V].
- Never log the token.
- Exclude the token, models and history from backup. `noBackupFilesDir` covers models; `dataExtractionRules` covers the rest.

### 7.4 Large model downloads from Hugging Face

HF mechanics verified with live requests on 2026-11-18 [V]:

- **Metadata.** `GET https://huggingface.co/api/models/<repo>` returns `sha` (commit), `gated` (`false`, `"auto"` or `"manual"`) and `siblings[]`. `GET …/api/models/<repo>/tree/<sha>` returns per-file `size`, `lfs.oid` (SHA-256), `lfs.size` and `xetHash`.
  - Example: `Qwen/Qwen3-ASR-0.6B` at commit `5eb144179a02acc5e5ba31e748d22b0cf3e303b0`, `model.safetensors`, size 1 876 091 704, SHA-256 `79d6cbd4…23ea`.
- **Download.** `GET https://huggingface.co/<repo>/resolve/<sha>/<path>`. Small files answer 307 to a same-host `/api/resolve-cache/...`. LFS/Xet files answer 302 to `https://us.aws.cdn.hf.co/xet-bridge-us/...`.
  - The signed URL carries `Expires` about **1 hour** ahead.
  - Response headers include `X-Repo-Commit`, `X-Linked-Size`, `X-Linked-ETag` (equal to the SHA-256 for LFS files) and `Accept-Ranges: bytes`.
  - The CDN honours `Range` (`206`, `Content-Range: bytes 0-15/1876091704`).
- **Gated repos.** Without a token they return 401 (checked on `meta-llama/Llama-3.2-1B`, `gated: "manual"`). With `Authorization: Bearer <token>` the user must also have accepted the repo terms on the website [I].
- OkHttp removes `Authorization` "When redirecting across hosts" [V, [OkHttp source](https://github.com/square/okhttp/blob/master/okhttp/src/commonJvmAndroid/kotlin/okhttp3/internal/http/RetryAndFollowUpInterceptor.kt)], so the token never reaches the CDN.

Design:

1. **Plan.** On "Download" in Model Hub:
   1. Resolve the repo to a commit `sha` and pin it.
   2. Build a file list with expected size and SHA-256.
   3. Check space with `StorageManager.getAllocatableBytes(uuid)` and reserve it with `allocateBytes(fd, bytes)` [V].
   4. Persist the plan in Room as a `download` table: one row per file with state, bytes done and expected hash.
2. **Run.** `JobInfo.Builder(id, ComponentName(ctx, ModelDownloadJob))`:
   - `.setUserInitiated(true)`;
   - `.setRequiredNetwork(NetworkRequest)` (unmetered by default; let the user allow metered);
   - `.setEstimatedNetworkBytes(total, 0)`.

   It needs `RUN_USER_INITIATED_JOBS` (normal). Inside, call `setNotification(params, id, notif, JOB_END_NOTIFICATION_POLICY_DETACH)` and keep calling `updateTransferredNetworkBytes(...)` [V, [UIDT](https://developer.android.com/develop/background-work/background-tasks/uidt)].
   - UIDT jobs must be scheduled while the app is visible, which is fine from Model Hub [V]. They are exempt from standby-bucket quotas [V]. Android 16 subjects ordinary jobs running alongside an FGS to quotas, and the docs point downloads to UIDT [V].
   - If the user presses Stop in Task Manager, the process is killed and `onStopJob` is not called [V]. Persist progress continuously.
3. **Per file:**
   1. Write `<dest>.part` with `Range: bytes=<partLen>-`.
   2. Re-resolve through huggingface.co on every attempt, because the CDN URL expires in about 1 h.
   3. If the response is `200` instead of `206`, truncate and restart.
   4. If `X-Linked-ETag` differs from the plan, abort, because the file changed.
   5. Hash incrementally with `MessageDigest("SHA-256")` while writing. On resume, re-hash the existing part first.
   6. When finished, compare with `lfs.oid`, `fsync`, then `rename` atomically into `noBackupFilesDir/models/<repo>@<sha>/`.
   7. Non-LFS files (configs, tokenizer) have only a git blob `oid`. Verify their size and keep the `X-Linked-ETag`.
4. **Progress UI** observes the Room rows (Flow), so it survives process death. After the process restarts, `onStartJob` resumes from the rows. Use exponential back-off (`setBackoffCriteria`) for 5xx and network loss. A 401 or 403 needs user action: token or gate.
5. **Never execute downloaded code.** If an NPU runtime needs extra `.so` files, ship them in the APK. Android 17 requires `System.load()` targets to be read-only for apps targeting 37 [V].

---

## 8. Memory and process model

### 8.1 Guaranteeing models do not hold RAM while idle

| Fact | Source |
|---|---|
| Android 17 imposes app memory limits based on device RAM. An exceeded app exits with `ApplicationExitInfo.REASON_OTHER` and a description containing `"MemoryLimiter:AnonSwap"`. Inspect with `adb shell am memory-limiter status`; adjust with `am memory-limiter ignore|manual`. Whether the limit is per process or per UID is not stated [U]. | [V, behavior-changes-all](https://developer.android.com/about/versions/17/behavior-changes-all) |
| `ProfilingManager` `TRIGGER_TYPE_ANOMALY` delivers a heap dump *before* the memory-limit kill | [V, A17 features](https://developer.android.com/about/versions/17/features) |
| bionic `mallopt(M_PURGE, 0)` (API 28) "immediately purge any memory not in use… release the memory back to the kernel". `M_PURGE_ALL` (API 34) is more thorough. | [V, bionic malloc.h](https://android.googlesource.com/platform/bionic/+/refs/heads/main/libc/include/malloc.h) |
| `onTrimMemory`: since API 34 apps are not notified of `TRIM_MEMORY_RUNNING_CRITICAL` or `TRIM_MEMORY_COMPLETE`. Only `TRIM_MEMORY_UI_HIDDEN` and `TRIM_MEMORY_BACKGROUND` remain useful. | [V, ComponentCallbacks2](https://developer.android.com/reference/android/content/ComponentCallbacks2) |

Approach:

1. **`:infer` process** (`android:process=":infer"`) hosts `InferenceService`, a bound AIDL service. It is the only code that loads ASR, LLM or OCR runtimes, including NPU/QNN contexts.
2. On dictation press, `:main` calls `bindService(intent, conn, BIND_AUTO_CREATE | BIND_IMPORTANT)`. The binder client is the accessibility process at bound-FGS state, so `:infer` inherits that importance [I, AOSP `OomAdjuster`]. Load the ASR model first, then the LLM if there is RAM headroom.
   - Use `ActivityManager.getMemoryInfo(availMem, threshold, lowMemory)` [V].
   - If headroom is insufficient, load serially: ASR, free ASR, then LLM.
3. **Idle timeout.** After a dictation completes, keep `:infer` warm for N seconds (setting, default 60 s), so back-to-back dictations skip the load. Then:
   1. `:main` unbinds.
   2. `InferenceService.onUnbind` frees all runtimes.
   3. It calls `mallopt(M_PURGE_ALL)` from JNI.
   4. It calls `Process.killProcess(Process.myPid())`.

   The kill is the guarantee. Without it the process becomes *cached* and keeps its native heap until LMK reclaims it [I]. NPU (FastRPC/dmabuf) mappings are released at process exit [I]. Check with `dumpsys meminfo` that the `:infer` PSS goes to 0 [DEV].
4. **Prefer mmap-backed weights** (llama.cpp GGUF does this by default). File-backed clean pages are reclaimable and not "anon", which is what the Android 17 limiter names [I]. Buffers copied into anon memory or DSP memory are the real cost.
5. **Benchmark** runs in `:infer` with the same lifecycle, so it measures cold load.
6. A **native crash in `:infer`** surfaces as `onBindingDied` / `DeadObjectException` in `:main`. The dictation falls back: audio and history are kept, the result is "transcription failed, retry", and the accessibility service stays alive.

### 8.2 UI responsiveness

- `:main` never runs inference. Its main thread handles only overlay, node and IME calls.
- Node reads and tree walks run on a single-threaded `HandlerThread` dispatcher. They are synchronous binder calls.
- Audio capture uses a dedicated thread at `THREAD_PRIORITY_URGENT_AUDIO` [V].
- PCM streams to `:infer` through a `ParcelFileDescriptor` pipe, or a `SharedMemory` ring [V], with partial results delivered by an AIDL callback.
- In `:infer`, use `PerformanceHintManager` (API 31) sessions for CPU inference threads [V]. Watch `SystemHealthManager.getCpuHeadroom` / `getGpuHeadroom` (API 36) [V] when choosing thread counts during the benchmark.

### 8.3 Thermal and low-memory signals worth honouring

| Signal | API | Action |
|---|---|---|
| Thermal status | `PowerManager.getCurrentThermalStatus()`, `addThermalStatusListener` (API 29); `getThermalHeadroom(forecastSeconds)` (API 30, at most about 1 call/s) [V] | At `THERMAL_STATUS_SEVERE` (3) or above: skip the LLM rewrite and insert the raw transcript. At `CRITICAL`: refuse a new dictation with a message. |
| Low memory | `ActivityManager.MemoryInfo.lowMemory`, `availMem` [V] | Load serially; skip OCR |
| Trim | `onTrimMemory(TRIM_MEMORY_BACKGROUND/UI_HIDDEN)` in `:infer` [V] | Treat as "idle now": unload and exit if no dictation is in flight |
| Past kills | `ActivityManager.getHistoricalProcessExitReasons(pkg, 0, 10)` [V] | Show "Murmur was stopped by the system (memory)" in diagnostics. Lower defaults automatically. |
| Battery saver | `PowerManager.isPowerSaveMode()` | Prefer the smaller model; OCR off |

---

## 9. Prior art

### 9.1 Projects read (shallow clones, read-only, 2026-11-18)

| Project | Stars / last push | What it is | Relevance |
|---|---|---|---|
| [trevornk/ramblr](https://github.com/trevornk/ramblr) (GPL-3.0) | 107 / 2026-11-18 | Accessibility floating ring, sherpa-onnx + llama.cpp local, IME, QS tile, PROCESS_TEXT | **The most mature reference.** Many Fold and WebView findings cited above. GPL: read for ideas, do not copy code. |
| [soupslurpr/Transcribro](https://github.com/soupslurpr/Transcribro) (ISC) | 764 / 2026-11-12 | On-device whisper.cpp keyboard plus `RecognitionService` (FGS type `microphone`) | On-device ASR on Android, at the IME level |
| [apharrside-cloud/mouthpiece](https://github.com/apharrside-cloud/mouthpiece) (MIT) | 2 / 2026-11-18 | whisper.cpp bubble. `TYPE_ACCESSIBILITY_OVERLAY`, `VOICE_RECOGNITION`, SET_TEXT/PASTE. | Sets `isAccessibilityTool="true"`: do **not** copy that (§0.2) |
| [melanielingthai/vox-android](https://github.com/melanielingthai/vox-android) (MIT) | 0 / 2026-10-28 | whisper.cpp + Gemma cleanup. `TYPE_APPLICATION_OVERLAY` + mic FGS. | SAW-based variant |
| [Lame-Marine/aoide](https://github.com/Lame-Marine/aoide) (Apache-2.0) | 0 / 2026-10-08 | Offline bubble (Phone Whisper fork). `TYPE_ACCESSIBILITY_OVERLAY`, SET_TEXT then PASTE. | Confirms the ladder |
| [orev7s/bubble](https://github.com/orev7s/bubble), [digit1024/mbdictate](https://github.com/digit1024/mbdictate) | 0 / Oct–Nov 2026 | Cloud ASR bubbles. SAW overlay + mic FGS, SET_TEXT then PASTE. | Same pattern |
| Wispr Flow Android (closed; [help center](https://docs.wisprflow.ai/)) | — | Commercial reference | Shows the bubble only with the keyboard open; uses SAW, an ongoing notification and a battery exemption; clipboard fallback; hides in banking apps; about 5 min cap |

Proven approaches, each seen in at least two shipping projects [V, source]:

- Accessibility service plus overlay bubble.
- `ACTION_SET_TEXT` with `ACTION_PASTE` as the fallback, plus clipboard fallback.
- 16 kHz mono PCM16 `AudioRecord`.
- whisper.cpp or sherpa-onnx in-process on CPU.
- Password-field refusal.
- Exclusion lists.
- Hide over the lock screen.

Not proven anywhere I read:

- `commitText` through the accessibility IME as a *primary* path (Ramblr uses it for Termux only);
- `takeScreenshotOfWindow` + OCR for context;
- a separate inference process;
- Compose inside the overlay. All of these use plain Views.

These are Murmur's novel parts and need early device spikes.

### 9.2 Specific lessons to adopt (all from Ramblr source comments)

- Never `View.GONE` an accessibility-overlay root on foldables (the about 2 s fold stall). Use `alpha` with `NOT_TOUCHABLE`, or remove the window.
- `TYPE_ACCESSIBILITY_OVERLAY` draws on the keyguard and AOD. Hide it on `isKeyguardLocked` and when the display state is not ON. AOD reports DOZE states.
- Catch `BadTokenException` on `addView`.
- WebView: skip focused non-field nodes. Correct the `0/1` select-all bug. Never whole-field write into multi-line WebView rich editors.
- Termux needs `commitText`.
- The empty-scan retry runs at 200 ms.
- At delivery time, re-check that the foreground package is still the dictation target.
- Detect restricted settings with `android:access_restricted_settings` and guide the user.
- A one-shot "service died" recovery notification, through a periodic worker.

### 9.3 `/home/private/projects/intent-voice` review

**State.**
- About 2 200 lines of Kotlin, with no git commits.
- `settings.gradle.kts` includes `:data:transcription`, `:platform:accessibility`, `:ui:overlay`, `:ui:settings` and `:app`, which **do not exist**, so it does not build.
- Toolchain: AGP 8.7.3, Kotlin 2.0.21, compileSdk 35, minSdk 26. All stale.
- The engines are cloud-only: Gemini, OpenAI audio-chat, Groq Whisper.
- There is no accessibility, overlay or app code at all.

**Worth carrying over:**

| File | Why |
|---|---|
| `core/domain/usecase/DictationController.kt` + test | Clean coroutine state machine. A `Mutex` serialises transitions, a per-session `generation` stops stale results from overwriting state, and context capture runs concurrently with a timeout. Extend it to the states in §12. |
| `core/domain/policy/SmartSpacing.kt` + test | Correct spacing against the text before and after the cursor, including CJK/Thai |
| `core/domain/policy/ContextSanitizer.kt` | Payment-card (Luhn) redaction, budgets, password-field stripping. Still worth having even fully on-device, because context reaches the LLM prompt and history. |
| `core/domain/policy/AppCategoryClassifier.kt` | Package-to-category map including RU apps (`ru.vk.teams`, `ru.yandex.mail`, `ru.mail.mailapp`). Useful for prompt style. |
| `core/domain/model/ScreenContext.kt`, `DictationState.kt`, `Errors.kt` | Good shapes. Drop the cloud error variants; add model, NPU and storage errors. |
| `platform/audio/AndroidAudioRecorder.kt`, `LevelMeter.kt`, `WavEncoder.kt` | `VOICE_RECOGNITION` 16 k, `isClientSilenced` and digital-silence detection, level meter. Change it to stream to a file or pipe instead of `ByteArrayOutputStream`, and add an `AudioRecordingCallback`. |
| `platform/clipboard/AndroidClipboardWriter.kt` | Main-thread hop |
| `data/local/SecretCipher.kt` (`KeystoreSecretCipher`) | AES-GCM Keystore, used for the HF token |

**Drop:**
- all of `Settings.kt` engine config and the HTTP transcriber ports;
- `DataStoreHistoryRepository` (a JSON file capped at 100 entries; use Room);
- the build files.

---

## 10. Proposed module and process layout

```
:app                      application, MainActivity (Compose, 4 tabs), DI wiring, manifest   [process :main]
:core:domain              pure Kotlin: DictationController/state machine, policies (spacing, sanitizer,
                          category), ports (AudioSource, ContextReader, TextInserter, Inference, History…)
:core:model               shared data classes (HistoryEntry, ModelSpec, ScreenContext, …), AIDL parcelables
:feature:dashboard|history|modelhub|settings   Compose screens
:platform:a11y            MurmurAccessibilityService, MurmurInputMethod, ContextReader (tree + window
                          resolution), Screenshotter, TextInserter ladder, BubbleVisibilityPolicy
:platform:overlay         OverlayHost (Compose-in-window), bubble UI, drag/snap, fold/rotation handling
:platform:audio           RecordingEngine (AudioRecord), RecordingFgs (type microphone), BT routing, focus
:platform:clipboard       ClipboardWriter
:data:history             Room DB, OpusEncoder (MediaCodec+MediaMuxer OGG), RetentionWorker
:data:settings            DataStore, prompt store, KeystoreSecretCipher
:data:models              HF client (OkHttp), DownloadPlanner, ModelDownloadJob (UIDT), ModelRegistry
:infer:api                AIDL: IInferenceService, IAsrSession, ILlmSession, IOcr, callbacks
:infer:service            InferenceService [process :infer]: runtime adapters (owned by ML docs),
                          idle-exit, thermal/memory guards, benchmark runner
```

**Processes:**
- **`:main`** holds the accessibility service, overlay, recording, FGS, UI, Room, DataStore and the download job. It is always alive while the service is enabled, at bound-FGS importance while awake. Target under 120 MB PSS when idle [I].
- **`:infer`** is started on demand and self-terminates after idle. It holds everything native and heavy.

**Threads in `:main`:** main (overlay and IME calls), `a11y-io` (tree walks), `audio` (`URGENT_AUDIO`), `Dispatchers.IO` (DB and files), and the job worker.

---

## 11. Manifest essentials

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
          xmlns:tools="http://schemas.android.com/tools">
  <uses-permission android:name="android.permission.RECORD_AUDIO"/>
  <uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
  <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE"/>
  <uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
  <uses-permission android:name="android.permission.INTERNET"/>            <!-- HF downloads only -->
  <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>
  <uses-permission android:name="android.permission.RUN_USER_INITIATED_JOBS"/>
  <uses-permission android:name="android.permission.WAKE_LOCK"/>           <!-- bounded, screen-off decode -->
  <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"/>
  <uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS"/> <!-- only if BT SCO routing ships -->
  <uses-permission android:name="android.permission.QUERY_ADVANCED_PROTECTION_MODE"/>
  <!-- deliberately NOT requested: SYSTEM_ALERT_WINDOW, QUERY_ALL_PACKAGES, READ_SMS, notification listener,
       MANAGE_EXTERNAL_STORAGE, BLUETOOTH_CONNECT (not needed for getDevices/setPreferredDevice) [U for BT] -->

  <application
      android:name=".MurmurApp"
      android:allowBackup="true"
      android:dataExtractionRules="@xml/data_extraction_rules"
      android:fullBackupContent="@xml/backup_rules"
      android:enableOnBackInvokedCallback="true"
      android:extractNativeLibs="true"
      android:theme="@style/Theme.Murmur">
    <!-- extractNativeLibs: Qualcomm QNN tutorials set it so the DSP can load skel libs from disk;
         confirm against the ML runtime doc before shipping [U]. -->

    <uses-native-library android:name="libcdsprpc.so" android:required="false"/> <!-- QNN/Hexagon; per Qualcomm docs [V, vendor] -->

    <activity android:name=".MainActivity" android:exported="true"
              android:windowSoftInputMode="adjustResize">   <!-- must be resizable: A17 ignores orientation locks on sw>=600dp -->
      <intent-filter><action android:name="android.intent.action.MAIN"/>
                     <category android:name="android.intent.category.LAUNCHER"/></intent-filter>
    </activity>

    <service android:name=".a11y.MurmurAccessibilityService"
             android:exported="false"
             android:label="@string/a11y_label"
             android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
      <intent-filter><action android:name="android.accessibilityservice.AccessibilityService"/></intent-filter>
      <meta-data android:name="android.accessibilityservice" android:resource="@xml/murmur_accessibility"/>
    </service>

    <service android:name=".audio.RecordingFgs"
             android:exported="false"
             android:foregroundServiceType="microphone"/>

    <service android:name=".infer.InferenceService"
             android:exported="false"
             android:process=":infer"/>

    <service android:name=".models.ModelDownloadJob"
             android:exported="false"
             android:permission="android.permission.BIND_JOB_SERVICE"/>

    <!-- WorkManager's own SystemForegroundService is not needed: no FGS workers. -->
  </application>
</manifest>
```

The accessibility XML is in §2.1. `data_extraction_rules` excludes `files/history`, `no_backup` (models), the datastore file holding the token ciphertext and the databases.

---

## 12. State machine: one dictation

```
            ┌───────────────────────────────── cancel (long-press / Stop in notif) ─────────────────────────────┐
            │                                                                                                    ▼
IDLE ─press─▶ PREFLIGHT ─ok─▶ ARMING ─first audio buffer─▶ LISTENING ─tap / max-dur / silence-stop─▶ STOPPING ─▶ TRANSCRIBING
  ▲            │fail            │fail                          │ events (below)                                   │
  │            ▼                ▼                              ▼                                                  ▼
  │         BLOCKED         MIC_FAILED                   (stay or STOPPING)                               POSTPROCESSING
  │       (explain, 3 s)   (explain, 3 s)                                                                         │
  │                                                                                                               ▼
  └──────────────── RESULT_SHOWN (≤ 2.5 s, tappable) ◀── DELIVERING (clipboard → insert ladder → history) ◀────────┘
```

| State | Entry actions | Exit / transitions | Failure paths, each ending at the outcome shown |
|---|---|---|---|
| **IDLE** | Bubble per visibility policy; `:infer` warm or absent | press → PREFLIGHT | — |
| **PREFLIGHT** (synchronous, < 5 ms) | Check: service connected; `RECORD_AUDIO` granted; ASR model installed and verified; target not password or excluded; keyguard unlocked; not thermal CRITICAL; free space > 50 MB | ok → ARMING | Mic permission revoked → BLOCKED "Open Murmur to allow microphone" (tap opens app). No model → BLOCKED "Download a speech model" (opens Model Hub). Password field → BLOCKED silently (shake). Thermal → BLOCKED "Phone too hot". Disk full → BLOCKED. |
| **ARMING** | 1. `startForegroundService(RecordingFgs)` → `startForeground(TYPE_MICROPHONE)`. 2. `AudioRecord.startRecording()`. 3. Snapshot target (package, window id, `EditorInfo`, node, selection). 4. Async: bind `:infer` + load ASR (then LLM); schedule screenshot at +400 ms; tree-context walk (≤150 ms); request audio focus. | First non-zero buffer → LISTENING (haptic). Drag/long-press classified → CANCELLED (silent). | `ForegroundServiceStartNotAllowedException` / `SecurityException` → MIC_FAILED "Couldn't start the microphone", logged with reason code; record without FGS as fallback, no focus request. `AudioRecord` not initialised or `RECORDSTATE` ≠ recording → MIC_FAILED "Microphone busy". All-zero audio for 1 s or `isClientSilenced` → MIC_FAILED "Microphone blocked (privacy toggle or another app)". |
| **LISTENING** | Level meter → bubble animation. Stream PCM to file and to `:infer` if streaming ASR. Partial text optional. | tap → STOPPING. Max duration (default 5 min, Wispr-like) → STOPPING + notice. Optional auto-stop on silence. | Model load fails in `:infer` (OOM / NPU init) → keep recording; mark "fallback to CPU / smaller model" for TRANSCRIBING. `:infer` dies → keep recording; re-bind at STOPPING. Service disconnected (user disabled it) → stop capture, save audio to history as "unprocessed", end. Phone call starts (`MODE_IN_CALL`) → STOPPING with "call started". Screen off / keyguard → continue (the user may pocket the phone) until tap or max duration; result goes to clipboard only. Focus moves to another app → continue; delivery rule decides. BT route change → keep recording on the new device (callback); note it in history. |
| **STOPPING** | `AudioRecord.stop()/release()`, abandon focus, `stopForeground` + `stopSelf` on `RecordingFgs`, acquire bounded wake lock (timeout = 2× expected processing) | Audio ≥ 300 ms and peak above the silence floor → TRANSCRIBING; else → RESULT_SHOWN "Didn't catch that" (audio discarded) | — |
| **TRANSCRIBING** | Hand the final PCM to `:infer`; wait for the final transcript. Timeout = max(10 s, 1.5× audio duration). | Text non-empty → POSTPROCESSING | Timeout → retry once on CPU backend → else DELIVERING nothing, history "failed", RESULT_SHOWN "Transcription failed — tap to retry" (retry re-runs from saved PCM). Empty → "Didn't catch that". `:infer` crash → re-bind once, retry, else as timeout. |
| **POSTPROCESSING** | If an LLM is selected and loaded: prompt = system prompt + context (sanitised: app, field hint, text before cursor, OCR text) + transcript. Timeout 8 s. | Output passes validation (non-empty; length ratio within bounds; no prompt echo) → DELIVERING(final). | LLM not loaded in time / thermal SEVERE / timeout / invalid output → DELIVERING(raw transcript) with an "unpolished" marker in history. |
| **DELIVERING** | 1. If focus is now a password field → discard everything (no clipboard, no history text). 2. `setPrimaryClip(text)`. 3. If the foreground package == snapshot package and an editable target exists → insertion ladder (§2.5) with verification. 4. Insert the history row (text, raw, timings, method), schedule Opus encode, run retention. | → RESULT_SHOWN | Clipboard throws → continue to insert. All insert methods fail / moved to another app / no field → "Copied — tap a field and paste" (tap re-copies). Excluded app → "Murmur is off in this app — nothing inserted" (no clipboard, per exclusion semantics). DB write fails → log; text is already delivered. |
| **RESULT_SHOWN** | Pill near the bubble: ✓ inserted / ⧉ copied / ⚠ error. Start the `:infer` idle timer. | Timeout or next press → IDLE | — |
| **CANCELLED** | Stop capture, delete PCM, stop FGS, abandon focus | → IDLE | — |

Invariants:

- Exactly one session is alive at a time.
- Every async callback carries the session `generation` and is dropped if stale (as in intent-voice).
- The FGS lives only while `AudioRecord` is recording.
- The PCM file is deleted on every terminal path except the "failed, tap to retry" path, which keeps it until retention.

---

## 13. Emulator versus phone

Build-server note: `/dev/kvm` exists but is `root:kvm 660`, and user `private` is not in group `kvm` (checked with `id`). The emulator therefore cannot use hardware acceleration until that changes. See "requests for the orchestrator". The toolchain agent is installing `system-images/android-37.0/google_apis/x86_64`.

| Area | Emulator (API 37 google_apis x86_64) | Phone only (Fold7, One UI 9) |
|---|---|---|
| Accessibility config, enable flow, `onServiceConnected`, events | ✓ (enable via `adb shell settings put secure enabled_accessibility_services …`) | Samsung "Installed apps" location; restricted-settings dialog after a real sideload |
| Focus detection: `onStartInput`, `findFocus`, node props | ✓ with stock apps, Chrome/WebView test pages, a Compose test app | Samsung Keyboard composition interplay; Samsung Notes, Samsung Internet, Telegram, WhatsApp, Gmail/Outlook rich editors |
| Insertion ladder A/B/C + verification | ✓ logic and per-surface behaviour on AOSP apps | Real-app matrix (top ~20 apps, RU + EN), Samsung clipboard toast behaviour |
| Screenshot APIs, rate limit, secure window | ✓ (`FLAG_SECURE` test activity) | Real performance; split-screen window ids; cover screen |
| Overlay window, Compose host, drag, lock screen hiding | ✓ (resizable / foldable AVD profile for size changes) | **Fold/unfold BLAST stall, cover display as same/different display, Flex mode, AOD states** |
| Mic legality (FGS type microphone from overlay tap, WIU) | ✓ AOSP behaviour, `adb shell cmd audio set-enable-hardening throw` | Samsung's actual FGS/WIU handling, privacy indicator, mic quality per source (`VOICE_RECOGNITION` vs `MIC`), tap→first-buffer latency |
| Bluetooth headset routing | ✗ | ✓ Galaxy Buds (LE Audio), classic SCO headset |
| Concurrency (call, other recorder, Assistant) | Partial | ✓ |
| Restricted settings / ECM | ✓ by installing via `pm install` from a file path (`PACKAGE_SOURCE_LOCAL_FILE` path) [I] | Auto Blocker, One UI 9 high-risk-app scan, Play Protect |
| Advanced Protection detection | ✓ if the image supports it [U] | — |
| Samsung sleeping / force-stop recovery | Force-stop disables service: ✓ (AOSP) | Device care auto-optimisation, Never-sleeping list deep link |
| Downloads: UIDT, resume, SHA-256, process death | ✓ fully (`adb shell cmd jobscheduler run -f/timeout`, kill process) | Large-file throughput, storage |
| Room/retention/Opus encode | ✓ (unit + instrumented); Robolectric for logic | — |
| `:infer` lifecycle, memory returned (`dumpsys meminfo`), Android 17 memory limiter (`am memory-limiter`) | ✓ for CPU runtimes | **NPU (Hexagon/QNN) init, dmabuf release, thermal throttling, real tokens/s** |
| Thermal / low-memory policy | Simulated (`adb shell cmd thermalservice override-status 3`) [U] | Real |

Spikes to run first on the phone, in order:

1. Overlay tap → microphone FGS → `AudioRecord` with Samsung privacy indicator, and latency logging.
2. `commitText` via the accessibility IME in Samsung Keyboard and Gboard, in 10 apps.
3. Fold/unfold with the Compose overlay attached.
4. `takeScreenshotOfWindow` in split-screen and on the cover screen.
5. `:infer` kill → PSS returns to baseline with the NPU backend loaded.

---

## 14. Sources (primary unless noted)

- Android 17 behavior changes: [all apps](https://developer.android.com/about/versions/17/behavior-changes-all) · [targeting 37](https://developer.android.com/about/versions/17/behavior-changes-17) · [bg audio](https://developer.android.com/about/versions/17/changes/bg-audio) · [features](https://developer.android.com/about/versions/17/features)
- Android 16 [all apps](https://developer.android.com/about/versions/16/behavior-changes-all) · Android 12 [all apps](https://developer.android.com/about/versions/12/behavior-changes-all) · Android 10 [privacy](https://developer.android.com/about/versions/10/privacy/changes)
- References: [AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService) · [AccessibilityServiceInfo](https://developer.android.com/reference/android/accessibilityservice/AccessibilityServiceInfo) · [InputMethod](https://developer.android.com/reference/android/accessibilityservice/InputMethod) · [AccessibilityInputConnection](https://developer.android.com/reference/android/accessibilityservice/InputMethod.AccessibilityInputConnection) · [AccessibilityNodeInfo](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo) · [AccessibilityWindowInfo](https://developer.android.com/reference/android/view/accessibility/AccessibilityWindowInfo) · [WindowManager.LayoutParams](https://developer.android.com/reference/android/view/WindowManager.LayoutParams) · [AudioManager](https://developer.android.com/reference/android/media/AudioManager) · [ComponentCallbacks2](https://developer.android.com/reference/android/content/ComponentCallbacks2) · [PowerManager](https://developer.android.com/reference/android/os/PowerManager) · [StorageManager](https://developer.android.com/reference/android/os/storage/StorageManager) · [JobService](https://developer.android.com/reference/android/app/job/JobService) · [AdvancedProtectionManager](https://developer.android.com/reference/android/security/advancedprotection/AdvancedProtectionManager) · [Manifest.permission](https://developer.android.com/reference/android/Manifest.permission)
- Guides: [FGS background-start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start) · [FGS types](https://developer.android.com/develop/background-work/services/fgs/service-types) · [FGS timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout) · [UIDT](https://developer.android.com/develop/background-work/background-tasks/uidt) · [Sharing audio input](https://developer.android.com/media/platform/sharing-audio-input) · [BLE audio recording](https://developer.android.com/develop/connectivity/bluetooth/ble-audio/audio-recording) · [Supported media formats](https://developer.android.com/media/platform/supported-formats) · [Copy and paste](https://developer.android.com/develop/ui/views/touch-and-input/copy-paste) · [Developer verification](https://developer.android.com/developer-verification) + [FAQ](https://developer.android.com/developer-verification/guides/faq) · [Android 16 CDD](https://source.android.com/docs/compatibility/16/android-16-cdd) · [a11y data-sensitive blog](https://developer.android.com/blog/posts/enhancing-android-security-stop-malware-from-snooping-on-your-app-data) · [security-crypto releases](https://developer.android.com/jetpack/androidx/releases/security)
- AOSP (`main`): `services/accessibility/.../AccessibilityServiceConnection.java`, `AbstractAccessibilityServiceConnection.java`, `AccessibilityManagerService.java`, `AccessibilityUserState.java`; `core/java/android/accessibilityservice/AccessibilityService.java`; `services/core/.../am/ActiveServices.java`, `am/OomAdjuster.java`; `wm/WindowState.java`, `wm/MirrorActiveUids.java`, `wm/ActivityTaskManagerService.java`, `wm/BackgroundLaunchProcessController.java`; `clipboard/ClipboardService.java`; `packages/modules/Permission/.../ecm/EnhancedConfirmationService.java`; `bionic/libc/include/malloc.h`
- Google Help: [Restricted settings](https://support.google.com/android/answer/12623953) · [Advanced Protection](https://support.google.com/android/answer/16339980)
- Samsung: [App management (sleeping apps)](https://developer.samsung.com/mobile/app-management.html) · [Auto Blocker](https://www.samsung.com/us/support/answer/ANS10003636/)
- Wispr Flow help: [setup](https://docs.wisprflow.ai/articles/8858845757-setup-wispr-flow-on-android-android-settings) · [bubble troubleshooting](https://docs.wisprflow.ai/articles/5002934560-why-is-the-wispr-bar-is-not-appearing-or-disappearing) · [reply boxes & address bars](https://docs.wisprflow.ai/articles/5090227041-dictating-in-notification-reply-boxes-and-browser-address-bars)
- Hugging Face live API/headers (2026-11-18): `api/models/Qwen/Qwen3-ASR-0.6B?blobs=true`, `/tree/main`, `resolve/main/model.safetensors` HEAD, CDN Range GET; `meta-llama/Llama-3.2-1B` 401
- OkHttp: [RetryAndFollowUpInterceptor.kt](https://github.com/square/okhttp/blob/master/okhttp/src/commonJvmAndroid/kotlin/okhttp3/internal/http/RetryAndFollowUpInterceptor.kt)
- Secondary (dates and device specs): Android Authority, 9to5Google, GSMArena, securityaffairs, Gizchina, dontkillmyapp.com
