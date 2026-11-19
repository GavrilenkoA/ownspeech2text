package app.murmur.feature.dashboard

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.murmur.core.store.DefaultStatsCalculator
import app.murmur.core.store.DictationMetrics
import app.murmur.core.store.MetricsRepository
import app.murmur.core.store.Settings
import app.murmur.testing.InMemorySettingsRepository
import app.murmur.testing.contrib.Wp12MetricsFixture
import app.murmur.ui.design.MurmurTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.time.LocalDate

/** The dashboard's controls on the emulator: range chip, typing speed sheet, Try, Retry, bubble-off, 200 % text. */
@RunWith(AndroidJUnit4::class)
class DashboardInteractionTest {
    @get:Rule
    val rule = createComposeRule()

    private val fixtureDeps
        get() = FrameData.deps(metrics = FrameData.store(Wp12MetricsFixture.week), calculator = DefaultStatsCalculator())

    private fun show(deps: DashboardDeps, onTry: () -> Unit = {}) {
        rule.setContent { MurmurTheme { DashboardScreen(deps, onTryDictation = onTry) } }
        rule.waitForIdle()
    }

    @Test
    fun rangeChipSwitchesBetweenWeekAndToday() {
        show(fixtureDeps)
        val chip = rule.onNodeWithTag(DashboardTags.RANGE)
        assertEquals("Range: Last 7 days", chip.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single())
        assertEquals("Show Today", chip.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
        rule.onNodeWithText("words, 7 days").performScrollTo().assertIsDisplayed()
        chip.performClick()
        rule.waitForIdle()
        assertEquals("Range: Today", chip.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single())
        rule.onNodeWithText("words, today").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Median of today, pauses excluded", substring = true).performScrollTo().assertIsDisplayed()
        chip.performClick()
        rule.waitForIdle()
        rule.onNodeWithText("words, 7 days").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun todayCardSetsTypingSpeedFromPreset() {
        val settings = InMemorySettingsRepository()
        show(FrameData.deps(metrics = FrameData.store(Wp12MetricsFixture.week), calculator = DefaultStatsCalculator(), settings = settings))
        rule.onNodeWithText("Typing this at 40 wpm", substring = true).assertIsDisplayed()
        rule.onNodeWithTag(DashboardTags.TODAY_CARD).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TypingSpeedTags.SHEET).assertIsDisplayed()
        rule.onNodeWithText("Your typing speed").assertIsDisplayed()
        // 40 is selected and marked as the default.
        val forty = rule.onNodeWithTag(TypingSpeedTags.option(40)).fetchSemanticsNode()
        assertEquals(true, forty.config.getOrNull(SemanticsProperties.Selected))
        rule.onNodeWithTag(TypingSpeedTags.option(50)).performClick()
        rule.waitForIdle()
        assertEquals(50, settings.settings.value.typingWpm)
        assertEquals(0, rule.onAllNodesWithText("Your typing speed").fetchSemanticsNodes().size)
        // 184 / 50 = 3.7 -> 4 min of typing, 1 min of speaking.
        rule.onNodeWithText("Typing this at 50 wpm would take about 4 min, so roughly 3 min saved.", substring = true).assertIsDisplayed()
    }

    @Test
    fun typingSpeedCustomValue() {
        val settings = InMemorySettingsRepository(Settings(typingWpm = 40))
        show(FrameData.deps(metrics = FrameData.store(Wp12MetricsFixture.week), calculator = DefaultStatsCalculator(), settings = settings))
        rule.onNodeWithTag(DashboardTags.TODAY_CARD).performClick()
        rule.onNodeWithTag(TypingSpeedTags.CUSTOM).performClick()
        rule.waitForIdle()
        // Empty field: Save is disabled; out of range too.
        rule.onNodeWithTag(TypingSpeedTags.SAVE).assertIsNotEnabled()
        rule.onNodeWithTag(TypingSpeedTags.CUSTOM_FIELD).performTextInput("5")
        rule.onNodeWithTag(TypingSpeedTags.SAVE).assertIsNotEnabled()
        rule.onNodeWithTag(TypingSpeedTags.CUSTOM_FIELD).performTextInput("5")
        rule.onNodeWithTag(TypingSpeedTags.SAVE).performClick()
        rule.waitForIdle()
        assertEquals(55, settings.settings.value.typingWpm)
        rule.onNodeWithText("Typing this at 55 wpm", substring = true).assertIsDisplayed()
        // Reopened, the sheet shows the custom value selected.
        rule.onNodeWithTag(DashboardTags.TODAY_CARD).performClick()
        rule.waitForIdle()
        assertEquals(true, rule.onNodeWithTag(TypingSpeedTags.CUSTOM).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected))
        rule.onNodeWithText("55 wpm").assertIsDisplayed()
    }

