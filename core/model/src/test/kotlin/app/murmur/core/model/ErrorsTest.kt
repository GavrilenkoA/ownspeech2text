package app.murmur.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorsTest {
    @Test
    fun codesAreStableAndSerializable() {
        val e: MurmurError = MurmurError.Timeout(Stage.LLM)
        assertEquals("timeout.llm", e.code)
        val json = Json.encodeToString(MurmurError.serializer(), e)
        assertEquals(e, Json.decodeFromString(MurmurError.serializer(), json))
    }

    @Test
    fun productNameIsGenerated() {
        assertTrue(ProductName.NAME.isNotBlank())
    }

    @Test
    fun languageRejectsUppercase() {
        assertTrue(runCatching { Language("RU") }.isFailure)
    }
}
