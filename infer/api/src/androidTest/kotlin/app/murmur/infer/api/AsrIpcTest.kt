package app.murmur.infer.api

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.murmur.core.audio.PcmChunk
import app.murmur.core.engine.AsrEvent
import app.murmur.core.engine.AsrSessionOptions
import app.murmur.core.engine.HostState
import app.murmur.core.model.InferProcess
import app.murmur.core.model.ModelKind
import app.murmur.core.model.MurmurError
import app.murmur.core.model.Outcome
import app.murmur.core.model.Stage
import app.murmur.core.ocr.Rect
import app.murmur.core.ocr.ScreenImage
import app.murmur.infer.api.Ipc.asrProcess
import app.murmur.infer.api.Ipc.ctx
import app.murmur.infer.api.Ipc.pidof
import app.murmur.infer.api.Ipc.report
import app.murmur.infer.api.Ipc.spec
import app.murmur.infer.api.Ipc.waitUntil
import app.murmur.infer.api.fake.FakeControl
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.zip.CRC32
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** :main <-> :asr over a real process boundary, against [app.murmur.infer.api.fake.FakeAsrService] in :fakeasr. */
@RunWith(AndroidJUnit4::class)
class AsrIpcTest {
    private val clients = mutableListOf<AsrClient>()

    private fun client(timeouts: BinderTimeouts = BinderTimeouts(), bind: () -> android.content.Context = { ctx }) =
        Ipc.asr({ timeouts }, bind).also { clients += it }

    @Before
    fun setUp() {
        FakeControl.clear(ctx)
        Ipc.killFakes()
    }

    @After
    fun tearDown() = runBlocking {
        clients.forEach { it.shutdown() }
        FakeControl.clear(ctx)
        Ipc.killFakes()
    }

    @Test
    fun bindsOnDemandAndShutdownRemovesTheProcessWithin2s() = runBlocking {
        val c = client()
        assertNull("nothing runs before the first use", pidof(asrProcess))
        assertEquals(HostState.Down, c.state.value)
        assertTrue(c.connect())
        val up = c.state.value as HostState.Up
        assertEquals(pidof(asrProcess), up.pid)
        val t0 = SystemClock.elapsedRealtime()
        c.shutdown()
        val shutdownMs = SystemClock.elapsedRealtime() - t0
        val goneAfter = waitUntil(2_000) { pidof(asrProcess) == null }
        assertNotNull("pid still there 2 s after shutdown()", goneAfter)
        report("asr.shutdown_ms", shutdownMs)
        assertTrue("shutdown took $shutdownMs ms", shutdownMs + goneAfter!! <= 2_000)
        assertEquals(HostState.Down, c.state.value)
    }

    @Test
    fun bindContextIsReadAgainAtEveryBindAndUnboundOnTheSameContext() = runBlocking {
        val first = CountingContext(ctx)
        val second = CountingContext(ctx)
        var calls = 0
        val c = client(bind = { calls++; if (calls == 1) first else second })
        assertTrue(c.connect())
        val pid1 = (c.state.value as HostState.Up).pid
        assertTrue(c.connect()) // already connected: no new bind, no new read
        assertEquals(1, calls)
        c.shutdown()
        assertEquals(1, first.binds.get())
        assertEquals(1, first.unbinds.get())
        assertTrue(c.connect())
        val pid2 = (c.state.value as HostState.Up).pid
        assertEquals(2, calls)
        assertEquals(1, second.binds.get())
        assertNotEquals(pid1, pid2)
        c.shutdown()
        assertEquals(1, second.unbinds.get())
    }

    @Test
    fun idleExitIsRearmedByEveryUseAndNeverFiresDuringASession() = runBlocking {
        val c = client()
        assertTrue(c.connect())
        assertTrue(c.load(spec(ModelKind.ASR), spec(ModelKind.VAD)) is Outcome.Ok)
        c.scheduleIdleExit(1_500)
        Thread.sleep(1_000)
        c.probe() // a use: the 1.5 s start again from here
        Thread.sleep(1_000)
        assertNotNull("exited although used 1 s ago", pidof(asrProcess))
        val session = c.startSession(AsrSessionOptions())
        Thread.sleep(2_500) // longer than the idle time: an open session holds the process
        assertNotNull("idle exit fired during a session", pidof(asrProcess))
        session.accept(PcmChunk(ShortArray(3_200), 0))
        assertTrue(session.finish() is Outcome.Ok)
        val t0 = SystemClock.elapsedRealtime()
        val gone = waitUntil(1_500 + 2_000 + 1_000) { pidof(asrProcess) == null }
        assertNotNull("no idle exit after the session", gone)
        report("asr.idle_exit_after_finish_ms", SystemClock.elapsedRealtime() - t0)
        assertTrue(waitUntil(1_000) { c.state.value == HostState.Down } != null)
    }

