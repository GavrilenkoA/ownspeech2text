package app.murmur.core.context

import app.murmur.core.context.Fixtures.assertValid
import app.murmur.core.context.Fixtures.field
import app.murmur.core.context.Fixtures.screen
import app.murmur.core.context.Fixtures.snapshot
import app.murmur.core.ocr.OcrLine
import app.murmur.core.ocr.Rect
import app.murmur.core.ocr.ScreenText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ctx v2 assembly, one case per acceptance row. Every expected object is written out as JSON (absent keys are absent),
 * and every assembled object is validated against prompts/context.schema.v2.json.
 */
class AssembleTest {
    private val a = DefaultContextAssembler()
    private val on = ContextSettings()

    private fun run(snap: A11ySnapshot, settings: ContextSettings, screen: ScreenOutcome, lang: String? = "ru"): JsonObject {
        val ctx = a.assemble(snap, a.decide(snap, settings), screen, lang)
        return assertValid(ctx)
    }

    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    private val twoLines = """{"size":[1080,2520],"lines":[
        {"text":"Лёша Козлов","conf":0.95,"box":[52,100,600,150]},
        {"text":"был(а) недавно","conf":0.95,"box":[52,160,600,210]}]}"""

    @Test
    fun fullWithScreen() {
        val got = run(snapshot(), on, ScreenOutcome.Text(screen()))
        assertEquals(json("""{"v":2,
            "app":{"package":"org.telegram.messenger","label":"Telegram","category":"messaging"},
            "field":{"kind":"message","hint":"Сообщение","before_cursor":"","box":[40,2400,940,2490]},
            "screen":$twoLines,
            "language":{"asr":"ru"},
            "privacy":{"level":"full","reason":null}}"""), got)
    }

    @Test
    fun passwordField() {
        val snap = snapshot(field = field(inputType = FieldKinds.TYPE_CLASS_TEXT or FieldKinds.TYPE_TEXT_VARIATION_PASSWORD,
            hint = "Пароль", beforeCursor = "hunter2", isPassword = true))
        val d = a.decide(snap, on)
        assertTrue(d.skipLlm)
        assertFalse(d.takeScreenshot)
        // Even if a screen and text before the cursor reach the assembler, neither leaves it; the label is hidden.
        val got = assertValid(a.assemble(snap, d, ScreenOutcome.Text(screen()), "ru"))
        assertEquals(json("""{"v":2,
            "app":{"package":"org.telegram.messenger","category":"messaging"},
            "field":{"kind":"message","box":[40,2400,940,2490],"input_type_variation":"TYPE_TEXT_VARIATION_PASSWORD","password":true},
            "language":{"asr":"ru"},
            "privacy":{"level":"none","reason":"password_field"}}"""), got)
    }

    @Test
    fun focusTurnedIntoPasswordAfterAFullDecision() {
        val full = a.decide(snapshot(), on)
        val later = snapshot(field = field(isPassword = true, beforeCursor = "secret"))
        val got = assertValid(a.assemble(later, full, ScreenOutcome.Text(screen()), "ru"))
        assertEquals(json("""{"level":"none","reason":"password_field"}"""), got["privacy"])
        assertFalse("screen" in got)
        assertFalse("before_cursor" in got.getValue("field").jsonObject)
    }

    @Test
    fun numberAndPhoneFields() {
        for (type in listOf(FieldKinds.TYPE_CLASS_NUMBER, FieldKinds.TYPE_CLASS_PHONE)) {
            val snap = snapshot(field = field(inputType = type, hint = "Номер", beforeCursor = "+7"))
            assertFalse(a.decide(snap, on).dictationAllowed)
            val got = run(snap, on, ScreenOutcome.Text(screen()))
            assertEquals(json("""{"kind":"generic","hint":"Номер","before_cursor":"+7","box":[40,2400,940,2490]}"""), got["field"])
        }
    }

