package app.murmur.platform.a11y

import app.murmur.core.context.FieldKinds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldsTest {
    @Test
    fun beforeCursorIsCutAtTheSelectionStart() {
        // Multiline field "Line one\nLine two" with the caret after "Line one" (platform spike CTX2: caret 8).
        assertEquals("Line one", BeforeCursor.fromSurrounding("Line one\nLine two", 8))
        assertEquals("", BeforeCursor.fromSurrounding("abc", 0))
        assertEquals("abc", BeforeCursor.fromSurrounding("abc", 3))
        assertEquals("abc", BeforeCursor.fromSurrounding("abc", 7)) // selection past the returned window
        assertNull(BeforeCursor.fromSurrounding("abc", -1))
    }

    @Test
    fun beforeCursorKeepsOnlyTheLast1000Characters() {
        val long = "x".repeat(1500) + "END"
        val cut = BeforeCursor.fromSurrounding(long, long.length)!!
        assertEquals(BeforeCursor.MAX_CHARS, cut.length)
        assertTrue(cut.endsWith("END"))
    }

    @Test
    fun nodeFallbackNeverReturnsTheHint() {
        assertEquals("", BeforeCursor.fromNode("Сообщение", showingHint = true, selectionStart = 0))
        assertEquals("ab", BeforeCursor.fromNode("abcd", showingHint = false, selectionStart = 2))
        assertNull(BeforeCursor.fromNode("abcd", showingHint = false, selectionStart = 9))
        assertEquals("", BeforeCursor.fromNode(null, showingHint = false, selectionStart = -1))
    }

    @Test
    fun passwordsNeverHaveBeforeCursorAndTheImeWins() {
        assertNull(BeforeCursor.resolve(isPassword = true, surrounding = "secret", node = "secret"))
        assertEquals("abc Murmur_ok", BeforeCursor.resolve(false, surrounding = "abc Murmur_ok", node = "abc ")) // stale WebView node
        assertEquals("node", BeforeCursor.resolve(false, surrounding = null, node = "node"))
    }

    @Test
    fun fieldFactsMergeEditorInfoAndNode() {
        val text = FieldKinds.TYPE_CLASS_TEXT
        val pw = text or FieldKinds.TYPE_TEXT_VARIATION_PASSWORD
        assertEquals(pw, FieldFacts.inputType(editorInputType = pw, nodeInputType = 0)) // Compose host node: 0
        assertEquals(text, FieldFacts.inputType(editorInputType = null, nodeInputType = text))
        assertEquals(text, FieldFacts.inputType(editorInputType = 0, nodeInputType = text))
        assertTrue(FieldFacts.isPassword(nodePassword = true, inputType = text, editorInputType = null))
        assertTrue(FieldFacts.isPassword(nodePassword = false, inputType = text, editorInputType = pw))
        assertTrue(FieldFacts.isPassword(nodePassword = false, inputType = FieldKinds.TYPE_CLASS_NUMBER or FieldKinds.TYPE_NUMBER_VARIATION_PASSWORD, editorInputType = null))
        assertFalse(FieldFacts.isPassword(nodePassword = false, inputType = text, editorInputType = text))
        assertTrue(FieldFacts.isMultiline(nodeMultiline = false, inputType = text or FieldKinds.TYPE_TEXT_FLAG_MULTI_LINE))
        assertFalse(FieldFacts.isMultiline(nodeMultiline = false, inputType = text))
    }

    @Test
    fun editableFocusNeedsTheKeyboardUnlessAHardwareKeyboardIsUsed() {
        assertTrue(FieldFacts.editableFocused(editableNode = true, imeInputForApp = false, imeWindowShown = true, hardKeyboard = false))
        assertTrue(FieldFacts.editableFocused(editableNode = false, imeInputForApp = true, imeWindowShown = true, hardKeyboard = false))
        // Keyboard closed while the field keeps focus: DESIGN 5.2 hides the bubble.
        assertFalse(FieldFacts.editableFocused(editableNode = true, imeInputForApp = true, imeWindowShown = false, hardKeyboard = false))
        assertTrue(FieldFacts.editableFocused(editableNode = true, imeInputForApp = false, imeWindowShown = false, hardKeyboard = true))
        assertFalse(FieldFacts.editableFocused(editableNode = false, imeInputForApp = false, imeWindowShown = true, hardKeyboard = true))
    }
}
