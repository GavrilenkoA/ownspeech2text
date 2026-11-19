package app.murmur.core.dictation

/**
 * ARCHITECTURE 11 step 4 ([JoinPolicy] rules). A leading space when the text before the cursor ends with a letter,
 * digit or closing punctuation and the dictated text starts with a letter or digit (any script, so Cyrillic after
 * Latin joins like Latin after Latin); nothing at the start of a field, after whitespace, a newline or an opening
 * bracket/quote. The first letter is lowercased only mid-sentence and only for guard-accepted text; acronyms
 * ("NASA") and the English pronoun "I" keep their case. Ambiguous straight quotes count as neither opening nor closing.
 */
object DefaultJoinPolicy : JoinPolicy {
    private const val CLOSING = ".,!?;:…)]}»”’%"
    private const val SENTENCE_END = ".!?…"

    override fun join(beforeCursor: String?, text: String, lowercaseAllowed: Boolean): String {
        if (text.isEmpty() || beforeCursor.isNullOrEmpty()) return text
        val last = beforeCursor.last()
        val body = if (lowercaseAllowed && midSentence(beforeCursor)) lowerFirst(text) else text
        val space = (last.isLetterOrDigit() || last in CLOSING) && body.first().isLetterOrDigit()
        return if (space) " $body" else body
    }

    private fun midSentence(before: String): Boolean {
        if (before.endsWith('\n')) return false
        val t = before.trimEnd(' ', '\t')
        return t.isNotEmpty() && t.last() !in SENTENCE_END && t.last() != '\n'
    }

    private fun lowerFirst(text: String): String {
        val first = text.first()
        if (!first.isUpperCase()) return text
        val word = text.takeWhile { it.isLetter() }
        if (word.length > 1 && word[1].isUpperCase()) return text      // acronym
        if (word == "I") return text                                   // English pronoun (also "I'm", "I'll")
        return first.lowercaseChar() + text.substring(1)
    }
}
