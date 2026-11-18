package app.murmur.core.context

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** The Kotlin ctx v2 type round-trips the prompt goldens' contexts (prompts/examples/ctxv2_*.input.json). */
class CtxV2JsonTest {
    private val root = File(System.getProperty("murmur.repoRoot") ?: ".")

    @Test
    fun goldenContextsRoundTrip() {
        val files = File(root, "prompts/examples").listFiles { f -> f.name.startsWith("ctxv2_") && f.name.endsWith(".input.json") }
        assumeTrue("prompts/examples not found", !files.isNullOrEmpty())
        for (f in files!!) {
            val ctxJson = Json.parseToJsonElement(f.readText()).jsonObject.getValue("context")
            val ctx = CtxV2.json.decodeFromJsonElement(CtxV2.serializer(), ctxJson)
            assertEquals(2, ctx.v)
            val back = CtxV2.json.decodeFromJsonElement(CtxV2.serializer(), CtxV2.json.encodeToJsonElement(CtxV2.serializer(), ctx))
            assertEquals(f.name, ctx, back)
        }
    }

    @Test
    fun passwordAndNumberFieldsHideTheBubble() {
        assertEquals(false, FieldKinds.bubbleAllowed(FieldKinds.TYPE_CLASS_TEXT or FieldKinds.TYPE_TEXT_VARIATION_PASSWORD))
        assertEquals(false, FieldKinds.bubbleAllowed(FieldKinds.TYPE_CLASS_NUMBER))
        assertEquals(true, FieldKinds.bubbleAllowed(FieldKinds.TYPE_CLASS_TEXT))
    }
}
