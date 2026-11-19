package app.murmur.infer.api

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.murmur.core.engine.HostState
import app.murmur.core.engine.LlmEvent
import app.murmur.core.engine.LlmRequest
import app.murmur.core.engine.LlmStats
import app.murmur.core.engine.PrefixSpec
import app.murmur.core.model.InferProcess
import app.murmur.core.model.ModelKind
import app.murmur.core.model.MurmurError
import app.murmur.core.model.Outcome
import app.murmur.core.model.Stage
import app.murmur.infer.api.Ipc.ctx
import app.murmur.infer.api.Ipc.llmProcess
import app.murmur.infer.api.Ipc.pidof
import app.murmur.infer.api.Ipc.report
import app.murmur.infer.api.Ipc.spec
import app.murmur.infer.api.Ipc.waitUntil
import app.murmur.infer.api.fake.FakeControl
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** :main <-> :llm over a real process boundary, against [app.murmur.infer.api.fake.FakeLlmService] in :fakellm. */
@RunWith(AndroidJUnit4::class)
class LlmIpcTest {
    private val clients = mutableListOf<LlmClient>()
    private val request = LlmRequest(suffix = "<transcript>test</transcript>", maxTokens = 2_000, minTokens = 10)

    private fun client(timeouts: () -> BinderTimeouts = { BinderTimeouts() }) = Ipc.llm(timeouts).also { clients += it }

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

    private fun prefix3000Tokens() = PrefixSpec("a".repeat(7_500), "k".repeat(64))

    @Test
    fun aFortySecondPrefillSucceedsWithinItsWorkScaledTimeout() = runBlocking {
        FakeControl.set(ctx, FakeControl.LLM, "prefixDelayMs" to 40_000)
        val c = client()
        assertTrue(c.load(spec(ModelKind.LLM)) is Outcome.Ok)
        val prefix = prefix3000Tokens()
        assertEquals(3_000, BinderTimeouts.estimateTokens(prefix.text))
        assertEquals(null, c.knownPrefillTps())
        assertEquals(150_000, BinderTimeouts().prefixMs(3_000, c.knownPrefillTps())) // the timeout this call runs under
        val t0 = SystemClock.elapsedRealtime()
        val r = c.setPrefix(prefix)
        val ms = SystemClock.elapsedRealtime() - t0
        report("llm.prefix_40s_ms", ms)
        assertTrue("$r", r is Outcome.Ok)
        assertEquals(3_000, (r as Outcome.Ok).value.tokens)
        assertTrue(ms >= 40_000)
        assertTrue(c.state.value is HostState.Up)
    }

    @Test
    fun theSamePrefillTimesOutWithLlmPrefixAtScale0Point1() = runBlocking {
        // exitOnUnbind=false: the engine is stuck, so only the client's own kill removes the process.
        FakeControl.set(ctx, FakeControl.LLM, "prefixDelayMs" to 40_000, "exitOnUnbind" to false)
        var timeouts = BinderTimeouts()
        val c = client { timeouts } // read at every call, like Settings.debugTimeoutScale
        assertTrue(c.load(spec(ModelKind.LLM)) is Outcome.Ok)
        timeouts = timeouts.copy(scale = 0.1)
        val t0 = SystemClock.elapsedRealtime()
        val r = c.setPrefix(prefix3000Tokens())
        val ms = SystemClock.elapsedRealtime() - t0
        report("llm.prefix_timeout_scale_0.1_ms", ms)
        assertEquals(Outcome.Err(MurmurError.Timeout(Stage.LLM_PREFIX)), r)
        assertTrue("returned after $ms ms", ms in 15_000..17_500)
        assertNull("process not killed", pidof(llmProcess))
        assertEquals(HostState.Down, c.state.value)
    }

