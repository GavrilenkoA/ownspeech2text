package app.murmur.infer.asr

import app.murmur.core.engine.AsrSessionOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream

class QnnAndPipeTest {
    private val libs = QnnLibs.inDir("/lib")

    @Test
    fun probeLineOfAWorkingHtp() {
        // the line murmur_qnn_probe.cc printed on the x86_64 HTP simulator of QNN 2.52 (host run, WP-06 report)
        val s = QnnStatus.parse("stage=ok;rc=0;build=v2.52.0.261104115025;soc=30;arch=68", libs)
        assertTrue(s.available)
        assertEquals("2.52", s.version)
        assertEquals(68, s.htpArch)
        assertEquals(30, s.socModel)
        assertEquals(libs, s.libs)
    }

    @Test
    fun probeLineOfAFailedBackend() {
        val s = QnnStatus.parse("stage=backend;rc=1008;build=v2.52.0.261104115025;soc=0;arch=0", libs)
        assertFalse(s.available)
        assertEquals("backend", s.stage)
        assertEquals(1008L, s.rc)
        assertNull(s.htpArch)
        assertNull(QnnStatus.parse("stage=dlopen;rc=0;build=;soc=0;arch=0", libs).buildId)
    }

    /** Returns the bytes in reads of the given sizes, so that samples straddle read boundaries. */
    private class ChoppyStream(private val bytes: ByteArray, private val sizes: IntArray) : InputStream() {
        private var pos = 0
        private var k = 0
        override fun read(): Int = if (pos < bytes.size) bytes[pos++].toInt() and 0xff else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= bytes.size) return -1
            val n = minOf(len, sizes[k++ % sizes.size], bytes.size - pos)
            System.arraycopy(bytes, pos, b, off, n)
            pos += n
            return n
        }
    }

    @Test
    fun pipeReaderReassemblesLittleEndianSamplesAcrossOddReads() {
        val samples = ShortArray(5_001) { (it * 37 - 32_000).toShort() }.also { it[0] = Short.MIN_VALUE; it[1] = Short.MAX_VALUE; it[2] = -1 }
        val bytes = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, s -> bytes[2 * i] = (s.toInt() and 0xff).toByte(); bytes[2 * i + 1] = (s.toInt() shr 8).toByte() }
        val core = AsrSessionCore(AsrSessionOptions(), {})
        val got = ArrayList<Short>()
        val capture = object : SessionProcessor {
            override val unit = app.murmur.core.model.ComputeUnit.CPU
            override fun accept(samples: FloatArray) { samples.forEach { got += (it * 32768f).toInt().toShort() } }
            override fun finish() = FinalText("", null, 0, 0)
            override fun release() {}
        }
        core.attach(capture)
        val reader = PcmPipeReader(ChoppyStream(bytes, intArrayOf(1, 3, 640, 7, 2, 1_001)), core) {}
        reader.start()
        reader.join(10_000)
        core.pump()
        assertEquals(samples.size.toLong(), core.samplesIn)
        assertEquals(samples.toList(), got)
    }
}
