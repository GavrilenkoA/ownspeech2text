package app.murmur.core.context

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Module-level rules: JVM only (no Android imports), the bundled category table equals prompts/ (D8), no logging (D9). */
class ModuleRulesTest {
    private val moduleSources: List<File> by lazy {
        val dir = Fixtures.root.resolve("core/context/src")
        assertTrue("missing $dir", dir.isDirectory)
        dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList().also { assertTrue(it.isNotEmpty()) }
    }

    @Test
    fun noAndroidImports() {
        val offenders = moduleSources.flatMap { f ->
            f.readLines().filter { Regex("""^\s*import\s+(android|androidx|com\.android)\.""").containsMatchIn(it) }.map { "${f.name}: $it" }
        }
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun mainSourcesNeverLogOrPrint() {
        val main = moduleSources.filter { "/src/main/" in it.invariantSeparatorsPath }
        assertTrue(main.isNotEmpty())
        val offenders = main.flatMap { f ->
            f.readLines().filter { Regex("""\b(println|print|Log\.[a-z]|System\.(out|err)|printStackTrace|Logger)\b""").containsMatchIn(it) }
                .map { "${f.name}: $it" }
        }
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun bundledCategoriesEqualThePromptsFile() {
        val source = AppCategories.parse(Fixtures.repoFile("prompts/tones/app_categories.v1.json").readText())
        assertEquals(source, AppCategories.bundled())
        assertEquals("messaging", AppCategories.bundled()["org.telegram.messenger"])
        assertEquals("email", AppCategories.bundled()["com.google.android.gm"])
    }

    @Test
    fun everyBundledCategoryIsATone() {
        // prompts/tones/tones.v1.json "categories" keys: a category the renderer does not know would silently become "default".
        val tones = Json.parseToJsonElement(Fixtures.repoFile("prompts/tones/tones.v1.json").readText()).jsonObject
        val known = tones.getValue("categories").jsonObject.keys
        val unknown = AppCategories.bundled().filterValues { it !in known }
        assertEquals(emptyMap<String, String>(), unknown)
    }
}
