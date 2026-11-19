package app.murmur.platform.a11y

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

class IoLaneTest {
    /** A single-thread "looper" whose post() returns false once quit, like Handler.post. */
    private class FakeLooper {
        private val pool = Executors.newSingleThreadExecutor { Thread(it, "a11y-io") }
        val quit = AtomicBoolean(false)
        fun post(r: Runnable): Boolean {
            if (quit.get()) return false
            pool.execute(r)
            return true
        }
        fun shutdown() = pool.shutdown()
    }

    @Test
    fun callsRunOnTheLaneThread() = runBlocking {
        val looper = FakeLooper()
        val lane = IoLane(looper::post, Dispatchers.Default.asExecutor())
        // Coroutine debug mode appends " @coroutine#n" to the thread name.
        assertTrue(lane.call("gone") { Thread.currentThread().name }.startsWith("a11y-io"))
        looper.shutdown()
    }

    @Test
    fun aRejectedDispatchNeitherThrowsNorCancelsTheCaller() = runBlocking {
        val looper = FakeLooper()
        val lane = IoLane(looper::post, Dispatchers.Default.asExecutor())
        // The looper quits while the call is suspended inside the lane: its resumption is rejected.
        // Awaited from outside the lane, so a dropped resumption shows as a timeout instead of a hang.
        val call = CoroutineScope(Dispatchers.Default).async {
            lane.call("gone") {
                looper.quit.set(true)
                // Resumed from another thread (a binder callback): the resumption goes through dispatch(), now rejected.
                suspendCancellableCoroutine<Unit> { c -> Thread { c.resume(Unit) }.start() }
                "done after quit on ${if (Thread.currentThread().name.startsWith("a11y-io")) "lane" else "fallback"}"
            }
        }
        val r = withTimeoutOrNull(2_000) { call.await() }
        call.cancel()
        assertEquals("done after quit on fallback", r)
        assertTrue(coroutineContext.job.isActive)
        looper.shutdown()
    }

    @Test
    fun aClosedLaneAnswersGoneWithoutRunningTheBlock() = runBlocking {
        val looper = FakeLooper()
        val lane = IoLane(looper::post, Dispatchers.Default.asExecutor())
        lane.close()
        looper.quit.set(true)
        var ran = false
        assertEquals("gone", lane.call("gone") { ran = true; "ran" })
        assertFalse(ran)
        assertTrue(coroutineContext.job.isActive)
        looper.shutdown()
    }

    @Test
    fun aCallbackPostedAfterTheQuitIsDeliveredNotDropped() = runBlocking {
        val looper = FakeLooper().apply { quit.set(true) }
        val lane = IoLane(looper::post, Dispatchers.Default.asExecutor())
        // A platform callback (TakeScreenshotCallback.onFailure) arriving through the lane's executor after the quit.
        val code = withTimeout(1_000) {
            suspendCancellableCoroutine { cont -> lane.executor.execute { cont.resume(ShotError.INTERNAL) } }
        }
        assertEquals(ShotError.INTERNAL, code)
        looper.shutdown()
    }
}