    @Test
    fun tenSecondsOfPcmArriveByteIdentical() = runBlocking {
        val c = client()
        assertTrue(c.load(spec(ModelKind.ASR), null) is Outcome.Ok)
        val session = c.startSession(AsrSessionOptions(language = "ru"))
        val events = Collections.synchronizedList(mutableListOf<AsrEvent>())
        val collector = launch(Dispatchers.Default) { session.events.collect { events += it } }
        val crc = CRC32()
        val frame = ByteBuffer.allocate(640).order(ByteOrder.LITTLE_ENDIAN)
        val acceptNs = LongArray(500)
        val start = SystemClock.elapsedRealtimeNanos()
        for (i in 0 until 500) { // 10 s of 20 ms frames at the capture rate (50 Hz)
            val samples = ShortArray(320) { j -> (((i * 320 + j) * 7919) xor (i shl 4)).toShort() }
            frame.clear(); samples.forEach { frame.putShort(it) }; crc.update(frame.array(), 0, 640)
            val t0 = System.nanoTime()
            session.accept(PcmChunk(samples, i * 320L))
            acceptNs[i] = System.nanoTime() - t0
            val sleepNs = start + (i + 1) * 20_000_000L - SystemClock.elapsedRealtimeNanos()
            if (sleepNs > 0) Thread.sleep(sleepNs / 1_000_000, (sleepNs % 1_000_000).toInt())
        }
        val r = session.finish()
        collector.join()
        assertTrue("$r", r is Outcome.Ok)
        val expected = "bytes=320000 crc32=%08x".format(crc.value)
        assertEquals(expected, (r as Outcome.Ok).value.text)
        report("asr.pcm_10s", expected)
        // accept() only copies into the ring; the wall clock also contains preemption of the emulator's vCPUs.
        val sorted = acceptNs.sorted()
        val p99 = sorted[(sorted.size * 99) / 100]
        report("asr.accept_us_p50_p99_max", "${sorted[sorted.size / 2] / 1000} ${p99 / 1000} ${sorted.last() / 1000} (max at frame ${acceptNs.indexOf(sorted.last())})")
        assertTrue("accept() p99 ${p99 / 1000} us", p99 < 2_000_000)
        val committed = events.filterIsInstance<AsrEvent.Committed>()
        assertTrue("committed ${committed.size}", committed.size >= 9)
        assertEquals((0 until committed.size).toList(), committed.map { it.index })
    }

    @Test
    fun a17MbFrameCrossesThroughReadOnlySharedMemoryInUnder50ms() = runBlocking {
        val c = client()
        assertTrue(c.loadOcr(spec(ModelKind.OCR_DET), spec(ModelKind.OCR_REC)) is Outcome.Ok)
        val w = 1968
        val h = 2184 // the inner display: 17,192,448 bytes
        val argb = IntArray(w * h) { i -> (i * -1640531535) or 0xff000000.toInt() }
        val image = ScreenImage(w, h, argb, listOf(Rect(0, 0, w, 63)))
        assertTrue(c.recognize(image) is Outcome.Ok) // warm-up: first mapping, JIT
        val svc = mutableListOf<Double>()
        val times = (1..7).map {
            val t0 = System.nanoTime()
            val r = c.recognize(image)
            val ms = (System.nanoTime() - t0) / 1e6
            assertTrue("$r", r is Outcome.Ok)
            svc += Regex("svc_us=(\\d+)").find((r as Outcome.Ok).value.lines.single().text)!!.groupValues[1].toDouble() / 1000
            ms
        }.sorted()
        // The :main half alone: create + map + copy 17 MB + unmap + setProtect.
        val copy = (1..7).map {
            val t0 = System.nanoTime()
            c.frameOf(image)!!.close()
            (System.nanoTime() - t0) / 1e6
        }.sorted()
        report("ocr.frame_bytes", w * h * 4)
        report("ocr.frame_ms_sorted", times.joinToString { "%.1f".format(it) })
        report("ocr.main_copy_ms_sorted", copy.joinToString { "%.1f".format(it) })
        report("ocr.service_ms_sorted", svc.sorted().joinToString { "%.1f".format(it) })
        val median = times[times.size / 2]
        assertTrue("median $median ms", median < 50.0)

        FakeControl.set(ctx, FakeControl.ASR, "ocr" to "crc")
        val r = c.recognize(image)
        val bytes = ByteBuffer.allocate(w * h * 4).order(ByteOrder.LITTLE_ENDIAN).apply { asIntBuffer().put(argb) }
        val crc = CRC32().apply { update(bytes.array()) }.value
        val line = (r as Outcome.Ok).value.lines.single().text
        assertEquals("crc32=%08x ro=true first=%08x".format(crc, argb[0]), line.substringBefore(" svc_us="))
        assertEquals(listOf(w, h), r.value.size)
    }