    @Test
    fun denylistedApp() {
        val snap = snapshot(pkg = "com.android.settings", label = "Настройки", field = field(hint = "Поиск", beforeCursor = "wi"))
        // The bubble is hidden there; if a dictation runs anyway: app_only, no screen, no text before the cursor.
        val got = run(snap, on, ScreenOutcome.NotTaken)
        assertEquals(json("""{"v":2,
            "app":{"package":"com.android.settings","label":"Настройки"},
            "field":{"kind":"generic","hint":"Поиск","box":[40,2400,940,2490]},
            "language":{"asr":"ru"},
            "privacy":{"level":"app_only","reason":"denylisted_app"}}"""), got)
        // A screen handed over by mistake is still dropped.
        assertFalse("screen" in run(snap, on, ScreenOutcome.Text(screen())))
    }

    @Test
    fun userHiddenApp() {
        val got = run(snapshot(pkg = "com.whatsapp", label = "WhatsApp"), ContextSettings(excludedPackages = setOf("com.whatsapp")),
            ScreenOutcome.NotTaken)
        assertEquals(json("""{"level":"app_only","reason":"denylisted_app"}"""), got["privacy"])
        assertEquals(json("""{"package":"com.whatsapp","label":"WhatsApp","category":"messaging"}"""), got["app"])
        assertFalse("before_cursor" in got.getValue("field").jsonObject)
    }

    @Test
    fun ownPackageInAppPractice() {
        val snap = snapshot(pkg = DefaultContextAssembler.OWN_PACKAGE, label = "Practice", window = Fixtures.INNER,
            field = field(hint = "Скажите что-нибудь", beforeCursor = "Тест", bounds = Rect(48, 1800, 1920, 1990)))
        val d = a.decide(snap, ContextSettings(inAppPractice = true))
        assertTrue(d.dictationAllowed)
        assertFalse(d.takeScreenshot)
        val got = assertValid(a.assemble(snap, d, ScreenOutcome.NotTaken, "en"))
        assertEquals(json("""{"v":2,
            "app":{"package":"app.murmur","label":"Practice"},
            "field":{"kind":"generic","hint":"Скажите что-нибудь","box":[48,1800,1920,1990]},
            "language":{"asr":"en"},
            "privacy":{"level":"app_only","reason":"denylisted_app"}}"""), got)
    }

    @Test
    fun useScreenSettingOff() {
        val snap = snapshot(field = field(beforeCursor = "Привет"))
        val got = run(snap, ContextSettings(useScreen = false), ScreenOutcome.NotTaken)
        assertEquals(json("""{"level":"app_only","reason":"user_setting"}"""), got["privacy"])
        assertEquals(json("""{"kind":"message","hint":"Сообщение","box":[40,2400,940,2490]}"""), got["field"])
        assertFalse("screen" in got)
    }

    @Test
    fun secureWindowHasNoScreen() {
        // NO_SCREEN_LEVEL: secure_window -> app_only (the app marked its content sensitive): no text before the cursor.
        val got = run(snapshot(field = field(beforeCursor = "Перевод 500")), on, ScreenOutcome.SecureWindow)
        assertFalse("screen" in got)
        assertEquals(json("""{"level":"app_only","reason":"secure_window"}"""), got["privacy"])
        assertEquals(json("""{"kind":"message","hint":"Сообщение","box":[40,2400,940,2490]}"""), got["field"])
    }

    @Test
    fun ocrEmptyKeepsTheFieldContext() {
        val got = run(snapshot(field = field(beforeCursor = "Добрый день")), on, ScreenOutcome.Text(ScreenText(listOf(1080, 2520), emptyList())))
        assertFalse("screen" in got)
        assertEquals(json("""{"level":"full","reason":"ocr_empty"}"""), got["privacy"])
        assertEquals(json("""{"kind":"message","hint":"Сообщение","before_cursor":"Добрый день","box":[40,2400,940,2490]}"""), got["field"])
    }

