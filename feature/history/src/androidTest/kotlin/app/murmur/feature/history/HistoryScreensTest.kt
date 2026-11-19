package app.murmur.feature.history

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.murmur.core.model.EntryId
import app.murmur.feature.history.ui.HistoryTags
import app.murmur.testing.contrib.Wp16HistoryFixture
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import kotlin.math.floor

/**
 * WP-16 acceptance on the cover (compact) and inner (medium) AVDs, with the frames' data set on :testing fakes:
 * list, detail, original, changes, search, no results, empty, off, delete + undo + commit, dark, player, fold
 * continuity (activity recreate keeps the chosen entry). Screenshots of every frame are written to
 * `<externalFiles>/wp16/<frame>--{cover,inner}.png` (pulled into `.cache/spikes/wp-16/`). Every screen is checked
 * for touch targets >= 48 dp and for named controls (TalkBack).
 */
@RunWith(AndroidJUnit4::class)
class HistoryScreensTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val density = context.resources.displayMetrics.density
    private val wide = context.resources.configuration.screenWidthDp >= 600
    private val device = if (wide) "inner" else "cover"
    private var scenario: ActivityScenario<HistoryTestActivity>? = null

    @After
    fun close() {
        scenario?.close()
        HistoryTestActivity.config = null
    }

    private fun launch(world: HistoryWorld, dark: Boolean = false) {
        HistoryTestActivity.config = HistoryTestActivity.Config(world, dark)
        scenario = ActivityScenario.launch(HistoryTestActivity::class.java)
        rule.waitForIdle()
    }

    private fun world(seed: Boolean = true, retentionDays: Int = 7) = HistoryWorld(context, seed, retentionDays)

    /** On the cover display the detail is pushed: tap the row. On the inner display the newest entry is already shown. */
    private fun openFirst(world: HistoryWorld) {
        if (!wide) rule.onNodeWithTag(HistoryTags.row(world.ids[0])).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(HistoryTags.DETAIL).assertIsDisplayed()
    }

    // ------------------------------------------------------------------ list

    @Test
    fun list() {
        val w = world()
        launch(w)
        rule.onNode(hasText("History") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).assertIsDisplayed()
        rule.onNodeWithText("Dictations stay on this phone for 7 days, then delete automatically.").assertIsDisplayed()
        rule.onNodeWithText("Today").assertIsDisplayed()
        rule.onNodeWithText("6 dictations").assertIsDisplayed()
        rule.onNodeWithTag(HistoryTags.row(w.ids[0])).assertIsDisplayed()
        assertTrue(rowDescription(w.ids[3]).contains("Copied"))
        assertTrue(rowDescription(w.ids[5]).contains("Failed"))
        assertTrue(rowDescription(w.ids[5]).contains("No speech detected. The recording is kept, so you can try again."))
        assertTrue(rowDescription(w.ids[0]).startsWith("Telegram · Дима Соколов, 14:32"))
        if (wide) rule.onNodeWithTag(HistoryTags.DETAIL).assertIsDisplayed()
        checkScreen("hist")
    }

    @Test
    fun listDark() {
        launch(world(), dark = true)
        rule.onNodeWithText("Today").assertIsDisplayed()
        shot("hist-dark")
    }

    @Test
    fun oldestDayShowsDeletesTonight() {
        val w = world()
        launch(w)
        rule.onNodeWithTag(HistoryTags.LIST).performScrollToKey("day-${Wp16HistoryFixture.TODAY.minusDays(6)}")
        rule.waitForIdle()
        rule.onNodeWithText("Deletes tonight").assertIsDisplayed()
        // Only the oldest kept day: the other headers show their counts.
        assertEquals(1, rule.onAllNodesWithText("Deletes tonight").fetchSemanticsNodes().size)
        shot("hist-deletes-tonight")
    }

    // ------------------------------------------------------------------ detail

    @Test
    fun detail() {
        val w = world()
        launch(w)
        openFirst(w)
        rule.onNodeWithText("Дима Соколов").assertIsDisplayed()
        rule.onNodeWithText("Telegram · Today, 14:32 · 0:12").assertIsDisplayed()
        rule.onNodeWithTag(HistoryTags.READING).assertTextEquals(Wp16HistoryFixture.H1_FINAL)
        rule.onNodeWithText("182 words · not stored").assertIsDisplayed()
        rule.onNodeWithText("Telegram · chat with Дима Соколов").assertIsDisplayed()
        rule.onNodeWithText("Casual").assertIsDisplayed()
        rule.onNodeWithText("Qwen3-ASR 0.6B NPU → LFM3 1.7B NPU").assertIsDisplayed()
        rule.onNodeWithText("0.4 s + 0.9 s after stop").assertIsDisplayed()
        rule.onNodeWithText("Deletes with its audio on Wed 25 Nov at 00:00").assertIsDisplayed()
        rule.onNodeWithTag(HistoryTags.PLAYER).assertIsDisplayed()
        rule.onNodeWithText("Copy").assertIsDisplayed()
        rule.onNodeWithText("Re-polish").assertIsDisplayed()
        // The screen-text row is information only (ADR-043): nothing clickable there.
        assertTrue(rule.onAllNodes(hasText("182 words · not stored") and hasClickAction()).fetchSemanticsNodes().isEmpty())
        checkScreen("hist-detail")
    }

    @Test
    fun original() {
        val w = world()
        launch(w)
        openFirst(w)
        rule.onNodeWithText("Original").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(HistoryTags.READING).assertTextEquals(Wp16HistoryFixture.H1_RAW)
        checkScreen("hist-raw")
    }

    @Test
    fun changes() {
        val w = world()
        launch(w)
        openFirst(w)
        rule.onNodeWithText("Changes").performClick()
        rule.waitForIdle()
        val cd = rule.onNodeWithTag(HistoryTags.DIFF).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()
        assertEquals(
            "added: Привет!, Буду, 11,, Сашей, четверг., Скинь,, пожалуйста,, документ.; removed: э, одиннадцати, ну",
            cd,
        )
        rule.onNodeWithText("added").assertIsDisplayed()
        rule.onNodeWithText("removed").assertIsDisplayed()
        checkScreen("hist-diff")
    }

    @Test
    fun failedEntryOffersTryAgainAndDisablesItWithoutTheRecording() {
        val w = world()
        launch(w)
        val chrome = w.ids[5]
        open(chrome)
        rule.onNodeWithText("Try again").assertIsDisplayed().performClick()
        rule.waitUntil(5_000) { w.retries.contains(chrome) }
        // Without the file: "Recording not available" and Try again disabled.
        runBlocking { w.audio.delete(w.history.snapshot().first { it.id == chrome }.audio!!) }
        open(w.ids[3]) // Termux: stored without audio
        rule.onNodeWithTag(HistoryTags.NO_RECORDING).assertIsDisplayed()
        rule.onNodeWithText("Recording not available").assertIsDisplayed()
        open(chrome)
        rule.onNodeWithTag(HistoryTags.NO_RECORDING).assertIsDisplayed()
        rule.onNode(hasText("Try again") and hasClickAction()).assertIsNotEnabled()
    }

    @Test
    fun repolishCallsTheOrchestrator() {
        val w = world()
        launch(w)
        openFirst(w)
        rule.onNodeWithText("Re-polish").performClick()
        rule.waitUntil(5_000) { w.repolishes.contains(w.ids[0]) }
    }

    @Test
    fun playerPlaysAndMovesThePosition() {
        val w = world()
        launch(w)
        openFirst(w)
        val position = { rule.onNode(hasContentDescription("Recording position")).fetchSemanticsNode().config[SemanticsProperties.StateDescription] }
        assertEquals("0:00 / 0:12", position())
        rule.onNode(hasContentDescription("Play recording")).performClick()
        rule.waitUntil(6_000) { position() != "0:00 / 0:12" }
        rule.onNode(hasContentDescription("Pause playback")).performClick()
        rule.waitForIdle()
        assertNotEquals("0:00 / 0:12", position())
    }

    // ------------------------------------------------------------------ search

    @Test
    fun search() {
        launch(world())
        rule.onNode(hasContentDescription("Search history")).performClick()
        rule.waitForIdle()
        rule.onNode(hasSetTextAction()).performTextInput("созвон")
        rule.waitForIdle()
        rule.onNodeWithText("2 results · final text and original transcripts").assertIsDisplayed()
        rule.onNodeWithText("All apps").assertIsDisplayed()
        rule.onAllNodesWithText("1 result").fetchSemanticsNodes().let { assertEquals(2, it.size) }
        checkScreen("hist-search")
    }

    @Test
    fun searchOriginalOnlyHitIsMarked() {
        val w = world()
        launch(w)
        rule.onNode(hasContentDescription("Search history")).performClick()
        rule.onNode(hasSetTextAction()).performTextInput("одиннадцати")
        rule.waitForIdle()
        rule.onNodeWithText("1 result · final text and original transcripts").assertIsDisplayed()
        assertTrue(rowDescription(w.ids[0]).contains("Original: " + Wp16HistoryFixture.H1_RAW))
    }

    @Test
    fun noResults() {
        launch(world())
        rule.onNode(hasContentDescription("Search history")).performClick()
        rule.onNode(hasSetTextAction()).performTextInput("созвон с юристом")
        rule.waitForIdle()
        rule.onNodeWithTag(HistoryTags.NO_RESULTS).assertIsDisplayed()
        rule.onNodeWithText("No matches in the last 7 days").assertIsDisplayed()
        checkScreen("hist-noresults")
        // Clearing brings the whole list back; Back closes search.
        rule.onNode(hasSetTextAction()).performTextReplacement("")
        rule.waitForIdle()
        rule.onNodeWithText("Today").assertIsDisplayed()
        rule.onNode(hasContentDescription("Close search")).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Dictations stay on this phone for 7 days, then delete automatically.").assertIsDisplayed()
    }

    // ------------------------------------------------------------------ empty / off

    @Test
    fun empty() {
        launch(world(seed = false))
        rule.onNodeWithTag(HistoryTags.EMPTY).assertIsDisplayed()
        rule.onNodeWithText("Nothing here yet").assertIsDisplayed()
        rule.onNodeWithText("Tap the bubble in any app and speak. Each dictation shows up here with its audio for 7 days.").assertIsDisplayed()
        rule.onNodeWithText("Try a dictation").assertIsDisplayed()
        checkScreen("hist-empty")
    }

    @Test
    fun emptyWithoutAPracticeHookHidesTryADictation() {
        HistoryTestActivity.config = HistoryTestActivity.Config(world(seed = false), content = { w -> HistoryListDetail(w.deps, bottomBar = { TestNavBar() }) })
        scenario = ActivityScenario.launch(HistoryTestActivity::class.java)
        rule.waitForIdle()
        rule.onNodeWithText("Nothing here yet").assertIsDisplayed()
        assertTrue(rule.onAllNodesWithText("Try a dictation").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun retentionOffShowsDontKeepHistory() {
        launch(world(seed = false, retentionDays = 0))
        rule.onNodeWithText("History is off").assertIsDisplayed()
        checkScreen("hist-off")
    }

    // ------------------------------------------------------------------ delete

    @Test
    fun deleteThenUndo() {
        val w = world()
        launch(w)
        openFirst(w)
        rule.onNode(hasContentDescription("Delete") and hasClickAction()).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Delete this dictation?").assertIsDisplayed()
        rule.onNodeWithText("The text and the recording are removed from this phone. Text already pasted into Telegram stays there.").assertIsDisplayed()
        shot("hist-delete")
        assertTargets("hist-delete dialog", dialogOnly = true)
        rule.onNode(hasText("Delete") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(HistoryTags.UNDO).assertIsDisplayed()
        rule.onNodeWithText("Dictation deleted, with its audio").assertIsDisplayed()
        rule.onNodeWithTag(HistoryTags.LIST).assertIsDisplayed()
        assertTrue(rule.onAllNodes(hasTestTag(HistoryTags.row(w.ids[0]))).fetchSemanticsNodes().isEmpty())
        shot("hist-undo")
        rule.onNodeWithText("Undo").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(HistoryTags.row(w.ids[0])).assertIsDisplayed()
        Thread.sleep(7_000)
        assertTrue(w.history.snapshot().any { it.id == w.ids[0] })
    }

    @Test
    fun deleteCommitsAfterTheSnackbarWithItsAudio() {
        val w = world()
        launch(w)
        val ref = w.history.snapshot().first { it.id == w.ids[1] }.audio!!
        open(w.ids[1])
        rule.onNode(hasContentDescription("Delete") and hasClickAction()).performClick()
        rule.onNodeWithText("Text already pasted into Gmail stays there.", substring = true).assertIsDisplayed()
        rule.onNode(hasText("Delete") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(HistoryTags.UNDO).assertIsDisplayed()
        rule.waitUntil(15_000) { w.history.snapshot().none { it.id == w.ids[1] } }
        assertFalse(runBlocking { w.audio.exists(ref) })
        rule.waitUntil(3_000) { rule.onAllNodes(hasTestTag(HistoryTags.UNDO)).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun multiSelectDeletesSeveral() {
        val w = world()
        launch(w)
        rule.onNodeWithTag(HistoryTags.row(w.ids[1])).performTouchInput { longClick() }
        rule.onNodeWithTag(HistoryTags.row(w.ids[2])).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("2 selected").assertIsDisplayed()
        shot("hist-select")
        assertTargets("hist-select")
        rule.onNode(hasContentDescription("Delete") and hasClickAction()).performClick()
        rule.onNodeWithText("Delete 2 dictations?").assertIsDisplayed()
        rule.onNode(hasText("Delete") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("2 dictations deleted, with their audio").assertIsDisplayed()
        rule.onNodeWithText("4 dictations").assertIsDisplayed()
    }

    // ------------------------------------------------------------------ fold continuity

    @Test
    fun chosenEntrySurvivesRecreation() {
        val w = world()
        launch(w)
        val gmail = w.ids[1]
        rule.onNodeWithTag(HistoryTags.row(gmail)).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(HistoryTags.DETAIL).assertIsDisplayed()
        rule.onNodeWithText("Gmail · Today, 13:05 · 0:18").assertIsDisplayed()
        rule.onNodeWithText("Original").performClick()
        scenario!!.recreate()
        rule.waitForIdle()
        rule.onNodeWithTag(HistoryTags.DETAIL).assertIsDisplayed()
        rule.onNodeWithText("Gmail · Today, 13:05 · 0:18").assertIsDisplayed()
        rule.onNodeWithTag(HistoryTags.READING).assertTextEquals(Wp16HistoryFixture.ITEMS[1].raw!!)
    }

    // ------------------------------------------------------------------ helpers

    /** Opens [id]: on the cover display Back first if a detail is shown, then the row (scrolled into view). */
    private fun open(id: EntryId) {
        if (!wide && rule.onAllNodes(hasTestTag(HistoryTags.DETAIL)).fetchSemanticsNodes().isNotEmpty()) {
            scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            rule.waitForIdle()
        }
        rule.onNodeWithTag(HistoryTags.LIST).performScrollToKey(id.value)
        rule.onNodeWithTag(HistoryTags.row(id)).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(HistoryTags.DETAIL).assertIsDisplayed()
    }

    private fun rowDescription(id: EntryId): String =
        rule.onNodeWithTag(HistoryTags.row(id)).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()

    private fun checkScreen(name: String) {
        shot(name)
        assertTargets(name)
        assertNamed(name)
    }

    /** Full-screen screenshot (status bar, dialog windows included) to `<externalFiles>/wp16/<name>--<device>.png`. */
    private fun shot(name: String) {
        rule.waitForIdle()
        // The shared AVDs' System UI sometimes raises an ANR dialog under the software renderer; it is not ours.
        instrumentation.uiAutomation.executeShellCommand("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS")
            .let { pfd -> android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() } }
        Thread.sleep(600)
        val bmp = instrumentation.uiAutomation.takeScreenshot()
        val dir = File(context.getExternalFilesDir(null), "wp16").apply { mkdirs() }
        val file = File(dir, "$name--$device.png")
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Log.i(TAG, "screenshot ${file.absolutePath}")
    }

    /** Every clickable is at least 48 × 48 dp (layout size; DESIGN 4, 8). */
    private fun assertTargets(name: String, dialogOnly: Boolean = false) {
        val min = floor(48 * density) - 0.5f
        val matcher = if (dialogOnly) hasClickAction() and hasAnyAncestor(isDialog()) else hasClickAction()
        val nodes = rule.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("clickables on $name", nodes.isNotEmpty())
        val small = nodes.filter { it.size.width < min || it.size.height < min }.map { describe(it) }
        Log.i(TAG, "$name: ${nodes.size} clickables, smallest ${nodes.minOf { minOf(it.size.width, it.size.height) } / density} dp")
        assertTrue("clickables below 48 dp on $name:\n" + small.joinToString("\n"), small.isEmpty())
    }

    /** TalkBack: every button, tab, chip and row has a text or a content description. */
    private fun assertNamed(name: String) {
        val nodes = rule.onAllNodes(SemanticsMatcher("clickable or role") { it.config.contains(SemanticsActions.OnClick) || it.config.getOrNull(SemanticsProperties.Role) in ROLES })
            .fetchSemanticsNodes()
        val unnamed = nodes.filter { n ->
            val text = (n.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }.orEmpty() +
                n.config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty())
            val cd = n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString().orEmpty()
            text.isBlank() && cd.isBlank()
        }.map { describe(it) }
        Log.i(TAG, "$name: ${nodes.size} controls named")
        assertTrue("unnamed controls on $name:\n" + unnamed.joinToString("\n"), unnamed.isEmpty())
    }

    private fun describe(n: SemanticsNode) =
        "#${n.id} ${n.config.getOrNull(SemanticsProperties.Role)} ${n.config.getOrNull(SemanticsProperties.TestTag)} " +
            "${n.config.getOrNull(SemanticsProperties.ContentDescription)} ${n.size.width / density}x${n.size.height / density} dp"

    private companion object {
        const val TAG = "Wp16History"
        val ROLES = setOf(Role.Button, Role.Tab, Role.RadioButton, Role.Checkbox, Role.Switch)
    }
}
