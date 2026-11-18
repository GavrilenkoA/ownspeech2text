package app.murmur.testing

import app.murmur.core.model.MurmurClock
import java.time.Instant
import java.time.ZoneId

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
