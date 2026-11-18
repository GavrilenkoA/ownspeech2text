package app.murmur.platform.a11y

import app.murmur.core.context.FieldKinds

/** before_cursor rules of CONTEXT_V2 / ARCHITECTURE 9.1 (platform spike CTX2). */
object BeforeCursor {
    /** getSurroundingText(MAX_CHARS, 0, 0): enough for the renderer, cheap over binder (0-8 ms on the emulator). */
    const val MAX_CHARS = 1000

    /**
     * Text of a SurroundingText cut at the selection start ([selectionStart] is relative to [text]). The caret can sit
     * inside the text (multiline field, caret after "Line one"): only what is before it counts. null = no caret.
     */
    fun fromSurrounding(text: CharSequence, selectionStart: Int): String? {
        if (selectionStart < 0) return null
        val end = selectionStart.coerceAtMost(text.length)
        return text.subSequence((end - MAX_CHARS).coerceAtLeast(0), end).toString()
    }

    /** Fallback from the node when the accessibility IME has no connection: "" while the hint shows (never the hint). */
    fun fromNode(text: CharSequence?, showingHint: Boolean, selectionStart: Int): String? {
        if (showingHint || text == null) return ""
        if (selectionStart < 0 || selectionStart > text.length) return null
        return text.subSequence((selectionStart - MAX_CHARS).coerceAtLeast(0), selectionStart).toString()
    }

    /** Never for passwords; the IME signal wins over the node (WebView node text is stale, platform spike e). */
    fun resolve(isPassword: Boolean, surrounding: String?, node: String?): String? =
        if (isPassword) null else surrounding ?: node
}

/** Field facts merged from the node and the accessibility IME's EditorInfo (platform spike 5). */
object FieldFacts {
    /** EditorInfo is right for Compose/WebView where the node is only the host view; the node covers the rest. */
    fun inputType(editorInputType: Int?, nodeInputType: Int): Int =
        editorInputType?.takeIf { it != 0 } ?: nodeInputType

    fun isPassword(nodePassword: Boolean, inputType: Int, editorInputType: Int?): Boolean =
        nodePassword || FieldKinds.isPassword(inputType) || (editorInputType != null && FieldKinds.isPassword(editorInputType))

    fun isMultiline(nodeMultiline: Boolean, inputType: Int): Boolean =
        nodeMultiline || (inputType and FieldKinds.TYPE_TEXT_FLAG_MULTI_LINE) != 0

    /**
     * DESIGN 5.2 "editable field focused" for the bubble: a focused editable node (or an input session the accessibility
     * IME sees for the app) while the keyboard is up. "Keyboard closes while Ready or Rest -> Hidden": with the IME window
     * gone the bubble goes (after the blur debounce). A hardware keyboard keeps the soft keyboard down, so then focus
     * alone counts.
     */
    fun editableFocused(editableNode: Boolean, imeInputForApp: Boolean, imeWindowShown: Boolean, hardKeyboard: Boolean): Boolean =
        (editableNode || imeInputForApp) && (imeWindowShown || hardKeyboard)
}
