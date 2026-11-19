package app.murmur.feature.settings

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.murmur.core.store.Settings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.floor

/**
 * The eight Settings frames of the WP-19 brief (`settings`, `settings-off`, `settings-dark`, `prompt`, `prompt-err`,
 * `prompt-reset`, `tone`, `retention`) inside the prototype frame at 1 dp = 2 px, the same size as
 * `docs/design/screens/<frame>--{cover,inner}[--dark].png`, written to `<externalFiles>/settings-shots/` for the pixel
 * diff (`.cache/spikes/wp-19/tools/diff.py`). Each state also checks: clickables at least 48 × 48 dp, no text
 * overflow (cut, ellipsized or wider than its box), and the state's decisive text.
 */
@RunWith(AndroidJUnit4::class)
class SettingsScreenshotTest {
    @get:Rule
    val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val wide = context.resources.configuration.screenWidthDp >= 600
    private val device = if (wide) Device.Inner else Device.Cover
    private val suffix = if (wide) "inner" else "cover"

    @Test
    fun settings() = shot("settings", expect = "Cleanup prompt") { it.selectCategory(SettingsCategory.CLEANUP) }

    @Test
    fun settingsOff() = shot("settings-off", connected = false, expect = "The bubble is off.") { it.selectCategory(SettingsCategory.CLEANUP) }

    @Test
    fun settingsDark() = shot("settings-dark", dark = true, expect = "Cleanup prompt") { it.selectCategory(SettingsCategory.CLEANUP) }

    @Test
    fun prompt() = shot("prompt", bottomBar = false, expect = "Test again") { vm ->
        vm.openPrompt()
        vm.promptEditor.value!!.runTry(PromptEditor.SAMPLE)
    }

    @Test
    fun promptErr() = shot("prompt-err", bottomBar = false, expect = "Remove chat markup like <|im_start|> or <think>: the model reads it as its own.") { vm ->
        vm.openPrompt()
        val e = vm.promptEditor.value!!
        // The frame's state: the card keeps the result of the last valid text; invalid text is never sent.
        e.runTry(PromptEditor.SAMPLE)
        e.setText(SettingsFixtures.PROTOTYPE_PROMPT_MARKUP)
    }

    @Test
    fun promptReset() = shot("prompt-reset", bottomBar = false, promptDialog = PromptDialog.RESET, expect = "Your edits will be replaced. Tone per app is kept.") { vm ->
        vm.openPrompt()
        vm.promptEditor.value!!.runTry(PromptEditor.SAMPLE)
    }

    @Test
    fun tone() = shot("tone", bottomBar = false, expect = "All other apps") { it.openTones() }

    @Test
    fun retention() = shot("retention", sheet = SheetKind.RETENTION, expect = "Choosing a shorter period deletes older dictations right away.") {
        it.selectCategory(SettingsCategory.PRIVACY)
    }

    /** No frame: a fine-tune as the cleanup model (read-only editor and its note), for the design owner. */
    @Test
    fun promptFineTune() = shot(
        "prompt-finetune", bottomBar = false, expect = "This model was trained on its own prompt. Its instructions can't be edited. My preferences still apply.",
        settings = SettingsFixtures.frameSettings.copy(
            cleanup = app.murmur.core.store.CleanupChoice.Model(app.murmur.core.model.ModelId("murmur-cleanup-lfm3-1.7b")),
            promptOverride = null,
        ),
    ) { it.openPrompt() }

    /** No frame: an edit made for an older stock prompt (warn banner with Reset / Keep editing). */
    @Test
    fun promptOlder() = shot(
        "prompt-older", bottomBar = false, expect = "Keep editing",
        settings = SettingsFixtures.frameSettings.copy(promptOverride = app.murmur.core.store.PromptOverride(SettingsFixtures.PROTOTYPE_PROMPT, "default.v3")),
    ) { it.openPrompt() }

