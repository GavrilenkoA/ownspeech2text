package app.murmur.platform.a11y

import android.accessibilityservice.InputMethod.AccessibilityInputConnection
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Bundle
import android.os.PersistableBundle
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import app.murmur.core.context.A11ySnapshot
import app.murmur.core.dictation.InsertResult
import app.murmur.core.dictation.Inserter
import app.murmur.core.model.MurmurError
import app.murmur.core.store.InsertMethod

/**
 * [Inserter] over the connected service (ARCHITECTURE 11 steps 3 and 5): the field is re-resolved at insert time,
 * then [InsertionLadder] runs commitText -> ACTION_PASTE -> ACTION_SET_TEXT with read-back. The clipboard is only
 * ever written (a background read is denied, and Murmur never needs it).
 */
class A11yInserter internal constructor(private val service: MurmurAccessibilityService) : Inserter {
    private val ladder = InsertionLadder(SystemClock::uptimeMillis)

    /** What [copyToClipboard] last put there, restored after a PASTE rung replaced it with the joined text. */
    @Volatile private var lastCopied: String? = null

    override suspend fun insert(target: A11ySnapshot, text: String): InsertResult = service.lane.call(InsertResult.Failed(MurmurError.AccessibilityOff)) {
        if (text.isEmpty()) return@call InsertResult.Failed(MurmurError.InsertRejected)
        val t0 = SystemClock.uptimeMillis()
        val nodes = service.nodes
        val now = WindowChoice.target(nodes.windows())
        if (now == null || now.pkg != target.pkg) {
            Log.i(TAG, "insert result=${MurmurError.TargetChanged.code}")
            return@call InsertResult.Failed(MurmurError.TargetChanged)
        }
        val node = nodes.focusedEditable(now.id)
        val ime = service.ime
        val connection = ime?.connectionFor(target.pkg)
        if (node == null && connection == null) {
            Log.i(TAG, "insert result=${MurmurError.NoEditableField.code}")
            return@call InsertResult.Failed(MurmurError.NoEditableField)
        }
        val editor = ime?.editorFor(target.pkg)
        val inputType = FieldFacts.inputType(editor?.inputType, node?.inputType ?: 0)
        if (FieldFacts.isPassword(node?.isPassword == true, inputType, editor?.inputType)) {
            Log.i(TAG, "insert result=${MurmurError.PasswordField.code}")
            return@call InsertResult.Failed(MurmurError.PasswordField)
        }
        val field = AndroidLadderField(node, connection)
        val r = ladder.run(field, text)
        if (r.attempts.any { it.method == InsertMethod.PASTE && it.called }) lastCopied?.takeIf { it != text }?.let { setClip(it) }
        Log.i(TAG, "insert method=${r.method} attempts=${r.attempts.joinToString { "${it.method}:${it.called}/${it.verified}/${it.ms}" }} ms=${SystemClock.uptimeMillis() - t0}")
        r.method?.let { InsertResult.Inserted(it) } ?: InsertResult.Failed(MurmurError.InsertRejected)
    }

    override fun copyToClipboard(text: String): Boolean {
        val ok = setClip(text)
        if (ok) lastCopied = text
        return ok
    }

    /** ClipData with EXTRA_IS_SENSITIVE=false: dictated text is shown in the clipboard preview like any copy. */
    private fun setClip(text: String): Boolean = runCatching {
        val label = service.applicationInfo.loadLabel(service.packageManager)
        val clip = ClipData.newPlainText(label, text)
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) }
        service.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        true
    }.getOrElse {
        Log.w(TAG, "clip_write_failed ${it.javaClass.simpleName}")
        false
    }

    private inner class AndroidLadderField(
        private val node: AccessibilityNodeInfo?,
        private val connection: AccessibilityInputConnection?,
    ) : LadderField {
        override suspend fun beforeCursor(maxChars: Int): String? {
            if (connection != null) {
                return connection.getSurroundingText(maxChars, 0, 0)?.let { BeforeCursor.fromSurrounding(it.text, it.selectionStart) }
            }
            val n = node ?: return null
            if (!n.refresh()) return null
            return BeforeCursor.fromNode(n.text, n.isShowingHintText, n.textSelectionStart)
        }

        override fun commitText(text: String): Boolean {
            val c = connection ?: return false
            c.commitText(text, 1, null)
            return true
        }

        override fun paste(text: String): Boolean {
            val n = node ?: return false
            if (!setClip(text)) return false
            return n.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }

        override fun setText(text: String): Boolean {
            val n = node ?: return false
            n.refresh()
            val current = if (n.isShowingHintText) "" else n.text?.toString().orEmpty()
            var s = n.textSelectionStart
            var e = n.textSelectionEnd
            if (s < 0 || e < 0 || s > current.length || e > current.length) { s = current.length; e = current.length }
            val a = minOf(s, e)
            val b = maxOf(s, e)
            val ok = n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, current.substring(0, a) + text + current.substring(b))
            })
            if (!ok) return false
            val caret = a + text.length
            // SET_TEXT puts the caret at the end; put it right after the inserted text (platform spike e).
            n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, caret)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, caret)
            })
            return true
        }
    }

    private companion object {
        const val TAG = "MurmurA11y"
    }
}
