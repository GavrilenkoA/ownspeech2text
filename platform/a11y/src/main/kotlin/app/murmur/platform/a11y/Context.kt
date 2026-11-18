package app.murmur.platform.a11y

import android.accessibilityservice.AccessibilityService
import app.murmur.core.context.A11ySnapshot
import app.murmur.core.dictation.InsertResult
import app.murmur.core.dictation.Inserter
import app.murmur.core.dictation.TargetReader
import app.murmur.core.model.Outcome
import app.murmur.core.ocr.ScreenImage

/**
 * TargetReader over the live service (WP-02). Lift spikes/platform SpikeA11yService: targetWindow() (focused
 * TYPE_APPLICATION window, never rootInActiveWindow at tap time), focusedEditable() with BFS fallback for
 * Compose/WebView, ctxV2() fields, shotOnce(). Screenshot (ARCHITECTURE.md 9.1):
 *   takeScreenshotOfWindow(target) -> on error 3 retry once after >= 340 ms -> on error 5 (window gone)
 *   re-resolve, then findFocus(FOCUS_INPUT).windowId, then takeScreenshot(display) + exclusion rects;
 *   error 6 / black frame -> Err(SecureWindow). HardwareBuffer -> ARGB_8888 copy; never PNG.
 */
class A11yTargetReader(private val service: AccessibilityService) : TargetReader {
    override suspend fun snapshot(): A11ySnapshot? = TODO("WP-02")
    override suspend fun screenshot(target: A11ySnapshot): Outcome<ScreenImage> = TODO("WP-02")
    override suspend fun current(): A11ySnapshot? = TODO("WP-02")
}

/** commitText (accessibility IME) -> ACTION_PASTE -> ACTION_SET_TEXT (+SET_SELECTION); verify with getSurroundingText. */
class A11yInserter(private val service: AccessibilityService) : Inserter {
    override suspend fun insert(target: A11ySnapshot, text: String): InsertResult = TODO("WP-02")
    override fun copyToClipboard(text: String): Boolean = TODO("WP-02: ClipData + EXTRA_IS_SENSITIVE=false")
}

/** Restricted-settings detection that works (platform spike REFUTED the app-op check): enabled-but-never-connected + install source. */
object RestrictedSettingsProbe {
    fun likelyBlocked(enabledInSettings: Boolean, everConnected: Boolean, packageSource: Int): Boolean =
        enabledInSettings && !everConnected && packageSource != PACKAGE_SOURCE_STORE

    /** PackageInstaller.PACKAGE_SOURCE_STORE (API 33). */
    const val PACKAGE_SOURCE_STORE = 2
}

/**
 * The orchestrator lives longer than any service instance. These adapters forward to the currently
 * connected service and fail cleanly (AccessibilityOff) when it is not connected.
 */
object A11yBridge {
    val service = kotlinx.coroutines.flow.MutableStateFlow<AccessibilityService?>(null)
}

class BridgedTargetReader : TargetReader {
    private fun delegate() = A11yBridge.service.value?.let { A11yTargetReader(it) }
    override suspend fun snapshot(): A11ySnapshot? = delegate()?.snapshot()
    override suspend fun screenshot(target: A11ySnapshot): Outcome<ScreenImage> =
        delegate()?.screenshot(target) ?: Outcome.Err(app.murmur.core.model.MurmurError.AccessibilityOff)
    override suspend fun current(): A11ySnapshot? = delegate()?.current()
}

class BridgedInserter : Inserter {
    private fun delegate() = A11yBridge.service.value?.let { A11yInserter(it) }
    override suspend fun insert(target: A11ySnapshot, text: String): InsertResult =
        delegate()?.insert(target, text) ?: InsertResult.Failed(app.murmur.core.model.MurmurError.AccessibilityOff)
    override fun copyToClipboard(text: String): Boolean = delegate()?.copyToClipboard(text) ?: false
}
