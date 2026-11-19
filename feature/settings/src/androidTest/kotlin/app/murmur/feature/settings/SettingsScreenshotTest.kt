package app.murmur.feature.settings

import android.graphics.Bitmap
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
        e.setText(SettingsFixtures.PROTOTYPE_PROMPT_MARKUP)
        e.runTry(PromptEditor.SAMPLE)
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
        val file = save("$name--$suffix" + if (dark) "--dark" else "")
        assertTrue("$file written", file.length() > 0)
        // The state's decisive text is composed (some sit below the fold of the frame).
        rule.onAllNodesWithText(expect, substring = true, useUnmergedTree = true).onFirst().assertExists()
        assertNoTextOverflow(name)
        assertTouchTargets(name)
    }

    private fun save(name: String): File {
        val bmp: Bitmap = rule.onNodeWithTag(FRAME_TAG).captureToImage().asAndroidBitmap()
        assertEquals("frame width px", device.width * 2, bmp.width)
        assertEquals("frame height px", device.height * 2, bmp.height)
        val dir = File(requireNotNull(context.getExternalFilesDir(null)), "settings-shots").apply { mkdirs() }
        val file = File(dir, "$name.png")
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Log.i(TAG, "$name: ${bmp.width}x${bmp.height} -> $file")
        return file
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
    }
}
