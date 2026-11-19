package app.murmur.core.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Kotlin ctx v2 type round-trips the prompt goldens' contexts (prompts/examples/ctxv2_*.input.json). */
class CtxV2JsonTest {
    @Test
    fun goldenContextsRoundTrip() {
        for (f in Fixtures.examples) {
            val ctxJson = Fixtures.exampleContext(f)
            val ctx = CtxV2.json.decodeFromJsonElement(CtxV2.serializer(), ctxJson)
            assertEquals(2, ctx.v)
            assertEquals(f.name, ctxJson, Fixtures.encode(ctx))
            assertEquals(f.name, ctx, CtxV2.json.decodeFromString(CtxV2.serializer(), CtxV2.json.encodeToString(CtxV2.serializer(), ctx)))
        }
    }

    @Test
    fun privacyReasonNullIsWrittenOtherNullsAreAbsent() {
        val s = CtxV2.json.encodeToString(CtxV2.serializer(), CtxV2(app = AppInfo("p"), field = FieldInfo(kind = "message"), privacy = Privacy()))
        assertEquals("""{"v":2,"app":{"package":"p"},"field":{"kind":"message"},"privacy":{"level":"full","reason":null}}""", s)
        val withReason = CtxV2.json.encodeToString(CtxV2.serializer(), CtxV2(app = AppInfo("p"), privacy = Privacy(PrivacyLevel.APP_ONLY, PrivacyReason.USER_SETTING)))
        assertEquals("""{"v":2,"app":{"package":"p"},"privacy":{"level":"app_only","reason":"user_setting"}}""", withReason)
        assertFalse("privacy" in CtxV2.json.encodeToString(CtxV2.serializer(), CtxV2(app = AppInfo("p"))))
    }

    @Test
    fun privacyDecodesAbsentReasonAndRejectsUnknownKeys() {
        assertEquals(Privacy(PrivacyLevel.NONE, null), CtxV2.json.decodeFromString(Privacy.serializer(), """{"level":"none"}"""))
        assertTrue(runCatching { CtxV2.json.decodeFromString(Privacy.serializer(), """{"level":"full","x":1}""") }.isFailure)
    }

    @Test
    fun noScreenLevelMatchesRenderPy() {
        // prompts/render.py NO_SCREEN_LEVEL (+ user_setting -> app_only: the only "off" the settings offer).
        val renderPy = Fixtures.repoFile("prompts/render.py").readText()
        val table = Regex("""NO_SCREEN_LEVEL = \{([^}]*)\}""").find(renderPy)?.groupValues?.get(1) ?: error("NO_SCREEN_LEVEL not found")
        val pairs = Regex(""""(\w+)":\s*"(\w+)"""").findAll(table).associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals(setOf("secure_window", "denylisted_app", "password_field", "screenshot_error", "ocr_empty"), pairs.keys)
        for ((reason, level) in pairs) assertEquals(reason, level, PrivacyReason.noScreenLevel(reason))
        assertEquals(PrivacyLevel.APP_ONLY, PrivacyReason.noScreenLevel(PrivacyReason.USER_SETTING))
    }

    @Test
    fun passwordAndNumberFieldsHideTheBubble() {
        assertEquals(false, FieldKinds.bubbleAllowed(FieldKinds.TYPE_CLASS_TEXT or FieldKinds.TYPE_TEXT_VARIATION_PASSWORD))
        assertEquals(false, FieldKinds.bubbleAllowed(FieldKinds.TYPE_CLASS_NUMBER))
        assertEquals(false, FieldKinds.bubbleAllowed(FieldKinds.TYPE_CLASS_PHONE))
        assertEquals(true, FieldKinds.bubbleAllowed(FieldKinds.TYPE_CLASS_TEXT))
        assertEquals(true, FieldKinds.bubbleAllowed(FieldKinds.TYPE_CLASS_TEXT or FieldKinds.TYPE_TEXT_VARIATION_URI))
    }

    @Test
    fun fieldKindsCoverTheRendererVocabulary() {
        val t = FieldKinds.TYPE_CLASS_TEXT
        assertEquals("email_subject", FieldKinds.ctxKind(t or FieldKinds.TYPE_TEXT_VARIATION_EMAIL_SUBJECT, 0, "email"))
        assertEquals("address", FieldKinds.ctxKind(t or FieldKinds.TYPE_TEXT_VARIATION_URI, 0, "search"))
        assertEquals("address", FieldKinds.ctxKind(t or FieldKinds.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS, 0, "email"))
        assertEquals("search", FieldKinds.ctxKind(t, FieldKinds.IME_ACTION_SEARCH, "messaging"))
        assertEquals("search", FieldKinds.ctxKind(t or FieldKinds.TYPE_TEXT_VARIATION_FILTER, 0, null))
        assertEquals("message", FieldKinds.ctxKind(t, FieldKinds.IME_ACTION_SEND, null))
        assertEquals("message", FieldKinds.ctxKind(t or FieldKinds.TYPE_TEXT_VARIATION_SHORT_MESSAGE, 0, null))
        assertEquals("email_body", FieldKinds.ctxKind(t or FieldKinds.TYPE_TEXT_VARIATION_LONG_MESSAGE, 0, "email"))
        assertEquals("message", FieldKinds.ctxKind(t or FieldKinds.TYPE_TEXT_VARIATION_LONG_MESSAGE, 0, "work_chat"))
        assertEquals("message", FieldKinds.ctxKind(t, 0, "work_chat"))
        assertEquals("note", FieldKinds.ctxKind(t or FieldKinds.TYPE_TEXT_FLAG_MULTI_LINE, 0, "notes"))
        assertEquals("code", FieldKinds.ctxKind(t, 0, "code"))
        assertEquals("generic", FieldKinds.ctxKind(t, 0, "social"))
        assertEquals("generic", FieldKinds.ctxKind(FieldKinds.TYPE_CLASS_DATETIME, 0, "messaging"))
        // Every kind is a prompts/render.py FIELD_PHRASES key.
        val renderPy = Fixtures.repoFile("prompts/render.py").readText()
        val phrases = Regex("""FIELD_PHRASES = \{([^}]*)\}""").find(renderPy)!!.groupValues[1]
        val keys = Regex(""""(\w+)":""").findAll(phrases).map { it.groupValues[1] }.toSet()
        for (k in listOf("message", "email_body", "email_subject", "search", "address", "note", "code", "generic")) assertTrue(k, k in keys)
    }

    @Test
    fun variationNamesAreTheToneOverrideKeys() {
        val t = FieldKinds.TYPE_CLASS_TEXT
        assertEquals(null, FieldKinds.variationName(t))
        assertEquals(null, FieldKinds.variationName(FieldKinds.TYPE_CLASS_NUMBER or FieldKinds.TYPE_NUMBER_VARIATION_PASSWORD))
        val tones = Fixtures.repoFile("prompts/tones/tones.v1.json").readText()
        for (v in listOf(FieldKinds.TYPE_TEXT_VARIATION_URI, FieldKinds.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            FieldKinds.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS, FieldKinds.TYPE_TEXT_VARIATION_EMAIL_SUBJECT)) {
            val name = FieldKinds.variationName(t or v)!!
            assertTrue(name, tones.contains("\"$name\""))
        }
    }
}
