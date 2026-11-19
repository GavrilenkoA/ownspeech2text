package app.murmur.core.context

import app.murmur.core.ocr.OcrLine
import app.murmur.core.ocr.Rect
import app.murmur.core.ocr.ScreenText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File

/** Shared test inputs: repo files read in place (prompts/ is the single source, D8) and snapshot builders. */
object Fixtures {
    val root: File = File(System.getProperty("murmur.repoRoot") ?: error("murmur.repoRoot not set (run through Gradle)"))

    fun repoFile(path: String): File = File(root, path).also { assertTrue("missing $it", it.isFile) }

    val schema: JsonObject by lazy { Json.parseToJsonElement(repoFile("prompts/context.schema.v2.json").readText()).jsonObject }

    /** prompts/examples/ctxv2_*.input.json, sorted by name; at least the two goldens the renderer uses. */
    val examples: List<File> by lazy {
        val files = repoFile("prompts/context.schema.v2.json").parentFile.resolve("examples")
            .listFiles { f -> f.name.startsWith("ctxv2_") && f.name.endsWith(".input.json") }.orEmpty().sortedBy { it.name }
        assertTrue("expected the ctxv2 goldens, found ${files.map { it.name }}", files.size >= 2)
        files
    }

    fun exampleContext(f: File): JsonObject = Json.parseToJsonElement(f.readText()).jsonObject.getValue("context").jsonObject

    // android.text.InputType of a typical chat / mail body field: TEXT | CAP_SENTENCES | MULTI_LINE, variation NORMAL.
    const val TEXT_MULTILINE = FieldKinds.TYPE_CLASS_TEXT or FieldKinds.TYPE_TEXT_FLAG_CAP_SENTENCES or FieldKinds.TYPE_TEXT_FLAG_MULTI_LINE

    val COVER = Rect(0, 0, 1080, 2520)
    val INNER = Rect(0, 0, 1968, 2184)

    fun field(
        inputType: Int = TEXT_MULTILINE,
        imeOptions: Int = 0,
        hint: String? = "Сообщение",
        beforeCursor: String? = "",
        bounds: Rect? = Rect(40, 2400, 940, 2490),
        isPassword: Boolean = false,
    ) = FieldSnapshot(inputType, imeOptions, hint, beforeCursor, bounds, isPassword, (inputType and FieldKinds.TYPE_TEXT_FLAG_MULTI_LINE) != 0)

    fun snapshot(
        pkg: String = "org.telegram.messenger",
        label: String? = "Telegram",
        field: FieldSnapshot? = field(),
        window: Rect? = COVER,
    ) = A11ySnapshot(
        pkg = pkg, label = label, windowTitle = "Лёша Козлов", windowId = 7, displayId = 0, field = field,
        excludeRects = emptyList(), capturedAtMs = 0, windowBoundsInScreen = window,
    )

    fun screen(w: Int = 1080, h: Int = 2520, vararg texts: String = arrayOf("Лёша Козлов", "был(а) недавно")) =
        ScreenText(listOf(w, h), texts.mapIndexed { i, t -> OcrLine(t, 0.95, listOf(52, 100 + 60 * i, 600, 150 + 60 * i)) })

    fun encode(ctx: CtxV2): JsonElement = CtxV2.json.encodeToJsonElement(CtxV2.serializer(), ctx)

    /** Encodes [ctx] and fails with the schema errors if it does not validate. */
    fun assertValid(ctx: CtxV2, what: String = ""): JsonObject {
        val json = encode(ctx).jsonObject
        assertEquals("$what: schema errors for $json", emptyList<String>(), SchemaLite.validate(json, schema))
        return json
    }
}
