package app.murmur.feature.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlightTest {
    private fun marked(text: String, q: String) = Highlight.ranges(text, q).joinToString("|") { text.substring(it.first, it.last + 1) }

    @Test
    fun foldsCaseYoAndSharpSLikeTheStoredSearchText() {
        assertEquals("Созвон|созвон", marked("Созвон в 12, потом созвон ещё", "СОЗВОН"))
        assertEquals("ещё", marked("потом созвон ещё", "еще"))
        assertEquals("Straße", marked("Die Straße ist lang", "STRASSE"))
        assertEquals("", marked("anything", "  "))
        assertTrue(Highlight.ranges("abc", "zzz").isEmpty())
    }

    @Test
    fun rangesPointIntoTheOriginalTextAfterExpandingFolds() {
        // "ß" folds to two characters; the range after it must still land on the right word.
        val text = "Maß und Ziel"
        assertEquals("Ziel", marked(text, "ziel"))
        assertEquals("Maß", marked(text, "MASS"))
    }
}
