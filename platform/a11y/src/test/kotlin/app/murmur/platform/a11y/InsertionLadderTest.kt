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
        // re-check right before the paste rung: the paste must not run.
        val f = FakeField(this, commit = Rung.LANDS_ON_LATER_READ, landOnRead = 15)
        val r = ladder().run(f, "once")
        assertEquals(InsertMethod.COMMIT_TEXT, r.method)
        assertEquals(listOf(InsertMethod.COMMIT_TEXT), f.calls)
        assertEquals("once", f.text)
        assertEquals(listOf(false), r.attempts.map { it.verified })
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
