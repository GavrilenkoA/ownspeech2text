package app.murmur.platform.audio

import app.murmur.core.audio.PcmFormat
import app.murmur.core.dictation.CaptureSignal
import app.murmur.core.model.MurmurError
import app.murmur.core.store.AudioRef
import app.murmur.core.store.AudioWriter
import app.murmur.testing.InMemoryAudioStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class CaptureSessionTest {
    private class FakeSource : CaptureSession.Source {
        var halted = 0
        override val finished = CompletableDeferred<Unit>()
        override fun halt() { halted++; finished.complete(Unit) }
    }

    /** Records the calls a writer gets (thread-confined here). */
    private class RecordingWriter(private val inner: AudioWriter) : AudioWriter {
        var samples = 0
        var discarded = 0
        var closed = 0
        override fun write(samples: ShortArray, count: Int) { this.samples += count; inner.write(samples, count) }
        override suspend fun close(): AudioRef? { closed++; return inner.close() }
        override fun discard() { discarded++; inner.discard() }
    }

    private fun tone(n: Int, amp: Int) = ShortArray(n) { (amp * sin(2 * PI * 1000 * it / 16_000)).toInt().toShort() }

    private fun feed(session: CaptureSession, pcm: ShortArray) {
        for (i in pcm.indices step PcmFormat.FRAME_SAMPLES) {
            val f = pcm.copyOfRange(i, minOf(pcm.size, i + PcmFormat.FRAME_SAMPLES))
            session.onFrame(f, f.size)
        }
    }

    @Test
    fun everyFrameReachesBufferWriterMeterAndThePeak() = runTest {
        val store = InMemoryAudioStore()
        val writer = RecordingWriter(store.newWriter("1700000000000-1"))
        val source = FakeSource()
        val s = CaptureSession(writer, source)
        val pcm = tone(16_000, 9000)
        feed(s, pcm)
        assertEquals(16_000L, s.totalSamples)
        assertEquals(16_000, writer.samples)
        assertTrue("levels published at the 30 Hz ticks", s.levels.value[2] > 0.5f)
        assertEquals(PcmMathPeak.of(pcm), s.peakAbs)

        val replay = async { s.chunks.toList() }
        val result = s.stop()
        val chunks = replay.await()
        assertEquals(50, chunks.size) // 1 s = 50 x 20 ms
        assertArrayEquals(pcm, chunks.map { it.samples }.reduce { a, b -> a + b })
        assertEquals(1, source.halted)
        assertEquals(1000L, result.durationMs)
        assertEquals(PcmMathPeak.of(pcm), result.peakAbs)
        assertFalse(result.silenced)
        assertNotNull(result.audio)
        val ref = result.audio!!
        assertEquals("mem://audio/1700000000000-1.ogg", ref.path)
        assertArrayEquals(pcm, store.files[ref.path])
        // Idempotent: a second stop returns the same result and does not close the writer twice.
        assertSame(result, s.stop())
        assertEquals(1, writer.closed)
    }

    @Test
    fun keepRecordingsOffMeansNoWriterAndNoAudio() = runTest {
        val s = CaptureSession(null, FakeSource())
        feed(s, tone(3200, 5000))
        val r = s.stop()
        assertNull(r.audio)
        assertEquals(200L, r.durationMs)
    }

    @Test
    fun cancelDiscardsTheFileAndEndsTheChunks() = runTest {
        val store = InMemoryAudioStore()
        val writer = RecordingWriter(store.newWriter("k"))
        val source = FakeSource()
        val s = CaptureSession(writer, source)
        var ended = 0
        s.doOnEnd { ended++ }
        feed(s, tone(1600, 4000))
        s.cancel()
        s.cancel()
        assertTrue(writer.discarded >= 1)
        assertEquals(0, writer.closed)
        assertTrue(store.files.isEmpty())
        assertEquals(1, ended)
        assertEquals(5, s.chunks.toList().size) // the replay completes with what was captured
        // Frames after the end are dropped everywhere.
        feed(s, tone(320, 4000))
        assertEquals(1600L, s.totalSamples)
        assertNull(s.stop().audio)
    }

    @Test
    fun endCallbacksRunOnlyAfterTheProducerFinished() = runTest {
        val source = object : CaptureSession.Source {
            override val finished = CompletableDeferred<Unit>()
            override fun halt() {} // the thread notices later
        }
        val s = CaptureSession(null, source)
        var ended = 0
        s.doOnEnd { ended++ }
        val stopping = async { s.stop() }
        yield()
        assertEquals(0, ended)
        source.finished.complete(Unit)
        stopping.await()
        assertEquals(1, ended)
    }

    @Test
    fun signalsAreDeduplicatedReplayedAndSilencedIsReported() = runTest {
        val s = CaptureSession(null, FakeSource())
        s.signal(CaptureSignal.CallStarted)
        s.signal(CaptureSignal.CallStarted)
        s.signal(CaptureSignal.Silenced)
        s.signal(CaptureSignal.Lost(MurmurError.MicStartFailed("read:-6")))
        s.signal(CaptureSignal.Lost(MurmurError.MicStartFailed("read:-6")))
        // A collector that attaches late still sees each signal once, in order.
        val seen = s.signals.take(3).toList()
        assertEquals(
            listOf(CaptureSignal.CallStarted, CaptureSignal.Silenced, CaptureSignal.Lost(MurmurError.MicStartFailed("read:-6"))),
            seen,
        )
        val r = s.stop()
        assertTrue(r.silenced)
        s.signal(CaptureSignal.Silenced) // after the end: ignored, no crash
    }

    @Test
    fun levelsStayFlatOnSilence() = runTest {
        val s = CaptureSession(null, FakeSource())
        feed(s, ShortArray(16_000))
        assertArrayEquals(FloatArray(7), s.levels.value, 0f)
        assertEquals(0, s.stop().peakAbs)
    }

    private object PcmMathPeak {
        fun of(p: ShortArray) = app.murmur.core.audio.PcmMath.peakAbs(p)
    }
}

class CallModesTest {
    @Test
    fun callAndCommunicationModesStopADictation() {
        // android.media.AudioManager constants (compile-time ints).
        val normal = 0; val ringtone = 1; val inCall = 2; val inCommunication = 3
        val callScreening = 4; val callRedirect = 5; val communicationRedirect = 6
        assertFalse(CallModes.isCall(normal))
        for (m in listOf(ringtone, inCall, inCommunication, callScreening, callRedirect, communicationRedirect)) {
            assertTrue("mode $m", CallModes.isCall(m))
        }
        assertFalse(CallModes.isCall(-2)) // MODE_CURRENT / MODE_INVALID are not calls
        assertEquals("IN_CALL", CallModes.name(inCall))
        assertEquals("RINGTONE", CallModes.name(ringtone))
        assertEquals("MODE_42", CallModes.name(42))
    }
}
