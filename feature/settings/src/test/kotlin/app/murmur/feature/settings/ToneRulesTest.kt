package app.murmur.feature.settings

import app.murmur.core.context.AppCategories
import app.murmur.core.context.AppInfo
import app.murmur.core.context.CtxV2
import app.murmur.core.prompt.ClasspathPromptAssets
import app.murmur.core.prompt.RenderPyPort
import app.murmur.core.store.Tone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ToneRulesTest {
    private val root = File(requireNotNull(System.getProperty("murmur.repoRoot")) { "murmur.repoRoot not set" })
    private val categories = AppCategories.parse(File(root, "prompts/tones/app_categories.v1.json").readText())
    private val tonesJson = File(root, "prompts/tones/tones.v1.json").readText()

    @Test
    fun everyListedCategoryExistsInThePromptFiles() {
        val inFile = categories.values.toSet()
        ToneRules.LISTED_CATEGORIES.forEach { assertTrue("$it in app_categories.v1.json", it in inFile) }
        assertTrue("the bundled copy is the prompts/ file", AppCategories.bundled() == categories)
    }

    @Test
    fun categoryTonesAreTheDesignDefaults() {
        assertEquals(Tone.CASUAL, ToneRules.categoryTone(categories["org.telegram.messenger"]))
        assertEquals(Tone.CASUAL, ToneRules.categoryTone(categories["com.whatsapp"]))
        assertEquals(Tone.CASUAL, ToneRules.categoryTone(categories["com.google.android.apps.messaging"]))
        assertEquals(Tone.FORMAL, ToneRules.categoryTone(categories["com.google.android.gm"]))
        assertEquals(Tone.FORMAL, ToneRules.categoryTone(categories["com.microsoft.office.outlook"]))
        assertEquals(Tone.FORMAL, ToneRules.categoryTone(categories["com.samsung.android.email.provider"]))
        assertEquals(Tone.VERBATIM, ToneRules.categoryTone(categories["com.termux"]))
        assertEquals(Tone.NEUTRAL, ToneRules.categoryTone(categories["com.Slack"]))
        assertEquals(Tone.NEUTRAL, ToneRules.categoryTone(null))
    }

    /** The preview flag must agree with what the renderer does: a named tone without text falls back (ADR-020). */
    @Test
    fun previewAgreesWithTheRenderersToneResolution() {
        val renderer = RenderPyPort(ClasspathPromptAssets())
        val withText = ToneRules.tonesWithText(ClasspathPromptAssets().text("tones/tones.v1.json"))
        assertEquals(ToneRules.tonesWithText(tonesJson), withText)
        val ctx = CtxV2(app = AppInfo("org.telegram.messenger", "Telegram", "messaging"))
        Tone.entries.forEach { tone ->
            val r = renderer.resolveTone(ctx, tone.name)
            assertEquals("$tone has text", tone in withText, !r.preview)
            assertEquals("stored $tone on a messenger", r.preview && tone != Tone.CASUAL, ToneRules.isPreview(tone, Tone.CASUAL, withText))
        }
    }

    @Test
    fun namedTextsAreReadFromTheRenderersSchema() {
        val json = """{"version":1,"categories":{},"named":{"formal":{"text":"Formal text."},"casual":{"text":"  "},"verbatim":{}}}"""
        assertEquals(setOf(Tone.FORMAL), ToneRules.tonesWithText(json))
        assertFalse(ToneRules.isPreview(Tone.FORMAL, Tone.CASUAL, setOf(Tone.FORMAL)))
        assertTrue(ToneRules.isPreview(Tone.VERBATIM, Tone.CASUAL, setOf(Tone.FORMAL)))
        assertFalse("the category's own tone is never a preview", ToneRules.isPreview(Tone.CASUAL, Tone.CASUAL, emptySet()))
    }

    @Test
    fun rowsKeepTheFileOrderOfEachCategoryThenAddedAppsByLabel() {
        val launcher = listOf("com.termux", "com.google.android.gm", "org.telegram.messenger", "com.android.chrome", "ru.sberbankmobile", "com.whatsapp")
        val labels = mapOf("com.android.chrome" to "Chrome", "ru.sberbankmobile" to "Альфа")
        val rows = ToneRules.rows(
            launcher, categories, mapOf("ru.sberbankmobile" to Tone.FORMAL, "com.android.chrome" to Tone.NEUTRAL, "com.whatsapp" to Tone.FORMAL),
            emptySet(),
        ) { labels[it] }
        assertEquals(
            listOf("org.telegram.messenger", "com.whatsapp", "com.google.android.gm", "com.termux", "com.android.chrome", "ru.sberbankmobile"),
            rows.map { it.pkg },
        )
        val whatsapp = rows[1]
        assertTrue(whatsapp.explicit && whatsapp.preview && !whatsapp.removable)
        assertTrue(rows[4].removable && !rows[4].preview)
        assertTrue("an added bank set to Formal has no Formal text yet", rows[5].preview)
        assertEquals(mapOf<String, Tone>(), ToneRules.pick(mapOf("com.whatsapp" to Tone.FORMAL), "com.whatsapp", Tone.CASUAL, listed = true, categoryTone = Tone.CASUAL))
        assertEquals(mapOf("x" to Tone.NEUTRAL), ToneRules.pick(emptyMap(), "x", Tone.NEUTRAL, listed = false, categoryTone = Tone.NEUTRAL))
    }
}
