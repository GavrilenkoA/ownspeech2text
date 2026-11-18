package app.murmur.ui.design

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import android.accessibilityservice.AccessibilityService
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.murmur.ui.design.components.MurmurDialog
import app.murmur.ui.design.components.MurmurSheet
import app.murmur.ui.design.components.MurmurSnackbarHost
import app.murmur.ui.design.components.MurmurSnackbarState
import app.murmur.ui.design.components.SideSheetWidth
import app.murmur.ui.design.components.SnackbarResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The windowed overlays the gallery cannot show inline: the confirmation dialog (own window, design scrim), the
 * sheet (bottom sheet on compact width, 420 dp side sheet on medium+) and the snackbar host. Screenshots of the
 * dialog and the sheet go next to the gallery PNGs.
 */
@RunWith(AndroidJUnit4::class)
class OverlaysTest {
    @get:Rule
    val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val wide = context.resources.configuration.screenWidthDp >= 600
    private val device = if (wide) "inner" else "cover"

    /** System back, as the user's gesture or key would send it (reaches the dialog / sheet window). */
    private fun pressBack() {
        // On a loaded host the emulator's systemui sometimes raises an ANR dialog ("System UI isn't responding") that
        // takes window focus and swallows the back. Close system dialogs and retry, at most 3 times; a back that our
        // window never handles still fails the test.
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        repeat(3) {
            rule.waitForIdle()
            ui.executeShellCommand("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS").close()
            Thread.sleep(500)
            ui.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            val done = runCatching { rule.waitUntil(3_000) { backHandled() } }.isSuccess
            if (done) {
                rule.waitForIdle()
                return
            }
        }
        throw AssertionError("back was not handled by the dialog or sheet")
    }

    private var backHandled: () -> Boolean = { true }

    /**
     * A real screen capture (UiAutomation): captureToImage of a node in the full-window dialog returns the activity
     * window instead of the dialog's on Compose 1.12.1, so the overlays are captured from the screen.
     */
    private fun saveScreen(name: String) {
        rule.waitForIdle()
        save(requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()), name)
    }

    private fun save(bitmap: Bitmap, name: String) {
        val f = File(requireNotNull(context.getExternalFilesDir(null)), "$name-$device.png")
        f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun dialogConfirmsAndDismisses() {
        var confirmed = 0
        var dismissed = 0
        var open by mutableStateOf(true)
        rule.setContent {
            MurmurTheme(darkTheme = false) {
                Box(Modifier.fillMaxSize().background(MurmurTheme.colors.bg)) { Text("Behind the dialog") }
                if (open) {
                    MurmurDialog(
                        title = "Delete this dictation?",
                        body = "The text and the recording are removed from this phone. Text already pasted into Telegram stays there.",
                        confirmLabel = "Delete", onConfirm = { confirmed++; open = false },
                        dismissLabel = "Cancel", onDismiss = { dismissed++; open = false },
                        destructive = true,
                    )
                }
            }
        }
        rule.onNodeWithText("Delete this dictation?").assertIsDisplayed()
        saveScreen("dialog")
        rule.onNodeWithText("Delete").performClick()
        rule.waitForIdle()
        assertEquals(1, confirmed)
        assertEquals(0, dismissed)
        rule.runOnIdle { open = true }
        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()
        assertEquals(1, dismissed)
        rule.runOnIdle { open = true }
        rule.onNodeWithText("Delete this dictation?").assertIsDisplayed()
        backHandled = { dismissed == 2 }
        pressBack()
        assertEquals("back dismisses", 2, dismissed)
    }

    @Test
    fun sheetIsBottomOnCompactAndSideOnMedium() {
        var dismissed = 0
        var open by mutableStateOf(true)
        rule.setContent {
            MurmurTheme(darkTheme = false) {
                Box(Modifier.fillMaxSize().background(MurmurTheme.colors.bg)) { Text("Behind the sheet") }
                if (open) {
                    MurmurSheet(onDismiss = { dismissed++; open = false }) {
                        Column(Modifier.padding(horizontal = 24.dp)) {
                            Text("Keep history for", Modifier.testTag("sheet-title"), style = MurmurTheme.type.title)
                            Text("Text and recordings are deleted at midnight once they are older than this.", style = MurmurTheme.type.body)
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("sheet-title").assertIsDisplayed()
        val bounds = rule.onNodeWithTag("sheet-title").fetchSemanticsNode().boundsInRoot
        val density = context.resources.displayMetrics.density
        val screenW = context.resources.displayMetrics.widthPixels
        if (wide) {
            // The side sheet hugs the right edge: its content starts within the last 420 dp of the screen.
            assertTrue("side sheet content at x=${bounds.left}", bounds.left >= screenW - SideSheetWidth.value * density)
        } else {
            assertTrue("bottom sheet spans the width (content at x=${bounds.left})", bounds.left < 48 * density)
        }
        saveScreen("sheet")
        backHandled = { dismissed == 1 }
        pressBack()
        assertEquals("back dismisses the sheet", 1, dismissed)
    }

    @Test
    fun snackbarActionAndTimeout() {
        val state = MurmurSnackbarState()
        var result by mutableStateOf<SnackbarResult?>(null)
        var round by mutableStateOf(0)
        rule.setContent {
            MurmurTheme(darkTheme = false) {
                Box(Modifier.fillMaxSize()) {
                    MurmurSnackbarHost(state, Modifier.testTag("host"))
                }
                LaunchedEffect(round) {
                    result = if (round == 0) state.show("Dictation deleted, with its audio", "Undo") else state.show("Bubble hidden until 15:32")
                }
            }
        }
        rule.onNodeWithText("Dictation deleted, with its audio").assertIsDisplayed()
        rule.onNodeWithText("Undo").performClick()
        rule.waitForIdle()
        assertEquals(SnackbarResult.ActionPerformed, result)
        // Without an action it dismisses itself after 4 s (virtual clock).
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { result = null; round = 1 }
        rule.mainClock.advanceTimeBy(1_000)
        rule.onNodeWithText("Bubble hidden until 15:32").assertIsDisplayed()
        rule.mainClock.advanceTimeBy(4_000)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertEquals(SnackbarResult.Dismissed, result)
    }
}
