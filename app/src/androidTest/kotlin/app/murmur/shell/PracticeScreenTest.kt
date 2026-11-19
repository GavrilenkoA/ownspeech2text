package app.murmur.shell

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.murmur.MurmurApp
import app.murmur.feature.onboarding.PracticeScreen
import app.murmur.shell.Device.hasTag
import app.murmur.shell.Device.waitForTag
import app.murmur.testing.FakeAsrEngine
import app.murmur.testing.FakeLlmEngine
import app.murmur.ui.design.MurmurTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "Try a dictation" (the shell's PracticeRoute, opened from the History and Dashboard empty states): the practice field
 * without the onboarding chrome runs a fake dictation through the in-app bubble and inserts it; Done closes the
 * screen. Run with `-e murmurFake seeded` (fake engines and capture, models installed). The route rules themselves
 * are ShellTest (JVM).
 */
@RunWith(AndroidJUnit4::class)
class PracticeScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()
    private val container get() = (Device.context.applicationContext as MurmurApp).container!!

    @Test
    fun practiceInsertsAFakeDictationAndDoneCloses() {
        assumeTrue("run with -e murmurFake seeded", container.fakeContainer)
        Device.sh("pm grant ${Device.pkg} android.permission.RECORD_AUDIO")
        container.permissions.refresh()
        var closed = false
        compose.setContent { MurmurTheme { PracticeScreen(container.onboardingDeps, onClose = { closed = true }) } }

        compose.waitForTag("practice")
        assertFalse("no onboarding checklist", compose.hasTag("ob-checklist"))
        assertFalse("the onboarding is not shown", compose.hasTag("ob-ready"))

        // An empty field: exactly the cleaned fake transcript, cursor after it.
        dictate()
        assertEquals(EXPECTED, fieldText())
        assertEquals(TextRange(EXPECTED.length), fieldSelection())
        compose.onNodeWithTag("practice-result").assertIsDisplayed()
        Device.shot("shell-practice")

        // The cursor moved to the start: the second dictation goes in there, not at the end (an append would join
        // with a space after the full stop), and the cursor lands right after it.
        compose.onNodeWithTag("practice-field").performTextInputSelection(TextRange(0))
        compose.waitForIdle()
        dictate()
        assertEquals(EXPECTED + EXPECTED, fieldText())
        assertEquals(TextRange(EXPECTED.length), fieldSelection())
        compose.onNodeWithTag("ob-cta").performClick()
        compose.waitForIdle()
        assertTrue("Done closes the practice screen", closed)
    }

    /** One fake dictation through the in-app bubble, until the result line shows and the bubble is a circle again. */
    private fun dictate() {
        compose.onNodeWithTag("practice-bubble-ready").performClick()
        compose.waitForTag("practice-bubble-listening")
        Thread.sleep(1_500)
        compose.onNodeWithTag("practice-bubble-listening").performClick()
        compose.waitForTag("practice-result", timeoutMs = 30_000)
        compose.waitForTag("practice-bubble-ready")
    }

    private fun fieldText(): String? =
        compose.onNodeWithTag("practice-field").fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text

    private fun fieldSelection(): TextRange? =
        compose.onNodeWithTag("practice-field").fetchSemanticsNode().config.getOrNull(SemanticsProperties.TextSelectionRange)

    companion object {
        /** The fake ASR's script after the fake cleanup (capital letter, full stop); the guard passes it through. */
        val EXPECTED: String = FakeLlmEngine.cleanup(FakeAsrEngine().script)
    }
}
