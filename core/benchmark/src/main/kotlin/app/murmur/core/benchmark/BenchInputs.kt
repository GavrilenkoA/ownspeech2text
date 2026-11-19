package app.murmur.core.benchmark

import app.murmur.core.context.CtxV2
import app.murmur.core.ocr.Rect
import app.murmur.core.ocr.ScreenImage
import app.murmur.core.ocr.ScreenText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The bundled benchmark inputs (resources `/bench/`, provenance in `bench/SOURCES.md`): three FLEURS clips (ru, en,
 * mixed), one OCR page per display size with its golden lines, one LLM cleanup case. Pages are decoded on demand
 * ([BenchPage.image]) because a decoded inner page is 17 MB of pixels.
 */
class BenchInputs(
    val clips: List<BenchClip>,
    val pages: List<BenchPage>,
    val cleanupCase: CleanupCase,
) {
    companion object {
        private const val ROOT = "/bench"
        private val json = Json { ignoreUnknownKeys = false }

        /** Reads the inputs packaged with this module. Throws [IOException] when a resource is missing or malformed. */
        fun bundled(): BenchInputs {
            val manifest = json.decodeFromString(ClipManifest.serializer(), text("$ROOT/asr/clips.json"))
            val clips = manifest.clips.map { c ->
                BenchClip(c.id, c.languages.toSet(), pcm16(bytes("$ROOT/asr/${c.file}")), c.reference)
            }
            val pages = json.decodeFromString(PageManifest.serializer(), text("$ROOT/ocr/pages.json")).pages.map { p ->
                BenchPage(
                    id = p.id,
                    exclude = p.exclude.map { Rect(it[0], it[1], it[2], it[3]) },
                    golden = ScreenText(p.size, p.lines),
                    png = { bytes("$ROOT/ocr/${p.file}") },
                )
            }
            val case = CtxJson.decodeFromString(CaseFile.serializer(), text("$ROOT/llm/case.json"))
            return BenchInputs(clips, pages, CleanupCase(case.transcript, case.context))
        }

        /** Little-endian PCM16, the format of the bundled clips. */
        fun pcm16(bytes: ByteArray): ShortArray {
            if (bytes.size % 2 != 0) throw IOException("odd PCM16 byte count ${bytes.size}")
            val out = ShortArray(bytes.size / 2)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out)
            return out
        }

        private fun bytes(path: String): ByteArray =
            BenchInputs::class.java.getResourceAsStream(path)?.use { it.readBytes() } ?: throw IOException("missing resource $path")

        private fun text(path: String): String = bytes(path).decodeToString()

        /** ctx v2 JSON is parsed with the schema's own settings (unknown keys are an error, as in core:context). */
        private val CtxJson = Json(from = CtxV2.json) { ignoreUnknownKeys = false }
    }

    @Serializable
    private class ClipManifest(val format: String, val clips: List<ClipEntry>)

    @Serializable
    private class ClipEntry(val id: String, val languages: List<String>, val file: String, val reference: String)

    @Serializable
    private class PageManifest(val pipeline: String, val pages: List<PageEntry>)

    @Serializable
    private class PageEntry(
        val id: String,
        val file: String,
        val golden: String,
        val exclude: List<List<Int>>,
        val size: List<Int>,
        val lines: List<app.murmur.core.ocr.OcrLine>,
    )

    @Serializable
    private class CaseFile(val source: String, val transcript: String, val context: CtxV2)
}

/** One OCR page: the PNG (decoded on demand), the exclusion rectangles of its capture and the golden output. */
class BenchPage(
    val id: String,
    val exclude: List<Rect>,
    val golden: ScreenText,
    private val png: () -> ByteArray,
) {
    fun image(): ScreenImage = Png.decode(png(), exclude)
}

/** The fixed LLM case: a 60-word raw Russian transcript and the ctx v2 of the chat it answers. */
class CleanupCase(val transcript: String, val context: CtxV2)
