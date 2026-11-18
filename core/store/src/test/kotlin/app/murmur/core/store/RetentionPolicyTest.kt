package app.murmur.core.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class RetentionPolicyTest {
    private val today = LocalDate.of(2026, 11, 18)

    @Test
    fun sevenDaysKeepsTodayAndSixDaysBefore() {
        assertEquals(LocalDate.of(2026, 11, 12), RetentionPolicy.firstKeptDay(today, 7))
        assertTrue(RetentionPolicy.deletesTonight(LocalDate.of(2026, 11, 12), today, 7))
    }

    @Test
    fun entryFromDayDIsDeletedAtMidnightOfDPlusN() {
        val z = ZoneId.of("Europe/Moscow")
        assertEquals(LocalDate.of(2026, 11, 25).atStartOfDay(z), RetentionPolicy.deletesAt(LocalDate.of(2026, 11, 18), 7, z))
    }

    @Test
    fun dontKeepHistoryDeletesTodayToo() {
        // review 2026-11-19: days = 0 used to return today, keeping today's dictations after "Don't keep history".
        assertEquals(LocalDate.of(2026, 11, 19), RetentionPolicy.firstKeptDay(today, 0))
        assertTrue(today.isBefore(RetentionPolicy.firstKeptDay(today, 0)))
        assertEquals(false, RetentionPolicy.keepsHistory(0))
        assertEquals(false, RetentionPolicy.deletesTonight(today, today, 0))
    }
}
