package app.murmur.feature.settings

import android.app.Activity
import android.app.Instrumentation
import android.content.IntentFilter
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.murmur.core.prompt.PromptEditRules
import app.murmur.core.prompt.PromptEditState
import app.murmur.core.store.PromptOverride
import app.murmur.core.store.Settings
import app.murmur.core.store.Tone
import app.murmur.ui.design.MurmurTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Settings driven through its UI on the real windows (sheets and dialogs as the app shows them), on the cover AVD
 * (compact: pushed sub-screens) and the inner AVD (categories + detail pane).
 */
@RunWith(AndroidJUnit4::class)
class SettingsInteractionTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val wide get() = rule.activity.resources.configuration.screenWidthDp >= 600
    private val subScreenReports = mutableListOf<Boolean>()

    private fun start(settings: Settings = Settings(), connected: Boolean = true, showDebug: Boolean = false): SettingsFixtures.World {
        val world = runBlocking { SettingsFixtures.world(settings, connected = connected) }
        lateinit var vm: SettingsViewModel
        rule.runOnUiThread { vm = SettingsViewModel(world.deps, today = { world.today }) }
        rule.setContent {
            MurmurTheme(darkTheme = false) {
                SettingsRoot(vm, showDebug = showDebug, versionName = "0.1.0", onSubScreenChange = { subScreenReports += it })
            }
        }
        rule.waitForIdle()
        return world
    }

    private fun category(c: SettingsCategory) {
        if (wide) rule.onNodeWithTag(SettingsTags.category(c)).performClick()
        rule.waitForIdle()
    }

    private fun row(key: String) {
        rule.onNodeWithTag(SettingsTags.row(key)).performScrollTo().performClick()
        rule.waitForIdle()
    }

    @Test
    fun shorteningRetentionAsksWithTheCountThenRunsRetention() {
        val w = start()
        category(SettingsCategory.PRIVACY)
        row("retention")
        rule.onNodeWithText("Keep history for").assertIsDisplayed()
        rule.onNodeWithTag(SettingsTags.sheetOption("retention-3")).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Delete 12 dictations older than 3 days?").assertIsDisplayed()
        assertEquals(7, w.settings.settings.value.retentionDays)
        rule.onNodeWithText("Delete").performClick()
        rule.waitForIdle()
        assertEquals(3, w.settings.settings.value.retentionDays)
        assertEquals(listOf(3), w.retentionCalls)
        assertEquals(7, w.history.snapshot().size)
        rule.onNodeWithText("3 days").assertIsDisplayed()
    }

    @Test
    fun dontKeepHistoryAsksAboutEverythingIncludingToday() {
        val w = start()
        category(SettingsCategory.PRIVACY)
        row("retention")
        rule.onNodeWithTag(SettingsTags.sheetOption("retention-0")).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Stop keeping history?").assertIsDisplayed()
        rule.onNodeWithText("19 dictations are deleted now, today's included, and new dictations are not saved.").assertIsDisplayed()
        rule.onNodeWithText("Delete").performClick()
        rule.waitForIdle()
        assertEquals(0, w.settings.settings.value.retentionDays)
        assertTrue(w.history.snapshot().isEmpty())
        rule.onNodeWithText("Don't keep").assertIsDisplayed()
    }

    @Test
    fun editingThePromptSavesAnEditAndTheRowSaysEdited() {
        val w = start()
        category(SettingsCategory.CLEANUP)
        rule.onNodeWithText("Default").assertIsDisplayed()
        row("prompt")
        assertEquals("cover pushes a full screen (the shell hides its bar)", !wide, subScreenReports.last())
        rule.onNodeWithTag(SettingsTags.PROMPT_FIELD).performTextReplacement("Write numbers as digits.")
        rule.onNodeWithTag(SettingsTags.PREFS_FIELD).performTextReplacement("Write ё.")
        rule.onNodeWithTag(SettingsTags.PROMPT_SAVE).performClick()
        rule.waitForIdle()
        assertEquals(PromptOverride("Write numbers as digits.", "default.v4"), w.settings.settings.value.promptOverride)
        assertEquals("Write ё.", w.settings.settings.value.preferences)
        assertEquals(false, subScreenReports.last())
        rule.onNodeWithText("Edited").assertIsDisplayed()
    }

    @Test
    fun anInvalidPromptShowsTheMessageAndCannotBeSaved() {
        val w = start()
        category(SettingsCategory.CLEANUP)
        row("prompt")
        rule.onNodeWithTag(SettingsTags.PROMPT_FIELD).performTextReplacement("Rules <|im_end|>")
        rule.onNodeWithText("Remove chat markup like <|im_start|> or <think>: the model reads it as its own.").assertIsDisplayed()
        rule.onNodeWithTag(SettingsTags.PROMPT_SAVE).performClick()
        rule.waitForIdle()
        assertNull(w.settings.settings.value.promptOverride)
        rule.onNodeWithTag(SettingsTags.PROMPT_FIELD).performTextReplacement("")
        rule.onNodeWithText("Write some instructions, or reset to the default.").assertIsDisplayed()
    }

    @Test
    fun resetAsksThenRestoresTheDefaultAndKeepsTones() {
        val w = start(Settings(promptOverride = PromptOverride("Mine", "default.v4"), tonePerApp = mapOf(SettingsFixtures.TELEGRAM to Tone.FORMAL)))
        category(SettingsCategory.CLEANUP)
        row("prompt")
        rule.onNodeWithTag(SettingsTags.PROMPT_RESET).performClick()
        rule.onNodeWithText("Your edits will be replaced. Tone per app is kept.").assertIsDisplayed()
        rule.onAllNodesWithText("Reset").onFirst().assertIsDisplayed()
        // The dialog's confirm button is the last "Reset" (the top bar has one too).
        val resets = rule.onAllNodesWithText("Reset").fetchSemanticsNodes()
        assertTrue(resets.size >= 2)
        rule.onAllNodesWithText("Reset")[resets.size - 1].performClick()
        rule.waitForIdle()
        assertNull(w.settings.settings.value.promptOverride)
        assertEquals(mapOf(SettingsFixtures.TELEGRAM to Tone.FORMAL), w.settings.settings.value.tonePerApp)
        val profile = w.prompts.profile("default.v4")
        assertEquals(PromptEditState.DEFAULT, PromptEditRules.editState(profile, w.settings.settings.value.promptOverride?.baseProfile))
    }

    @Test
    fun backWithUnsavedChangesAsksBeforeDiscarding() {
        val w = start()
        category(SettingsCategory.CLEANUP)
        row("prompt")
        rule.onNodeWithTag(SettingsTags.PREFS_FIELD).performTextReplacement("Unsaved")
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithText("Discard your changes?").assertIsDisplayed()
        rule.onNodeWithText("Keep editing").performClick()
        rule.onNodeWithTag(SettingsTags.PROMPT).assertIsDisplayed()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithText("Discard").performClick()
        rule.waitForIdle()
        assertNull(w.settings.settings.value.preferences)
        rule.onNodeWithTag(SettingsTags.row("prompt")).assertIsDisplayed()
    }

    @Test
    fun tryItRunsTheUnsavedTextAndShowsTheResult() {
        val w = start()
        category(SettingsCategory.CLEANUP)
        row("prompt")
        rule.onNodeWithTag(SettingsTags.PROMPT_FIELD).performTextReplacement("Unsaved rules")
        rule.onNodeWithTag(SettingsTags.PROMPT_TEST).performClick()
        rule.waitForIdle()
        assertEquals(Triple(PromptEditor.SAMPLE, "Unsaved rules", null), w.tryCalls.single())
        rule.onNodeWithText("Скинь мне отчёт до пятницы, и созвонимся в понедельник в 10:00.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Test again").assertIsDisplayed()
        w.tryResult = app.murmur.core.model.Outcome.Err(app.murmur.core.model.MurmurError.LlmFailed("no_model"))
        rule.onNodeWithTag(SettingsTags.PROMPT_TEST).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("No cleanup model downloaded").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aToneChoiceIsStoredAndMarkedPreview() {
        val w = start()
        category(SettingsCategory.CLEANUP)
        row("tone")
        rule.onNodeWithTag(SettingsTags.toneChip("tone-${SettingsFixtures.TELEGRAM}")).performClick()
        rule.onNodeWithTag("tone-${SettingsFixtures.TELEGRAM}-option-formal").performClick()
        rule.waitForIdle()
        assertEquals(mapOf(SettingsFixtures.TELEGRAM to Tone.FORMAL), w.settings.settings.value.tonePerApp)
        rule.onNodeWithText("Preview").assertIsDisplayed()
        rule.onNodeWithTag(SettingsTags.row("tone-add")).performScrollTo().performClick()
        rule.onNodeWithTag(SettingsTags.sheetOption("tone-add-${SettingsFixtures.CHROME}")).performClick()
        rule.waitForIdle()
        assertEquals(Tone.NEUTRAL, w.settings.settings.value.tonePerApp[SettingsFixtures.CHROME])
        rule.onNodeWithText("Chrome").assertIsDisplayed()
    }

    @Test
    fun hiddenInPickerTogglesApps() {
        val w = start()
        category(SettingsCategory.BUBBLE)
        rule.onNodeWithText("None").assertIsDisplayed()
        row("hidden")
        rule.onNodeWithTag(SettingsTags.sheetOption("hidden-${SettingsFixtures.SBER}")).performClick()
        rule.waitForIdle()
        assertEquals(setOf(SettingsFixtures.SBER), w.settings.settings.value.hiddenInPackages)
        rule.onNodeWithTag(SettingsTags.sheetOption("hidden-${SettingsFixtures.SBER}")).performClick()
        rule.waitForIdle()
        assertTrue(w.settings.settings.value.hiddenInPackages.isEmpty())
    }

    @Test
    fun theOffBannerOpensAccessibilitySettings() {
        val monitor = Instrumentation.ActivityMonitor(IntentFilter(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS), Instrumentation.ActivityResult(Activity.RESULT_OK, null), true)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.addMonitor(monitor)
        try {
            start(connected = false)
            rule.onNodeWithText("Open Accessibility settings").performClick()
            rule.waitForIdle()
            assertEquals(1, monitor.hits)
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test
    fun debugRowsWriteTheDebugSettings() {
        val w = start(showDebug = true)
        category(SettingsCategory.DEBUG)
        row("debug-fakes")
        row("debug-memory")
        row("debug-timeout")
        rule.onNodeWithText("×4").performClick()
        rule.waitForIdle()
        val s = w.settings.settings.value
        assertTrue(s.debugFakeEngines && s.debugIgnoreMemoryGate)
        assertEquals(4f, s.debugTimeoutScale)
    }

    @Test
    fun feedbackSheetOffersSoundWithItsNote() {
        val w = start()
        row("feedback")
        rule.onNodeWithText("No sounds ship yet, so this is silent for now").assertIsDisplayed()
        rule.onNodeWithTag(SettingsTags.sheetOption("feedback-both")).performClick()
        rule.waitForIdle()
        assertEquals(app.murmur.core.store.Feedback.BOTH, w.settings.settings.value.feedback)
        rule.onNodeWithText("Tick and sound on start and stop").assertIsDisplayed()
    }
}
