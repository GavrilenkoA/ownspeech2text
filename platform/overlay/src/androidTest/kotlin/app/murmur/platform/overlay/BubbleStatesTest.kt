package app.murmur.platform.overlay

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.murmur.core.dictation.BubbleAction
import app.murmur.core.dictation.BubbleError
import app.murmur.core.dictation.DictationBubble
import app.murmur.core.model.ProductName
import app.murmur.core.store.BubbleSize
import app.murmur.platform.overlay.ui.BubbleActions
import app.murmur.platform.overlay.ui.BubbleUi
import app.murmur.platform.overlay.ui.BubbleWindowContent
import app.murmur.platform.overlay.ui.CancelButton
import app.murmur.platform.overlay.ui.DragLayer
import app.murmur.platform.overlay.ui.DragScene
import app.murmur.platform.overlay.ui.HiddenSnackbar
import app.murmur.platform.overlay.ui.TAG_ACTION
import app.murmur.platform.overlay.ui.TAG_BUBBLE
import app.murmur.platform.overlay.ui.TAG_CANCEL
import app.murmur.platform.overlay.ui.TAG_UNDO
import app.murmur.platform.overlay.ui.TAG_VISUAL
import app.murmur.ui.design.TokenColors
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Every state of DESIGN 5.3 rendered at the cover (411 dp) and inner (750 dp) widths, docked right as in the
 * b-<state> frames: screenshots for the by-eye comparison, the TalkBack texts of DESIGN 5.3 and >= 48 dp touch
 * targets. Run on murmur_fold7_inner (750 dp wide, so both widths fit).
 */
