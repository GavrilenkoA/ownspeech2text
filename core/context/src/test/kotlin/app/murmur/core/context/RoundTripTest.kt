package app.murmur.core.context

import app.murmur.core.context.Fixtures.assertValid
import app.murmur.core.ocr.OcrLine
import app.murmur.core.ocr.Rect
import app.murmur.core.ocr.ScreenText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * For every prompts/examples/ctxv2_*.input.json: a synthetic A11ySnapshot (what accessibility gives in that app) plus
 * the example's OCR lines assemble to the example's `context` exactly (JSON equality). This is the shape the
 * renderer goldens (*.default.v4.txt) are rendered from.
 */
class RoundTripTest {
    private val assembler = DefaultContextAssembler()

    /** Accessibility side of each golden, as :platform:a11y would read it (screen pixels). */
    private fun snapshotFor(example: File, offsetX: Int = 0, offsetY: Int = 0): A11ySnapshot {
        val ctx = Fixtures.exampleContext(example)
        val app = ctx.getValue("app").jsonObject
        val size = ctx.getValue("screen").jsonObject.getValue("size").jsonArray.map { it.jsonPrimitive.int }
        val f = ctx.getValue("field").jsonObject
        val box = f.getValue("box").jsonArray.map { it.jsonPrimitive.int }
        // Both goldens are multi-line text fields with variation NORMAL (Telegram composer, Gmail body); the kind comes
        // from the app category, exactly as on the phone.
        val field = FieldSnapshot(
            inputType = Fixtures.TEXT_MULTILINE,
            imeOptions = 0,
            hint = f["hint"]?.jsonPrimitive?.content,
            beforeCursor = f["before_cursor"]?.jsonPrimitive?.content, // chat_ru: caret position unknown -> absent
            boundsInScreen = Rect(box[0] + offsetX, box[1] + offsetY, box[2] + offsetX, box[3] + offsetY),
            isPassword = false,
            isMultiline = true,
        )
        return A11ySnapshot(
            pkg = app.getValue("package").jsonPrimitive.content,
            label = app["label"]?.jsonPrimitive?.content,
            windowTitle = null, windowId = 1, displayId = 0, field = field, excludeRects = emptyList(), capturedAtMs = 0,
            windowBoundsInScreen = Rect(offsetX, offsetY, offsetX + size[0], offsetY + size[1]),
        )
    }

    private fun ocrOf(example: File): ScreenText {
        val screen = Fixtures.exampleContext(example).getValue("screen")
        return CtxV2.json.decodeFromJsonElement(ScreenText.serializer(), screen)
    }

    private fun assembled(example: File, offsetX: Int = 0, offsetY: Int = 0): JsonObject {
        val snap = snapshotFor(example, offsetX, offsetY)
        val decision = assembler.decide(snap, ContextSettings())
        assertEquals(example.name, PrivacyLevel.FULL, decision.level)
        val lang = Fixtures.exampleContext(example).getValue("language").jsonObject.getValue("asr").jsonPrimitive.content
        return assertValid(assembler.assemble(snap, decision, ScreenOutcome.Text(ocrOf(example)), lang), example.name)
    }

    @Test
    fun goldensAssembleExactly() {
        for (f in Fixtures.examples) assertEquals(f.name, Fixtures.exampleContext(f), assembled(f))
    }

    @Test
    fun goldensAssembleExactlyFromAnOffsetWindow() {
        // The same screens shown in a window that starts at (320, 140) on the display: the window capture has the same
        // pixels, accessibility reports shifted bounds, and field.box must come back in screenshot pixels.
        for (f in Fixtures.examples) assertEquals(f.name, Fixtures.exampleContext(f), assembled(f, offsetX = 320, offsetY = 140))
    }

    @Test
    @Suppress("DEPRECATION") // the deprecated four-argument contract is what this test pins
    fun theFourArgumentContractGivesTheSameObject() {
        for (f in Fixtures.examples) {
            val snap = snapshotFor(f)
            val d = assembler.decide(snap, ContextSettings())
            assertEquals(f.name, assembler.assemble(snap, d, ScreenOutcome.Text(ocrOf(f)), "ru"), assembler.assemble(snap, d, ocrOf(f), "ru"))
        }
    }

    @Test
    fun exampleLinesSurviveUnchanged() {
        for (f in Fixtures.examples) {
            val expected: List<OcrLine> = ocrOf(f).lines
            val got = assembler.assemble(snapshotFor(f), assembler.decide(snapshotFor(f), ContextSettings()), ScreenOutcome.Text(ocrOf(f)), "ru")
            assertEquals(f.name, expected, got.screen?.lines)
        }
    }
}
