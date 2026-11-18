package app.murmur.core.model

import java.time.Instant
import java.time.ZoneId

/**
 * The only source of time in the app. Real: system clock + SystemClock.elapsedRealtime().
 * Fake (testing): fully controllable. Never call System.currentTimeMillis() in feature code.
 */
interface MurmurClock {
    fun now(): Instant
    fun zone(): ZoneId
    /** Monotonic milliseconds for latency measurement (elapsedRealtime on Android). */
    fun elapsedMs(): Long
}

object SystemMurmurClock : MurmurClock {
    private val start = System.nanoTime()
    override fun now(): Instant = Instant.now()
    override fun zone(): ZoneId = ZoneId.systemDefault()
    override fun elapsedMs(): Long = (System.nanoTime() - start) / 1_000_000
}
