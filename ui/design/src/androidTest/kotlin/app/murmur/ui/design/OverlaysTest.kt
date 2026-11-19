package app.murmur.ui.design

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
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
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        // A systemui "not responding" dialog on the loaded host would cover the overlay in the evidence: close system
        // dialogs and wait until no such window is on screen (or fail, rather than save a misleading picture).
        repeat(10) {
            if (!systemDialogShown()) {
                Thread.sleep(300)
                val shot = requireNotNull(ui.takeScreenshot())
                // A blank frame (display asleep or not yet drawn) is no evidence either.
                if (!systemDialogShown() && !isBlank(shot)) {
                    save(shot, name)
                    return
                }
                shell("input keyevent KEYCODE_WAKEUP")
            }
            ui.executeShellCommand("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS").close()
            Thread.sleep(1_000)
        }
        throw AssertionError("no clean $name screenshot: a system dialog stayed on screen or the display stayed blank")
    }

    /** True when nearly all of [b] is black: the display was asleep or nothing was drawn yet. */
    private fun isBlank(b: Bitmap): Boolean {
        var black = 0
        var total = 0
        for (y in 0 until b.height step 16) for (x in 0 until b.width step 16) {
            val p = b.getPixel(x, y)
            total++
            if (android.graphics.Color.red(p) + android.graphics.Color.green(p) + android.graphics.Color.blue(p) < 24) black++
        }
        return black > total * 0.95
    }

    /** True while an ANR or other system error dialog is among the windows on screen. */
    private fun systemDialogShown(): Boolean = shell("dumpsys window windows").lines().any {
        it.contains("Window{") && (it.contains("Application Not Responding") || it.contains("Application Error"))
    }

    private fun shell(cmd: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd))
            .use { it.readBytes().decodeToString() }

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
                Box(Modifier.fillMaxSize().background(MurmurTheme.colors.bg).windowInsetsPadding(WindowInsets.safeDrawing).padding(20.dp)) { Text("Behind the dialog") }
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
                Box(Modifier.fillMaxSize().background(MurmurTheme.colors.bg).windowInsetsPadding(WindowInsets.safeDrawing).padding(20.dp)) { Text("Behind the sheet") }
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

    /**
     * A tap on the scrim dismisses the sheet, and the sheet leaves with its exit animation: onDismiss comes only once
     * the sheet is gone (side sheet: 240 ms slide out to the right; bottom sheet: the M3 hide animation).
     */
    @Test
    fun scrimTapPlaysTheExitThenDismisses() = withAnimatorScale("1") {
        var dismissed = 0
        var open by mutableStateOf(true)
        rule.setContent {
            MurmurTheme(darkTheme = false) {
                Box(Modifier.fillMaxSize().background(MurmurTheme.colors.bg).windowInsetsPadding(WindowInsets.safeDrawing).padding(20.dp)) { Text("Behind the sheet") }
                if (open) {
                    MurmurSheet(onDismiss = { dismissed++; open = false }) {
                        Text("Keep history for", Modifier.padding(horizontal = 24.dp).testTag("sheet-title"), style = MurmurTheme.type.title)
                    }
                }
            }
        }
        rule.waitForIdle()
        val shownLeft = rule.onNodeWithTag("sheet-title").fetchSemanticsNode().boundsInRoot.left
        rule.mainClock.autoAdvance = false
        // The sheet's window is the root that holds the title; tap its top-left, which only the scrim covers.
        val roots = rule.onAllNodes(isRoot())
        val overlay = (0 until roots.fetchSemanticsNodes().size).first { i ->
            roots[i].fetchSemanticsNode().let { root -> root.findAll { it.config.getOrNull(SemanticsProperties.TestTag) == "sheet-title" } }
        }
        roots[overlay].performTouchInput { click(Offset(width * 0.05f, height * 0.2f)) }
        rule.mainClock.advanceTimeBy(Motion.mediumMs / 2L)
        assertEquals("not dismissed while the exit runs", 0, dismissed)
        rule.onNodeWithTag("sheet-title").assertExists()
        if (wide) {
            val left = rule.onNodeWithTag("sheet-title").fetchSemanticsNode().boundsInRoot.left
            assertTrue("side sheet slides out to the right ($shownLeft -> $left)", left > shownLeft + 8 * context.resources.displayMetrics.density)
        }
        rule.mainClock.autoAdvance = true
        rule.waitUntil(5_000) { dismissed == 1 }
        rule.waitForIdle()
        rule.onNodeWithTag("sheet-title").assertDoesNotExist()
        assertEquals(1, dismissed)
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
                    result = when (round) {
                        0 -> state.show("Dictation deleted, with its audio", "Undo")
                        1 -> state.show("Bubble hidden until 15:32")
                        else -> state.show("Model ready", "Use it")
                    }
                }
            }
        }
        rule.onNodeWithText("Dictation deleted, with its audio").assertIsDisplayed()
        rule.onNodeWithText("Undo").performClick()
        rule.waitForIdle()
        assertEquals(SnackbarResult.ActionPerformed, result)
        // Without an action it dismisses itself after 4 s (virtual clock): still there at 3.85 s, gone after 4 s.
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { result = null; round = 1 }
        rule.mainClock.advanceTimeBy(100)
        rule.onNodeWithText("Bubble hidden until 15:32").assertIsDisplayed()
        rule.mainClock.advanceTimeBy(3_750)
        rule.onNodeWithText("Bubble hidden until 15:32").assertIsDisplayed()
        assertEquals(null, result)
        rule.mainClock.advanceTimeBy(300)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertEquals(SnackbarResult.Dismissed, result)
        // With an action it stays 6 s: still there at 5.85 s, gone after 6 s.
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { result = null; round = 2 }
        rule.mainClock.advanceTimeBy(100)
        rule.onNodeWithText("Model ready").assertIsDisplayed()
        rule.mainClock.advanceTimeBy(5_750)
        rule.onNodeWithText("Model ready").assertIsDisplayed()
        assertEquals(null, result)
        rule.mainClock.advanceTimeBy(300)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertEquals(SnackbarResult.Dismissed, result)
    }

    /**
     * Runs [block] with the system animator duration scale at [scale]. The test AVDs run with animations off (scale
     * 0), and Compose scales every animation by it, so an exit animation is only observable with the scale at 1.
     */
    private fun withAnimatorScale(scale: String, block: () -> Unit) {
        val before = shell("settings get global animator_duration_scale").trim()
        shell("settings put global animator_duration_scale $scale")
        try {
            rule.waitForIdle()
            block()
        } finally {
            shell("settings put global animator_duration_scale ${if (before == "null" || before.isEmpty()) "1" else before}")
        }
    }

    private fun SemanticsNode.findAll(match: (SemanticsNode) -> Boolean): Boolean = match(this) || children.any { it.findAll(match) }
}
