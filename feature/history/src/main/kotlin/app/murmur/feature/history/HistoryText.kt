package app.murmur.feature.history

import app.murmur.core.store.Words
import java.text.Normalizer

/**
 * Search highlighting (DESIGN 6.3 search): the repository matches the query against the folded `search_text`
 * ([Words.fold]: NFC, full case folding, ё → е; ADR-072), so the screen finds the same matches in the shown text by
 * folding it code point by code point and mapping every folded index back to the original one.
 */
internal object Highlight {
    /** Ranges (end exclusive) of every non-overlapping match of [query] in [text]; [text] should be NFC ([nfc]). */
    fun ranges(text: String, query: String): List<IntRange> {
        val q = Words.fold(query.trim())
        if (q.isEmpty() || text.isEmpty()) return emptyList()
        val folded = StringBuilder(text.length)
        // origin[k] = index in [text] of the code point that produced folded char k; ends[k] = that code point's end.
        val origin = IntArray(text.length * 3 + 4)
        val ends = IntArray(text.length * 3 + 4)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val n = Character.charCount(cp)
            for (c in Words.fold(String(Character.toChars(cp)))) {
                val k = folded.length
                folded.append(c)
                origin[k] = i
                ends[k] = i + n
            }
            i += n
        }
        val out = ArrayList<IntRange>()
        var from = 0
        while (true) {
            val at = folded.indexOf(q, from)
            if (at < 0) break
            val end = at + q.length
            out += origin[at] until ends[end - 1]
            from = end
        }
        return out
    }

    fun nfc(s: String): String = if (Normalizer.isNormalized(s, Normalizer.Form.NFC)) s else Normalizer.normalize(s, Normalizer.Form.NFC)

    /**
     * Rows clamp at three lines: when the first match starts later than [lead] characters, the text is cut at a word
     * boundary about [context] characters before it and prefixed with "…", so the highlighted word stays visible.
     */
    fun excerpt(text: String, ranges: List<IntRange>, lead: Int = EXCERPT_LEAD, context: Int = EXCERPT_CONTEXT): Pair<String, List<IntRange>> {
        val first = ranges.firstOrNull() ?: return text to ranges
        if (first.first <= lead) return text to ranges
        var cut = (first.first - context).coerceAtLeast(0)
        while (cut > 0 && !text[cut - 1].isWhitespace()) cut--
        if (cut == 0) return text to ranges
        val prefix = "…"
        val shift = prefix.length - cut
        return (prefix + text.substring(cut)) to ranges.map { (it.first + shift)..(it.last + shift) }
    }

    const val EXCERPT_LEAD = 90
    const val EXCERPT_CONTEXT = 32
}
