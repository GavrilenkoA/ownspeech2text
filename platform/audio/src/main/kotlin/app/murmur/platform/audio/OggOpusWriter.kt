package app.murmur.platform.audio

import app.murmur.core.model.ProductName
import java.io.Closeable
import java.io.OutputStream
import kotlin.random.Random

/**
 * Ogg encapsulation of an Opus stream (RFC 7845), written by hand instead of MediaMuxer(MUXER_OUTPUT_OGG): the
 * platform muxer puts every 20 ms packet on its own Ogg page, 27-28 header bytes per ~60-byte packet, which turns a
 * 24 kbit/s stream into a 35.5 kbit/s file (measured on the API 36 emulator: 133 KB for 30 s). Here a page carries up
 * to one second of packets (~0.3 kbit/s of overhead) and the last page's granule position trims the encoder's padding.
 * MediaExtractor reads the result like any other .opus/.ogg file. Pure Kotlin (JVM-tested).
 *
 * A full page is held back until the next packet arrives, so that [finish] always puts the end-of-stream flag and the
 * trimmed granule position on a page that carries packets (an empty EOS page after a full one would carry a granule
 * lower than its predecessor's, which decoders reject or ignore, losing the end trim).
 *
 * @param opusHead the identification header (`OpusHead`, RFC 7845 5.1) the encoder reports as csd-0.
 * @param serial the logical stream serial; RFC 3533 asks for a random one.
 */
class OggOpusWriter(
    private val out: OutputStream,
    opusHead: ByteArray,
    vendor: String = ProductName.NAME,
    private val serial: Int = Random.nextInt(),
    private val packetsPerPage: Int = 50,
) : Closeable {
    /** Pre-skip in 48 kHz samples, from the header. */
    val preSkip: Int
    private var sequence = 0
    /** Granule position at the end of the packets written so far (pending ones included). */
    private var granule = 0L
    /** Granule position at the end of the last page written (0 until the first audio page). */
    private var writtenGranule = 0L
    private val pending = ArrayList<ByteArray>()
    private var finished = false

    init {
        require(opusHead.size >= 19 && String(opusHead, 0, 8, Charsets.US_ASCII) == "OpusHead") { "not an OpusHead" }
        preSkip = (opusHead[10].toInt() and 0xff) or ((opusHead[11].toInt() and 0xff) shl 8)
        page(listOf(opusHead), granule = 0, flags = BOS)
        val v = vendor.toByteArray(Charsets.UTF_8)
        val tags = "OpusTags".toByteArray(Charsets.US_ASCII) + le32(v.size) + v + le32(0)
        page(listOf(tags), granule = 0, flags = 0)
    }

    /** One Opus packet as the encoder produced it (no CODEC_CONFIG buffers). */
    fun writePacket(packet: ByteArray) {
        check(!finished) { "finished" }
        require(packet.isNotEmpty()) { "empty packet" }
        // The page is written only now that a further packet exists: the last page always keeps packets for finish().
        if (pending.size >= packetsPerPage || segmentsOf(pending) > MAX_SEGMENTS - MAX_PACKET_SEGMENTS) flush(0)
        granule += samples48k(packet)
        pending += packet
    }

    /**
     * Writes the last page (end of stream). [inputSamples48k] = the PCM fed to the encoder, in 48 kHz samples: the
     * final granule position becomes preSkip + that, so decoders drop the padding of the last frame.
     */
    fun finish(inputSamples48k: Long) {
        if (finished) return
        // RFC 7845 4.4: the end trim may only drop samples of the last page.
        val end = preSkip + inputSamples48k
        if (end < granule) granule = maxOf(end, writtenGranule)
        // pending is empty only when no packet was ever written: then the EOS page is the (empty) first audio page.
        page(pending.toList(), granule, EOS)
        pending.clear()
        finished = true
        out.flush()
    }

    override fun close() = out.close()

    private fun flush(flags: Int) {
        if (pending.isEmpty()) return
        page(pending.toList(), granule, flags)
        writtenGranule = granule
        pending.clear()
    }

    private fun page(packets: List<ByteArray>, granule: Long, flags: Int) {
        val lacing = ArrayList<Int>()
        for (p in packets) {
            var left = p.size
            while (left >= 255) { lacing += 255; left -= 255 }
            lacing += left
        }
        check(lacing.size <= MAX_SEGMENTS) { "page too large" }
        val bodySize = packets.sumOf { it.size }
        val page = ByteArray(27 + lacing.size + bodySize)
        "OggS".toByteArray(Charsets.US_ASCII).copyInto(page, 0)
        page[4] = 0 // version
        page[5] = flags.toByte()
        for (i in 0 until 8) page[6 + i] = (granule ushr (8 * i)).toByte()
        le32(serial).copyInto(page, 14)
        le32(sequence++).copyInto(page, 18)
        // CRC at 22..25 stays zero while computing.
        page[26] = lacing.size.toByte()
        for (i in lacing.indices) page[27 + i] = lacing[i].toByte()
        var pos = 27 + lacing.size
        for (p in packets) { p.copyInto(page, pos); pos += p.size }
        le32(OggCrc.of(page)).copyInto(page, 22)
        out.write(page)
    }

    companion object {
        private const val BOS = 0x02
        private const val EOS = 0x04
        private const val MAX_SEGMENTS = 255
        /** A 1275-byte Opus packet (the largest) needs 6 lacing values. */
        private const val MAX_PACKET_SEGMENTS = 6

        private fun segmentsOf(packets: List<ByteArray>) = packets.sumOf { it.size / 255 + 1 }

        private fun le32(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())

        /** Duration of one Opus packet in 48 kHz samples, from its TOC byte (RFC 6716 3.1). */
        fun samples48k(packet: ByteArray): Int {
            val toc = packet[0].toInt() and 0xff
            val config = toc ushr 3
            val frame = when {
                config < 12 -> intArrayOf(480, 960, 1920, 2880)[config and 3] // SILK: 10/20/40/60 ms
                config < 16 -> intArrayOf(480, 960)[config and 1] // hybrid: 10/20 ms
                else -> intArrayOf(120, 240, 480, 960)[config and 3] // CELT: 2.5/5/10/20 ms
            }
            val frames = when (toc and 3) {
                0 -> 1
                1, 2 -> 2
                else -> if (packet.size > 1) packet[1].toInt() and 0x3f else 0
            }
            return frame * frames
        }
    }
}

/** The Ogg page checksum: CRC-32, polynomial 0x04C11DB7, not reflected, initial value 0, no final xor. */
internal object OggCrc {
    private val table = IntArray(256) { i ->
        var r = i shl 24
        repeat(8) { r = if (r and 0x80000000.toInt() != 0) (r shl 1) xor 0x04C11DB7 else r shl 1 }
        r
    }

    fun of(data: ByteArray): Int {
        var crc = 0
        for (b in data) crc = (crc shl 8) xor table[((crc ushr 24) xor (b.toInt() and 0xff)) and 0xff]
        return crc
    }
}
