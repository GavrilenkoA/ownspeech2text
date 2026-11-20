package app.murmur.platform.a11y

import app.murmur.core.store.InsertMethod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InsertionLadderTest {
    /** How a rung behaves on the fake field. */
    enum class Rung { LANDS, LANDS_LATE, LANDS_ON_LATER_READ, REPORTS_OK_BUT_NOTHING, UNAVAILABLE }

    /** A text field with a caret; every action appends at the caret like the real ones do. */
    private class FakeField(
        private val scope: CoroutineScope,
        var text: String = "",
        val commit: Rung = Rung.LANDS,
        val paste: Rung = Rung.LANDS,
        val setText: Rung = Rung.LANDS,
        val readable: Boolean = true,
        val lateMs: Long = 400,
        val nbsp: Boolean = false,
        /** LANDS_ON_LATER_READ: the text shows up only from this read on (a commit that arrives at the window's edge). */
        val landOnRead: Int = Int.MAX_VALUE,
    ) : LadderField {
        val calls = mutableListOf<InsertMethod>()
        var caret = text.length
        var reads = 0
        private var pending: String? = null

        private fun land(t: String) {
            val s = if (nbsp) t.replace(' ', ' ') else t
            text = text.substring(0, caret) + s + text.substring(caret); caret += s.length
        }

        private fun act(m: InsertMethod, r: Rung, t: String): Boolean {
            if (r == Rung.UNAVAILABLE) return false
            calls += m
            when (r) {
                Rung.LANDS -> land(t)
                Rung.LANDS_LATE -> scope.launch { delay(lateMs); land(t) }
                Rung.LANDS_ON_LATER_READ -> pending = t
                else -> Unit
            }
            return true
        }

        override suspend fun beforeCursor(maxChars: Int): String? {
            if (++reads >= landOnRead) pending?.let { pending = null; land(it) }
            return if (!readable) null else text.substring(0, caret).takeLast(maxChars)
        }
        override fun commitText(text: String) = act(InsertMethod.COMMIT_TEXT, commit, text)
        override fun paste(text: String) = act(InsertMethod.PASTE, paste, text)
        override fun setText(text: String) = act(InsertMethod.SET_TEXT, setText, text)
    }

    private fun TestScope.ladder() = InsertionLadder(now = { testScheduler.currentTime })

    @Test
    fun commitTextIsTriedFirstAndWinsWhenItLands() = runTest {
        val f = FakeField(this, text = "Hi ")
        val r = ladder().run(f, "there")
        assertEquals(InsertMethod.COMMIT_TEXT, r.method)
        assertEquals(listOf(InsertMethod.COMMIT_TEXT), f.calls)
        assertEquals("Hi there", f.text)
    }

    @Test
    fun ladderOrderIsCommitThenPasteThenSetText() = runTest {
        val f = FakeField(this, commit = Rung.REPORTS_OK_BUT_NOTHING, paste = Rung.REPORTS_OK_BUT_NOTHING)
        val r = ladder().run(f, "hello")
        assertEquals(InsertMethod.SET_TEXT, r.method)
        assertEquals(listOf(InsertMethod.COMMIT_TEXT, InsertMethod.PASTE, InsertMethod.SET_TEXT), f.calls)
        assertEquals(listOf(false, false, true), r.attempts.map { it.verified })
        assertEquals("hello", f.text)
    }

    @Test
    fun unavailableRungsAreSkippedWithoutWaiting() = runTest {
        val f = FakeField(this, commit = Rung.UNAVAILABLE)
        val r = ladder().run(f, "x")
        assertEquals(InsertMethod.PASTE, r.method)
        assertEquals(listOf(false, true), r.attempts.map { it.called })
        assertEquals(0L, r.attempts.first().ms)
    }

    @Test
    fun aLateCommitIsAcceptedAndNeverInsertedTwice() = runTest {
        // The commit becomes visible only after the verify window (1 original read + 13 polls over 600 ms), at the
        // first read of the grace period: the paste must not run, and the attempt is logged as verified (late).
        val f = FakeField(this, commit = Rung.LANDS_ON_LATER_READ, landOnRead = 15)
        val r = ladder().run(f, "once")
        assertEquals(InsertMethod.COMMIT_TEXT, r.method)
        assertEquals(listOf(InsertMethod.COMMIT_TEXT), f.calls)
        assertEquals("once", f.text)
        assertEquals(listOf(true), r.attempts.map { it.verified })
    }

    /**
     * ADR-086 item 5, the emulator finding: the IME accepted the commit, the field showed it only ~1 s later (past the
     * 600 ms window), and the old ladder pasted the text a second time. Now the grace period accepts COMMIT_TEXT.
     */
    @Test
    fun aCommitThatShowsUpAfterTheVerifyWindowIsAcceptedInTheGracePeriodNotPastedAgain() = runTest {
        val f = FakeField(this, text = "Note: ", commit = Rung.LANDS_LATE, lateMs = 1_000)
        val r = ladder().run(f, "buy milk")
        assertEquals(InsertMethod.COMMIT_TEXT, r.method)
        assertEquals(listOf(InsertMethod.COMMIT_TEXT), f.calls)
        assertEquals("Note: buy milk", f.text)
        val a = r.attempts.single()
        assertTrue("verified late", a.verified)
        assertTrue("accepted when it landed (${a.ms} ms)", a.ms in 1_000..1_100)
    }

    @Test
    fun anUnreadableFieldAfterATrueCommitIsNeverEscalated() = runTest {
        // The commit was accepted but nothing can read the field: a paste could only double the text, so the ladder
        // stops (the orchestrator then reports Copied, the text is on the clipboard).
        val f = FakeField(this, text = "abc", readable = false)
        val r = ladder().run(f, "x")
        assertNull(r.method)
        assertEquals(listOf(InsertMethod.COMMIT_TEXT), f.calls)
        assertEquals("abcx", f.text)
        assertEquals(listOf(true to false), r.attempts.map { it.called to it.verified })
    }

    @Test
    fun aTrueCommitThatNeverLandsEscalatesToPasteAfterTheGracePeriod() = runTest {
        val f = FakeField(this, text = "a", commit = Rung.REPORTS_OK_BUT_NOTHING)
        val r = ladder().run(f, "b")
        assertEquals(InsertMethod.PASTE, r.method)
        assertEquals(listOf(InsertMethod.COMMIT_TEXT, InsertMethod.PASTE), f.calls)
        assertEquals("ab", f.text)
        val commit = r.attempts.first()
        assertTrue(
            "the commit got the window and the grace period (${commit.ms} ms)",
            commit.ms >= InsertionLadder.VERIFY_TIMEOUT_MS + InsertionLadder.COMMIT_GRACE_MS,
        )
        // Window + grace + a paste window fit the orchestrator's 2 s delivery timeout.
        assertTrue(InsertionLadder.VERIFY_TIMEOUT_MS * 2 + InsertionLadder.COMMIT_GRACE_MS <= app.murmur.core.dictation.DictationConfig().deliveryTimeoutMs)
    }

    @Test
    fun aFalseCommitGoesStraightToPaste() = runTest {
        // commitText false = the IME has no connection: no grace period (nothing can arrive later).
        val f = FakeField(this, commit = Rung.UNAVAILABLE)
        val r = ladder().run(f, "y")
        assertEquals(InsertMethod.PASTE, r.method)
        assertEquals(0L, r.attempts.first().ms)
    }

    @Test
    fun grewMeansTheOldTextPlusTheInsertion() {
        val w = InsertionLadder.readWindow("there")
        assertTrue(InsertionLadder.grew(before = "Hi ", after = "Hi there", text = "there", window = w))
        assertFalse(InsertionLadder.grew(before = "Hi ", after = "Hi therethere", text = "there", window = w)) // inserted twice
        assertFalse(InsertionLadder.grew(before = "Hi ", after = "Hi ", text = "there", window = w))
        assertFalse(InsertionLadder.grew(before = null, after = "there", text = "there", window = w))
        assertFalse(InsertionLadder.grew(before = "Hi ", after = null, text = "there", window = w))
        // Both reads are capped at the window: a long field compares the window-long tails.
        val old = "x".repeat(500)
        val text = "конец"
        val win = InsertionLadder.readWindow(text)
        assertTrue(InsertionLadder.grew(old.takeLast(win), (old + text).takeLast(win), text, win))
        assertTrue(InsertionLadder.grew(before = "a\u00a0", after = "a b", text = "b", window = w)) // nbsp as a space
    }

    @Test
    fun aSlowCommitWithinTheWindowIsVerifiedByPolling() = runTest {
        val f = FakeField(this, commit = Rung.LANDS_LATE, lateMs = 400)
        val r = ladder().run(f, "slow")
        assertEquals(InsertMethod.COMMIT_TEXT, r.method)
        assertTrue(r.attempts.single().ms in 400..450)
    }

    @Test
    fun allRungsFailingReturnsNoMethod() = runTest {
        val f = FakeField(this, commit = Rung.REPORTS_OK_BUT_NOTHING, paste = Rung.UNAVAILABLE, setText = Rung.REPORTS_OK_BUT_NOTHING)
        val r = ladder().run(f, "lost")
        assertNull(r.method)
        assertEquals(listOf(InsertMethod.COMMIT_TEXT, InsertMethod.SET_TEXT), f.calls)
    }

    @Test
    fun unreadableFieldIsNeverReportedAsInserted() = runTest {
        val f = FakeField(this, readable = false)
        assertNull(ladder().run(f, "x").method)
    }

    @Test
    fun contentEditableNonBreakingSpacesCompareAsSpaces() = runTest {
        val f = FakeField(this, text = "abc", nbsp = true)
        assertEquals(InsertMethod.COMMIT_TEXT, ladder().run(f, " Murmur ok").method)
    }

    @Test
    fun verifiedNeedsAChangeThatEndsWithTheText() {
        assertTrue(InsertionLadder.verified(before = "Hi ", after = "Hi there", text = "there"))
        assertFalse(InsertionLadder.verified(before = "there", after = "there", text = "there")) // already there, nothing changed
        assertFalse(InsertionLadder.verified(before = "", after = "ther", text = "there"))
        assertFalse(InsertionLadder.verified(before = "", after = null, text = "there"))
        assertTrue(InsertionLadder.verified(before = null, after = "there", text = "there"))
        val long = "й".repeat(2000)
        assertTrue(InsertionLadder.verified(before = "", after = long.takeLast(InsertionLadder.readWindow(long)), text = long))
    }
}