    @Test
    fun screenshotErrorOrLateOcr() {
        val snap = snapshot(field = field(beforeCursor = "Да"))
        val viaOutcome = run(snap, on, ScreenOutcome.Failed)
        val viaNull = assertValid(a.assemble(snap, a.decide(snap, on), null as ScreenText?, "ru"))
        assertEquals(viaOutcome, viaNull)
        assertEquals(json("""{"level":"full","reason":"screenshot_error"}"""), viaOutcome["privacy"])
        assertEquals("\"Да\"", viaOutcome.getValue("field").jsonObject.getValue("before_cursor").toString())
    }

    @Test
    fun missingFieldWhenTheAppExposesNoFocusedNode() {
        val got = run(snapshot(field = null), on, ScreenOutcome.Text(screen()))
        assertEquals(json("""{"v":2,
            "app":{"package":"org.telegram.messenger","label":"Telegram","category":"messaging"},
            "screen":$twoLines,
            "language":{"asr":"ru"},
            "privacy":{"level":"full","reason":null}}"""), got)
    }

    @Test
    fun multilineBeforeCursorIsKeptVerbatim() {
        // Newlines, tabs and trailing spaces reach the renderer untouched (render.py clean() decides what it shows).
        val text = "Привет!\nСписок:\n- молоко\n\tхлеб  \n"
        val snap = snapshot(field = field(beforeCursor = text))
        val ctx = a.assemble(snap, a.decide(snap, on), ScreenOutcome.Text(screen()), "ru")
        assertEquals(text, ctx.field?.beforeCursor)
        val encoded = CtxV2.json.encodeToString(CtxV2.serializer(), ctx)
        assertTrue(encoded, encoded.contains("\"before_cursor\":\"Привет!\\nСписок:\\n- молоко\\n\\tхлеб  \\n\""))
        assertEquals(ctx, CtxV2.json.decodeFromString(CtxV2.serializer(), encoded))
        assertValid(ctx)
    }

    @Test
    fun longBeforeCursorKeepsTheEndAndNeverSplitsASurrogatePair() {
        val tail = "конец 👍"
        val text = "😀".repeat(5000) + tail
        val got = run(snapshot(field = field(beforeCursor = text)), on, ScreenOutcome.Text(screen()))
        val kept = got.getValue("field").jsonObject.getValue("before_cursor").toString().removeSurrounding("\"")
        assertEquals(4000, kept.codePointCount(0, kept.length))
        assertTrue(kept.endsWith(tail))
        assertTrue(kept.startsWith("😀"))
    }

    @Test
    fun fieldBoxWithAWindowOffset() {
        // Split screen on the inner display, app in the right pane: window capture 984 x 2184 starting at x = 984.
        val window = Rect(984, 0, 1968, 2184)
        val snap = snapshot(window = window, field = field(bounds = Rect(1010, 2050, 1930, 2140)))
        val got = run(snap, on, ScreenOutcome.Text(screen(984, 2184, "Анна", "Привет")))
        assertEquals("[26,2050,946,2140]", got.getValue("field").jsonObject.getValue("box").toString())
        // Display-capture fallback (capture = whole display): screen coordinates stay.
        val fallback = run(snap, on, ScreenOutcome.Text(screen(1968, 2184, "Анна", "Привет")))
        assertEquals("[1010,2050,1930,2140]", fallback.getValue("field").jsonObject.getValue("box").toString())
        // No screenshot: the frame of the window capture that would have been taken.
        val noShot = run(snap, ContextSettings(useScreen = false), ScreenOutcome.NotTaken)
        assertEquals("[26,2050,946,2140]", noShot.getValue("field").jsonObject.getValue("box").toString())
    }

    @Test
    fun fieldBoxOutsideTheCaptureIsOmitted() {
        val snap = snapshot(window = Rect(984, 0, 1968, 2184), field = field(bounds = Rect(10, 2050, 900, 2140)))
        val got = run(snap, on, ScreenOutcome.Text(screen(984, 2184)))
        assertFalse("box" in got.getValue("field").jsonObject)
    }

    @Test
    fun unknownWindowGivesScreenPixels() {
        val snap = snapshot(window = null)
        assertEquals("[40,2400,940,2490]", run(snap, ContextSettings(useScreen = false), ScreenOutcome.NotTaken)
            .getValue("field").jsonObject.getValue("box").toString())
    }

