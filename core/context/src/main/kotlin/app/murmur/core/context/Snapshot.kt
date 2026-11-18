package app.murmur.core.context

import app.murmur.core.ocr.Rect

/**
 * What :platform:a11y reads at bubble press (the target snapshot). Exact values from accessibility.
 * Screen TEXT is never part of it (D11): only app, focused field and geometry.
 */
data class A11ySnapshot(
    val pkg: String,
    /** PackageManager label (needs <queries> LAUNCHER, ADR-021) or the window title as a fallback. */
    val label: String?,
    /** AccessibilityWindowInfo.getTitle(): conversation name in messengers (History meta line). */
    val windowTitle: String?,
    val windowId: Int,
    val displayId: Int,
    val field: FieldSnapshot?,
    /** IME window + own overlay + system bars, only needed for the display-capture fallback. */
    val excludeRects: List<Rect>,
    val capturedAtMs: Long,
)

data class FieldSnapshot(
    /** android.text.InputType bits and EditorInfo.imeOptions, as ints (kept platform-free here). */
    val inputType: Int,
    val imeOptions: Int,
    val hint: String?,
    /** getSurroundingText(1000, 0, 0) cut at the selection start; null = unknown. Never for passwords. */
    val beforeCursor: String?,
    val boundsInScreen: Rect?,
    val isPassword: Boolean,
    val isMultiline: Boolean,
)
