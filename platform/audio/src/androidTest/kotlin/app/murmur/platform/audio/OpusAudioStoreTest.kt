package app.murmur.platform.audio

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.murmur.core.audio.PcmFormat
import app.murmur.core.audio.PcmMath
import app.murmur.core.model.AudioKey
import app.murmur.core.model.SessionId
import app.murmur.core.store.AudioRef
import app.murmur.platform.audio.test.Shell
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/** History audio on the API 36 emulator (ADR-018 had to be proven here before relying on it). */
@RunWith(AndroidJUnit4::class)
class OpusAudioStoreTest {
    private val context = Shell.context

    private fun store(codec: HistoryCodec, name: String) =
        OpusAudioStore(context, codec, File(context.filesDir, "history/$name").also { it.deleteRecursively() })

    /** 30 s synthetic "voice-like" tone: 220 Hz fundamental with two harmonics, slow amplitude envelope. */
    private fun tone30s(): ShortArray {
        val n = PcmFormat.msToSamples(30_000).toInt()
        return ShortArray(n) { i ->
            val t = i.toDouble() / PcmFormat.SAMPLE_RATE
            val env = 0.6 + 0.4 * sin(2 * PI * 0.5 * t)
            val v = 6000 * sin(2 * PI * 220 * t) + 2500 * sin(2 * PI * 440 * t) + 1200 * sin(2 * PI * 660 * t)
            (env * v).toInt().toShort()
        }
    }

    private suspend fun encode(store: OpusAudioStore, key: String, pcm: ShortArray): AudioRef? {
        val w = store.newWriter(key)
        for (i in pcm.indices step PcmFormat.FRAME_SAMPLES) {
            val f = pcm.copyOfRange(i, minOf(pcm.size, i + PcmFormat.FRAME_SAMPLES))
            w.write(f, f.size)
        }
        return w.close()
    }

    private suspend fun roundTrip(codec: HistoryCodec, maxBytes: Long?) {
        val store = store(codec, "rt-${codec.label}")
        val pcm = tone30s()
        val t0 = System.nanoTime()
        val ref = encode(store, AudioKey.of(1_763_500_000_000, SessionId(1)).value, pcm)
        val encMs = (System.nanoTime() - t0) / 1_000_000
        assertNotNull("encoder produced a file", ref)
        ref!!
        val file = File(ref.path)
        val t1 = System.nanoTime()
        val decoded = store.readPcm(ref)
        val decMs = (System.nanoTime() - t1) / 1_000_000
        assertNotNull("decoded", decoded)
        decoded!!
        val t2 = System.nanoTime()
        val raw = OpusAudioStore.decode(file)
        val rawMs = (System.nanoTime() - t2) / 1_000_000
        val snr = PcmMath.snr(pcm, decoded, maxLag = 1_600, edge = 1_600)
        val encoder = codec.encoderName()
        Log.i(MicCaptureTest.TAG, "codec=${ref.codec} encoder=$encoder file=${file.name} bytes=${file.length()} " +
            "kbit_s=${file.length() * 8 / 30_000.0} duration_ms=${ref.durationMs} decoder_rate=${raw.sampleRate} " +
            "decoder_channels=${raw.channels} decoded=${decoded.size}/${pcm.size} snr_db=${"%.2f".format(snr.db)} " +
            "lag=${snr.lag} enc_ms=$encMs dec_ms=$decMs decoder_only_ms=$rawMs")
        assertEquals(codec.label, ref.codec)
        assertEquals(30_000L, ref.durationMs)
        assertEquals(file.length(), ref.bytes)
        maxBytes?.let { assertTrue("${file.length()} bytes <= $it", file.length() <= it) }
        assertTrue("decoded ${decoded.size} >= 99 % of ${pcm.size}", decoded.size >= pcm.size * 99 / 100)
        assertTrue("SNR ${snr.db} dB >= 20 dB", snr.db >= 20.0)
        assertTrue(store.exists(ref))
        store.delete(ref)
        assertFalse(store.exists(ref))
        assertNull(store.readPcm(ref))
    }

    @Test
    fun thisDeviceUsesOpus() {
        // The emulator image must have an Opus encoder; otherwise the store falls back to AAC (reported, not hidden).
        val detected = HistoryCodec.detect()
        Log.i(MicCaptureTest.TAG, "detected_codec=$detected")
        assertEquals(HistoryCodec.OPUS, detected)
    }

    @Test
    fun opus30sToneIsSmallAndDecodesBack() = runBlocking { roundTrip(HistoryCodec.OPUS, maxBytes = 120 * 1024L) }

    @Test
    fun aacFallback30sToneDecodesBack() = runBlocking { roundTrip(HistoryCodec.AAC, maxBytes = 120 * 1024L) }

    @Test
    fun sameSessionDifferentCreationTimesGiveTwoFiles() = runBlocking {
        val store = store(HistoryCodec.OPUS, "two")
        val pcm = tone30s().copyOf(PcmFormat.msToSamples(1_000).toInt())
        // SessionId restarts at 1 with :main (ADR-053): same session, two creation times.
        val a = encode(store, AudioKey.of(1_763_500_000_000, SessionId(1)).value, pcm)!!
        val b = encode(store, AudioKey.of(1_763_600_000_000, SessionId(1)).value, pcm)!!
        assertNotEquals(a.path, b.path)
        assertEquals(setOf(a.path, b.path), store.listAll().map { it.path }.toSet())
        assertTrue(store.listAll().all { it.codec == "opus" && it.durationMs in 900..1100 })
        store.delete(a)
        assertFalse(store.exists(a))
        assertTrue(store.exists(b))
        assertEquals(listOf(b.path), store.listAll().map { it.path })
    }

    @Test
    fun discardAndEmptyWritersLeaveNoFile() = runBlocking {
        val store = store(HistoryCodec.OPUS, "discard")
        val w = store.newWriter("1-1")
        val pcm = ShortArray(PcmFormat.FRAME_SAMPLES) { (it * 50).toShort() }
        repeat(100) { w.write(pcm, pcm.size) }
        w.discard()
        Thread.sleep(500)
        assertNull(store.newWriter("2-2").close()) // nothing written: no file
        assertTrue(store.dir.listFiles().isNullOrEmpty())
    }

    @Test
    fun listAllReportsLeftoverPartsButNotFilesBeingWritten() = runBlocking {
        val store = store(HistoryCodec.OPUS, "parts")
        store.dir.mkdirs()
        val leftover = File(store.dir, "1700000000000-3.ogg.part").also { it.writeBytes(ByteArray(10)) }
        val w = store.newWriter("1700000000001-4")
        val pcm = ShortArray(PcmFormat.FRAME_SAMPLES) { (it * 50).toShort() }
        w.write(pcm, pcm.size)
        Thread.sleep(300) // the encoder thread has created its .part by now
        val listed = store.listAll()
        assertEquals(listOf(leftover.path), listed.map { it.path })
        assertEquals("partial", listed.single().codec)
        assertNotNull(w.close())
        assertEquals(2, store.listAll().size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun keysMustBePlainFileNames() {
        store(HistoryCodec.OPUS, "keys").newWriter("../escape")
    }
}