    /** No frame: Tone per app with two stored tones, one of them a preview (ADR-020). */
    @Test
    fun tonePreview() = shot(
        "tone-preview", bottomBar = false, expect = "Preview: until",
        settings = SettingsFixtures.frameSettings.copy(tonePerApp = mapOf(SettingsFixtures.TELEGRAM to app.murmur.core.store.Tone.FORMAL, SettingsFixtures.CHROME to app.murmur.core.store.Tone.NEUTRAL)),
    ) { it.openTones() }

    /** No frame: the Permissions category / group with a missing permission and the restricted-setting case. */
    @Test
    fun permissions() = shot(
        "settings-permissions", expect = "Background use",
        permissions = SettingsFixtures.framePermissions.copy(micGranted = false, a11yConnected = false, restrictedLikely = true),
        scrollTo = SettingsTags.row("perm-battery"),
    ) { it.selectCategory(SettingsCategory.PERMISSIONS) }

    private fun shot(
        name: String,
        expect: String,
        dark: Boolean = false,
        connected: Boolean = true,
        bottomBar: Boolean = true,
        sheet: SheetKind? = null,
        promptDialog: PromptDialog? = null,
        settings: Settings = SettingsFixtures.frameSettings,
        permissions: app.murmur.core.dictation.PermissionState = SettingsFixtures.framePermissions,
        scrollTo: String? = null,
        setUp: (SettingsViewModel) -> Unit,
    ) {
        val world = runBlocking { SettingsFixtures.world(settings, permissions = permissions, connected = connected) }
        lateinit var vm: SettingsViewModel
        rule.runOnUiThread {
            vm = SettingsViewModel(world.deps, today = { world.today })
        }
        rule.setContent {
            SettingsFrame(device, dark, bottomBar = bottomBar || wide) {
                SettingsRoot(vm, showDebug = false, versionName = "0.1.0", onSubScreenChange = {}, initialSheet = sheet, initialPromptDialog = promptDialog)
            }
        }
        rule.waitForIdle()
        rule.runOnUiThread { setUp(vm) }
        rule.waitForIdle()
        if (scrollTo != null && !wide) {
            rule.onNodeWithTag(scrollTo).performScrollTo()
            rule.waitForIdle()
        }
        val shotName = "$name--$suffix" + if (dark) "--dark" else ""
        val (file, bmp) = save(shotName)
        assertTrue("$file written", file.length() > 0)
        assertMatchesFrame(shotName, bmp)
        // The state's decisive text is composed (some sit below the fold of the frame).
        rule.onAllNodesWithText(expect, substring = true, useUnmergedTree = true).onFirst().assertExists()
        assertNoTextOverflow(name)
        assertTouchTargets(name)
    }

    /**
     * The shot against its design frame (docs/design/screens, packed into the test APK under frames/): the share of
     * pixels that differ by more than 40 levels in any channel, in the app area (status bar 36 dp and gesture bar
     * 20 dp masked), stays within the recorded budget. The budgets are the measured shares plus 0.5 points; what
     * remains is listed in the WP-19 report (rows the frames lack, the ADR-019 panel, letter tiles). Rendering on an
     * AVD is deterministic, so a regression of a row or a control shows up as a larger share.
     */
    private fun assertMatchesFrame(name: String, shot: Bitmap) {
        val budget = FRAME_BUDGETS[name] ?: return
        val frame = InstrumentationRegistry.getInstrumentation().context.assets.open("frames/$name.png").use { BitmapFactory.decodeStream(it) }
        assertEquals("$name frame size", frame.width to frame.height, shot.width to shot.height)
        val w = shot.width
        val h = shot.height
        val a = IntArray(w * h).also { frame.getPixels(it, 0, w, 0, 0, w, h) }
        val b = IntArray(w * h).also { shot.getPixels(it, 0, w, 0, 0, w, h) }
        val top = 36 * 2
        val bottom = h - 20 * 2
        var differ = 0
        for (i in top * w until bottom * w) {
            val p = a[i]
            val q = b[i]
            val d = maxOf(
                kotlin.math.abs((p shr 16 and 0xFF) - (q shr 16 and 0xFF)),
                kotlin.math.abs((p shr 8 and 0xFF) - (q shr 8 and 0xFF)),
                kotlin.math.abs((p and 0xFF) - (q and 0xFF)),
            )
            if (d > 40) differ++
        }
        val share = 100.0 * differ / ((bottom - top).toLong() * w)
        Log.i(TAG, "$name: %.2f%% pixels differ from the frame (budget %.2f%%)".format(share, budget))
        assertTrue("$name differs from its frame in %.2f%% of the pixels (budget %.2f%%)".format(share, budget), share <= budget)
    }

