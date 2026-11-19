package app.murmur.platform.a11y

import app.murmur.core.store.InsertMethod
import kotlinx.coroutines.delay

/**
 * One focused field as the insertion ladder sees it (ARCHITECTURE 11). The Android implementation wraps the
 * accessibility IME's input connection and the focused node; tests use a fake.
 */
interface LadderField {
    /**
     * Up to [maxChars] characters before the caret, read through the accessibility IME's getSurroundingText (node text
     * is stale on WebView, platform spike e). Only when the IME has no connection to the field does the implementation
     * fall back to the refreshed node text. null = unreadable.
     */
    suspend fun beforeCursor(maxChars: Int): String?
    /** AccessibilityInputConnection.commitText; false when the IME has no connection to this field. */
    fun commitText(text: String): Boolean
    /** Clipboard = [text], then ACTION_PASTE; false when the node refused the action. */
    fun paste(text: String): Boolean
    /** ACTION_SET_TEXT with the text spliced in at the selection, then ACTION_SET_SELECTION after it. */
    fun setText(text: String): Boolean
}

data class LadderAttempt(val method: InsertMethod, val called: Boolean, val verified: Boolean, val ms: Long)

data class LadderResult(val method: InsertMethod?, val attempts: List<LadderAttempt>)

/**
 * commitText -> ACTION_PASTE -> ACTION_SET_TEXT; after each, read back the text before the caret and accept the first
 * method whose result is verified. Before a later step the field is read again: if an earlier step landed late (an
 * async commit), that step wins and nothing is inserted twice.
 */
class InsertionLadder(
    private val now: () -> Long,
    private val verifyTimeoutMs: Long = VERIFY_TIMEOUT_MS,
    private val pollMs: Long = POLL_MS,
) {
    suspend fun run(field: LadderField, text: String): LadderResult {
        val attempts = mutableListOf<LadderAttempt>()
        val window = readWindow(text)
        val original = field.beforeCursor(window)
        val steps = listOf<Pair<InsertMethod, (String) -> Boolean>>(
            InsertMethod.COMMIT_TEXT to field::commitText,
            InsertMethod.PASTE to field::paste,
            InsertMethod.SET_TEXT to field::setText,
        )
        for ((method, action) in steps) {
            val lastCalled = attempts.lastOrNull { it.called }
            if (lastCalled != null && verified(original, field.beforeCursor(window), text)) {
                return LadderResult(lastCalled.method, attempts)
            }
            val t0 = now()
            val called = runCatching { action(text) }.getOrDefault(false)
            val ok = called && awaitVerified(field, original, text, window)
            attempts += LadderAttempt(method, called, ok, now() - t0)
            if (ok) return LadderResult(method, attempts)
        }
        return LadderResult(null, attempts)
    }

    private suspend fun awaitVerified(field: LadderField, original: String?, text: String, window: Int): Boolean {
        val deadline = now() + verifyTimeoutMs
        while (true) {
            if (verified(original, field.beforeCursor(window), text)) return true
            if (now() >= deadline) return false
            delay(pollMs)
        }
    }

    companion object {
        /** Long enough for an async commit or a WebView paste to arrive (spike read back after 150 ms). */
        const val VERIFY_TIMEOUT_MS = 600L
        const val POLL_MS = 50L
        /** Only the tail of a long text is compared: bounded binder payload, same certainty. */
        const val COMPARE_TAIL = 256
        private const val MARGIN = 32

        fun readWindow(text: String) = minOf(text.length, COMPARE_TAIL) + MARGIN

        /**
         * The text before the caret now ends with [text] and differs from what was there before: the insertion landed at
         * the caret. Non-breaking spaces (contenteditable) compare as spaces.
         */
        fun verified(before: String?, after: String?, text: String): Boolean {
            if (after == null) return false
            val a = norm(after)
            if (!a.endsWith(norm(text).takeLast(COMPARE_TAIL))) return false
            return before == null || norm(before) != a
        }

        private fun norm(s: String) = s.replace(' ', ' ')
    }
}
