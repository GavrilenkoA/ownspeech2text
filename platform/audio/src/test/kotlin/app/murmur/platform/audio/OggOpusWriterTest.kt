package app.murmur.platform.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class OggOpusWriterTest {
    private class Page(val flags: Int, val granule: Long, val serial: Int, val seq: Int, val packets: List<ByteArray>, val continued: Boolean)

    /** Minimal independent Ogg reader: checks capture pattern and CRC of every page, reassembles packets. */
    private fun parse(bytes: ByteArray): List<Page> {
        val pages = ArrayList<Page>()
        var pos = 0
        while (pos < bytes.size) {
            assertEquals("OggS", String(bytes, pos, 4, Charsets.US_ASCII))
            val b = ByteBuffer.wrap(bytes, pos, bytes.size - pos).order(ByteOrder.LITTLE_ENDIAN)
            val flags = bytes[pos + 5].toInt()
            val granule = b.getLong(pos + 6)
            val serial = b.getInt(pos + 14)
            val seq = b.getInt(pos + 18)
            val crc = b.getInt(pos + 22)
            val nSeg = bytes[pos + 26].toInt() and 0xff
            val lacing = (0 until nSeg).map { bytes[pos + 27 + it].toInt() and 0xff }
            val len = 27 + nSeg + lacing.sum()
            val page = bytes.copyOfRange(pos, pos + len)
            for (i in 22..25) page[i] = 0
            assertEquals("crc of page $seq", OggCrc.of(page), crc)
            val packets = ArrayList<ByteArray>()
            var body = pos + 27 + nSeg
            var cur = ByteArrayOutputStream()
            for (l in lacing) {
                cur.write(bytes, body, l); body += l
                if (l < 255) { packets += cur.toByteArray(); cur = ByteArrayOutputStream() }
            }
            pages += Page(flags, granule, serial, seq, packets, cur.size() > 0)
            pos += len
        }
        return pages
    }

    private fun opusHead(preSkip: Int = 312): ByteArray =
        ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).put("OpusHead".toByteArray()).put(1).put(1)
            .putShort(preSkip.toShort()).putInt(16_000).putShort(0).put(0).array()

    /** A CELT 20 ms single-frame packet: TOC config 31 (FB 20 ms), code 0. */
    private fun packet(size: Int, fill: Int) = ByteArray(size) { if (it == 0) (31 shl 3).toByte() else fill.toByte() }

    @Test
    fun crcIsTheOggVariant() {
        // CRC-32 poly 0x04C11DB7, init 0, no reflection, no final xor: check value of "123456789" = ~0x765E7680
        // (the catalogued CRC-32/POSIX check without its final inversion).
        assertEquals(0x89A1897F.toInt(), OggCrc.of("123456789".toByteArray()))
    }

    @Test
    fun headersThenOneSecondPagesWithGranulesAndAnEndTrim() {
        val out = ByteArrayOutputStream()
        val w = OggOpusWriter(out, opusHead(), packetsPerPage = 50)
        assertEquals(312, w.preSkip)
        val input = (0 until 120).map { packet(40 + it % 7, it) }
        input.forEach(w::writePacket)
        // 120 packets x 960 = 115200 samples at 48 kHz; the PCM fed was 2.39 s = 114720 samples.
        w.finish(inputSamples48k = 114_720)
        val pages = parse(out.toByteArray())

        assertEquals(2 + 3, pages.size) // OpusHead, OpusTags, 50 + 50 + 20 packets
        assertEquals(0x02, pages[0].flags) // BOS
        assertArrayEquals(opusHead(), pages[0].packets.single())
        assertEquals("OpusTags", String(pages[1].packets.single(), 0, 8, Charsets.US_ASCII))
        assertEquals(0L, pages[0].granule)
        assertEquals(0L, pages[1].granule)
        assertEquals(listOf(0, 1, 2, 3, 4), pages.map { it.seq })
        assertTrue(pages.all { it.serial == pages[0].serial && !it.continued })
        assertEquals(50 * 960L, pages[2].granule)
        assertEquals(100 * 960L, pages[3].granule)
        assertEquals(0x04, pages[4].flags) // EOS
        assertEquals(312 + 114_720L, pages[4].granule) // trims the padding of the last frame
        val audio = pages.drop(2).flatMap { it.packets }
        assertEquals(input.size, audio.size)
        input.indices.forEach { assertArrayEquals(input[it], audio[it]) }
    }

    @Test
    fun largePacketsAreLacedAcrossSegments() {
        val out = ByteArrayOutputStream()
        val w = OggOpusWriter(out, opusHead())
        val big = packet(600, 9) // 255 + 255 + 90
        val exact = packet(510, 3) // 255 + 255 + 0
        w.writePacket(big); w.writePacket(exact)
        w.finish(2 * 960L)
        val audio = parse(out.toByteArray()).drop(2).flatMap { it.packets }
        assertArrayEquals(big, audio[0])
        assertArrayEquals(exact, audio[1])
    }

    @Test
    fun packetDurationFromTheTocByte() {
        fun toc(config: Int, code: Int, vararg more: Int) = byteArrayOf(((config shl 3) or code).toByte(), *more.map { it.toByte() }.toByteArray())
        assertEquals(960, OggOpusWriter.samples48k(toc(1, 0))) // SILK NB 20 ms
        assertEquals(2880, OggOpusWriter.samples48k(toc(3, 0))) // SILK 60 ms
        assertEquals(480, OggOpusWriter.samples48k(toc(12, 0))) // hybrid 10 ms
        assertEquals(960, OggOpusWriter.samples48k(toc(15, 0))) // hybrid 20 ms
        assertEquals(120, OggOpusWriter.samples48k(toc(16, 0))) // CELT 2.5 ms
        assertEquals(1920, OggOpusWriter.samples48k(toc(31, 1))) // two 20 ms frames
        assertEquals(3 * 960, OggOpusWriter.samples48k(toc(31, 3, 3))) // code 3: frame count in byte 1
    }
}
