package app.murmur.core.prompt

import app.murmur.core.context.CtxV2
import java.text.Normalizer
import java.util.regex.Pattern

/**
 * Kotlin port of prompts/guard.py (GUARD_VERSION guard.v1.1). Same rules in the same order, same reason codes; the
 * contract is every case of prompts/tests/test_guard.py (exported to goldens/guard.json by make_goldens.py).
 *
 * Regexes keep the Python patterns; `\w`, `\s`, `\S` and `\b` are spelled out as explicit classes ([Py.W], [Py.S]) so
 * that the host JVM and Android's ICU regex both match Python's meaning, `.` excludes only "\n" (UNIX_LINES, as in
 * Python), and re.I is CASE_INSENSITIVE | UNICODE_CASE. Word tokenising, phonetic keys and the letter counts are
 * hand-written code-point loops.
 */
class GuardPyPort : OutputGuard {
    override val fillers: Set<String> get() = FILLERS

    override fun precheck(transcript: String): Precheck {
        val t = Py.strip(Normalizer.normalize(transcript, Normalizer.Form.NFC))
        val bare = Py.strip(t, " .,!?…-—\"'«»()[]")
        if (content(words(bare)).isEmpty()) return Precheck(true, "", if (bare.isNotEmpty()) "filler_only" else "empty")
        if (HALLU_FULL.matcher(bare).find()) return Precheck(true, "", "asr_hallucination")
        val cut = Py.rstrip(HALLU_TAIL.matcher(t).replaceAll(""))
        if (cut != t && cut.isNotEmpty()) return Precheck(false, cut, "tail_removed")
        return Precheck(false, t, "ok")
    }

    /** guard.fallback_text: the raw transcript, first letter capitalised unless search/address/code or mid-sentence. */
    fun fallbackText(transcript: String, ctx: CtxV2?): String {
        var t = Py.strip(transcript)
        val cat = ctx?.app?.category
        val before = Py.rstrip(ctx?.field?.beforeCursor ?: "")
        val mid = before.isNotEmpty() && !".!?…:\n".contains(Py.last(before))
        if (t.isNotEmpty() && cat != "search" && cat != "address" && cat != "code" && !mid) t = Py.upperFirst(t)
        return t
    }

    /** guard.repair. */
    fun repair(output: String, transcript: String, ctx: CtxV2?): String {
        var o = THINK.matcher(output).replaceAll("")
        o = Py.strip(CHAT_TOKENS.matcher(o).replaceAll(""))
        o = Py.strip(LABEL.matcher(o).replaceFirst(""))
        val t = Py.strip(transcript)
        if (o.startsWith("```") && o.endsWith("```") && Py.len(o) > 6 && !t.startsWith("```")) {
            o = Py.strip(FENCE.matcher(o).replaceAll(""))
        }
        while (Py.len(o) >= 2) {
            val q = o[0]
            val close = QUOTE_PAIRS[q] ?: break
            if (!o.endsWith(close) || t.startsWith(q) || o.count { it == q } != (if (q == close) 2 else 1)) break
            o = Py.strip(o.substring(1, o.length - 1))
        }
        val before = Py.strip(ctx?.field?.beforeCursor ?: "")
        if (Py.len(before) >= 15 && o.startsWith(before)) o = Py.lstrip(o.substring(before.length))
        return o
    }

