package app.murmur.platform.a11y

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor
import kotlin.coroutines.CoroutineContext

/**
 * The service's node-reading thread ("a11y-io", ARCHITECTURE 14) for callers that outlive the service.
 *
 * A Handler dispatcher whose looper has quit rejects the task and cancels the caller's `withContext` with a
 * CancellationException: the orchestrator, which is not cancelled itself, would read it as its own cancellation. A
 * callback posted to that Handler is silently dropped, so a pending screenshot would never resume. Here a rejected
 * task runs on [fallback] (a coroutine resumes and sees the lane closed), a rejected callback runs on the calling
 * thread, and [call] returns its "service gone" value once [close] ran.
 *
 * [post] is Handler.post: false when the looper has quit.
 */
class IoLane(private val post: (Runnable) -> Boolean, private val fallback: Executor) {
    @Volatile var open = true
        private set

    val dispatcher: CoroutineDispatcher = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (!post(block)) fallback.execute(block)
        }

        override fun toString() = "a11y-io"
    }

    /** For platform callbacks (TakeScreenshotCallback): never drops one. */
    val executor = Executor { r -> if (!post(r)) r.run() }

    /** The service is going away: later [call]s return their "gone" value without touching it. */
    fun close() {
        open = false
    }

    /** Runs [block] on the lane; [gone] when the service ended before or while the call was dispatched. */
    suspend fun <T> call(gone: T, block: suspend CoroutineScope.() -> T): T {
        if (!open) return gone
        return withContext(dispatcher) { if (open) block() else gone }
    }
}
