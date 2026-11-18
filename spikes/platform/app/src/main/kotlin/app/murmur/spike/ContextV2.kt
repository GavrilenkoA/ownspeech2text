package app.murmur.spike

import android.graphics.Rect
import android.text.InputType
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.EditorInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * What docs/ml/CONTEXT_V2.md needs from accessibility (lift as-is into :platform:a11y):
 *  - the current app (package + label),
 *  - the focused field: kind, hint, text before the cursor, password flag,
 *  - OCR exclusion rectangles: IME window, our own overlay windows, system bars.
 * Screen *text* is deliberately NOT read here (v2 rule: it comes from OCR only).
 */
object ContextV2 {
    fun rect(r: Rect) = JSONArray(listOf(r.left, r.top, r.right, r.bottom))

    fun windowTypeName(t: Int) = when (t) {
        AccessibilityWindowInfo.TYPE_APPLICATION -> "application"
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "input_method"
        AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "accessibility_overlay"
        AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "split_divider"
        AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY -> "magnification_overlay"
        else -> "type$t"
    }

    fun isPasswordType(inputType: Int): Boolean {
        val cls = inputType and InputType.TYPE_MASK_CLASS
        val v = inputType and InputType.TYPE_MASK_VARIATION
        return (cls == InputType.TYPE_CLASS_TEXT && (v == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            v == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD || v == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
            (cls == InputType.TYPE_CLASS_NUMBER && v == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
    }

    /** Platform-level field kind. App semantics ("message", "search") need the package + hint on top. */
    fun kindOf(inputType: Int, imeOptions: Int): String {
        val cls = inputType and InputType.TYPE_MASK_CLASS
        val v = inputType and InputType.TYPE_MASK_VARIATION
        if (isPasswordType(inputType)) return "password"
        return when (cls) {
            InputType.TYPE_CLASS_NUMBER -> "number"
            InputType.TYPE_CLASS_PHONE -> "phone"
            InputType.TYPE_CLASS_DATETIME -> "datetime"
            InputType.TYPE_CLASS_TEXT -> when (v) {
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> "email"
                InputType.TYPE_TEXT_VARIATION_URI -> "url"
                InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE -> "message"
                InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE, InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT -> "email_body"
                InputType.TYPE_TEXT_VARIATION_PERSON_NAME -> "name"
                InputType.TYPE_TEXT_VARIATION_FILTER -> "search"
                else -> when {
                    (imeOptions and EditorInfo.IME_MASK_ACTION) == EditorInfo.IME_ACTION_SEARCH -> "search"
                    (imeOptions and EditorInfo.IME_MASK_ACTION) == EditorInfo.IME_ACTION_SEND -> "message"
                    (inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0 -> "text_multiline"
                    else -> "text"
                }
            }
            0 -> "none"
            else -> "other"
        }
    }

    fun windows(ws: List<AccessibilityWindowInfo>, ownPkg: String): Pair<JSONArray, JSONArray> {
        val all = JSONArray(); val exclude = JSONArray()
        for (w in ws) {
            val r = Rect().also { w.getBoundsInScreen(it) }
            val pkg = runCatching { w.root?.packageName?.toString() }.getOrNull()
            val type = windowTypeName(w.type)
            all.put(JSONObject().put("id", w.id).put("type", type).put("layer", w.layer).put("display", w.displayId)
                .put("focused", w.isFocused).put("title", w.title?.toString()).put("pkg", pkg).put("bounds", rect(r)))
            val why = when {
                w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "ime"
                w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && (pkg == ownPkg || pkg == null) -> "own_overlay"
                w.type == AccessibilityWindowInfo.TYPE_SYSTEM -> "system:" + (w.title ?: "")
                else -> null
            }
            if (why != null && !r.isEmpty) exclude.put(JSONObject().put("why", why).put("box", rect(r)))
        }
        return all to exclude
    }

    /** before_cursor from a node: "" when the node shows its hint; null when there is no caret. */
    fun beforeCursorFromNode(n: AccessibilityNodeInfo): String? {
        if (n.isShowingHintText) return ""
        val t = n.text?.toString() ?: return ""
        val s = n.textSelectionStart
        if (s < 0 || s > t.length) return null
        return t.substring(0, s)
    }
}