    override fun guard(transcript: String, output: String, ctx: CtxV2?): GuardVerdict {
        val pre = precheck(transcript)
        if (pre.skip) return GuardVerdict("", GuardAction.EMPTY, pre.reason)
        val tr = pre.transcript
        val fb = fallbackText(tr, ctx)
        val o = repair(output, tr, ctx)
        val cat = ctx?.app?.category
        val tw = content(words(tr))
        val ow = content(words(o))
        fun fallback(reason: String) = GuardVerdict(fb, GuardAction.FALLBACK, reason)

        if (o.isEmpty()) return fallback("R1_empty")
        if (LEAK.matcher(o).find()) return fallback("R2_leaked_tags")
        if (PREAMBLE.matcher(o).find() && !PREAMBLE.matcher(tr).find()) return fallback("R3_preamble")
        val grams = trigrams(ow)
        val tgrams = trigrams(tw)
        for ((g, n) in grams) if (n >= 4 && (tgrams[g] ?: 0) < 2) return fallback("R4_loop")
        val m = CHAR_LOOP.matcher(o)
        if (m.find() && !tr.contains(m.group())) return fallback("R4_loop")
        if (Py.len(o) > CHAR_GROW_K * Py.len(tr) + CHAR_GROW_ADD) return fallback("R4_length")
        if (ow.size > GROW_K * tw.size + GROW_ADD || (tw.size >= SHRINK_MIN_IN && ow.size < SHRINK_K * tw.size)) {
            return fallback("R4_length")
        }
        if (cat == null || cat !in NO_SCRIPT_CHECK) {
            val (tl, tc) = letters(tr)
            val (ol, oc) = letters(o)
            val tn = tl + tc
            val on = ol + oc
            if (tn >= SCRIPT_MIN_LETTERS && on != 0) {
                for ((ts, os) in listOf(tl to ol, tc to oc)) {
                    if (ts >= SCRIPT_MIN_LETTERS && ts.toDouble() / tn >= 0.2 && os.toDouble() / on < 0.05) return fallback("R5_script")
                }
                if (tc.toDouble() / tn >= 0.7 && oc.toDouble() / on < 0.3) return fallback("R5_script")
            }
            if (translatedSpan(tr, o)) return fallback("R5b_translated_span")
        }
        val allowed = HashSet<String>()
        words(tr).mapTo(allowed) { Py.head(it, 5) }
        allowed.addAll(screenWords(ctx))
        val contentWords = ow.filter { Py.len(it) >= 4 && !Py.isDigitString(it) }
        val novel = contentWords.filter { Py.head(it, 5) !in allowed && bestSim(it, tr) < NOVEL_SIM }
        if (novel.size >= NOVEL_MIN && novel.size.toDouble() / contentWords.size > NOVEL_SHARE) return fallback("R6_novel")
        val oFold = Py.casefold(o)
        val tFold = Py.casefold(tr)
        val terms = screenTerms(ctx)
        for (term in terms) {
            val f = Py.casefold(term)
            if (!oFold.contains(f) || tFold.contains(f)) continue
            val sTerm = bestSim(term, tr)
            val rivals = terms.filter { it != term && !oFold.contains(Py.casefold(it)) }.map { bestSim(it, tr) }
            if (sTerm < DISTRACTOR_SIM || rivals.any { it >= sTerm + RIVAL_MARGIN }) return fallback("R7_distractor")
        }
        return GuardVerdict(o, GuardAction.ACCEPT, if (o == Py.strip(output)) "ok" else "repaired")
    }

    // ------------------------------------------------------------ helpers (guard.py names)
    /** _WORD.findall: `[\w]+(?:[-'’][\w]+)*`. */
    internal fun wordTokens(s: String): List<String> {
        val c = Py.cps(s)
        val out = ArrayList<String>()
        var k = 0
        while (k < c.size) {
            if (!Py.isWord(c[k])) { k++; continue }
            val start = k
            while (k < c.size && Py.isWord(c[k])) k++
            while (k + 1 < c.size && (c[k] == '-'.code || c[k] == '\''.code || c[k] == '’'.code) && Py.isWord(c[k + 1])) {
                k++
                while (k < c.size && Py.isWord(c[k])) k++
            }
            out.add(Py.str(c, start, k))
        }
        return out
    }

    /** _words: casefolded tokens, ё -> е. */
    internal fun words(s: String): List<String> = wordTokens(s).map { Py.casefold(it).replace('ё', 'е') }

    private fun content(ws: List<String>): List<String> = ws.filter { it !in FILLERS }

    private fun trigrams(ws: List<String>): Map<String, Int> {
        val m = HashMap<String, Int>()
        for (i in 0 until ws.size - 2) m.merge(ws[i] + " " + ws[i + 1] + " " + ws[i + 2], 1, Int::plus)
        return m
    }

    private fun letters(s: String): Pair<Int, Int> {
        var lat = 0
        var cyr = 0
        s.codePoints().forEach {
            if (Py.lowerIsAsciiLetter(it)) lat++
            if (Py.isCyrillic(it)) cyr++
        }
        return lat to cyr
    }

    /**
     * guard.phonetic_key. Python drops combining marks after NFKD; here they fall out with every other character
     * outside [a-z0-9] a step later, which gives the same key (no combining mark is a Cyrillic letter or ASCII).
     */
    fun phoneticKey(s: String): String {
        val nfkd = Normalizer.normalize(Py.casefold(s), Normalizer.Form.NFKD)
        val b = StringBuilder(nfkd.length)
        nfkd.codePoints().forEach { cp ->
            val tr = if (cp in 0x430..0x44F || cp == 0x451) TR[cp] else null
            if (tr != null) b.append(tr)
            else if (cp in 'a'.code..'z'.code || cp in '0'.code..'9'.code) b.appendCodePoint(cp)
        }
        var k = b.toString()
        for ((x, y) in PHON) k = k.replace(x, y)
        val v = StringBuilder(k.length)
        var prevVowel = false
        for (ch in k) {  // re.sub(r"[aeiou]+", "a", s)
            val vowel = ch == 'a' || ch == 'e' || ch == 'i' || ch == 'o' || ch == 'u'
            if (vowel) { if (!prevVowel) v.append('a') } else v.append(ch)
            prevVowel = vowel
        }
        val d = StringBuilder(v.length)
        for (ch in v) if (d.isEmpty() || d[d.length - 1] != ch) d.append(ch)  // re.sub(r"(.)\1+", r"\1", s)
        return d.toString()
    }

