package app.murmur.infer.asr

import app.murmur.core.audio.PauseSegmenter
import app.murmur.core.audio.VadModel
import app.murmur.core.engine.AsrEvent
import app.murmur.core.engine.AsrSessionOptions
import app.murmur.core.engine.Transcript
import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.MurmurError
import app.murmur.core.model.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The session logic of the :asr process with a fake VAD and fake recognizers (no native code). */
class SessionCoreTest {
    /** "Speech" = loud frames: the fake VAD answers 0.9 for a frame whose mean |x| > 0.1, else 0.05. */
    private class LoudnessVad : VadModel {
        var resets = 0
        override fun probability(frame: FloatArray) = if (frame.map { abs(it) }.average() > 0.1) 0.9f else 0.05f
        override fun reset() { resets++ }
    }

    /** Decodes a segment to "w<samples>" so that the test can check which audio each decode received. */
    private class CountingDecoder : SegmentDecoder {
        val calls = ArrayList<Int>()
        override fun decode(samples: FloatArray, language: String?): Decoded {
            calls += samples.size
            return Decoded("w${samples.size}", null)
        }
    }

    private val events = ArrayList<AsrEvent>()
    private var now = 0L
    private fun core(options: AsrSessionOptions = AsrSessionOptions()) = AsrSessionCore(options, { events += it }, { now })

    private fun pcm(ms: Int, loud: Boolean): ShortArray = ShortArray(ms * 16) { if (loud) (if (it % 2 == 0) 16_000 else -16_000).toShort() else 0 }

    /** Offers [audio] in 20 ms chunks, as the capture thread writes them. */
    private fun AsrSessionCore.feed(audio: ShortArray) {
        var i = 0
        while (i < audio.size) {
            val n = minOf(320, audio.size - i)
            offer(audio.copyOfRange(i, i + n))
            i += n
        }
    }

    private fun speechPattern(): ShortArray =
        pcm(1_000, true) + pcm(1_000, false) + pcm(1_200, true) + pcm(1_000, false) + pcm(800, true)

    private fun segmenting(vad: VadModel = LoudnessVad(), decoder: SegmentDecoder = CountingDecoder(), language: String? = null) =
        SegmentingProcessor(ComputeUnit.CPU, vad, PauseSegmenter(), decoder, language) { events += it }

    private fun AsrSessionCore.finishNow(): Outcome<Transcript>? {
        var result: Outcome<Transcript>? = null
        requestFinish { result = it }
        return result
    }

    @Test
    fun audioOfferedBeforeTheModelIsLoadedIsProcessedCompletely() {
        val c = core()
        val audio = speechPattern()
        c.feed(audio)                       // the model is still loading: everything waits in the queue
        c.endOfAudio()
        c.pump()
        assertEquals(0L, c.samplesProcessed)
        assertTrue(events.isEmpty())
        c.attach(segmenting())
        assertEquals(audio.size.toLong(), c.samplesIn)
        assertEquals(c.samplesIn, c.samplesProcessed)
        val t = (c.finishNow() as Outcome.Ok).value
        assertEquals(audio.size * 1000L / 16_000, t.audioMs)
        assertEquals(3, t.segments)
        assertEquals(ComputeUnit.CPU, t.unit)
    }

    @Test
    fun segmentsAreCommittedAsTheyCloseAndOnlyTheTailIsDecodedAtTheStop() {
        val c = core()
        val decoder = CountingDecoder()
        c.attach(segmenting(decoder = decoder))
        c.feed(speechPattern())
        c.pump()
        val committedBeforeStop = events.filterIsInstance<AsrEvent.Committed>()
        assertEquals(2, committedBeforeStop.size)
        assertEquals(listOf(0, 1), committedBeforeStop.map { it.index })
        // first segment: pre-roll clamped at 0, 1 s speech, 0.3 s post-roll
        assertEquals(0L, committedBeforeStop[0].startMs)
        assertEquals(1_324L, committedBeforeStop[0].endMs)   // last loud frame ends at 1,024 ms (32 ms frames) + 300
        c.endOfAudio()
        val t = (c.finishNow() as Outcome.Ok).value
        assertEquals(3, decoder.calls.size)                 // two pause cuts + the open tail, no re-decode
        assertEquals(3, t.segments)
        assertEquals(decoder.calls.joinToString(" ") { "w$it" }, t.text)
        val speech = events.filterIsInstance<AsrEvent.Speech>()
        assertEquals(listOf(true, false, true, false, true, false), speech.map { it.speaking })
        assertTrue(t.speechMs in 2_900L..3_100L)            // 1.0 + 1.2 + 0.8 s of loud frames
    }

