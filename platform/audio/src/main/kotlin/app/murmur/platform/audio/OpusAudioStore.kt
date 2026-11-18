package app.murmur.platform.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Process
import android.util.Log
import app.murmur.core.audio.PcmFormat
import app.murmur.core.audio.Resampler
import app.murmur.core.store.AudioRef
import app.murmur.core.store.AudioStore
import app.murmur.core.store.AudioWriter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue

/**
 * How history audio is compressed (ADR-018): Opus at 24 kbit/s in Ogg (MediaCodec encoder + [OggOpusWriter]), or,
 * where the device has no Opus encoder, AAC-LC at 24 kbit/s in MP4 (MediaCodec + MediaMuxer).
 */
enum class HistoryCodec(val mime: String, val extension: String, val bitRate: Int, val label: String) {
    OPUS(MediaFormat.MIMETYPE_AUDIO_OPUS, "ogg", 24_000, "opus"),
    AAC(MediaFormat.MIMETYPE_AUDIO_AAC, "m4a", 24_000, "aac");

    fun encoderFormat(): MediaFormat =
        MediaFormat.createAudioFormat(mime, PcmFormat.SAMPLE_RATE, PcmFormat.CHANNELS).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            if (this@HistoryCodec == AAC) setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        }

    /** The platform encoder for this codec at 16 kHz mono, or null (MediaCodecList). */
    fun encoderName(): String? {
        // findEncoderForFormat rejects keys a codec does not advertise: ask with the essentials only.
        val f = MediaFormat.createAudioFormat(mime, PcmFormat.SAMPLE_RATE, PcmFormat.CHANNELS)
            .apply { setInteger(MediaFormat.KEY_BIT_RATE, bitRate) }
        return MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(f)
    }

    companion object {
        /** Opus when the platform has an Opus encoder for 16 kHz mono (API 29+ normally does), else AAC. */
        fun detect(): HistoryCodec = if (OPUS.encoderName() != null) OPUS else AAC

        fun ofExtension(ext: String): HistoryCodec? = entries.firstOrNull { it.extension == ext }
    }
}

/**
 * History audio (ADR-018, ADR-053): every dictation's PCM, exactly as the ASR saw it, compressed while it is recorded
 * into `filesDir/history/audio/<AudioKey>.ogg` (Opus, 24 kbit/s, ~90 KB per 30 s), or `.m4a` (AAC-LC) when the device
 * has no Opus encoder. A file is written as `<key>.<ext>.part` and renamed when the encoder finished, so a file with
 * the final name is always complete. [readPcm] decodes back to 16 kHz mono PCM16 (Retry / Re-polish) with
 * MediaExtractor + MediaCodec. keepRecordings=false is honoured by the capture, which then never asks for a writer.
 */
