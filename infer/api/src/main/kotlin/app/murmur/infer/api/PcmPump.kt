package app.murmur.infer.api

import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

/**
 * Carries PCM16 from the capture thread to an ASR session's pipe without ever blocking the capture thread
 * (ARCHITECTURE.md 4.3 "PCM path", 14).
 *
 * - [offer] copies the samples into a single-producer / single-consumer lock-free ring and returns: no lock, no
 *   allocation, no system call in the steady state.
 * - A writer thread drains the ring and writes little-endian PCM16 to [out]; a full pipe blocks only that thread.
 * - Overflow policy: a chunk that does not fit the ring (the reader stalled, or a whole session buffer is replayed
 *   in one call) is copied to a lock-free spill queue that the writer drains after the ring, in order, so nothing
 *   is lost. Only when more than [maxPendingSamples] are waiting (longer than any dictation) is the chunk dropped;
 *   from then on every chunk is dropped and counted, [overflowed] is true and [onOverflow] runs once, so the session
 *   fails visibly instead of feeding the recognizer audio with a hole in it.
 * - [close] writes everything still pending, then closes [out] (EOF for the reader); [abort] closes at once.
 *
 * One producer at a time (the capture thread, or the executor replaying a buffer before capture continues).
 */
class PcmPump(
    private val out: OutputStream,
    ringSamples: Int = DEFAULT_RING_SAMPLES,
    private val maxPendingSamples: Long = DEFAULT_MAX_PENDING_SAMPLES,
    private val onOverflow: () -> Unit = {},
    private val onWriteError: (IOException) -> Unit = {},
    threadName: String = "murmur-pcm-writer",
) {
    private val capacity: Int = Integer.highestOneBit((ringSamples - 1).coerceAtLeast(1)) shl 1
    private val mask = capacity - 1
    private val ring = ShortArray(capacity)

    /** Total samples put into the ring (written by the producer only). */
    @Volatile private var head = 0L
    /** Total samples taken out of the ring (written by the writer only). */
    @Volatile private var tail = 0L
    private val spill = ConcurrentLinkedQueue<ShortArray>()
    private val spilledSamples = AtomicLong()
    /** Producer-only: while true, new chunks go to [spill] so that they stay behind the spilled ones. */
    private var spilling = false

    @Volatile private var closing = false
    @Volatile private var aborted = false
    @Volatile private var failed = false
    private val done = CountDownLatch(1)

    @Volatile var overflowed = false
        private set
    @Volatile var acceptedSamples = 0L
        private set
    @Volatile var droppedSamples = 0L
        private set
    @Volatile var writtenSamples = 0L
        private set

    /** Samples accepted but not yet written to [out]. */
    val pendingSamples: Long get() = (head - tail) + spilledSamples.get()

    private val writer = Thread(::drain, threadName).apply { isDaemon = true }

    init {
        require(ringSamples > 0 && maxPendingSamples > 0)
        writer.start()
    }

    /**
     * Never blocks. Returns false when the samples were dropped: after [close]/[abort], after a write error (the
     * reader is gone), or by the overflow policy.
     */
    fun offer(samples: ShortArray, offset: Int = 0, length: Int = samples.size - offset): Boolean {
        if (length <= 0) return true
        if (closing || failed || overflowed) {
            droppedSamples += length
            return false
        }
        if (pendingSamples + length > maxPendingSamples) {
            overflowed = true
            droppedSamples += length
            onOverflow()
            return false
        }
        if (spilling && spill.isEmpty()) spilling = false
        val h = head
        if (!spilling && length <= capacity - (h - tail)) {
            val start = (h and mask.toLong()).toInt()
            val first = minOf(length, capacity - start)
            System.arraycopy(samples, offset, ring, start, first)
            if (first < length) System.arraycopy(samples, offset + first, ring, 0, length - first)
            head = h + length
        } else {
            spilling = true
            spilledSamples.addAndGet(length.toLong())
            spill.add(samples.copyOfRange(offset, offset + length))
        }
        acceptedSamples += length
        return true
    }

    /** No more audio: the writer writes what is pending, then closes the stream (EOF). Returns at once. */
    fun close() {
        closing = true
        LockSupport.unpark(writer)
    }

    /** Stop now: pending audio is discarded and the stream closed (a writer blocked on a full pipe is released). */
    fun abort() {
        aborted = true
        closing = true
        runCatching { out.close() }
        LockSupport.unpark(writer)
    }

    /** Waits until the writer has closed the stream; true if it did within [timeoutMs]. */
    fun awaitClosed(timeoutMs: Long): Boolean = done.await(timeoutMs, TimeUnit.MILLISECONDS)

    private fun drain() {
        val bytes = ByteArray(BLOCK_SAMPLES * 2)
        try {
            while (!aborted) {
                // Read before the data: an offer() that precedes close() is then always seen below.
                val closingNow = closing
                val t = tail
                val h = head
                if (h != t) {
                    val n = minOf(h - t, BLOCK_SAMPLES.toLong()).toInt()
                    val start = (t and mask.toLong()).toInt()
                    for (i in 0 until n) {
                        val s = ring[(start + i) and mask].toInt()
                        bytes[2 * i] = s.toByte()
                        bytes[2 * i + 1] = (s shr 8).toByte()
                    }
                    out.write(bytes, 0, 2 * n)
                    tail = t + n
                    writtenSamples += n
                    continue
                }
                val chunk = spill.peek()
                if (chunk != null) {
                    var off = 0
                    while (off < chunk.size && !aborted) {
                        val n = minOf(chunk.size - off, BLOCK_SAMPLES)
                        for (i in 0 until n) {
                            val s = chunk[off + i].toInt()
                            bytes[2 * i] = s.toByte()
                            bytes[2 * i + 1] = (s shr 8).toByte()
                        }
                        out.write(bytes, 0, 2 * n)
                        off += n
                        writtenSamples += n
                    }
                    // Removed only once written: the producer leaves spill mode when the queue is empty, and the ring
                    // data it then adds must come after this chunk.
                    spill.poll()
                    spilledSamples.addAndGet(-chunk.size.toLong())
                    continue
                }
                if (closingNow) break
                LockSupport.parkNanos(IDLE_PARK_NS)
            }
            if (!aborted) out.flush()
        } catch (e: IOException) {
            if (!aborted) {
                failed = true
                onWriteError(e)
            }
        } finally {
            runCatching { out.close() }
            done.countDown()
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        /** 16.4 s of audio in the lock-free ring (512 KB). */
        const val DEFAULT_RING_SAMPLES = 1 shl 18
        /** 6 min of audio: more than the 5 min dictation cap plus a full replay of it. */
        const val DEFAULT_MAX_PENDING_SAMPLES = 360L * SAMPLE_RATE
        private const val BLOCK_SAMPLES = 4_096
        /** The writer's wait when the ring is empty: half a 20 ms frame. */
        private const val IDLE_PARK_NS = 10_000_000L
    }
}
