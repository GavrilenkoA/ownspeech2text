package app.murmur.feature.onboarding

import android.text.InputType
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import app.murmur.testing.FakeClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The practice field as the orchestrator's in-app target: the snapshot it reads and where [PracticeTarget.insert] writes. */
class PracticeTargetTest {
    private class Field(var value: TextFieldValue) {
        val target = PracticeTarget("app.murmur", "Murmur", FakeClock(), read = { value }, write = { value = it })
    }

    @Test
    fun insertReplacesTheSelectionAndPutsTheCursorAfterTheText() {
        val f = Field(TextFieldValue("Hello world, bye", TextRange(6, 11)))
        f.target.insert("there")
        assertEquals("Hello there, bye", f.value.text)
        assertEquals(TextRange(11), f.value.selection)
    }

    @Test
    fun aBackwardSelectionIsReplacedTheSameWay() {
        val f = Field(TextFieldValue("Hello world, bye", TextRange(11, 6)))
        f.target.insert("there")
        assertEquals("Hello there, bye", f.value.text)
        assertEquals(TextRange(11), f.value.selection)
    }

    @Test
    fun aCollapsedCursorInsertsInPlace() {
        val f = Field(TextFieldValue("Купить молоко", TextRange(7)))
        f.target.insert("хлеб и ")
        assertEquals("Купить хлеб и молоко", f.value.text)
        assertEquals(TextRange(14), f.value.selection)
        // Again at the new cursor: the second piece follows the first.
        f.target.insert("сыр, ")
        assertEquals("Купить хлеб и сыр, молоко", f.value.text)
        assertEquals(TextRange(19), f.value.selection)
    }

    @Test
    fun anEmptyFieldTakesTheTextAndASelectionPastTheEndIsClamped() {
        val empty = Field(TextFieldValue())
        empty.target.insert("Привет.")
        assertEquals("Привет.", empty.value.text)
        assertEquals(TextRange(7), empty.value.selection)
    }

    @Test
    fun theSnapshotSeesTheTextBeforeTheCursorOfAMultiLineField() {
        val f = Field(TextFieldValue("Hello world", TextRange(5, 11)))
        val field = f.target.snapshot.field!!
        assertEquals("app.murmur", f.target.snapshot.pkg)
        assertEquals("the join rules see the text before the selection", "Hello", field.beforeCursor)
        assertTrue(field.isMultiline)
        assertTrue(field.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0)
        assertEquals(false, field.isPassword)
    }
}
