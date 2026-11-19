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
    /**
     * AccessibilityWindowInfo.getBoundsInScreen() of the target window. The window capture's pixel (0, 0) is this
     * rectangle's top-left, so [CaptureFrame] needs it to put field.box into screenshot pixels (split screen, inner
     * display, freeform). null = unknown: screen coordinates are used as they are (right for full-screen windows).
     */
    val windowBoundsInScreen: Rect? = null,
)

data class FieldSnapshot(
    /** android.text.InputType bits and EditorInfo.imeOptions, as ints (kept platform-free here). */
    val inputType: Int,
    val imeOptions: Int,
    val hint: String?,
    /**
     * getSurroundingText(1000, 0, 0) cut at the selection start; "" when the field is empty (hint showing);
     * null = unknown (no caret). Never for passwords (the assembler drops it even if set).
     */
    val beforeCursor: String?,
    /** AccessibilityNodeInfo.getBoundsInScreen() of the focused node (screen pixels, not screenshot pixels). */
    val boundsInScreen: Rect?,
    val isPassword: Boolean,
    val isMultiline: Boolean,
)