    private fun lev(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] != b[j - 1]) 1 else 0)
            prev = cur
        }
        return prev[b.length]
    }

    /** Phonetic keys are ASCII, so UTF-16 lengths equal Python's. */
    private fun sim(a: String, b: String): Double =
        if (a.isEmpty() || b.isEmpty()) 0.0 else 1.0 - lev(a, b).toDouble() / maxOf(a.length, b.length)

    /** guard.best_sim: the term's phonetic key vs the key of any 1-4 word transcript span. */
    fun bestSim(term: String, transcript: String): Double {
        val tk = phoneticKey(term)
        if (tk.isEmpty()) return 1.0
        val keys = wordTokens(transcript).map { phoneticKey(it) }
        var best = 0.0
        for (i in keys.indices) {
            val span = StringBuilder()
            for (n in 1..4) {
                if (i + n > keys.size) break
                span.append(keys[i + n - 1])
                best = maxOf(best, sim(tk, span.toString()))
            }
        }
        return best
    }

    /** guard.spoken. */
    fun spoken(term: String, transcript: String): Boolean = bestSim(term, transcript) >= DISTRACTOR_SIM

    /**
     * guard._screen_terms. ctx v2: render.ocr_terms of the raw OCR line texts. A null ctx or v != 2 takes the v1 path,
     * which finds nothing in the members a CtxV2 has (no ranked terms, no conversation).
     */
    internal fun screenTerms(ctx: CtxV2?): List<String> {
        if (ctx == null || ctx.v != 2) return emptyList()
        return RenderPy.ocrTerms(ctx.screen?.lines.orEmpty().map { it.text }, limit = 60).filter { Py.len(Py.strip(it)) >= 3 }
    }

    /** guard._screen_words: 5-letter stems of the screen text, the field text before the cursor and (v2) the hint. */
    private fun screenWords(ctx: CtxV2?): Set<String> {
        val parts = ArrayList<String>()
        if (ctx != null && ctx.v == 2) {
            ctx.screen?.lines.orEmpty().mapTo(parts) { it.text }
            parts.add(ctx.field?.beforeCursor ?: "")
            parts.add(ctx.field?.hint ?: "")
        } else {
            parts.add(ctx?.field?.beforeCursor ?: "")
        }
        return parts.flatMapTo(HashSet()) { p -> words(p).map { Py.head(it, 5) } }
    }

    private fun script(w: String): String {
        val (lat, cyr) = letters(w)
        return if (lat > cyr) "lat" else if (cyr > lat) "cyr" else ""
    }

    /** guard._translated_span (R5b). */
    private fun translatedSpan(transcript: String, output: String): Boolean {
        val flatT = SPAN_SPLIT.matcher(transcript).replaceAll(" ")
        val flatO = SPAN_SPLIT.matcher(output).replaceAll(" ")
        val tw = content(words(flatT)).filter { Py.len(it) >= 3 }
        val ow = content(words(flatO)).filter { Py.len(it) >= 3 }
        val tset = tw.toSet()
        val oset = ow.toSet()
        val ok1 = ow.map { phoneticKey(it) }
        val okeys = ok1 + ok1.zipWithNext { a, b -> a + b }
        for ((src, dst) in listOf("lat" to "cyr", "cyr" to "lat")) {
            val lost = tw.filter { w ->
                script(w) == src && w !in oset && (okeys.maxOfOrNull { sim(phoneticKey(w), it) } ?: 0.0) < SPAN_KEEP_SIM
            }
            val new = ow.filter { w -> script(w) == dst && w !in tset && bestSim(w, flatT) < SPAN_SIM }
            if (lost.toSet().size >= SPAN_LOST_MIN && new.isNotEmpty()) return true
        }
        return false
    }

    companion object {
        const val GUARD_VERSION = "guard.v1.1"

        val FILLERS: Set<String> = """э ээ эээ э-э э-э-э м мм ммм м-м хм гм а-а ну эм ам
um uh uhm uh-huh erm er hmm hm mm mmm ah eh""".split(' ', '\n').filter { it.isNotEmpty() }.toSet()

        private const val GROW_K = 1.5
        private const val GROW_ADD = 4
        private const val CHAR_GROW_K = 2.5
        private const val CHAR_GROW_ADD = 40
        private const val SHRINK_K = 0.3
        private const val SHRINK_MIN_IN = 8
        private const val NOVEL_MIN = 3
        private const val NOVEL_SHARE = 0.5
        private const val NOVEL_SIM = 0.75
        private const val SCRIPT_MIN_LETTERS = 8
        private const val DISTRACTOR_SIM = 0.6
        private const val RIVAL_MARGIN = 0.15
        private val NO_SCRIPT_CHECK = setOf("code", "address")
        private const val SPAN_LOST_MIN = 1
        private const val SPAN_SIM = 0.5
        private const val SPAN_KEEP_SIM = 0.7

        private const val W = "[" + Py.W + "]"
        private const val S = "[" + Py.S + "]"
        private const val NS = "[^" + Py.S + "]"
        private const val I = Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE or Pattern.UNIX_LINES

        private val HALLU_FULL = Pattern.compile(
            "^(?:\\[?$S*(?:blank_audio|silence|music|музыка|тишина|аплодисменты|смех)$S*\\]?|" +
                "\\(?$S*(?:music|музыка)$S*\\)?|" +
                "thanks? (?:you )?for watching!?|thank you for watching!?|please subscribe.*|" +
                "продолжение следует\\.*|спасибо за просмотр!?|подписывайтесь на канал.*|" +
                "субтитры (?:сделал|делал|создавал|подготовил)$W*$S.*|редактор субтитров$S.*|" +
                "корректор$S+$NS+\\.$S*$NS+|dimatorzok)$",
            I,
        )
        private val HALLU_TAIL = Pattern.compile(
            "(?:(?<=[.!?…])$S*|$S+)(?:продолжение следует\\.*|субтитры (?:сделал|делал|создавал|подготовил)$W*$S.*|" +
                "редактор субтитров$S.*|thanks? for watching!?|thank you for watching!?|\\[blank_audio\\])$S*$",
            I,
        )
        private val THINK = Pattern.compile("<think>.*?</think>$S*|^.*?</think>$S*", Pattern.DOTALL)
        private val CHAT_TOKENS = Pattern.compile("<\\|[a-z_]+\\|>|<\\|im_end\\|>|<\\|endoftext\\|>")
        private val LABEL = Pattern.compile(
            "^$S*(?:output|edited text|edited|result|corrected text|text|вывод|результат|текст|" +
                "исправленный текст|отредактированный текст)$S*:$S*",
            I,
        )
        private val LEAK = Pattern.compile(
            "</?(?:context|transcript|preferences)>|^$S*Style:|<\\|im_|<think|Names and terms on screen|" +
                "Dictation language:|Text already in the field",
            I or Pattern.MULTILINE,
        )
        /** `\b` after a word character = not followed by a word character. */
        private const val B = "(?!$W)"
        private val PREAMBLE = Pattern.compile(
            "^$S*(?:sure$B|certainly$B|of course$B|here is$B|here's$B|here are$B|okay, here$B|i'm sorry$B|i cannot$B|" +
                "as an ai$B|конечно[,!]|вот (?:исправленный|ваш|отредактированный|готовый)|исправленный текст|" +
                "отредактированный текст|извините, (?:я|но)|я не могу$B)",
            I,
        )
        private val CHAR_LOOP = Pattern.compile("(.{1,6}?)\\1{7,}", Pattern.DOTALL)
        private val FENCE = Pattern.compile("^```$W*\\n?|\\n?```$", Pattern.UNIX_LINES)
        private val SPAN_SPLIT = Pattern.compile("[-_'’]")
        private val QUOTE_PAIRS = mapOf('"' to '"', '«' to '»', '“' to '”', '\'' to '\'', '`' to '`')

        private val TR: Map<Int, String> = run {
            val src = "абвгдеёжзийклмнопрстуфхцчшщъыьэюя"
            val dst = listOf("a", "b", "v", "g", "d", "e", "e", "zh", "z", "i", "i", "k", "l", "m", "n", "o", "p", "r", "s",
                "t", "u", "f", "h", "ts", "ch", "sh", "sch", "", "y", "", "e", "yu", "ya")
            src.indices.associate { src[it].code to dst[it] }
        }
        private val PHON = listOf("sch" to "sh", "ph" to "f", "ck" to "k", "qu" to "kv", "w" to "v", "th" to "t", "x" to "ks",
            "c" to "k", "y" to "i", "j" to "dj", "q" to "k", "ou" to "au", "ee" to "i", "oo" to "u", "h" to "")
    }
}