    @Test
    fun finishWaitsForTheEndOfThePipe() {
        val c = core()
        c.attach(segmenting())
        c.feed(speechPattern())
        var result: Outcome<Transcript>? = null
        now = 1_000
        c.requestFinish { result = it }
        c.pump()
        assertNull(result)                                  // EOF not seen yet: more audio may come
        now = 1_400
        c.endOfAudio()
        now = 1_650
        c.pump()
        val t = (result as Outcome.Ok).value
        assertEquals(250L, t.tailDecodeMs)                  // measured from the later of stop and EOF
    }

    @Test
    fun aFailedLoadAnswersFinishWithTheErrorButAFallbackLoadStillGetsAllAudio() {
        val c = core()
        c.feed(speechPattern())
        c.loadFailed(MurmurError.UnitUnavailable(ComputeUnit.NPU))
        assertEquals(AsrEvent.Failure(MurmurError.UnitUnavailable(ComputeUnit.NPU)), events.single())
        // the executor loads the CPU artifact (ADR-050): the session continues with every buffered sample
        c.attach(segmenting())
        c.endOfAudio()
        val t = (c.finishNow() as Outcome.Ok).value
        assertEquals(speechPattern().size * 1000L / 16_000, t.audioMs)

        val d = core()
        d.feed(pcm(500, true))
        d.loadFailed(MurmurError.EngineLoadFailed(app.murmur.core.model.ModelKind.ASR, "config"))
        assertEquals(Outcome.Err(MurmurError.EngineLoadFailed(app.murmur.core.model.ModelKind.ASR, "config")), d.finishNow())
    }

    @Test
    fun aDecodeErrorFailsTheSession() {
        val c = core()
        c.attach(segmenting(decoder = { _, _ -> throw IllegalStateException("native") }))
        c.feed(speechPattern())
        c.pump()
        assertTrue(events.any { it is AsrEvent.Failure })
        c.endOfAudio()
        assertEquals(Outcome.Err(MurmurError.AsrFailed("decode:IllegalStateException")), c.finishNow())
    }

    @Test
    fun cancelDropsTheSessionWithoutAnAnswer() {
        val c = core()
        val vad = LoudnessVad()
        c.attach(segmenting(vad = vad))
        c.feed(pcm(500, true))
        c.cancel()
        c.endOfAudio()
        assertNull(c.finishNow())
        assertEquals(1, vad.resets)
        assertTrue(c.closed)
    }

    @Test
    fun theLanguageHintReachesTheDecoder() {
        val seen = ArrayList<String?>()
        val c = core(AsrSessionOptions(language = "ru"))
        c.attach(segmenting(decoder = { _, lang -> seen += lang; Decoded("текст", null) }, language = c.language))
        c.feed(speechPattern())
        c.endOfAudio()
        val t = (c.finishNow() as Outcome.Ok).value
        assertEquals(listOf("ru", "ru", "ru"), seen)
        assertEquals("ru", t.language)
        assertEquals("ru", events.filterIsInstance<AsrEvent.Committed>().first().language)
    }

    private class FakeStream : StreamDecoder {
        var fed = 0
        var released = false
        override fun accept(samples: FloatArray): String {
            fed += samples.size
            return "w" + "x".repeat(fed / 16_000)            // the hypothesis grows by one letter per second
        }
        override fun finish() = "final"
        override fun release() { released = true }
    }

    @Test
    fun streamingModelsEmitPartialsAndTheFinalTextAtTheStop() {
        val c = core()
        val s = FakeStream()
        c.attach(StreamingProcessor(ComputeUnit.CPU, s, null, emitPartials = true) { events += it })
        c.feed(pcm(3_000, true))
        c.pump()
        val partials = events.filterIsInstance<AsrEvent.Partial>().map { it.text }
        assertEquals(listOf("w", "wx", "wxx", "wxxx"), partials)
        c.endOfAudio()
        val t = (c.finishNow() as Outcome.Ok).value
        assertEquals("final", t.text)
        assertEquals(1, t.segments)
        assertEquals(0L, t.speechMs)
        assertEquals(48_000, s.fed)
        assertTrue(s.released)
    }

    @Test
    fun partialsCanBeSwitchedOff() {
        val c = core(AsrSessionOptions(emitPartials = false))
        c.attach(StreamingProcessor(ComputeUnit.CPU, FakeStream(), null, c.emitPartials) { events += it })
        c.feed(pcm(2_000, true))
        c.pump()
        assertTrue(events.isEmpty())
    }

    @Test
    fun sampleBufferSlicesAndTrims() {
        val b = SampleBuffer(4)
        b.append(FloatArray(10) { it.toFloat() })
        b.trim(4)
        assertEquals(4L, b.start)
        assertEquals(10L, b.end)
        b.append(floatArrayOf(10f, 11f))
        assertEquals(listOf(5f, 6f, 7f), b.slice(5, 8).toList())
        b.trim(100)
        assertEquals(12L, b.start)
        assertEquals(12L, b.end)
    }
}
