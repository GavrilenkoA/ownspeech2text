package app.murmur.testing

import app.murmur.core.model.MurmurClock
import java.time.Instant
import java.time.ZoneId

/** Manually advanced clock (view-model and repository tests). */
class FakeClock(
    var instant: Instant = Instant.parse("2026-11-18T12:00:00Z"),
    var zoneId: ZoneId = ZoneId.of("Europe/Moscow"),
) : MurmurClock {
    var elapsed: Long = 0
    override fun now(): Instant = instant
    override fun zone(): ZoneId = zoneId
    override fun elapsedMs(): Long = elapsed
    fun advance(ms: Long) { elapsed += ms; instant = instant.plusMillis(ms) }
}

/**
 * Clock that follows kotlinx-coroutines-test virtual time, so that timers (delay) and latencies (elapsedMs) agree:
 * `runTest { val clock = VirtualClock { testScheduler.currentTime } }`. No coroutines-test dependency here (the
 * debug APK uses :testing too).
 */
class VirtualClock(
    private val origin: Instant = Instant.parse("2026-11-18T12:00:00Z"),
    private val zoneId: ZoneId = ZoneId.of("Europe/Moscow"),
    private val virtualMs: () -> Long,
) : MurmurClock {
    override fun now(): Instant = origin.plusMillis(virtualMs())
    override fun zone(): ZoneId = zoneId
    override fun elapsedMs(): Long = virtualMs()
}
