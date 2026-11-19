package app.murmur.feature.models

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * D3: the hub has no model-specific code. Scans every source and resource file of `src/main` for `ModelId("…")` /
 * `ArtifactId("…")` constructions with a literal, and for any string literal that contains a catalog model id or
 * artifact id.
 */
class NoModelLiteralsTest {
    private val root: File = File(System.getProperty("murmur.modelsSrc") ?: "src/main").absoluteFile

    private val files: List<File> by lazy {
        require(root.isDirectory) { "sources not found at $root" }
        root.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }.toList()
    }

    @Test
    fun noIdConstructorsWithLiterals() {
        val ctor = Regex("""\b(ModelId|ArtifactId)\s*\(\s*"""")
        val hits = files.flatMap { f -> f.readLines().mapIndexedNotNull { i, l -> if (ctor.containsMatchIn(l)) "${f.name}:${i + 1}: ${l.trim()}" else null } }
        assertTrue("scanned ${files.size} files", files.size >= 5)
        assertTrue("id literals:\n" + hits.joinToString("\n"), hits.isEmpty())
    }

    @Test
    fun noCatalogIdInAnyLiteral() {
        val c = HubFixtures.catalog
        val ids = c.models.map { it.id.value } + c.models.flatMap { m -> m.artifacts.map { it.id.value } }
        val literal = Regex(""""((?:[^"\\]|\\.)*)"""")
        val hits = mutableListOf<String>()
        for (f in files) {
            f.readLines().forEachIndexed { i, line ->
                val texts = if (f.extension == "xml") listOf(line) else literal.findAll(line).map { it.groupValues[1] }.toList()
                for (t in texts) for (id in ids) if (t.contains(id, ignoreCase = true)) hits += "${f.name}:${i + 1}: \"$t\" contains $id"
            }
        }
        assertTrue("catalog ids in sources:\n" + hits.joinToString("\n"), hits.isEmpty())
    }
}
