package app.murmur.testing

import app.murmur.core.audio.PcmChunk
import app.murmur.core.engine.AsrSessionOptions
import app.murmur.core.engine.LlmEvent
import app.murmur.core.engine.LlmRequest
import app.murmur.core.model.MurmurError
import app.murmur.core.model.Outcome
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakesTest {
    @Test
    fun fakeAsrNeedsSpeech() = runTest {
        val asr = FakeAsrEngine(script = "тест")
        val s = asr.startSession(AsrSessionOptions())
        s.accept(PcmChunk(ShortArray(16_000), 0))
        assertEquals(Outcome.Err(MurmurError.NoSpeech), s.finish())
        val s2 = asr.startSession(AsrSessionOptions())
        s2.accept(PcmChunk(syntheticSpeech(), 0))
        assertEquals("тест", (s2.finish() as Outcome.Ok).value.text)
    }

    @Test
    fun fakeLlmIsDeterministic() = runTest {
        val out = FakeLlmEngine().generate(LlmRequest("<transcript>\nпривет мир\n</transcript>", 64)).toList()
        assertEquals("Привет мир.", (out.last() as LlmEvent.Done).text)
    }

    @Test
    fun fakeWorldBuildsDeps() {
        val d = FakeWorld().deps()
        assertTrue(d.prompts.systemPrompt("default.v4").isNotBlank())
    }
}