@RunWith(AndroidJUnit4::class)
class BubbleStatesTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private fun s(id: Int, vararg args: Any): String = ctx.getString(id, *args)

    private val start = 1_000_000L
    /** The timer shows 0:07, as in the frames. */
    private val clock = { start + 7_400L }
    private val levels = MutableStateFlow(floatArrayOf(0.17f, 0.67f, 1f, 0.5f, 0.8f, 0.3f, 0.1f))

    private class Scene(
        val name: String,
        val visual: BubbleVisual?,
        val dark: Boolean = false,
        val talkBack: Boolean = false,
        val ru: Boolean = false,
        val special: String? = null,
    )

    private var scene by mutableStateOf<Scene?>(null)
    private var widthDp by mutableStateOf(411)
    /** Full-frame drag layer (width, height in dp) or the snackbar (width in dp), when not null. */
    private var drag by mutableStateOf<Pair<Int, Int>?>(null)
    private var snack by mutableStateOf<Int?>(null)

    private val noop = object : BubbleActions {
        override fun tap(region: TouchRegion) = Unit
        override fun hide() = Unit
    }

    private fun listening(hold: Boolean = false, warn: Boolean = false) =
        BubbleVisual.Active(DictationBubble.Listening(startedAtMs = start, hold = hold, warn = warn))

    private val scenes = listOf(
        Scene("ready", BubbleVisual.Ready),
        Scene("tab", BubbleVisual.Rest),
        Scene("listen", listening()),
        Scene("listen-warn", listening(warn = true)),
        Scene("hold", listening(hold = true)),
        Scene("trans", BubbleVisual.Active(DictationBubble.Transcribing)),
        Scene("polish", BubbleVisual.Active(DictationBubble.Polishing)),
        Scene("ok", BubbleVisual.Active(DictationBubble.Inserted(noCleanup = false))),
        Scene("ok-nocleanup", BubbleVisual.Active(DictationBubble.Inserted(noCleanup = true))),
        Scene("copied", BubbleVisual.Active(DictationBubble.Copied(entry = null))),
        Scene("err", BubbleVisual.Active(DictationBubble.Error(BubbleError.NO_SPEECH, BubbleAction.RETRY))),
        Scene("err-nomodel", BubbleVisual.Active(DictationBubble.Error(BubbleError.NO_MODEL, BubbleAction.OPEN_MODELS))),
        Scene("err-micbusy", BubbleVisual.Active(DictationBubble.Error(BubbleError.MIC_BUSY, null))),
        Scene("listen-dark", listening(), dark = true),
        Scene("ready-talkback", BubbleVisual.Ready, talkBack = true),
    )

    @Before
    fun manualClock() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            val sc = scene
            val dr = drag
            val sn = snack
            when {
                sc != null -> key(sc.name, widthDp) { Localized(sc.ru) { Stage(sc, widthDp) } }
                dr != null -> key("drag", dr) { DragFrame(dr.first, dr.second) }
                sn != null -> key("snack", sn) { SnackFrame(sn) }
            }
        }
    }

    @Composable
    private fun Localized(ru: Boolean, content: @Composable () -> Unit) {
        if (!ru) { content(); return }
        val base = LocalContext.current
        val conf = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag("ru")) }
        val ruCtx = base.createConfigurationContext(conf)
        CompositionLocalProvider(LocalContext provides ruCtx, LocalConfiguration provides conf) { content() }
    }

    /** The bubble window as OverlayHost lays it out: circle top 70 dp, window band 6 dp, 8 dp from the right edge. */
    @Composable
    private fun Stage(sc: Scene, w: Int) {
        val colors = if (sc.dark) TokenColors.dark else TokenColors.light
        val v = sc.visual
        val m = BubbleMetrics.of(BubbleSize.STANDARD)
        Box(Modifier.testTag(STAGE).size(w.dp, 150.dp).background(colors.bg)) {
            if (v != null) {
                val ui = BubbleUi(v, shown = true, rightEdge = true, metrics = m, reducedMotion = false, talkBack = sc.talkBack)
                val visualTop = CIRCLE_TOP + (m.diameter - m.visualHeight(v)) / 2f
                Box(Modifier.align(Alignment.TopEnd).padding(
                    end = (m.edgeMargin(v) - m.windowPadEdge(v)).dp, top = (visualTop - m.windowPadTop(v)).dp,
                )) {
                    BubbleWindowContent(ui, sc.dark, levels.collectAsState(), clock, noop)
                }
                if (v is BubbleVisual.Active && (v.state as? DictationBubble.Listening)?.hold == false) {
                    val pad = (BubbleMetrics.CANCEL_HIT - BubbleMetrics.CANCEL_DIAMETER) / 2f
                    Box(Modifier.align(Alignment.TopEnd).padding(
                        end = (BubblePlacement.EDGE_MARGIN_DP + m.cancelInset - pad).dp, top = (CIRCLE_TOP - m.cancelOffsetY - pad).dp,
                    )) { CancelButton(sc.dark, shown = true) {} }
                }
            }
        }
    }

    private fun show(sc: Scene, w: Int) {
        scene = sc; widthDp = w
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
    }

    private fun shot(name: String) {
        Shots.save(name, rule.onNodeWithTag(STAGE).captureToImage().asAndroidBitmap())
    }

    @Test
    fun everyStateAtCoverAndInnerWidth_textsAndTouchTargets() {
        for ((dev, w) in listOf("cover" to 411, "inner" to 750)) {
            for (sc in scenes) {
                show(sc, w)
                shot("b-${sc.name}--$dev")
                assertTexts(sc)
                assertTouchTargets(sc.name)
            }
        }
    }

    @Test
    fun russianCopyFitsTheMaxWidth() {
        val ru = listOf(
            Scene("copied-ru", BubbleVisual.Active(DictationBubble.Copied(null)), ru = true),
            Scene("hold-ru", listening(hold = true), ru = true),
            Scene("polish-ru", BubbleVisual.Active(DictationBubble.Polishing), ru = true),
            Scene("ok-nocleanup-ru", BubbleVisual.Active(DictationBubble.Inserted(true)), ru = true),
            Scene("err-nomodel-ru", BubbleVisual.Active(DictationBubble.Error(BubbleError.NO_MODEL, BubbleAction.OPEN_MODELS)), ru = true),
            Scene("err-load-ru", BubbleVisual.Active(DictationBubble.Error(BubbleError.LOAD_FAILED, BubbleAction.RETRY)), ru = true),
        )
        for (sc in ru) {
            show(sc, 411)
            shot("b-${sc.name}--cover")
            // DESIGN 7: the Russian strings fit the bubble's 280 dp max width at Standard size.
            val visual = rule.onNodeWithTag(TAG_VISUAL).fetchSemanticsNode()
            val widthDp = visual.size.width / visual.layoutInfo.density.density
            assertTrue("${sc.name}: ${widthDp}dp", widthDp <= BubbleMetrics.MAX_WIDTH + 1f)
        }
        // Russian TalkBack text of the bubble comes from values-ru.
        show(Scene("ready-ru", BubbleVisual.Ready, ru = true), 411)
        rule.onNodeWithTag(TAG_BUBBLE).assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription,
            listOf("Начать диктовку. Нажмите дважды, чтобы говорить, и ещё раз дважды, чтобы закончить")))
    }

    @Test
    fun dragAndHiddenFrames() {
        for ((dev, w) in listOf("cover" to 411, "inner" to 750)) {
            scene = null; snack = null; drag = w to 740
            rule.mainClock.advanceTimeBy(2_000)
            rule.waitForIdle()
            Shots.save("b-drag--$dev", rule.onNodeWithTag(DRAG).captureToImage().asAndroidBitmap())
            drag = null; snack = w
            rule.mainClock.advanceTimeBy(1_000)
            rule.waitForIdle()
            Shots.save("b-hidden--$dev", rule.onNodeWithTag(SNACK).captureToImage().asAndroidBitmap())
            rule.onNodeWithTag(TAG_UNDO).assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
            assertTouchTargets("hidden")
        }
    }

    /** b-drag: lifted circle mid-screen, ghost on the right edge, target shown. Pixels at this frame's density. */
    @Composable
    private fun DragFrame(w: Int, h: Int) {
        val density = LocalDensity.current.density
        fun px(dp: Float) = dp * density
        val d = (52f * density).roundToInt()
        val circleTop = px(h * 0.54f)
        val input = PlacementInput((w * density).roundToInt(), (h * density).roundToInt(), density, safeTop = px(24f).roundToInt(),
            safeBottom = (h * density).roundToInt(), fieldTop = null, imeTop = null, stored = null, bubbleDiameterPx = d)
        val targetCenterY = px(h - BubbleMetrics.HIDE_TARGET_BOTTOM - 28f - BubbleMetrics.HIDE_LABEL_GAP - BubbleMetrics.HIDE_TARGET / 2f)
        val scene = DragScene(
            circleX = px(w * 0.46f), circleY = circleTop, diameterPx = d, screenW = input.screenW,
            guideTop = BubblePlacement.topLimit(input), guideBottom = BubblePlacement.bottomLimit(input),
            ghostX = BubblePlacement.xFor(true, input.screenW, d, density), ghostY = circleTop.roundToInt(),
            overTarget = false, targetCenterX = input.screenW / 2f, targetCenterY = targetCenterY, targetShown = true,
            settleTo = null, reducedMotion = false,
        )
        Box(Modifier.testTag(DRAG).size(w.dp, h.dp).background(TokenColors.light.bg)) { DragLayer(scene, dark = false) {} }
    }

    /** b-hidden: the snackbar as OverlayHost places it (12 dp side inset; 380 dp wide on the inner display). */
    @Composable
    private fun SnackFrame(w: Int) {
        val wide = w >= 600
        Box(Modifier.testTag(SNACK).size(w.dp, 120.dp).background(TokenColors.light.bg)) {
            Box(Modifier.align(Alignment.Center).padding(horizontal = (12f - BubbleMetrics.SHADOW_PAD).dp)
                .then(if (wide) Modifier.align(Alignment.CenterEnd).width((380f + 2 * BubbleMetrics.SHADOW_PAD).dp) else Modifier)) {
                HiddenSnackbar(dark = false, text = s(R.string.bubble_hidden_snack, "15:32")) {}
            }
        }
    }

    private fun assertTexts(sc: Scene) {
        val v = sc.visual ?: return
        val copy = BubbleCopy.of(v) ?: return
        val node = rule.onNodeWithTag(TAG_BUBBLE)
        val desc = if (v == BubbleVisual.Rest) s(copy.description, ProductName.NAME) else s(copy.description)
        node.assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(desc)))
        copy.state?.let { node.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, s(it))) }
        when (copy.live) {
            Live.POLITE -> node.assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            Live.ASSERTIVE -> node.assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Assertive))
            Live.OFF -> node.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
        }
        copy.action?.let { rule.onNodeWithTag(TAG_ACTION).assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(s(it)))) }
        if (v is BubbleVisual.Active && (v.state as? DictationBubble.Listening)?.hold == false) {
            rule.onNodeWithTag(TAG_CANCEL).assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(s(R.string.bubble_cancel_cd))))
        }
        val actions = rule.onNodeWithTag(TAG_BUBBLE).fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty()
        if (sc.talkBack) assertEquals(listOf(s(R.string.bubble_hide_action)), actions.map { it.label })
        else assertTrue(actions.isEmpty())
    }

    /** DESIGN 4 / 8: every interactive element is at least 48 x 48 dp; the bubble itself is never shorter than 48 dp. */
    private fun assertTouchTargets(name: String) {
        rule.onAllNodes(androidx.compose.ui.test.hasTestTag(TAG_VISUAL)).fetchSemanticsNodes().forEach { v ->
            val h = v.size.height / v.layoutInfo.density.density
            assertTrue("$name: visual is $h dp tall", h >= 48f)
        }
        val nodes = rule.onAllNodesWithAction().fetchSemanticsNodes()
        assertTrue("$name: no clickable node", name.startsWith("trans") || name.startsWith("polish") || name.startsWith("hold") || nodes.isNotEmpty())
        for (n in nodes) {
            val d = n.layoutInfo.density.density
            val w = n.size.width / d
            val h = n.size.height / d
            assertTrue("$name: ${n.config.getOrNull(SemanticsProperties.ContentDescription)} is ${w.roundToInt()}x${h.roundToInt()} dp", w >= 47.5f && h >= 47.5f)
        }
    }

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithAction() =
        onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))

    private companion object {
        const val STAGE = "stage"
        const val DRAG = "dragframe"
        const val SNACK = "snackframe"
        const val CIRCLE_TOP = 70f
    }
}