    private fun save(name: String): Pair<File, Bitmap> {
        val bmp: Bitmap = rule.onNodeWithTag(FRAME_TAG).captureToImage().asAndroidBitmap()
        assertEquals("frame width px", device.width * 2, bmp.width)
        assertEquals("frame height px", device.height * 2, bmp.height)
        val dir = File(requireNotNull(context.getExternalFilesDir(null)), "settings-shots").apply { mkdirs() }
        val file = File(dir, "$name.png")
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Log.i(TAG, "$name: ${bmp.width}x${bmp.height} -> $file")
        return file to bmp
    }

    /** Clickables are at least 48 × 48 dp (in the frame 1 dp = 2 px), by layout size and by touch bounds. */
    private fun assertTouchTargets(name: String) {
        val min = floor(48 * 2f) - 0.5f
        val nodes = rule.onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("clickables found in $name", nodes.isNotEmpty())
        val small = nodes.filter { n ->
            // Nodes scrolled partly out of view are clipped by the viewport; check what is fully visible.
            val visible = kotlin.math.abs(n.boundsInRoot.height - n.size.height) < 1f && kotlin.math.abs(n.boundsInRoot.width - n.size.width) < 1f
            n.size.width < min || n.size.height < min || (visible && (n.touchBoundsInRoot.height < min || n.touchBoundsInRoot.width < min))
        }.map { describe(it) }
        Log.i(TAG, "$name: ${nodes.size} clickables")
        assertTrue("clickables below 48 dp in $name:\n" + small.joinToString("\n"), small.isEmpty())
    }

    private fun assertNoTextOverflow(name: String) {
        val nodes = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
            .fetchSemanticsNodes()
        val bad = mutableListOf<String>()
        rule.runOnIdle {
            for (n in nodes) {
                // The inner prompt preview is a deliberately clipped excerpt (the frame clips it the same way).
                if (n.config.getOrNull(SemanticsProperties.TestTag) == SettingsTags.PROMPT_PREVIEW) continue
                val results = mutableListOf<TextLayoutResult>()
                n.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
                val r = results.firstOrNull() ?: continue
                val overflow = r.didOverflowHeight || r.multiParagraph.didExceedMaxLines ||
                    (0 until r.lineCount).any { i -> r.isLineEllipsized(i) || r.getLineRight(i) - r.getLineLeft(i) > r.size.width + 1f }
                if (overflow) bad += describe(n)
            }
        }
        Log.i(TAG, "$name: ${nodes.size} texts checked")
        assertTrue("texts found in $name", nodes.isNotEmpty())
        assertTrue("text overflow in $name:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    private fun describe(n: SemanticsNode): String {
        val text = n.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }
            ?: n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
            ?: n.config.getOrNull(SemanticsProperties.TestTag)
        return "#${n.id} '$text' ${n.size.width / 2f}x${n.size.height / 2f} dp"
    }

    private companion object {
        const val TAG = "SettingsShot"

        /** Per frame: measured share of differing pixels (2026-11-20, both AVDs) plus 0.5 points. */
        val FRAME_BUDGETS: Map<String, Double> = mapOf(
            "settings--cover" to 6.74, "settings--inner" to 3.28,
            "settings-dark--cover--dark" to 6.96, "settings-dark--inner--dark" to 3.32,
            "settings-off--cover" to 6.87, "settings-off--inner" to 4.10,
            "prompt--cover" to 7.99, "prompt--inner" to 5.26,
            "prompt-err--cover" to 9.67, "prompt-err--inner" to 5.99,
            "prompt-reset--cover" to 6.66, "prompt-reset--inner" to 4.10,
            "tone--cover" to 4.78, "tone--inner" to 3.80,
            "retention--cover" to 5.01, "retention--inner" to 2.05,
        )
    }
}
