package app.murmur.core.context

import app.murmur.core.ocr.OcrLine
import app.murmur.core.ocr.Rect
import app.murmur.core.ocr.ScreenText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Every assembled object validates against prompts/context.schema.v2.json (Kotlin port of ml/common/jsonschema_lite).
 * The validator itself is checked first: it must understand every keyword the schema uses and reject broken objects.
 */
class SchemaValidationTest {
    private val schema = Fixtures.schema

    private fun errors(s: String) = SchemaLite.validate(Json.parseToJsonElement(s), schema)

    @Test
    fun validatorCoversEveryKeywordOfTheSchema() {
        assertEquals(emptyList<String>(), SchemaLite.unsupportedKeywords(schema))
    }

    @Test
    fun validatorAcceptsTheGoldens() {
        for (f in Fixtures.examples) assertEquals(f.name, emptyList<String>(), SchemaLite.validate(Fixtures.exampleContext(f), schema))
    }

    @Test
    fun validatorRejectsBrokenObjects() {
        val cases = mapOf(
            """{"app":{"package":"a"}}""" to "missing v",
            """{"v":1,"app":{"package":"a"}}""" to "const",
            """{"v":2}""" to "missing app",
            """{"v":2,"app":{"package":"a","x":1}}""" to "additionalProperties",
            """{"v":2,"app":{"package":"a"},"extra":true}""" to "additionalProperties top",
            """{"v":2,"app":{"package":3}}""" to "type string",
            """{"v":2,"app":{"package":"a"},"field":{"box":[1,2,3]}}""" to "minItems",
            """{"v":2,"app":{"package":"a"},"field":{"box":[1,2,3,4,5]}}""" to "maxItems",
            """{"v":2,"app":{"package":"a"},"field":{"box":[1,2,3,4.5]}}""" to "integer",
            """{"v":2,"app":{"package":"a"},"field":{"box":[1,2,3,4.0]}}""" to "integer excludes 4.0",
            """{"v":2,"app":{"package":"a"},"field":{"password":"yes"}}""" to "boolean",
            """{"v":2,"app":{"package":"a"},"screen":{"size":[1,1],"lines":[{"text":"t","conf":1.5,"box":[0,0,1,1]}]}}""" to "maximum",
            """{"v":2,"app":{"package":"a"},"screen":{"size":[1,1],"lines":[{"text":"t","conf":-0.1,"box":[0,0,1,1]}]}}""" to "minimum",
            """{"v":2,"app":{"package":"a"},"screen":{"size":[1,1],"lines":[{"text":"t","box":[0,0,1,1],"x":0}]}}""" to "line keys",
            """{"v":2,"app":{"package":"a"},"screen":{"size":[1,1]}}""" to "missing lines",
            """{"v":2,"app":{"package":"a"},"privacy":{"level":"full","reason":"because"}}""" to "enum",
            """{"v":2,"app":{"package":"a"},"privacy":{"level":true}}""" to "type of level",
            """{"v":2,"app":{"package":"a"},"language":{"asr":"123456789"}}""" to "maxLength",
        )
        for ((json, why) in cases) assertTrue("$why: $json", errors(json).isNotEmpty())
        // maxLength counts code points: 8 emoji are 16 UTF-16 units and still valid.
        assertEquals(emptyList<String>(), errors("""{"v":2,"app":{"package":"a"},"language":{"asr":"${"😀".repeat(8)}"}}"""))
        assertEquals(emptyList<String>(), errors("""{"v":2,"app":{"package":"a"},"privacy":{"level":"full","reason":null}}"""))
    }

