package app.murmur.infer.api

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.Pipe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The capture-side ring of the ASR pipe. A real OS pipe (java.nio Pipe, ~64 KB like the Android pipe) sits between
 * the pump and a reader thread, so "the pipe is full" is real backpressure, not a simulation.
 */
class PcmPumpTest {
    /** Reads everything until EOF; [gate] holds the reader (a stalled :asr) until it is counted down. */
    private class Reader(private val input: InputStream, private val gate: CountDownLatch = CountDownLatch(0)) : Thread("reader") {
        val bytes = ByteArrayOutputStream()
        val eof = CountDownLatch(1)
        override fun run() {
            gate.await()
            val buf = ByteArray(4096)
            while (true) {
                val n = try { input.read(buf) } catch (_: IOException) { -1 }
                if (n < 0) break
                bytes.write(buf, 0, n)
            }
            eof.countDown()
        }
    }

    private fun pipe(): Pair<OutputStream, InputStream> {
        val p = Pipe.open()
        return Channels.newOutputStream(p.sink()) to Channels.newInputStream(p.source())
    }

    /** Deterministic non-trivial PCM: a sweep with the sample index mixed in, so any reordering changes the bytes. */
    private fun chunk(index: Int, size: Int = 320): ShortArray =
        ShortArray(size) { i -> (((index * size + i) * 7919) xor (index shl 3)).toShort() }

    private fun le(samples: List<ShortArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for (c in samples) for (s in c) { out.write(s.toInt() and 0xff); out.write((s.toInt() shr 8) and 0xff) }
        return out.toByteArray()
    }

    private fun crc(b: ByteArray) = CRC32().apply { update(b) }.value

    @Test
    fun noLossAndNoBlockingUnderA50HzWriterWithAStallingReader() {
        val (out, input) = pipe()
        val gate = CountDownLatch(1)
        val reader = Reader(input, gate).apply { start() }
        val pump = PcmPump(out)
        val chunks = (0 until 250).map { chunk(it) } // 5 s of 20 ms frames
        var worstNs = 0L
        val start = System.nanoTime()
        chunks.forEachIndexed { i, c ->
            if (i == 100) gate.countDown() // the reader starts 2 s late: the pipe filled after ~1 s, the ring holds the rest
            val t0 = System.nanoTime()
            assertTrue(pump.offer(c))
            worstNs = maxOf(worstNs, System.nanoTime() - t0)
            val next = start + (i + 1) * 20_000_000L
            val sleep = next - System.nanoTime()
            if (sleep > 0) TimeUnit.NANOSECONDS.sleep(sleep)
        }
        pump.close()
        assertTrue(pump.awaitClosed(5_000))
        assertTrue(reader.eof.await(5, TimeUnit.SECONDS))
        val expected = le(chunks)
        assertEquals(expected.size, reader.bytes.size())
        assertEquals(crc(expected), crc(reader.bytes.toByteArray()))
        assertEquals(0L, pump.droppedSamples)
        assertEquals(250L * 320, pump.writtenSamples)
        assertFalse(pump.overflowed)
        // offer() is a copy into the ring: far below one frame even with JIT warm-up on a loaded host.
        assertTrue("worst offer ${worstNs / 1000} us", worstNs < 5_000_000)
    }

    @Test
    fun chunksThatDoNotFitTheRingSpillInOrder() {
        val (out, input) = pipe()
        val gate = CountDownLatch(1)
        val reader = Reader(input, gate).apply { start() }
        val pump = PcmPump(out, ringSamples = 1_024)
        val chunks = (0 until 400).map { chunk(it) } // 128,000 samples: pipe (~32k samples) + ring (1k) overflow into the spill
        for (c in chunks) assertTrue(pump.offer(c))
        assertTrue("pending ${pump.pendingSamples}", pump.pendingSamples > 1_024)
        gate.countDown()
        // Live chunks keep coming while the spill drains: they must stay behind it.
        val live = (400 until 450).map { chunk(it) }
        for (c in live) { assertTrue(pump.offer(c)); Thread.sleep(2) }
        pump.close()
        assertTrue(pump.awaitClosed(5_000))
        assertTrue(reader.eof.await(5, TimeUnit.SECONDS))
        assertArrayEquals(le(chunks + live), reader.bytes.toByteArray())
        assertEquals(0L, pump.droppedSamples)
    }

    @Test
    fun aWholeSessionBufferReplaysInOneOfferFollowedByLiveAudio() {
        val (out, input) = pipe()
        val reader = Reader(input).apply { start() }
        val pump = PcmPump(out)
        val replay = ShortArray(300 * 16_000) { i -> (i * 31 + (i shr 9)).toShort() } // 5 min, 9.6 MB
        assertTrue(pump.offer(replay))
        val live = (0 until 50).map { chunk(it) }
        for (c in live) assertTrue(pump.offer(c))
        pump.close()
        assertTrue(pump.awaitClosed(20_000))
        assertTrue(reader.eof.await(20, TimeUnit.SECONDS))
        val expected = le(listOf(replay) + live)
        assertEquals(expected.size, reader.bytes.size())
        assertEquals(crc(expected), crc(reader.bytes.toByteArray()))
    }

