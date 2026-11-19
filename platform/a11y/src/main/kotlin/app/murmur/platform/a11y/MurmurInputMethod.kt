package app.murmur.platform.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
import android.accessibilityservice.InputMethod.AccessibilityInputConnection
import android.view.inputmethod.EditorInfo

/**
 * The accessibility "IME" (API 33, flagInputMethodEditor): sees the same input sessions a keyboard sees without
 * replacing the keyboard. Gives EditorInfo (Compose/WebView fields where the node is only the host view),
 * getSurroundingText (before_cursor, insert verification) and commitText (the first rung of the ladder).
 */
class MurmurInputMethod(
    service: AccessibilityService,
    private val onInputChanged: () -> Unit,
) : InputMethod(service) {

    /** The EditorInfo of the current input session, only when it belongs to [pkg] (never another app's field). */
    fun editorFor(pkg: String): EditorInfo? =
        currentInputEditorInfo?.takeIf { currentInputStarted && it.packageName == pkg }

    /** The input connection, only while the session belongs to [pkg]. */
    fun connectionFor(pkg: String): AccessibilityInputConnection? =
        if (editorFor(pkg) != null) currentInputConnection else null

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        onInputChanged()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        onInputChanged()
    }
}
