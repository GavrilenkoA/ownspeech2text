package app.murmur.feature.history

import app.murmur.core.store.Words

/** One piece of the Changes view (DESIGN 6.3): kept text, an addition (accentInk on accentSoft) or a removal. */
data class DiffSpan(val text: String, val kind: Kind) {
    enum class Kind { SAME, ADDED, REMOVED }
}

/**
 * Inline word diff between the original transcript and the final text, as the prototype draws it (hist-diff):
 * words are aligned on their folded letters and digits, so "привет" → "Привет!" is one kept word whose new
 * characters ("П", "!") are marked as added, while "одиннадцати" → "11," is a removed word followed by an added one.
 * Removed words come before the words added in their place. Spaces between words are kept as SAME spans.
 */
object WordDiff {
    fun diff(original: String, final: String): List<DiffSpan> {
        val a = tokens(original)
        val b = tokens(final)
        val ka = a.map(::key)
        val kb = b.map(::key)
        val pairs = lcs(ka, kb) { x, y -> x.isNotEmpty() && x == y }
        val out = Builder()
        var i = 0
        var j = 0
        for ((pi, pj) in pairs + (a.size to b.size)) {
            while (i < pi) out.word(a[i++], DiffSpan.Kind.REMOVED)
            while (j < pj) out.word(b[j++], DiffSpan.Kind.ADDED)
            if (pi < a.size && pj < b.size) {
                out.changedWord(a[pi], b[pj])
                i = pi + 1
                j = pj + 1
            }
        }
        return out.spans()
    }

    /** "added: …; removed: …" for TalkBack (DESIGN 8). */
    fun description(spans: List<DiffSpan>, addedLabel: String, removedLabel: String, noneLabel: String): String {
        val words = wordsOf(spans)
        val added = words.filter { w -> w.any { it.kind == DiffSpan.Kind.ADDED } }.map { w -> w.filter { it.kind != DiffSpan.Kind.REMOVED }.joinToString("") { it.text } }
        val removed = words.filter { w -> w.all { it.kind == DiffSpan.Kind.REMOVED } }.map { w -> w.joinToString("") { it.text } }
        if (added.isEmpty() && removed.isEmpty()) return noneLabel
        return buildList {
            if (added.isNotEmpty()) add("$addedLabel: ${added.joinToString(", ")}")
            if (removed.isNotEmpty()) add("$removedLabel: ${removed.joinToString(", ")}")
        }.joinToString("; ")
    }

    fun hasChanges(spans: List<DiffSpan>): Boolean = spans.any { it.kind != DiffSpan.Kind.SAME }

    private fun tokens(s: String): List<String> = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    private fun key(word: String): String = Words.fold(word.filter { it.isLetterOrDigit() })

    /** Words of the span list: characters grouped between the kept spaces that separate words. */
    private fun wordsOf(spans: List<DiffSpan>): List<List<DiffSpan>> {
        val words = mutableListOf<List<DiffSpan>>()
        var cur = mutableListOf<DiffSpan>()
        for (s in spans) for (ch in s.text) {
            if (s.kind == DiffSpan.Kind.SAME && ch == ' ') {
                if (cur.isNotEmpty()) words += cur
                cur = mutableListOf()
            } else {
                cur += DiffSpan(ch.toString(), s.kind)
            }
        }
        if (cur.isNotEmpty()) words += cur
        return words
    }

    /** Index pairs of a longest common subsequence (classic DP; dictations are at most a few hundred words). */
    private fun <T> lcs(a: List<T>, b: List<T>, eq: (T, T) -> Boolean): List<Pair<Int, Int>> {
        val n = a.size
        val m = b.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (x in n - 1 downTo 0) for (y in m - 1 downTo 0) {
            dp[x][y] = if (eq(a[x], b[y])) dp[x + 1][y + 1] + 1 else maxOf(dp[x + 1][y], dp[x][y + 1])
        }
        val out = ArrayList<Pair<Int, Int>>()
        var x = 0
        var y = 0
        while (x < n && y < m) {
            when {
                eq(a[x], b[y]) && dp[x][y] == dp[x + 1][y + 1] + 1 -> { out += x to y; x++; y++ }
                dp[x + 1][y] >= dp[x][y + 1] -> x++
                else -> y++
            }
        }
        return out
    }

    private class Builder {
        private val spans = ArrayList<DiffSpan>()
        private var started = false

        private fun add(text: String, kind: DiffSpan.Kind) {
            if (text.isEmpty()) return
            val last = spans.lastOrNull()
            if (last != null && last.kind == kind) spans[spans.size - 1] = DiffSpan(last.text + text, kind) else spans += DiffSpan(text, kind)
        }

        private fun gap() { if (started) add(" ", DiffSpan.Kind.SAME); started = true }

        fun word(w: String, kind: DiffSpan.Kind) { gap(); add(w, kind) }

        /**
         * A kept word whose characters may differ (case, punctuation): characters of [after] that are not in the
         * character LCS with [before] are marked as added; characters only in [before] are not drawn (the
         * prototype shows "Привет!" for "привет", not a struck-out "п").
         */
        fun changedWord(before: String, after: String) {
            gap()
            if (before == after) { add(after, DiffSpan.Kind.SAME); return }
            val bc = before.toList()
            val ac = after.toList()
            val kept = lcs(bc, ac) { x, y -> x == y }.map { it.second }.toHashSet()
            for (k in ac.indices) add(ac[k].toString(), if (k in kept) DiffSpan.Kind.SAME else DiffSpan.Kind.ADDED)
        }

        fun spans(): List<DiffSpan> = spans
    }
}
