package app.murmur.shell

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.murmur.MainActivity
import app.murmur.R
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import app.murmur.MurmurApp
import app.murmur.core.model.EntryId
import app.murmur.shell.Device.hasTag
import app.murmur.shell.Device.waitForTag
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Shell on the emulator (WP-15 acceptance), run with `-e murmurFake seeded` (debug fake container: onboarding done,
 * the DESIGN sample day in History, entry 8 = "Telegram · Дима Соколов"). On murmur_fold7_cover (compact width):
 * bottom bar, no rail, detail pushed. On murmur_fold7_inner (medium width): rail, no bar, list + detail side by side.
 */
@RunWith(AndroidJUnit4::class)
class AppShellTest {
    @get:Rule
    val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null
    private val entry = 8L

    /** A bar / rail destination by its label (the design-system bar takes no per-item modifiers). */
    private fun tab(label: Int): SemanticsMatcher =
        hasText(Device.context.getString(label)) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)

    private fun launch(intent: Intent? = null) {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        scenario = if (intent == null) ActivityScenario.launch(MainActivity::class.java)
        else ActivityScenario.launch(intent.setClass(ctx, MainActivity::class.java))
        compose.waitForTag("shell")
    }

    @After
    fun tearDown() {
        scenario?.close()
        Device.finishAll()
    }

    private fun requireFakeContainer() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MurmurApp
        assumeTrue("run with -e murmurFake seeded", app.container?.fakeContainer == true)
    }

    @Test
    fun opensOnHistoryAndSwitchesTabs() {
        launch()
        compose.onNodeWithTag("screen-history").assertIsDisplayed()
        compose.onNode(tab(R.string.tab_history)).assertIsSelected()
        compose.onNode(tab(R.string.tab_models)).performClick()
        compose.waitForTag("screen-models")
        compose.onNodeWithTag("screen-models").assertIsDisplayed()
        Device.shot("shell-models")
        compose.onNode(tab(R.string.tab_dashboard)).performClick()
        compose.waitForTag("screen-dashboard")
        Device.shot("shell-dashboard")
        compose.onNode(tab(R.string.tab_settings)).performClick()
        compose.waitForTag("screen-settings")
        Device.shot("shell-settings")
        compose.onNode(tab(R.string.tab_history)).performClick()
        compose.waitForTag("screen-history")
        Device.shot("shell-history")
    }

    /** Integration pass 6: a Settings sub-screen on the cover display covers the bottom bar (WP-19 onSubScreenChange). */
    @Test
    fun aSettingsSubScreenCoversTheBarOnCover() {
        requireFakeContainer()
        assumeTrue("cover display (murmur_fold7_cover)", !Device.wide())
        launch()
        compose.onNode(tab(R.string.tab_settings)).performClick()
        compose.waitForTag("screen-settings")
        compose.onNodeWithTag("settings-row-prompt").performScrollTo().performClick()
        compose.waitForTag("settings-prompt-editor")
        compose.waitForIdle()
        assertFalse("the prompt editor covers the bottom bar", compose.hasTag("nav-bar"))
        Device.ui.pressBack()
        compose.waitForTag("nav-bar")
        compose.onNode(tab(R.string.tab_settings)).assertIsSelected()
    }

    /** Integration pass 6 (ADR-087): "Open Models" for a cleanup problem lands on the Cleanup segment. */
    @Test
    fun anOpenModelsLinkForCleanupShowsTheCleanupSegment() {
        requireFakeContainer()
        launch(DeepLink.intent(Device.context, DeepLink.Models(app.murmur.core.model.ModelKind.LLM)))
        compose.waitForTag("screen-models")
        compose.waitForTag("models-none")
        compose.onNode(tab(R.string.tab_models)).assertIsSelected()
    }

    @Test
    fun barOnCoverRailOnInner() {
        launch()
        if (Device.wide()) {
            compose.onNodeWithTag("nav-rail").assertIsDisplayed()
            assertFalse("no bottom bar on the inner display", compose.hasTag("nav-bar"))
        } else {
            compose.onNodeWithTag("nav-bar").assertIsDisplayed()
            assertFalse("no rail on the cover display", compose.hasTag("nav-rail"))
        }
    }

    @Test
    fun deepLinkOpensTheEntryAndListAndDetailSitSideBySideOnInner() {
        requireFakeContainer()
        launch(DeepLink.intent(Device.context, DeepLink.Entry(EntryId(entry))))
        compose.waitForTag("detail-entry-$entry")
        compose.onNodeWithTag("detail-entry-$entry").assertIsDisplayed()
        compose.onNodeWithTag("history-detail").assertIsDisplayed()
        if (Device.wide()) {
            compose.onNodeWithTag("screen-history").assertIsDisplayed()
            val list = compose.onNodeWithTag("screen-history").fetchSemanticsNode().boundsInRoot
            val detail = compose.onNodeWithTag("detail-entry-$entry").fetchSemanticsNode().boundsInRoot
            assertTrue("list left of the detail: $list / $detail", list.right <= detail.left + 1f)
            val density = Device.context.resources.displayMetrics.density
            assertEquals("History list pane is 300 dp (DESIGN 2)", 300f, list.width / density, 1.5f)
            Device.shot("shell-history-detail")
        } else {
            assertFalse("cover: the detail is a pushed screen", compose.hasTag("screen-history"))
            assertFalse("cover: the pushed detail covers the bottom bar (hist-detail)", compose.hasTag("nav-bar"))
            Device.shot("shell-history-detail")
            // Back closes the detail and brings the bar back.
            Device.ui.pressBack()
            compose.waitForTag("screen-history")
            compose.waitForTag("nav-bar")
        }
    }

    @Test
    fun aDeepLinkWhileRunningArrivesThroughOnNewIntent() {
        requireFakeContainer()
        // Started like the launcher does (own task, MainActivity at its root), so that the bubble's
        // NEW_TASK | SINGLE_TOP | CLEAR_TOP intent reaches the running instance through onNewIntent.
        Device.context.startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClass(Device.context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        compose.waitForTag("shell")
        val first = Device.resumedMain()
        compose.onNode(tab(R.string.tab_settings)).performClick()
        compose.waitForTag("screen-settings")
        Device.context.startActivity(DeepLink.intent(Device.context, DeepLink.Entry(EntryId(entry))))
        compose.waitForTag("detail-entry-$entry")
        Device.context.startActivity(DeepLink.intent(Device.context, DeepLink.Models(null)))
        compose.waitForTag("screen-models")
        compose.onNode(tab(R.string.tab_models)).assertIsSelected()
        assertTrue("the same activity instance took both links", first === Device.resumedMain())
    }

    /** Fold/unfold as a size change (ADR-058): the selected entry survives the activity being re-created. */
    @Test
    fun selectionSurvivesASwitchToTheCoverSize() {
        requireFakeContainer()
        assumeTrue("starts on the inner display (murmur_fold7_inner)", Device.wide())
        launch(DeepLink.intent(Device.context, DeepLink.Entry(EntryId(entry))))
        compose.waitForTag("detail-entry-$entry")
        try {
            Device.sh("wm size 1080x2520")
            Device.waitFor(what = "compact width") { !Device.wide() }
            compose.waitForTag("detail-entry-$entry")
            compose.onNodeWithTag("detail-entry-$entry").assertIsDisplayed()
            // Compact width: the selected entry is now the pushed detail (no rail, the bar under it is covered).
            assertFalse(compose.hasTag("nav-rail"))
            assertFalse(compose.hasTag("nav-bar"))
            Device.shot("shell-after-fold")
        } finally {
            Device.sh("wm size reset")
            Device.waitFor(what = "medium width again") { Device.wide() }
        }
        compose.waitForTag("nav-rail")
        compose.waitForTag("detail-entry-$entry")
    }
}
