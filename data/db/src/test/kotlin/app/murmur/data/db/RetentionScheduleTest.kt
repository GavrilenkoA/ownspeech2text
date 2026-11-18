package app.murmur.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class RetentionScheduleTest {
    @Test
    fun nextLocalMidnight() {
        val moscow = ZoneId.of("Europe/Moscow")
        val now = LocalDateTime.of(2026, 11, 18, 23, 59, 30).atZone(moscow).toInstant()
        assertEquals(30_000L, RetentionScheduler.delayToNextMidnight(now, moscow))
        // Exactly at midnight the next run is a full day later.
        val midnight = LocalDateTime.of(2026, 11, 19, 0, 0).atZone(moscow).toInstant()
        assertEquals(24 * 3_600_000L, RetentionScheduler.delayToNextMidnight(midnight, moscow))
        // The same instant is 21:00 in UTC (Moscow is UTC+3): three hours to the UTC midnight.
        assertEquals(3 * 3_600_000L, RetentionScheduler.delayToNextMidnight(midnight, ZoneId.of("UTC")))
    }

    @Test
    fun daylightSavingDaysAreShortOrLong() {
        val berlin = ZoneId.of("Europe/Berlin")
        // 2026-03-29: clocks jump 02:00 -> 03:00, the day has 23 hours.
        val dayBefore = LocalDateTime.of(2026, 3, 28, 0, 0).atZone(berlin).toInstant()
        assertEquals(24 * 3_600_000L, RetentionScheduler.delayToNextMidnight(dayBefore, berlin))
        val springDay = LocalDateTime.of(2026, 3, 29, 0, 0).atZone(berlin).toInstant()
        assertEquals(23 * 3_600_000L, RetentionScheduler.delayToNextMidnight(springDay, berlin))
        // 2026-10-25: 03:00 -> 02:00, 25 hours.
        val autumnDay = LocalDateTime.of(2026, 10, 25, 0, 0).atZone(berlin).toInstant()
        assertEquals(25 * 3_600_000L, RetentionScheduler.delayToNextMidnight(autumnDay, berlin))
    }

    @Test
    fun whichChangesShortenThePeriod() {
        assertTrue(RetentionScheduler.shortens(7, 3))
        assertTrue(RetentionScheduler.shortens(7, 1))
        assertTrue(RetentionScheduler.shortens(3, 0))
        assertFalse(RetentionScheduler.shortens(3, 7))
        assertFalse(RetentionScheduler.shortens(0, 7))
        assertFalse(RetentionScheduler.shortens(7, 7))
        assertFalse(RetentionScheduler.shortens(0, 0))
    }

    @Test
    fun audioKeyTimeFromFileName() {
        assertEquals(1_763_456_789_000L, Retention.audioKeyTime("/data/user/0/app/files/history/audio/1763456789000-12.ogg"))
        assertEquals(1_763_456_789_000L, Retention.audioKeyTime("1763456789000-3"))
        assertNull(Retention.audioKeyTime("/x/rec_abc.ogg"))
    }

    @Test
    fun searchPatternFoldsCaseAndEscapesWildcards() {
        assertEquals("%привет%", SearchText.pattern("  Привет "))
        assertEquals("%елка%", SearchText.pattern("Ёлка"))
        assertEquals("%100\\%\\_done\\\\x%", SearchText.pattern("100%_done\\x"))
        assertEquals("финал\u001Fсырой текст", SearchText.of("Финал", "СЫРОЙ текст"))
        assertEquals("сырой", SearchText.of(null, "Сырой"))
    }
}