    @Test
    fun overflowDropsNewestCountsAndReportsOnce() {
        val (out, input) = pipe()
        val gate = CountDownLatch(1)
        val reader = Reader(input, gate).apply { start() }
        val overflows = AtomicInteger()
        val pump = PcmPump(out, ringSamples = 2_048, maxPendingSamples = 48_000, onOverflow = { overflows.incrementAndGet() })
        val accepted = mutableListOf<ShortArray>()
        var i = 0
        // The reader is stalled: the pipe takes ~32k samples, then up to 48k more may wait; the rest is dropped.
        while (i < 400) {
            val c = chunk(i++)
            if (pump.offer(c)) accepted += c else break
            if (pump.pendingSamples > 40_000) Thread.sleep(1) // let the writer fill the pipe first
        }
        assertTrue(pump.overflowed)
        assertEquals(1, overflows.get())
        // Once overflowed, everything later is dropped too (no audio with a hole in it reaches the recognizer).
        repeat(10) { assertFalse(pump.offer(chunk(i++))) }
        assertEquals(1, overflows.get())
        assertEquals(11L * 320, pump.droppedSamples)
        assertEquals(accepted.size * 320L, pump.acceptedSamples)
        gate.countDown()
        pump.close()
        assertTrue(pump.awaitClosed(5_000))
        assertTrue(reader.eof.await(5, TimeUnit.SECONDS))
        // What was accepted arrives complete and in order.
        assertArrayEquals(le(accepted), reader.bytes.toByteArray())
    }

    @Test
    fun aClosedReaderEndIsAWriteErrorAndLaterAudioIsDropped() {
        val (out, input) = pipe()
        input.close() // :asr died: EPIPE on the next write
        val error = AtomicReference<IOException>()
        val reported = CountDownLatch(1)
        val pump = PcmPump(out, onWriteError = { error.set(it); reported.countDown() })
        assertTrue(pump.offer(chunk(0)))
        assertTrue(reported.await(2, TimeUnit.SECONDS))
        assertNotNull(error.get())
        assertTrue(pump.awaitClosed(2_000))
        assertFalse(pump.offer(chunk(1)))
    }

    @Test
    fun abortReleasesAWriterBlockedOnAFullPipeWithoutReportingAnError() {
        val (out, input) = pipe()
        val errors = AtomicInteger()
        val pump = PcmPump(out, onWriteError = { errors.incrementAndGet() })
        repeat(200) { pump.offer(chunk(it)) } // nobody reads: the writer blocks once the OS pipe is full
        Thread.sleep(100)
        pump.abort()
        assertTrue(pump.awaitClosed(2_000))
        assertEquals(0, errors.get())
        assertFalse(pump.offer(chunk(0)))
        input.close()
    }

    @Test
    fun closeIsEofAfterEverythingPendingWasWritten() {
        val (out, input) = pipe()
        val reader = Reader(input).apply { start() }
        val pump = PcmPump(out)
        val c = chunk(7, size = 5)
        pump.offer(c)
        pump.close() // immediately after the offer: the writer must still see it
        assertTrue(reader.eof.await(2, TimeUnit.SECONDS))
        assertArrayEquals(le(listOf(c)), reader.bytes.toByteArray())
        assertFalse(pump.offer(c)) // after close
    }

    /** The replay on one thread, then live audio from another (handed over, never at the same time): all in order. */
    @Test
    fun consecutiveProducersOnDifferentThreadsKeepTheOrder() {
        val (out, input) = pipe()
        val reader = Reader(input).apply { start() }
        val pump = PcmPump(out)
        val replay = ShortArray(60 * 16_000) { i -> (i * 13 + (i shr 7)).toShort() }
        val live = (0 until 100).map { chunk(it) }
        val replayer = Thread { assertTrue(pump.offer(replay)) }.apply { start(); join() }
        val capture = Thread { for (c in live) assertTrue(pump.offer(c)) }.apply { start(); join() }
        assertFalse(replayer.isAlive || capture.isAlive)
        pump.close()
        assertTrue(pump.awaitClosed(10_000))
        assertTrue(reader.eof.await(10, TimeUnit.SECONDS))
        assertArrayEquals(le(listOf(replay) + live), reader.bytes.toByteArray())
        assertFalse(pump.concurrentOffer)
        assertEquals(0L, pump.droppedSamples)
    }

    /**
     * Two producers at the same time (a replay racing live capture): the overlap is detected, reported once, and the
     * pump takes nothing more, so the recognizer never gets interleaved audio. The replays are large (each is a
     * multi-millisecond copy into the spill) while the other thread offers without pause: they overlap.
     */
    @Test
    fun overlappingProducersAreDetectedReportedOnceAndStopThePump() {
        val (out, input) = pipe()
        val reader = Reader(input).apply { start() }
        val reports = AtomicInteger()
        val pump = PcmPump(out, maxPendingSamples = Long.MAX_VALUE, onConcurrentOffer = { reports.incrementAndGet() })
        val replay = ShortArray(4 * 16_000 * 60)
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val spinning = CountDownLatch(1)
        val capture = Thread {
            val c = chunk(0)
            spinning.countDown()
            while (!stop.get() && !pump.concurrentOffer) {
                pump.offer(c)
                val t = System.nanoTime()
                while (System.nanoTime() - t < 20_000) Thread.onSpinWait() // ~50k offers/s: bounded spill growth
            }
        }.apply { start() }
        spinning.await()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!pump.concurrentOffer && System.nanoTime() < deadline) pump.offer(replay)
        stop.set(true)
        capture.join()
        assertTrue("no overlap detected in 10 s", pump.concurrentOffer)
        assertEquals(1, reports.get())
        assertFalse(pump.offer(chunk(1)))
        assertTrue(pump.droppedSamples > 0)
        pump.abort()
        assertTrue(pump.awaitClosed(5_000))
        reader.join(5_000)
    }
}