    @Test
    fun generateStreamsPiecesInOrderAndCancelStopsIt() = runBlocking {
        FakeControl.set(ctx, FakeControl.LLM, "pieces" to 2_000, "pieceDelayMs" to 5)
        val c = client()
        assertTrue(c.load(spec(ModelKind.LLM)) is Outcome.Ok)
        val events = mutableListOf<LlmEvent>()
        withTimeout(20_000) {
            c.generate(request).collect { e ->
                events += e
                if (events.size == 60) c.cancel()
            }
        }
        val pieces = events.filterIsInstance<LlmEvent.Piece>()
        val done = events.last() as LlmEvent.Done
        report("llm.pieces_before_cancel", pieces.size)
        assertTrue(pieces.size >= 60)
        assertTrue("cancel did not stop it", pieces.size < 2_000)
        assertEquals(pieces.indices.map { "$it," }, pieces.map { it.text })
        assertEquals(LlmStats.STOP_CANCELLED, done.stats.stopReason)
        assertEquals(pieces.joinToString("") { it.text }, done.text)
    }

    @Test
    fun cancellingTheCollectorCancelsTheGenerationInTheService() = runBlocking {
        FakeControl.set(ctx, FakeControl.LLM, "pieces" to 2_000, "pieceDelayMs" to 5)
        val c = client()
        assertTrue(c.load(spec(ModelKind.LLM)) is Outcome.Ok)
        val got = c.generate(request).filterIsInstance<LlmEvent.Piece>().take(50).toList()
        assertEquals((0 until 50).map { "$it," }, got.map { it.text })
        assertNotNull("service never saw cancel()", waitUntil(2_000) { runBlocking { c.probe().facts["cancels"] } == "1" })
        val sent = c.probe().facts.getValue("pieces.sent").toInt()
        Thread.sleep(200)
        assertEquals("still generating after cancel", sent, c.probe().facts.getValue("pieces.sent").toInt())
        assertTrue(sent < 2_000)
        // The engine is free again: a second generation runs to the end.
        FakeControl.set(ctx, FakeControl.LLM, "pieces" to 5)
        val second = c.generate(request).toList()
        assertEquals(LlmStats.STOP_EOG, (second.last() as LlmEvent.Done).stats.stopReason)
    }

    @Test
    fun aDeathMidGenerationEndsTheStreamWithProcessDied() = runBlocking {
        FakeControl.set(ctx, FakeControl.LLM, "pieces" to 2_000, "pieceDelayMs" to 5, "killAfterPieces" to 30)
        val c = client()
        assertTrue(c.load(spec(ModelKind.LLM)) is Outcome.Ok)
        val events = withTimeout(5_000) { c.generate(request).toList() }
        assertEquals(LlmEvent.Failed(MurmurError.ProcessDied(InferProcess.LLM)), events.last())
        // Pieces already queued when the death arrives may be dropped; none after piece 29 exists.
        assertTrue(events.count { it is LlmEvent.Piece } in 1..30)
        assertEquals(HostState.Crashed(1), c.state.value)
        FakeControl.clear(ctx)
        assertTrue(c.connect())
        assertTrue(c.state.value is HostState.Up)
    }

    @Test
    fun benchAnswersAndItsPrefillSpeedScalesTheNextPrefixTimeout() = runBlocking {
        FakeControl.set(ctx, FakeControl.LLM, "ppTps" to 300.0)
        val c = client()
        assertTrue(c.load(spec(ModelKind.LLM)) is Outcome.Ok)
        val r = c.bench(512, 128, 3)
        assertTrue("$r", r is Outcome.Ok)
        assertEquals(300.0, (r as Outcome.Ok).value.ppTps, 0.0)
        assertEquals(300.0, c.knownPrefillTps()!!, 0.0)
        assertTrue(c.setPrefix(PrefixSpec("short", "k")) is Outcome.Ok)
        val m = c.memory()
        assertTrue("$m", m.vmRssKb > 0 && m.pssKb > 0 && m.systemAvailMb > 0)
    }
}
