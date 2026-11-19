package app.murmur.shell

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.murmur.MurmurApp
import app.murmur.feature.onboarding.PracticeScreen
import app.murmur.shell.Device.hasTag
import app.murmur.shell.Device.waitForTag
import app.murmur.ui.design.MurmurTheme
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
        compose.onNodeWithTag("practice-bubble-ready").performClick()
        compose.waitForTag("practice-bubble-listening")
        Thread.sleep(1_500)
        compose.onNodeWithTag("practice-bubble-listening").performClick()
        compose.waitForTag("practice-result", timeoutMs = 30_000)
        val text = compose.onNodeWithTag("practice-field").fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text
        assertTrue("the practice field got the dictation: '$text'", !text.isNullOrBlank())
        compose.waitForTag("practice-bubble-ready")
        compose.onNodeWithTag("practice-result").assertIsDisplayed()
        Device.shot("shell-practice")
        compose.onNodeWithTag("ob-cta").performClick()
        compose.waitForIdle()
        assertTrue("Done closes the practice screen", closed)
    }
}