    @Test
    fun aKilledProcessIsCrashedFinishFailsWithin1sAndANewConnectWorks() = runBlocking {
        FakeControl.set(ctx, FakeControl.ASR, "killAfterBytes" to 64_000) // dies after 2 s of audio
        val c = client()
        assertTrue(c.load(spec(ModelKind.ASR), null) is Outcome.Ok)
        val oldPid = (c.state.value as HostState.Up).pid
        val session = c.startSession(AsrSessionOptions())
        val events = Collections.synchronizedList(mutableListOf<AsrEvent>())
        val collector = launch(Dispatchers.Default) { session.events.collect { events += it } }
        for (i in 0 until 150) session.accept(PcmChunk(ShortArray(320) { (it * i).toShort() }, i * 320L))
        val crashed = withTimeout(3_000) { c.state.first { it is HostState.Crashed } }
        assertEquals(HostState.Crashed(1), crashed)
        val t0 = SystemClock.elapsedRealtime()
        val r = session.finish()
        val finishMs = SystemClock.elapsedRealtime() - t0
        report("asr.finish_after_death_ms", finishMs)
        assertEquals(Outcome.Err(MurmurError.ProcessDied(InferProcess.ASR)), r)
        assertTrue("finish took $finishMs ms", finishMs <= 1_000)
        collector.join()
        assertTrue(events.contains(AsrEvent.Failure(MurmurError.ProcessDied(InferProcess.ASR))))
        assertNull(pidof(asrProcess))

        FakeControl.clear(ctx)
        assertTrue(c.connect())
        val newPid = (c.state.value as HostState.Up).pid
        assertNotEquals(oldPid, newPid)
        assertEquals(newPid, pidof(asrProcess))
        assertTrue(c.load(spec(ModelKind.ASR), null) is Outcome.Ok)
    }

    @Test
    fun aLoadThatNeverAnswersTimesOutAndTheProcessIsKilled() = runBlocking {
        FakeControl.set(ctx, FakeControl.ASR, "loadAsr" to "hang")
        val c = client(BinderTimeouts(asrLoadMs = 3_000))
        assertTrue(c.connect())
        val t0 = SystemClock.elapsedRealtime()
        val r = c.load(spec(ModelKind.ASR), spec(ModelKind.VAD))
        val ms = SystemClock.elapsedRealtime() - t0
        report("asr.load_timeout_ms", ms)
        assertEquals(Outcome.Err(MurmurError.Timeout(Stage.ASR_LOAD)), r)
        assertTrue("returned after $ms ms", ms in 3_000..4_500)
        assertNull("process not killed", pidof(asrProcess))
        assertEquals(HostState.Down, c.state.value)
    }

    @Test
    fun aFinishThatNeverAnswersTimesOutWithAsrFinal() = runBlocking {
        FakeControl.set(ctx, FakeControl.ASR, "finish" to "hang")
        val c = client(BinderTimeouts(asrFinalBaseMs = 1_000))
        val session = c.startSession(AsrSessionOptions())
        session.accept(PcmChunk(ShortArray(16_000), 0)) // 1 s, none of it committed yet -> 1 s + 0.5 s
        val t0 = SystemClock.elapsedRealtime()
        val r = session.finish()
        val ms = SystemClock.elapsedRealtime() - t0
        assertEquals(Outcome.Err(MurmurError.Timeout(Stage.ASR_FINAL)), r)
        assertTrue("returned after $ms ms", ms in 1_500..3_000)
        assertNull(pidof(asrProcess))
    }

    @Test
    fun anotherApiVersionIsRefusedAndTheProcessDoesNotStay() = runBlocking {
        FakeControl.set(ctx, FakeControl.ASR, "apiVersion" to InferApi.API_VERSION + 1)
        val c = client()
        assertTrue(!c.connect())
        assertEquals(Outcome.Err(MurmurError.Internal("api")), c.load(spec(ModelKind.ASR), null))
        assertEquals(HostState.Down, c.state.value)
        assertNotNull(waitUntil(2_000) { pidof(asrProcess) == null })
    }

    @Test
    fun anUncaughtExceptionKillsTheProcessQuietly() = runBlocking {
        val crashesBefore = crashReports()
        FakeControl.set(ctx, FakeControl.ASR, "loadAsr" to "crash")
        val c = client()
        val r = c.load(spec(ModelKind.ASR), null)
        assertEquals(Outcome.Err(MurmurError.ProcessDied(InferProcess.ASR)), r)
        assertEquals(HostState.Crashed(1), c.state.value)
        assertNull(pidof(asrProcess))
        // The default handler would have filed an app crash (and shown the "keeps stopping" dialog).
        assertEquals(crashesBefore, crashReports())
    }

    /** data_app_crash entries the system recorded for the fake process. */
    private fun crashReports(): Int =
        Regex(Regex.escape("Process: $asrProcess")).findAll(Ipc.shell("dumpsys dropbox --print data_app_crash")).count()
}