class OpusAudioStore(
    context: Context,
    val codec: HistoryCodec = HistoryCodec.detect(),
    val dir: File = File(context.filesDir, "history/audio"),
) : AudioStore {
    private val active = HashSet<String>()

    override fun newWriter(key: String): AudioWriter {
        require(KEY.matches(key)) { "audio key must be a plain file name" }
        val target = File(dir, "$key.${codec.extension}")
        synchronized(active) { active += target.path }
        return EncodingWriter(codec, target) { synchronized(active) { active -= target.path } }
    }

    override suspend fun delete(ref: AudioRef) = withContext(Dispatchers.IO) {
        val f = ownedFile(ref.path) ?: return@withContext
        f.delete()
        File(f.path + PART).delete()
        Unit
    }

    override suspend fun readPcm(ref: AudioRef): ShortArray? = withContext(Dispatchers.IO) {
        val f = ownedFile(ref.path)?.takeIf { it.isFile } ?: return@withContext null
        decodeToPcm16k(f)
    }

    override suspend fun exists(ref: AudioRef): Boolean = withContext(Dispatchers.IO) { ownedFile(ref.path)?.isFile == true }

    /** Every stored file (complete, or a `.part` left by a dead process) except the ones being written right now. */
    override suspend fun listAll(): List<AudioRef> = withContext(Dispatchers.IO) {
        val busy = synchronized(active) { active.flatMap { listOf(it, it + PART) } }.toSet()
        (dir.listFiles() ?: emptyArray()).filter { it.isFile && it.path !in busy }.mapNotNull { f ->
            if (f.name.endsWith(PART)) return@mapNotNull AudioRef(f.path, 0, f.length(), "partial")
            val c = HistoryCodec.ofExtension(f.extension) ?: return@mapNotNull null
            AudioRef(f.path, durationMs(f), f.length(), c.label)
        }.sortedBy { it.path }
    }

    /** Only files directly in [dir] are ever read or deleted, whatever path a ref carries. */
    private fun ownedFile(path: String): File? {
        val f = File(path).canonicalFile
        return f.takeIf { it.parentFile == dir.canonicalFile }
    }

    private fun durationMs(f: File): Long = runCatching {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(f.path)
            val fmt = (0 until ex.trackCount).map { ex.getTrackFormat(it) }
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            if (fmt != null && fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) / 1000 else 0L
        } finally {
            ex.release()
        }
    }.getOrDefault(0L)

    /** Decoder output: interleaved PCM16 at the decoder's rate (the Opus decoder always gives 48 kHz). */
    class Decoded(val samples: ShortArray, val sampleRate: Int, val channels: Int, val mime: String)

    companion object {
        private val KEY = Regex("[A-Za-z0-9_-]{1,96}")
        internal const val PART = ".part"
        private const val TAG = "MurmurAudioStore"
        private const val TIMEOUT_US = 10_000L

        /** Decodes any audio file MediaExtractor reads to mono 16 kHz PCM16; null when it cannot be decoded. */
        fun decodeToPcm16k(file: File): ShortArray? = try {
            val d = decode(file)
            Resampler.resample(Resampler.downmix(d.samples, d.channels), d.sampleRate, PcmFormat.SAMPLE_RATE)
        } catch (e: Exception) {
            Log.w(TAG, "decode_failed ${e.javaClass.simpleName}")
            null
        }

        fun decode(file: File): Decoded {
            val ex = MediaExtractor()
            var codec: MediaCodec? = null
            try {
                ex.setDataSource(file.path)
                val track = (0 until ex.trackCount)
                    .firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                    ?: error("no audio track")
                ex.selectTrack(track)
                val inFormat = ex.getTrackFormat(track)
                val mime = inFormat.getString(MediaFormat.KEY_MIME)!!
                val c = MediaCodec.createDecoderByType(mime).also { codec = it }
                c.configure(inFormat, null, null, 0)
                c.start()
                var rate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var channels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
                val out = ShortGrowable()
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                while (true) {
                    // Feed every free input buffer, then drain every ready output; block only when neither moved.
                    var progressed = false
                    while (!inputDone) {
                        val i = c.dequeueInputBuffer(0)
                        if (i < 0) break
                        progressed = true
                        val n = ex.readSampleData(c.getInputBuffer(i)!!, 0)
                        if (n < 0) {
                            c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                    val o = c.dequeueOutputBuffer(info, if (progressed) 0 else TIMEOUT_US)
                    when {
                        o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = c.outputFormat
                            rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) pcmEncoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                        o >= 0 -> {
                            val buf = c.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                            buf.position(info.offset).limit(info.offset + info.size)
                            if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) out.addFloats(buf) else out.addShorts(buf)
                            c.releaseOutputBuffer(o, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        }
                    }
                }
                return Decoded(out.toArray(), rate, channels, mime)
            } finally {
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
                ex.release()
            }
        }
    }
}

/**
 * One history file being encoded. [write] only copies the samples into a queue (the capture thread never blocks);
 * a background-priority encoder thread, started at the first write, owns the MediaCodec and the container writer.
 */
