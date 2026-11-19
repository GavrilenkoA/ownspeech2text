package app.murmur.feature.settings

import app.murmur.core.model.ModelId
import app.murmur.core.prompt.PromptEditState
import app.murmur.core.store.BubbleSize
import app.murmur.core.store.BubblePosition
import app.murmur.core.store.BubbleVisibility
import app.murmur.core.store.CleanupChoice
import app.murmur.core.store.Feedback
import app.murmur.core.store.PromptOverride
import app.murmur.core.store.RetentionPolicy
import app.murmur.core.store.Settings
import app.murmur.core.store.Tone
import app.murmur.feature.settings.SettingsFixtures.CHROME
import app.murmur.feature.settings.SettingsFixtures.DOCS
import app.murmur.feature.settings.SettingsFixtures.GMAIL
import app.murmur.feature.settings.SettingsFixtures.MESSAGES
import app.murmur.feature.settings.SettingsFixtures.SLACK
import app.murmur.feature.settings.SettingsFixtures.TELEGRAM
import app.murmur.feature.settings.SettingsFixtures.TERMUX
import app.murmur.feature.settings.SettingsFixtures.TODAY
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private fun TestScope.vm(world: SettingsFixtures.World): SettingsViewModel {
        val d = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(d + Job())
        backgroundScope.coroutineContext[Job]!!.invokeOnCompletion { scope.cancel() }
        return SettingsViewModel(world.deps, scope, today = { world.today }, io = d)
    }

    // ---------------------------------------------------------------- every setting writes its field

    @Test
    fun everySettingWritesItsOwnFieldAndNothingElse() = runTest {
        val w = SettingsFixtures.world()
        val vm = vm(w)
        var expected = w.settings.settings.value

        fun check(what: String, action: () -> Unit, change: (Settings) -> Settings) {
            action()
            expected = change(expected)
            assertEquals(what, expected, w.settings.settings.value)
        }
        check("hold to talk", { vm.setHoldToTalk(false) }) { it.copy(holdToTalk = false) }
        check("feedback", { vm.setFeedback(Feedback.BOTH) }) { it.copy(feedback = Feedback.BOTH) }
        check("typing speed", { vm.setTypingWpm(55) }) { it.copy(typingWpm = 55) }
        check("language off", { vm.setLanguage("en", false) }) { it.copy(languages = listOf("ru")) }
        check("language on (ru first)", { vm.setLanguage("en", true) }) { it.copy(languages = listOf("ru", "en")) }
        check("auto detect", { vm.setAutoDetect(false) }) { it.copy(autoDetectLanguage = false) }
        check("visibility", { vm.setBubbleVisibility(BubbleVisibility.ALWAYS) }) { it.copy(bubbleVisibility = BubbleVisibility.ALWAYS) }
        check("tuck in", { vm.setTuckIn(false) }) { it.copy(tuckIn = false) }
        check("size", { vm.setBubbleSize(BubbleSize.LARGE) }) { it.copy(bubbleSize = BubbleSize.LARGE) }
        check("hidden in add", { vm.setHiddenIn(CHROME, true) }) { it.copy(hiddenInPackages = setOf(CHROME)) }
        check("hidden in remove", { vm.setHiddenIn(CHROME, false) }) { it.copy(hiddenInPackages = emptySet()) }
        check("use screen", { vm.setUseScreen(false) }) { it.copy(useScreen = false) }
        check("keep recordings", { vm.setKeepRecordings(false) }) { it.copy(keepRecordings = false) }
        check("tone", { vm.setTone(TELEGRAM, Tone.FORMAL) }) { it.copy(tonePerApp = mapOf(TELEGRAM to Tone.FORMAL)) }
        check("default tone", { vm.setDefaultTone(Tone.FORMAL) }) { it.copy(defaultTone = Tone.FORMAL) }
        check("default tone back to neutral", { vm.setDefaultTone(Tone.NEUTRAL) }) { it.copy(defaultTone = null) }
        check("debug fakes", { vm.setDebugFakeEngines(true) }) { it.copy(debugFakeEngines = true) }
        check("debug timeout", { vm.setDebugTimeoutScale(4f) }) { it.copy(debugTimeoutScale = 4f) }
        check("debug memory", { vm.setDebugIgnoreMemoryGate(true) }) { it.copy(debugIgnoreMemoryGate = true) }
        check("keep history longer", { vm.chooseRetention(7) }) { it }
    }

    @Test
    fun theDefaultsAreTheDesignDefaults() = runTest {
        val s = Settings()
        assertEquals(listOf("ru", "en"), s.languages)
        assertTrue(s.autoDetectLanguage && s.holdToTalk && s.tuckIn && s.useScreen && s.keepRecordings)
        assertEquals(Feedback.VIBRATION, s.feedback)
        assertEquals(40, s.typingWpm)
        assertEquals(BubbleVisibility.WHEN_TYPING, s.bubbleVisibility)
        assertEquals(BubbleSize.STANDARD, s.bubbleSize)
        assertTrue(s.hiddenInPackages.isEmpty())
        assertNull(s.promptOverride)
        assertEquals(7, s.retentionDays)
        val ui = vm(SettingsFixtures.world()).ui.value
        assertEquals(PromptEditState.DEFAULT, ui.prompt!!.state)
        assertEquals(Tone.NEUTRAL, ui.defaultTone)
        assertFalse(ui.defaultTonePreview)
    }

    @Test
    fun theLastLanguageCannotBeRemoved() = runTest {
        val w = SettingsFixtures.world(Settings(languages = listOf("en")))
        vm(w).setLanguage("en", false)
        assertEquals(listOf("en"), w.settings.settings.value.languages)
    }

    @Test
    fun typingSpeedOutsideTheCustomRangeIsIgnored() = runTest {
        val w = SettingsFixtures.world()
        val vm = vm(w)
        vm.setTypingWpm(SettingsViewModel.MAX_WPM + 1)
        vm.setTypingWpm(SettingsViewModel.MIN_WPM - 1)
        assertEquals(40, w.settings.settings.value.typingWpm)
        vm.setTypingWpm(SettingsViewModel.MAX_WPM)
        assertEquals(SettingsViewModel.MAX_WPM, w.settings.settings.value.typingWpm)
    }

    @Test
    fun resetPositionDropsTheStoredPositionsAndSaysSo() = runTest {
        val w = SettingsFixtures.world(Settings(bubblePositions = mapOf("cover" to BubblePosition(false, 0.3f), "inner" to BubblePosition(true, 0.6f))))
        val vm = vm(w)
        vm.resetPosition()
        assertTrue(w.settings.settings.value.bubblePositions.isEmpty())
        assertEquals(SettingsEvent.POSITION_RESET, vm.events.value)
        vm.eventShown()
        assertNull(vm.events.value)
    }

    // ---------------------------------------------------------------- retention

    @Test
    fun shorteningAsksWithTheRepositoryCountThenStoresAndRunsRetention() = runTest {
        val w = SettingsFixtures.world()
        val vm = vm(w)
        vm.chooseRetention(3)
        assertEquals(RetentionConfirm(days = 3, count = 12), vm.retentionConfirm.value)
        // Nothing happens before the dialog is confirmed.
        assertEquals(7, w.settings.settings.value.retentionDays)
        assertTrue(w.retentionCalls.isEmpty())
        assertEquals(19, w.history.snapshot().size)

        vm.confirmRetention()
        assertNull(vm.retentionConfirm.value)
        assertEquals(3, w.settings.settings.value.retentionDays)
        assertEquals(listOf(3), w.retentionCalls)
        val left = w.history.snapshot()
        assertEquals(7, left.size)
        assertTrue(left.all { !it.localDay.isBefore(TODAY.minusDays(2)) })
    }

    @Test
    fun cancellingTheConfirmKeepsThePeriodAndTheEntries() = runTest {
        val w = SettingsFixtures.world()
        val vm = vm(w)
        vm.chooseRetention(1)
        assertEquals("1 day keeps today only", RetentionConfirm(1, 17), vm.retentionConfirm.value)
        vm.cancelRetention()
        assertNull(vm.retentionConfirm.value)
        assertEquals(7, w.settings.settings.value.retentionDays)
        assertTrue(w.retentionCalls.isEmpty())
        assertEquals(19, w.history.snapshot().size)
    }

    @Test
    fun dontKeepHistoryCountsTodayStopsSavingAndDeletesTodaysEntries() = runTest {
        val w = SettingsFixtures.world()
        val vm = vm(w)
        vm.chooseRetention(0)
        // "Don't keep history" counts everything, today's two included.
        assertEquals(RetentionConfirm(0, 19), vm.retentionConfirm.value)
        vm.confirmRetention()
        assertEquals(0, w.settings.settings.value.retentionDays)
        assertFalse("0 = nothing is written any more", RetentionPolicy.keepsHistory(w.settings.settings.value.retentionDays))
        assertEquals(listOf(0), w.retentionCalls)
        assertTrue("today's entries are deleted too", w.history.snapshot().isEmpty())
    }

    @Test
    fun lengtheningIsWrittenAtOnceWithoutDeleting() = runTest {
        val w = SettingsFixtures.world(Settings(retentionDays = 0))
        val vm = vm(w)
        vm.chooseRetention(3)
        assertNull(vm.retentionConfirm.value)
        assertEquals(3, w.settings.settings.value.retentionDays)
        vm.chooseRetention(7)
        assertEquals(7, w.settings.settings.value.retentionDays)
        assertTrue(w.retentionCalls.isEmpty())
        assertEquals(19, w.history.snapshot().size)
    }

    @Test
    fun shorteningWithNothingOlderAppliesWithoutAsking() = runTest {
        val w = SettingsFixtures.world(history = app.murmur.testing.InMemoryHistoryRepository().apply { insert(SettingsFixtures.entry(TODAY, 1)) })
        val vm = vm(w)
        vm.chooseRetention(1)
        assertNull(vm.retentionConfirm.value)
        assertEquals(1, w.settings.settings.value.retentionDays)
        assertEquals(listOf(1), w.retentionCalls)
        assertEquals(1, w.history.snapshot().size)
    }

    @Test
    fun deleteAllAsksWithTheCountAndEmptiesHistory() = runTest {
        val w = SettingsFixtures.world()
        val vm = vm(w)
        vm.askDeleteAll()
        assertEquals(19, vm.deleteAllCount.value)
        vm.cancelDeleteAll()
        assertEquals(19, w.history.snapshot().size)
        vm.askDeleteAll()
        vm.confirmDeleteAll()
        assertNull(vm.deleteAllCount.value)
        assertTrue(w.history.entries().first().isEmpty())
        assertEquals(SettingsEvent.HISTORY_DELETED, vm.events.value)
        vm.eventShown()
        vm.askDeleteAll()
        assertNull("nothing to delete: no dialog", vm.deleteAllCount.value)
        assertEquals(SettingsEvent.NOTHING_TO_DELETE, vm.events.value)
    }

    // ---------------------------------------------------------------- cleanup prompt row

    @Test
    fun promptRowFollowsTheEditStateOfTheActiveProfile() = runTest {
        val w = SettingsFixtures.world()
        val vm = vm(w)
        fun state() = vm.ui.value.prompt!!
        assertEquals("default.v4", state().profile.name)
        assertEquals(PromptEditState.DEFAULT, state().state)
        w.settings.edit { it.copy(promptOverride = PromptOverride("My rules", "default.v4")) }
        assertEquals(PromptEditState.EDITED, state().state)
        assertEquals("the preview shows the edit", "My rules", state().previewText)
        w.settings.edit { it.copy(promptOverride = PromptOverride("My rules", "default.v3")) }
        assertEquals(PromptEditState.MADE_FOR_OLDER_PROMPT, state().state)
        assertTrue(state().previewText.startsWith("You are the dictation editor"))
        // A fine-tune as the cleanup model: its own prompt, not editable.
        w.settings.edit { it.copy(cleanup = CleanupChoice.Model(ModelId("murmur-cleanup-qwen3.5-2b")), promptOverride = null) }
        assertEquals("finetuned.v1", state().profile.name)
        assertFalse(state().editable)
        assertEquals(PromptEditState.NOT_EDITABLE, state().state)
        // Cleanup None: the prompt of the catalog default (what applies when a model is used again).
        w.settings.edit { it.copy(cleanup = CleanupChoice.None) }
        assertEquals("default.v4", state().profile.name)
    }

    @Test
    fun activeProfileComesFromTheChosenModelElseTheCatalogDefault() = runTest {
        val catalog = app.murmur.testing.FakeCatalogRepository().catalog.value
        assertEquals("default.v4", SettingsViewModel.activeProfileName(Settings(), catalog))
        assertEquals("finetuned.v1", SettingsViewModel.activeProfileName(Settings(cleanup = CleanupChoice.Model(ModelId("murmur-cleanup-lfm3-1.7b"))), catalog))
        assertEquals("default.v4", SettingsViewModel.activeProfileName(Settings(cleanup = CleanupChoice.Model(ModelId("qwen3.5-0.8b"))), catalog))
        assertEquals("unknown model -> catalog default", "default.v4", SettingsViewModel.activeProfileName(Settings(cleanup = CleanupChoice.Model(ModelId("gone"))), catalog))
        assertNull(SettingsViewModel.activeProfileName(Settings(), catalog.copy(defaults = catalog.defaults.copy(llm = null))))
    }

    @Test
    fun openingThePromptEditorSelectsCleanupAndClosingDropsTheEditor() = runTest {
        val vm = vm(SettingsFixtures.world())
        assertTrue(vm.openPrompt())
        assertEquals(SubScreen.PROMPT, vm.sub.value)
        assertEquals(SettingsCategory.CLEANUP, vm.category.value)
        assertEquals("default.v4", vm.promptEditor.value!!.profile.name)
        vm.closeSub()
        assertNull(vm.sub.value)
        assertNull(vm.promptEditor.value)
        vm.openTones()
        assertEquals(SubScreen.TONE, vm.sub.value)
        vm.selectCategory(SettingsCategory.PRIVACY)
        assertNull("another category closes the sub-screen", vm.sub.value)
    }

    // ---------------------------------------------------------------- tone per app

    @Test
    fun toneListShowsTheCategoryAppsWithTheirDefaults() = runTest {
        val vm = vm(SettingsFixtures.world())
        val rows = vm.ui.value.tones
        assertEquals(listOf(TELEGRAM, MESSAGES, SLACK, GMAIL, DOCS, TERMUX), rows.map { it.pkg })
        assertEquals(listOf(Tone.CASUAL, Tone.CASUAL, Tone.NEUTRAL, Tone.FORMAL, Tone.NEUTRAL, Tone.VERBATIM), rows.map { it.tone })
        assertTrue(rows.none { it.explicit || it.preview || it.removable })
        assertEquals("Google Docs", rows[4].label)
    }

    @Test
    fun pickingATonePreviewsItUntilPromptsHaveItsText() = runTest {
        val w = SettingsFixtures.world()
        val vm = vm(w)
        vm.setTone(TELEGRAM, Tone.FORMAL)
        val t = vm.ui.value.tones.first { it.pkg == TELEGRAM }
        assertEquals(Tone.FORMAL, t.tone)
        assertTrue(t.explicit)
        assertTrue("tones.v1.json has no named texts yet (ADR-020)", t.preview)
        // Back to the category's own tone: the stored choice is removed.
        vm.setTone(TELEGRAM, Tone.CASUAL)
        assertTrue(w.settings.settings.value.tonePerApp.isEmpty())
        assertFalse(vm.ui.value.tones.first { it.pkg == TELEGRAM }.preview)
        vm.setDefaultTone(Tone.CASUAL)
        assertTrue(vm.ui.value.defaultTonePreview)
    }

    @Test
    fun addedAppsAreListedAfterTheCategoryAppsAndCanBeRemoved() = runTest {
        val w = SettingsFixtures.world()
        val vm = vm(w)
        assertTrue(CHROME in vm.toneCandidates(ownPackage = null))
        assertFalse(TELEGRAM in vm.toneCandidates(ownPackage = null))
        vm.addToneApp(CHROME)
        assertEquals(mapOf(CHROME to Tone.NEUTRAL), w.settings.settings.value.tonePerApp)
        val row = vm.ui.value.tones.last()
        assertEquals(CHROME, row.pkg)
        assertTrue(row.removable && row.explicit && !row.preview)
        vm.setTone(CHROME, Tone.VERBATIM)
        assertEquals("an added app keeps its stored tone", Tone.VERBATIM, w.settings.settings.value.tonePerApp[CHROME])
        vm.removeToneApp(CHROME)
        assertEquals(6, vm.ui.value.tones.size)
        assertFalse(CHROME in vm.hiddenInCandidates(ownPackage = CHROME))
    }

    // ---------------------------------------------------------------- permissions

    @Test
    fun permissionRowsFollowTheMonitorAndOpenTheirScreens() = runTest {
        val w = SettingsFixtures.world()
        val vm = vm(w)
        fun rows() = PermissionRows.of(vm.ui.value.permissions).associate { it.kind to (it.status to it.opens) }
        assertEquals(PermissionStatus.ALLOWED to SystemScreen.APP_DETAILS, rows()[PermissionRow.Kind.MIC])
        assertEquals(PermissionStatus.ON to SystemScreen.ACCESSIBILITY, rows()[PermissionRow.Kind.ACCESSIBILITY])
        assertEquals(PermissionStatus.ALLOWED to SystemScreen.NOTIFICATIONS, rows()[PermissionRow.Kind.NOTIFICATIONS])
        assertEquals(PermissionStatus.RESTRICTED to SystemScreen.APP_DETAILS, rows()[PermissionRow.Kind.BATTERY])
        w.permissions.set { it.copy(micGranted = false, notificationsGranted = false, a11yConnected = false, restrictedLikely = true) }
        assertEquals(PermissionStatus.OFF, rows()[PermissionRow.Kind.MIC]!!.first)
        assertEquals(PermissionStatus.OFF, rows()[PermissionRow.Kind.NOTIFICATIONS]!!.first)
        assertEquals("restricted setting: App info", PermissionStatus.RESTRICTED to SystemScreen.APP_DETAILS, rows()[PermissionRow.Kind.ACCESSIBILITY])
        w.permissions.set { it.copy(restrictedLikely = false) }
        assertEquals(PermissionStatus.OFF to SystemScreen.ACCESSIBILITY, rows()[PermissionRow.Kind.ACCESSIBILITY])
        vm.refreshPermissions()
        assertEquals(1, w.permissions.refreshes)
        w.connected.value = false
        assertFalse(vm.ui.value.serviceConnected)
    }
}
