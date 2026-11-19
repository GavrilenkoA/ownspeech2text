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
import kotlin.math.abs
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
    private var size by mutableStateOf(BubbleSize.STANDARD)
    /** Full-frame drag layer (width, height in dp) or the snackbar (width in dp), when not null. */
    private var drag by mutableStateOf<Pair<Int, Int>?>(null)
    /** The pill the drag layer lifts (null = the idle bubble: the Ready circle and the armed hide target). */
    private var dragPill by mutableStateOf<BubbleVisual?>(null)
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
                sc != null -> key(sc.name, widthDp, size) { Localized(sc.ru) { Stage(sc, widthDp, size) } }
                dr != null -> key("drag", dr, dragPill) { DragFrame(dr.first, dr.second, dragPill) }
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
    private fun Stage(sc: Scene, w: Int, bubbleSize: BubbleSize) {
        val colors = if (sc.dark) TokenColors.dark else TokenColors.light
        val v = sc.visual
        val m = BubbleMetrics.of(bubbleSize)
        Box(Modifier.testTag(STAGE).size(w.dp, 150.dp).background(colors.bg)) {
            if (v != null) {
                val ui = BubbleUi(v, shown = true, rightEdge = true, metrics = m, reducedMotion = false, talkBack = sc.talkBack)
                val visualTop = CIRCLE_TOP + (m.diameter - m.visualHeight(v)) / 2f
                Box(Modifier.align(Alignment.TopEnd).padding(
                    end = (m.edgeMargin(v) - m.windowPadEdge(v)).dp, top = (visualTop - m.windowPadTop(v)).dp,
                ).testTag(WINDOW)) {
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

    private fun show(sc: Scene, w: Int, bubbleSize: BubbleSize = BubbleSize.STANDARD) {
        scene = sc; widthDp = w; size = bubbleSize
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
    }

    private fun shot(name: String) = Shots.save(name, capture(STAGE))

    /** captureToImage gives the window 2 s to redraw; a loaded emulator can miss that, so up to three attempts. */
    private fun capture(tag: String): android.graphics.Bitmap {
        repeat(2) {
            try { return rule.onNodeWithTag(tag).captureToImage().asAndroidBitmap() }
            catch (e: androidx.compose.ui.test.ComposeTimeoutException) { android.util.Log.w("WP03", "capture $tag timed out once") }
        }
        return rule.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
    }

    @Test
    fun everyStateAtCoverAndInnerWidth_textsAndTouchTargets() {
        for ((dev, w) in listOf("cover" to 411, "inner" to 750)) {
            for (sc in scenes) {
                show(sc, w)
                shot("b-${sc.name}--$dev")
                assertTexts(sc)
                assertTouchTargets(sc.name, BubbleMetrics.of(BubbleSize.STANDARD))
            }
        }
    }

    /**
     * DESIGN 5.6 Small / Large (44 / 52 / 60 dp): pills scale, the hit area and every TalkBack target stay >= 48 dp
     * (review F8: the Small Ready circle's node was 44 dp). Cover width, light theme.
     */
    @Test
    fun smallAndLargeSizes_textsAndTouchTargets() {
        for (bubbleSize in listOf(BubbleSize.SMALL, BubbleSize.LARGE)) {
            val m = BubbleMetrics.of(bubbleSize)
            for (sc in scenes.filter { !it.dark }) {
                show(sc, 411, bubbleSize)
                shot("b-${sc.name}--cover-${bubbleSize.name.lowercase()}")
                assertTexts(sc)
                assertTouchTargets("${sc.name}@$bubbleSize", m)
            }
            // The visual itself keeps its size: only the touch target grows (44 dp circle, 48 dp node at Small).
            show(Scene("ready", BubbleVisual.Ready), 411, bubbleSize)
            val visual = rule.onNodeWithTag(TAG_VISUAL).fetchSemanticsNode()
            val dp = visual.layoutInfo.density.density
            assertEquals(m.diameter, visual.size.width / dp, 0.6f)
            assertEquals(m.diameter, visual.size.height / dp, 0.6f)
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
            scene = null; snack = null; dragPill = null; drag = w to 740
            rule.mainClock.advanceTimeBy(2_000)
            rule.waitForIdle()
            val circleFrame = capture(DRAG)
            Shots.save("b-drag--$dev", circleFrame)
            val dpx = ctx.resources.displayMetrics.density
            // The hide target's area (bottom centre): its inverse label pill is drawn for the idle bubble.
            fun targetInk(frame: android.graphics.Bitmap) = countNear(frame, ((w / 2f - 120f) * dpx).roundToInt(),
                ((740f - BubbleMetrics.HIDE_TARGET_BOTTOM - 40f - BubbleMetrics.HIDE_LABEL_GAP - BubbleMetrics.HIDE_TARGET) * dpx).roundToInt(),
                ((w / 2f + 120f) * dpx).roundToInt(), ((740f - 40f) * dpx).roundToInt(), TokenColors.light.inverse)
            assertTrue("hide target drawn for the idle bubble", targetInk(circleFrame) > 200)
            // Review F3: a working pill is moved as itself (live bars, timer, stop and cancel circle), without the target.
            dragPill = listening()
            rule.mainClock.advanceTimeBy(2_000)
            rule.waitForIdle()
            val frame = capture(DRAG)
            Shots.save("b-drag-listen--$dev", frame)
            // Docked right at the lifted circle (w * 0.46, h * 0.54): the 164 x 52 pill grows to the left of its right end.
            val right = (w * 0.46f * dpx + 52f * dpx).roundToInt()
            val top = (740 * 0.54f * dpx).roundToInt()
            val pillPx = countNear(frame, right - (164f * dpx).roundToInt(), top, right, top + (52f * dpx).roundToInt(), TokenColors.light.accent)
            val area = 164f * 52f * dpx * dpx
            assertTrue("accent pixels of the lifted Listening pill: $pillPx of $area", pillPx > area * 0.5f)
            assertEquals("hide-target pixels while a pill moves", 0, targetInk(frame))
            dragPill = null
            drag = null; snack = w
            rule.mainClock.advanceTimeBy(1_000)
            rule.waitForIdle()
            Shots.save("b-hidden--$dev", capture(SNACK))
            rule.onNodeWithTag(TAG_UNDO).assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
            assertTouchTargets("hidden", BubbleMetrics.of(BubbleSize.STANDARD))
        }
    }

    /** Pixels of [b] in the rectangle within 12 per channel of [color]. */
    private fun countNear(b: android.graphics.Bitmap, l: Int, t: Int, r: Int, btm: Int, color: androidx.compose.ui.graphics.Color): Int {
        val argb = color.toArgbInt()
        var n = 0
        for (x in l.coerceAtLeast(0) until r.coerceAtMost(b.width)) for (y in t.coerceAtLeast(0) until btm.coerceAtMost(b.height)) {
            val p = b.getPixel(x, y)
            if (abs(((p shr 16) and 0xFF) - ((argb shr 16) and 0xFF)) <= 12 && abs(((p shr 8) and 0xFF) - ((argb shr 8) and 0xFF)) <= 12 &&
                abs((p and 0xFF) - (argb and 0xFF)) <= 12) n++
        }
        return n
    }

    private fun androidx.compose.ui.graphics.Color.toArgbInt(): Int = android.graphics.Color.argb(
        (alpha * 255).roundToInt(), (red * 255).roundToInt(), (green * 255).roundToInt(), (blue * 255).roundToInt())

    /**
     * b-drag: lifted circle mid-screen, ghost on the right edge, target shown. With [pill]: that pill lifted at the same
     * spot (docked right), no hide target. Pixels at this frame's density.
     */
    @Composable
    private fun DragFrame(w: Int, h: Int, pill: BubbleVisual?) {
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
            hideArmed = pill == null,
            pill = pill?.let { BubbleUi(it, shown = true, rightEdge = true, metrics = BubbleMetrics.of(BubbleSize.STANDARD), reducedMotion = false, talkBack = false) },
        )
        Box(Modifier.testTag(DRAG).size(w.dp, h.dp).background(TokenColors.light.bg)) {
            DragLayer(scene, dark = false, levels = levels.collectAsState(), clock = clock) {}
        }
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

    /**
     * DESIGN 4 / 5.6 / 8: every interactive element is at least 48 x 48 dp, measured as TalkBack sees it (bounds clipped
     * by the ancestors); the bubble window (its hit area) is never under 48 dp; a pill is never shorter than its size.
     */
    private fun assertTouchTargets(name: String, m: BubbleMetrics) {
        rule.onAllNodes(androidx.compose.ui.test.hasTestTag(TAG_VISUAL)).fetchSemanticsNodes().forEach { v ->
            val h = v.size.height / v.layoutInfo.density.density
            assertTrue("$name: visual is $h dp tall", h >= m.pillHeight - 0.5f)
        }
        rule.onAllNodes(androidx.compose.ui.test.hasTestTag(WINDOW)).fetchSemanticsNodes().forEach { win ->
            val d = win.layoutInfo.density.density
            assertTrue("$name: hit area ${win.size}", win.size.width / d >= 47.5f && win.size.height / d >= 47.5f)
        }
        val nodes = rule.onAllNodesWithAction().fetchSemanticsNodes()
        assertTrue("$name: no clickable node", name.startsWith("trans") || name.startsWith("polish") || name.startsWith("hold") || nodes.isNotEmpty())
        for (n in nodes) {
            val d = n.layoutInfo.density.density
            val w = n.boundsInWindow.width / d
            val h = n.boundsInWindow.height / d
            assertTrue("$name: ${n.config.getOrNull(SemanticsProperties.ContentDescription)} is ${w.roundToInt()}x${h.roundToInt()} dp", w >= 47.5f && h >= 47.5f)
        }
    }

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithAction() =
        onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))

    private companion object {
        const val STAGE = "stage"
        const val DRAG = "dragframe"
        const val SNACK = "snackframe"
        const val WINDOW = "window"
        const val CIRCLE_TOP = 70f
    }
}
