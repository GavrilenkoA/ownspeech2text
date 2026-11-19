package app.murmur.core.dictation

import org.junit.Assert.assertEquals
import org.junit.Test

/** ARCHITECTURE 11 step 4: how dictated text joins the text before the cursor. */
class JoinPolicyTest {
    private val p = DefaultJoinPolicy

    private data class Case(val before: String?, val text: String, val lowercase: Boolean, val expected: String, val why: String)

    private val table = listOf(
        Case(null, "Привет.", true, "Привет.", "unknown field content"),
        Case("", "Привет.", true, "Привет.", "start of field"),
        Case("Привет ", "Как дела?", false, "Как дела?", "after a space"),
        Case("Привет", "как дела", false, " как дела", "after a letter"),
        Case("Итог: 42", "штуки", false, " штуки", "after a digit"),
        Case("Готово.", "Дальше", false, " Дальше", "after closing punctuation"),
        Case("Смотри (", "вот это", false, "вот это", "after an opening bracket"),
        Case("«", "цитата", false, "цитата", "after an opening quote"),
        Case("Первая строка\n", "Вторая", true, "Вторая", "after a newline, never lowercased"),
        Case("Встреча в", "Zoom", false, " Zoom", "Cyrillic then Latin"),
        Case("Open the", "файл", false, " файл", "Latin then Cyrillic"),
        Case("Привет", ", как дела", false, ", как дела", "text starting with punctuation"),
        Case("Смотри, ", "Вот это.", true, "вот это.", "mid-sentence and accepted: lowercased"),
        Case("Смотри, ", "Вот это.", false, "Вот это.", "mid-sentence but raw: case kept"),
        Case("Всё. ", "Дальше.", true, "Дальше.", "after a sentence end: case kept"),
        Case("Это ", "NASA сказали", true, "NASA сказали", "acronym kept"),
        Case("Yes, ", "I think so", true, "I think so", "English pronoun kept"),
    )

    @Test fun joinTable() {
        for (c in table) assertEquals(c.why, c.expected, p.join(c.before, c.text, c.lowercase))
    }
}