    @Test
    fun emptyStateTryButtonStartsAPractice() {
        var tries = 0
        show(FrameData.deps(metrics = FrameData.store(emptyList()), calculator = DefaultStatsCalculator())) { tries++ }
        rule.onNodeWithText("Your numbers appear after the first dictation", substring = true).assertIsDisplayed()
        rule.onNodeWithText("What you'll see here").assertIsDisplayed()
        rule.onNodeWithTag(DashboardTags.TRY).performClick()
        assertEquals(1, tries)
    }

    @Test
    fun errorBannerRetries() {
        val store = FrameData.store(Wp12MetricsFixture.week)
        var fail = true
        val flaky = object : MetricsRepository by store {
            override fun since(day: LocalDate): Flow<List<DictationMetrics>> =
                if (fail) flow { throw IOException("unreadable") } else store.since(day)
        }
        show(FrameData.deps(metrics = flaky, calculator = DefaultStatsCalculator()))
        rule.onNodeWithText("Couldn't read your stats.", substring = true).assertIsDisplayed()
        rule.onNodeWithText("History is not affected.", substring = true).assertIsDisplayed()
        fail = false
        rule.onNodeWithText("Retry").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(DashboardTags.CHART).assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("Couldn't read your stats.", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun bubbleOffBannerFollowsTheService() {
        val connected = MutableStateFlow(false)
        val deps = FrameData.deps(metrics = FrameData.store(Wp12MetricsFixture.week), calculator = DefaultStatsCalculator())
        show(DashboardDeps(deps.metrics, deps.settings, deps.stats, deps.clock, connected, deps.apps))
        rule.onNodeWithTag(DashboardTags.BUBBLE_OFF).assertIsDisplayed()
        rule.onNodeWithText("The bubble is off.", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Open Accessibility settings").assertIsDisplayed()
        connected.value = true
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, DashboardTags.BUBBLE_OFF)).fetchSemanticsNodes().size)
    }

    /** DESIGN 8: at 200 % nothing is cut, and metric values move under their names when they no longer fit beside. */
    @Test
    fun largeTextNothingCut() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val density = context.resources.displayMetrics.density
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                MurmurTheme { DashboardScreen(FrameData.deps(), onTryDictation = {}) }
            }
        }
        rule.waitForIdle()
        val nodes = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
            .fetchSemanticsNodes()
        val bad = mutableListOf<String>()
        rule.runOnIdle {
            for (n in nodes) {
                val results = mutableListOf<TextLayoutResult>()
                n.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
                val r = results.firstOrNull() ?: continue
                val overflow = r.didOverflowHeight || r.multiParagraph.didExceedMaxLines ||
                    (0 until r.lineCount).any { i -> r.isLineEllipsized(i) || r.getLineRight(i) - r.getLineLeft(i) > r.size.width + 1f }
                if (overflow) bad += n.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }.orEmpty()
            }
        }
        assertTrue("texts checked", nodes.size > 30)
        assertTrue("text overflow at 200 %:\n" + bad.joinToString("\n"), bad.isEmpty())
        // "Changed by cleanup" and "9 % of words" do not fit on one line at 200 %: the value moves under the name.
        rule.onNodeWithText("% of words", useUnmergedTree = true).performScrollTo()
        rule.waitForIdle()
        val name = rule.onNodeWithText("Changed by cleanup", useUnmergedTree = true).fetchSemanticsNode()
        val value = rule.onNodeWithText("% of words", useUnmergedTree = true).fetchSemanticsNode()
        val nameBottom = name.positionInRoot.y + name.size.height
        assertTrue("value below the name (${value.positionInRoot} vs bottom $nameBottom)", value.positionInRoot.y >= nameBottom)
    }
}