    @Test
    fun appCategoryAndLabel() {
        val unknown = run(snapshot(pkg = "com.example.unknown", label = "  "), on, ScreenOutcome.Text(screen()))
        assertEquals(json("""{"package":"com.example.unknown"}"""), unknown["app"])
        assertEquals("generic", unknown.getValue("field").jsonObject.getValue("kind").toString().removeSurrounding("\""))
        val gmail = run(snapshot(pkg = "com.google.android.gm", label = "Gmail", field = field(hint = "Текст письма")), on,
            ScreenOutcome.Text(screen()))
        assertEquals(json("""{"package":"com.google.android.gm","label":"Gmail","category":"email"}"""), gmail["app"])
        assertEquals("\"email_body\"", gmail.getValue("field").jsonObject.getValue("kind").toString())
    }

    @Test
    fun inputTypeVariationFeedsTheStyleOverride() {
        val uri = field(inputType = FieldKinds.TYPE_CLASS_TEXT or FieldKinds.TYPE_TEXT_VARIATION_URI, hint = "Search or type URL")
        val got = run(snapshot(pkg = "com.android.chrome", label = "Chrome", field = uri), on, ScreenOutcome.Text(screen()))
        assertEquals(json("""{"kind":"address","hint":"Search or type URL","before_cursor":"","box":[40,2400,940,2490],
            "input_type_variation":"TYPE_TEXT_VARIATION_URI"}"""), got["field"])
        val to = field(inputType = FieldKinds.TYPE_CLASS_TEXT or FieldKinds.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, hint = "Кому")
        val gm = run(snapshot(pkg = "com.google.android.gm", label = "Gmail", field = to), on, ScreenOutcome.Text(screen()))
        assertEquals("\"address\"", gm.getValue("field").jsonObject.getValue("kind").toString())
        assertEquals("\"TYPE_TEXT_VARIATION_EMAIL_ADDRESS\"", gm.getValue("field").jsonObject.getValue("input_type_variation").toString())
    }

    @Test
    fun languageAbsentWhenUnknown() {
        assertFalse("language" in run(snapshot(), on, ScreenOutcome.Text(screen()), lang = null))
        assertFalse("language" in run(snapshot(), on, ScreenOutcome.Text(screen()), lang = "toolongcode"))
    }

    @Test
    fun ocrLinesArePassedThroughInOrder() {
        val lines = listOf(
            OcrLine("12:41", 0.94, listOf(729, 856, 801, 890)),
            OcrLine("Да, и к договору с ООО «Ромашка»", 0.91, listOf(52, 696, 695, 743)),
        )
        val got = run(snapshot(), on, ScreenOutcome.Text(ScreenText(listOf(1080, 2520), lines)))
        assertEquals(json("""{"size":[1080,2520],"lines":[{"text":"12:41","conf":0.94,"box":[729,856,801,890]},
            {"text":"Да, и к договору с ООО «Ромашка»","conf":0.91,"box":[52,696,695,743]}]}"""), got["screen"])
    }

    @Test
    fun schemaLimitsOnOcrOutput() {
        // Far beyond what the OCR pipeline produces; the assembled object must still validate (run() checks it).
        val many = (0 until 450).map { OcrLine("строка $it " + "ы".repeat(if (it == 0) 1500 else 3), 0.9, listOf(0, it, 10, it + 1)) }
        val ctx = a.assemble(snapshot(), a.decide(snapshot(), on), ScreenOutcome.Text(ScreenText(listOf(1080, 2520), many)), "ru")
        assertValid(ctx)
        val lines = ctx.screen!!.lines
        assertEquals(400, lines.size)
        assertEquals(many.take(400).map { it.box }, lines.map { it.box })
        assertEquals(1000, lines[0].text.codePointCount(0, lines[0].text.length))
        assertTrue(lines[0].text.startsWith("строка 0 ы"))
        assertEquals(many[1], lines[1])
    }
}