internal class EncodingWriter(
    private val codec: HistoryCodec,
    private val target: File,
    private val onDone: () -> Unit,
) : AudioWriter {
    private sealed interface Item {
        class Pcm(val samples: ShortArray) : Item
        data object End : Item
        data object Abort : Item
    }

    /** The container: Ogg for Opus ([OggOpusWriter]), MP4 for AAC (MediaMuxer). */
    private interface Container {
        fun start(format: MediaFormat)
        fun write(data: ByteBuffer, info: MediaCodec.BufferInfo)
        fun finish(inputSamples16k: Long)
        fun release()
    }

    private class OggContainer(private val file: File) : Container {
        private var fos: FileOutputStream? = null
        private var stream: BufferedOutputStream? = null
        private var ogg: OggOpusWriter? = null
        override fun start(format: MediaFormat) {
            val head = opusHead(format.getByteBuffer("csd-0") ?: error("no OpusHead"))
            val f = FileOutputStream(file).also { fos = it }
            ogg = OggOpusWriter(BufferedOutputStream(f, 64 * 1024).also { stream = it }, head)
        }
        override fun write(data: ByteBuffer, info: MediaCodec.BufferInfo) {
            val packet = ByteArray(info.size)
            data.position(info.offset)
            data.get(packet)
            ogg!!.writePacket(packet)
        }
        override fun finish(inputSamples16k: Long) {
            ogg!!.finish(inputSamples16k * (OPUS_RATE / PcmFormat.SAMPLE_RATE))
            fos!!.fd.sync()
        }
        override fun release() { runCatching { stream?.close() } }

        companion object {
            /** Opus granule positions always count 48 kHz samples (RFC 7845 4). */
            private const val OPUS_RATE = 48_000

            /** csd-0 is the OpusHead itself on AOSP; tolerate a wrapper by locating the magic. */
            fun opusHead(csd: ByteBuffer): ByteArray {
                val all = ByteArray(csd.remaining()).also { csd.duplicate().get(it) }
                val magic = "OpusHead".toByteArray(Charsets.US_ASCII)
                val at = (0..all.size - magic.size).firstOrNull { i -> magic.indices.all { all[i + it] == magic[it] } }
                    ?: error("csd-0 without OpusHead")
                val family = all.getOrElse(at + 18) { 0 }.toInt()
                val channels = all[at + 9].toInt() and 0xff
                val len = if (family == 0) 19 else 21 + channels
                return all.copyOfRange(at, at + len)
            }
        }
    }

    private class Mp4Container(file: File) : Container {
        private val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        private var track = -1
        private var started = false
        override fun start(format: MediaFormat) { track = muxer.addTrack(format); muxer.start(); started = true }
        override fun write(data: ByteBuffer, info: MediaCodec.BufferInfo) {
            data.position(info.offset).limit(info.offset + info.size)
            muxer.writeSampleData(track, data, info)
        }
        override fun finish(inputSamples16k: Long) { muxer.stop(); started = false }
        override fun release() {
            if (started) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
    }

    private val queue = LinkedBlockingQueue<Item>()
    private val result = CompletableDeferred<AudioRef?>()
    private val lock = Any()
    private var thread: Thread? = null
    private var closed = false
    private var samplesWritten = 0L

    override fun write(samples: ShortArray, count: Int) {
        if (count <= 0) return
        synchronized(lock) {
            if (closed) return
            if (thread == null) thread = Thread({ encode() }, "audio-encoder").also { it.start() }
            samplesWritten += count
            queue.put(Item.Pcm(samples.copyOf(count)))
        }
    }

    override suspend fun close(): AudioRef? {
        val started = synchronized(lock) {
            if (!closed) {
                closed = true
                if (thread != null) queue.put(Item.End)
            }
            thread != null
        }
        if (!started) {
            if (result.complete(null)) onDone()
            return null
        }
        return result.await()
    }

    /** Drops everything: no file is left. A no-op after [close] returned. */
    override fun discard() {
        val started = synchronized(lock) {
            closed = true
            if (thread != null && !result.isCompleted) {
                queue.clear()
                queue.put(Item.Abort)
            }
            thread != null
        }
        if (!started && result.complete(null)) onDone()
    }

    private fun encode() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        val part = File(target.path + OpusAudioStore.PART)
        var mc: MediaCodec? = null
        var container: Container? = null
        var ok = false
        try {
            target.parentFile?.mkdirs()
            val c = MediaCodec.createEncoderByType(codec.mime).also { mc = it }
            c.configure(codec.encoderFormat(), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
            val box = (if (codec == HistoryCodec.OPUS) OggContainer(part) else Mp4Container(part)).also { container = it }
            var boxStarted = false
            val info = MediaCodec.BufferInfo()
            var queued = 0L
            var inputEnded = false

            fun drain(untilEos: Boolean) {
                var idle = 0
                while (true) {
                    val o = c.dequeueOutputBuffer(info, if (untilEos) TIMEOUT_US else 0)
                    when {
                        o == MediaCodec.INFO_TRY_AGAIN_LATER ->
                            if (!untilEos) return else if (++idle > EOS_WAIT_TRIES) error("encoder did not finish")
                        o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { box.start(c.outputFormat); boxStarted = true }
                        o >= 0 -> {
                            val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (!config && info.size > 0 && boxStarted) box.write(c.getOutputBuffer(o)!!, info)
                            c.releaseOutputBuffer(o, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                        }
                    }
                }
            }

            fun feed(pcm: ShortArray) {
                var off = 0
                while (off < pcm.size) {
                    val i = c.dequeueInputBuffer(TIMEOUT_US)
                    if (i < 0) { drain(false); continue }
                    val buf = c.getInputBuffer(i)!!.order(ByteOrder.LITTLE_ENDIAN)
                    buf.clear()
                    val n = minOf(pcm.size - off, buf.remaining() / 2)
                    buf.asShortBuffer().put(pcm, off, n)
                    c.queueInputBuffer(i, 0, n * 2, queued * 1_000_000L / PcmFormat.SAMPLE_RATE, 0)
                    queued += n
                    off += n
                    drain(false)
                }
            }

            while (!inputEnded) {
                when (val item = queue.take()) {
                    is Item.Pcm -> feed(item.samples)
                    Item.Abort -> return
                    Item.End -> {
                        var i: Int
                        do { i = c.dequeueInputBuffer(TIMEOUT_US); if (i < 0) drain(false) } while (i < 0)
                        c.queueInputBuffer(i, 0, 0, queued * 1_000_000L / PcmFormat.SAMPLE_RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEnded = true
                    }
                }
            }
            drain(true)
            if (!boxStarted) error("encoder produced no output format")
            box.finish(queued)
            ok = true
        } catch (e: Exception) {
            Log.w(TAG, "encode_failed ${codec.label} ${e.javaClass.simpleName}")
        } finally {
            runCatching { mc?.stop() }
            runCatching { mc?.release() }
            container?.release()
            val written = synchronized(lock) { samplesWritten }
            val ref = if (ok && part.renameTo(target)) {
                AudioRef(target.path, PcmFormat.samplesToMs(written), target.length(), codec.label)
            } else {
                part.delete()
                null
            }
            onDone()
            result.complete(ref)
        }
    }

    private companion object {
        const val TAG = "MurmurAudioStore"
        const val TIMEOUT_US = 10_000L
        /** 5 s of 10 ms polls for the end-of-stream buffer. */
        const val EOS_WAIT_TRIES = 500
    }
}

/** Growable PCM16 array (decoder output of unknown length). */
private class ShortGrowable {
    private var data = ShortArray(1 shl 16)
    private var size = 0

    private fun ensure(extra: Int) {
        if (size + extra > data.size) data = data.copyOf(maxOf(data.size * 2, size + extra))
    }

    fun addShorts(buf: ByteBuffer) {
        val sb = buf.asShortBuffer()
        val n = sb.remaining()
        ensure(n)
        sb.get(data, size, n)
        size += n
    }

    fun addFloats(buf: ByteBuffer) {
        val fb = buf.asFloatBuffer()
        ensure(fb.remaining())
        while (fb.hasRemaining()) data[size++] = (fb.get().coerceIn(-1f, 1f) * 32767f).toInt().toShort()
    }

    fun toArray(): ShortArray = data.copyOf(size)
}