    /** Random snapshots, decisions and screen outcomes (seeded): every assembled object validates. */
    @Test
    fun randomAssembliesValidate() {
        val rnd = Random(20261119)
        val assembler = DefaultContextAssembler()
        val pkgs = listOf("org.telegram.messenger", "com.google.android.gm", "com.android.settings", "app.murmur", "x".repeat(260), "com.example")
        val variations = listOf(0x00, 0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70, 0x80, 0x90, 0xa0, 0xb0, 0xc0, 0xd0, 0xe0)
        fun text(max: Int) = buildString {
            val alphabet = "абвгдеёжзabcxyz 0123456789\n\t«»<>😀👍"
            repeat(rnd.nextInt(max)) { append(alphabet[rnd.nextInt(alphabet.length)].let { if (it.isSurrogate()) 'ы' else it }) }
            if (rnd.nextInt(5) == 0) append("😀".repeat(rnd.nextInt(max / 2 + 1)))
        }
        fun rect() = rnd.nextInt(-200, 2000).let { x -> rnd.nextInt(-200, 2600).let { y -> Rect(x, y, x + rnd.nextInt(0, 1200), y + rnd.nextInt(0, 300)) } }
        repeat(600) { i ->
            val cls = listOf(FieldKinds.TYPE_CLASS_TEXT, FieldKinds.TYPE_CLASS_NUMBER, FieldKinds.TYPE_CLASS_PHONE, 0, FieldKinds.TYPE_CLASS_DATETIME)[rnd.nextInt(5)]
            val inputType = cls or variations[rnd.nextInt(variations.size)] or (if (rnd.nextBoolean()) FieldKinds.TYPE_TEXT_FLAG_MULTI_LINE else 0)
            val field = if (rnd.nextInt(6) == 0) null else FieldSnapshot(
                inputType, rnd.nextInt(0, 8), if (rnd.nextBoolean()) text(400) else null, if (rnd.nextBoolean()) text(6000) else null,
                if (rnd.nextInt(5) == 0) null else rect(), rnd.nextInt(10) == 0, rnd.nextBoolean(),
            )
            val window = if (rnd.nextInt(4) == 0) null else listOf(Fixtures.COVER, Fixtures.INNER, Rect(984, 0, 1968, 2184), Rect(0, 1100, 1968, 2184))[rnd.nextInt(4)]
            val snap = A11ySnapshot(pkgs[rnd.nextInt(pkgs.size)], if (rnd.nextBoolean()) text(150) else null, null, 1, 0, field, emptyList(), 0, window)
            val settings = ContextSettings(useScreen = rnd.nextInt(4) != 0, excludedPackages = if (rnd.nextBoolean()) setOf("com.example") else emptySet(),
                inAppPractice = rnd.nextBoolean())
            // Window-sized captures, sometimes a pixel off (edge clipping), with or without the capturer's kind.
            val w = (window?.width ?: 1080) - rnd.nextInt(0, 2)
            val h = window?.height ?: 2520
            val kind = listOf(null, CaptureKind.WINDOW, CaptureKind.DISPLAY)[rnd.nextInt(3)]
            val outcome = when (rnd.nextInt(5)) {
                0 -> ScreenOutcome.NotTaken
                1 -> ScreenOutcome.SecureWindow
                2 -> ScreenOutcome.Failed
                else -> ScreenOutcome.Text(ScreenText(listOf(w, h), List(rnd.nextInt(0, 30)) {
                    OcrLine(text(60).ifEmpty { "ok" }, rnd.nextDouble(), listOf(0, it * 50, 400, it * 50 + 40))
                }), kind)
            }
            val lang = listOf("ru", "en", null, "", "mixed", "verylongcode")[rnd.nextInt(6)]
            val ctx = assembler.assemble(snap, assembler.decide(snap, settings), outcome, lang)
            Fixtures.assertValid(ctx, "case $i")
            // Privacy invariants on the way.
            val level = ctx.privacy!!.level
            if (level != PrivacyLevel.FULL) {
                assertTrue("case $i", ctx.screen == null && ctx.field?.beforeCursor == null)
            }
            if (field != null && FieldKinds.isPassword(field)) assertEquals("case $i", PrivacyLevel.NONE, level)
            val box = ctx.field?.box
            val size = ctx.screen?.size
            if (box != null && size != null) {
                assertTrue("case $i: $box outside $size", box[0] >= 0 && box[1] >= 0 && box[2] <= size[0] && box[3] <= size[1])
            }
            val json = CtxV2.json.encodeToString(CtxV2.serializer(), ctx)
            assertEquals("case $i", ctx, CtxV2.json.decodeFromString(CtxV2.serializer(), json))
            assertEquals("case $i", Fixtures.encode(ctx), Json.parseToJsonElement(json).jsonObject)
        }
    }
}
