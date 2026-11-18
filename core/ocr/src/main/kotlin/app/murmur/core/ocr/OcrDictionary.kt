package app.murmur.core.ocr

/**
 * Recogniser dictionary = `PostProcess.character_dict` of the model's `inference.yml` (ml/ocr README, "Models").
 * A minimal reader for exactly that block-sequence: plain, single-quoted ('' = ') and double-quoted (backslash
 * escapes) scalars. A naive line copy gets `''` instead of `'`; the result must equal ml/ocr/golden/rec_keys.txt
 * byte for byte (OcrDictionaryTest).
 */
object OcrDictionary {

    fun fromInferenceYml(yml: String): List<String> {
        val lines = yml.replace("\r\n", "\n").split('\n')
        var inPost = false
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.isNotEmpty() && !line[0].isWhitespace()) inPost = line.trimEnd() == "PostProcess:"
            if (inPost && line.trim() == "character_dict:") {
                val keyIndent = line.indexOfFirst { !it.isWhitespace() }
                return readSequence(lines, i + 1, keyIndent)
            }
            i++
        }
        throw IllegalArgumentException("PostProcess.character_dict not found")
    }

    /** One entry per line (rec_keys.txt): empty lines are skipped, like ml/ocr `load_keys` for text files. */
    fun fromKeysText(text: String): List<String> = text.split('\n').filter { it.isNotEmpty() }

    private fun readSequence(lines: List<String>, from: Int, keyIndent: Int): List<String> {
        val out = ArrayList<String>()
        for (k in from until lines.size) {
            val line = lines[k]
            val indent = line.indexOfFirst { !it.isWhitespace() }
            if (indent < 0) continue
            val body = line.substring(indent)
            if (indent < keyIndent || !body.startsWith("-")) break
            require(indent <= keyIndent + 2) { "nested sequence at line ${k + 1}" }
            require(body.startsWith("- ") && body.length > 2) { "empty dictionary entry at line ${k + 1}" }
            out += scalar(body.substring(2).trimStart(' '), k + 1)
        }
        require(out.isNotEmpty()) { "empty character_dict" }
        return out
    }

    private fun scalar(raw: String, lineNo: Int): String {
        val s = raw.trimEnd()
        return when {
            s.startsWith("'") -> {
                require(s.length >= 2 && s.endsWith("'")) { "unterminated quote at line $lineNo" }
                s.substring(1, s.length - 1).replace("''", "'")
            }
            s.startsWith("\"") -> {
                require(s.length >= 2 && s.endsWith("\"")) { "unterminated quote at line $lineNo" }
                unescapeDouble(s.substring(1, s.length - 1), lineNo)
            }
            else -> {
                // Plain scalar; a " #" starts a comment.
                val c = s.indexOf(" #")
                if (c >= 0) s.substring(0, c).trimEnd() else s
            }
        }
    }

    private fun unescapeDouble(s: String, lineNo: Int): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch != '\\') { sb.append(ch); i++; continue }
            require(i + 1 < s.length) { "bad escape at line $lineNo" }
            when (val e = s[i + 1]) {
                '\\' -> sb.append('\\')
                '"' -> sb.append('"')
                '/' -> sb.append('/')
                '0' -> sb.append('\u0000')
                'a' -> sb.append('\u0007')
                'b' -> sb.append('\b')
                't' -> sb.append('\t')
                'n' -> sb.append('\n')
                'v' -> sb.append('\u000B')
                'f' -> sb.append('\u000C')
                'r' -> sb.append('\r')
                'e' -> sb.append('\u001B')
                ' ' -> sb.append(' ')
                'N' -> sb.append('\u0085')
                '_' -> sb.append(' ')
                'x', 'u', 'U' -> {
                    val n = when (e) { 'x' -> 2; 'u' -> 4; else -> 8 }
                    require(i + 2 + n <= s.length) { "bad escape at line $lineNo" }
                    sb.appendCodePoint(s.substring(i + 2, i + 2 + n).toInt(16))
                    i += n
                }
                else -> throw IllegalArgumentException("unknown escape \\$e at line $lineNo")
            }
            i += 2
        }
        return sb.toString()
    }
}
