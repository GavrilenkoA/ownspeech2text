package app.murmur.core.dictation

import app.murmur.core.store.EntryStatus
import app.murmur.core.store.HistoryEntry
import app.murmur.testing.FakeWorld
import app.murmur.testing.VirtualClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.coroutines.ContinuationInterceptor

/**
 * An orchestrator over a [FakeWorld] on virtual time. The bubble and state flows are recorded with an unconfined
 * collector, so every value the orchestrator publishes is seen (no conflation).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class Harness(val scope: TestScope, val w: FakeWorld, config: DictationConfig) {
    val errors = mutableListOf<Throwable>()
    /**
     * Not backgroundScope: advanceUntilIdle() ignores background work, and the whole pipeline runs here. A separate
     * Job on the test dispatcher, so runTest does not wait for the orchestrator's never-ending loops.
     */
    private val orchestratorScope = CoroutineScope(scope.coroutineContext[ContinuationInterceptor]!! + SupervisorJob())
    val o: DictationOrchestrator = w.orchestrator(orchestratorScope, config, errors)
    val bubbles = mutableListOf<DictationBubble>()
    val states = mutableListOf<DictationState>()

    init {
        val unconfined = UnconfinedTestDispatcher(scope.testScheduler)
        scope.backgroundScope.launch(unconfined) { o.bubble.collect { bubbles += it } }
        scope.backgroundScope.launch(unconfined) { o.state.collect { states += it } }
    }

    val now: Long get() = scope.testScheduler.currentTime
    val state: DictationState get() = o.state.value

    /** Advance virtual time to the absolute [ms] and run what is due then. */
    fun at(ms: Long) {
        require(ms >= now) { "time goes forward: $ms < $now" }
        scope.advanceTimeBy(ms - now)
        scope.runCurrent()
    }

    fun tap() = press(UserInput.Tap)

    /** Post an input and process it now (no time passes). */
    fun press(input: UserInput) { o.onInput(input); scope.runCurrent() }

    /** Tap at [startAt], tap again at [stopAt], then run everything that follows (delivery, result pill, idle). */
    fun dictate(startAt: Long = now, stopAt: Long = startAt + 2_800) {
        at(startAt); tap(); scope.runCurrent()
        at(stopAt); tap()
        scope.testScheduler.advanceUntilIdle()
    }

    fun history(): List<HistoryEntry> = w.history.snapshot()
    fun only(): HistoryEntry = history().single()

    /** The final outcome of each session (a Done pill can be updated in place, e.g. when its entry arrives). */
    fun outcomes(): List<DictationOutcome> =
        states.filterIsInstance<DictationState.Done>().groupBy { it.session }.values.map { it.last().outcome }

    fun assertNoInternalErrors() = assertTrue(errors.joinToString { it.stackTraceToString() }, errors.isEmpty())

    fun assertInserted(cleanupOutcome: String, status: EntryStatus = EntryStatus.INSERTED) {
        assertEquals(cleanupOutcome, only().cleanup?.outcome)
        assertEquals(status, only().status)
        assertNoInternalErrors()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.harness(config: DictationConfig = DictationConfig(), setup: FakeWorld.() -> Unit = {}): Harness {
    val w = FakeWorld(clock = VirtualClock { testScheduler.currentTime }).apply(setup)
    return Harness(this, w, config)
}
