package app.murmur.feature.history

import app.murmur.testing.contrib.Wp16HistoryFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WordDiffTest {
    private fun render(spans: List<DiffSpan>) = spans.joinToString("") {
        when (it.kind) {
            DiffSpan.Kind.SAME -> it.text
            DiffSpan.Kind.ADDED -> "[+${it.text}]"
            DiffSpan.Kind.REMOVED -> "[-${it.text}]"
        }
    }

    /** The prototype's hist-diff markup, span for span. */
    @Test
    fun matchesThePrototypeDiff() {
        val spans = WordDiff.diff(Wp16HistoryFixture.H1_RAW, Wp16HistoryFixture.H1_FINAL)
        assertEquals(
            "[+П]ривет[+!] [-э] [+Б]уду в офисе к [-одиннадцати] [+11,] созвон с [+С]ашей [-ну] переносим на четверг[+.] " +
                "[+С]кинь[+,] пожалуйста[+,] ссылку на документ[+.]",
            render(spans),
        )
        // The final text is exactly the kept + added characters (spaces around removed words collapse).
        val kept = spans.filter { it.kind != DiffSpan.Kind.REMOVED }.joinToString("") { it.text }
        assertEquals(Wp16HistoryFixture.H1_FINAL, kept.replace(Regex(" +"), " ").trim())
    }

    @Test
    fun talkBackDescriptionNamesAddedAndRemovedWords() {
        val spans = WordDiff.diff("привет э буду к одиннадцати", "Привет! Буду к 11")
        assertEquals("added: Привет!, Буду, 11; removed: э, одиннадцати", WordDiff.description(spans, "added", "removed", "No changes"))
        val same = WordDiff.diff("same words", "same words")
        assertFalse(WordDiff.hasChanges(same))
        assertEquals("No changes", WordDiff.description(same, "added", "removed", "No changes"))
    }

    @Test
    fun insertionsAndDeletionsAtTheEdges() {
        assertEquals("[+Ну,] привет", render(WordDiff.diff("привет", "Ну, привет")))
        assertEquals("привет [-ну]", render(WordDiff.diff("привет ну", "привет")))
        assertTrue(WordDiff.diff("", "Новый текст").all { it.kind != DiffSpan.Kind.REMOVED })
    }
}
