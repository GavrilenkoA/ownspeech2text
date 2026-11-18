package app.murmur.data.db

import android.app.job.JobScheduler
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.murmur.core.store.RetentionPolicy
import app.murmur.core.store.Settings
import app.murmur.core.store.SettingsRepository
import app.murmur.testing.FakeClock
import app.murmur.testing.InMemorySettingsRepository
import app.murmur.testing.contrib.Wp12MetricsFixture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Retention against the real Room database and real audio files, with a fake clock (ARCHITECTURE 12). */
@RunWith(AndroidJUnit4::class)
class RetentionTest {
    private lateinit var db: MurmurDatabase
    private lateinit var audio: FileAudioStore
    private lateinit var history: RoomHistoryRepository
    private lateinit var metrics: RoomMetricsRepository
    private val clock = FakeClock(zoneId = MOSCOW)
    private lateinit var retention: Retention
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before
    fun setUp() {
        db = MurmurDatabase.inMemory(appContext)
        audio = FileAudioStore(File(appContext.cacheDir, "retention-test-audio").apply { deleteRecursively() })
        history = RoomHistoryRepository(db, audio)
        metrics = RoomMetricsRepository(db)
        retention = Retention(history, metrics, audio, clock) { db.history().audioPaths().toSet() }
    }

    @After
    fun tearDown() {
        scope.cancel()
        RetentionScheduler.active = null
        appContext.getSystemService(JobScheduler::class.java).cancel(RetentionScheduler.JOB_ID)
        db.close()
        audio.dir.deleteRecursively()
    }

    private fun setNow(t: LocalDateTime, zone: ZoneId = MOSCOW) {
        clock.zoneId = zone
        clock.instant = t.atZone(zone).toInstant()
    }

    /** One dictation with its audio file at local time [t] in Moscow. */
    private suspend fun dictation(t: LocalDateTime, session: Long): File {
        val ref = audio.record(t.atZone(MOSCOW).toInstant().toEpochMilli(), session)
        history.insert(entry(t, audio = ref))
        return File(ref.path)
    }

    /** [settings] that reports when the scheduler has read the stored period (its start-up run follows). */
    private class Observed(val inner: InMemorySettingsRepository = InMemorySettingsRepository()) : SettingsRepository by inner {
        val read = CompletableDeferred<Unit>()
        override suspend fun current(): Settings = inner.current().also { read.complete(Unit) }
    }

    private suspend fun days(): List<LocalDate> = history.entries().first().map { it.localDay }.distinct().sorted()

    @Test
    fun dayDIsDeletedAtMidnightOfDPlusSeven() = runBlocking {
        val d = LocalDate.of(2026, 11, 18)
        val early = dictation(d.atTime(0, 0), 1)
        val late = dictation(d.atTime(23, 59, 59), 2)
        val next = dictation(d.plusDays(1).atTime(0, 0), 3)

        setNow(LocalDateTime.of(2026, 11, 24, 23, 59, 59))
        assertEquals(0, retention.runNow(7))
        assertEquals(listOf(d, d.plusDays(1)), days())

        setNow(LocalDateTime.of(2026, 11, 25, 0, 0))
        assertEquals(RetentionPolicy.deletesAt(d, 7, MOSCOW).toInstant(), clock.now())
        assertEquals(2, retention.runNow(7))
        assertEquals(listOf(d.plusDays(1)), days())
        assertFalse(early.exists())
        assertFalse(late.exists())
        assertTrue(next.exists())
    }

    @Test
    fun metricsAreKeptNinetyDaysIndependentOfHistory() = runBlocking {
        val today = LocalDate.of(2026, 11, 18)
        val kept = Wp12MetricsFixture.row(today.minusDays(89), "a", wordsFinal = 1, recordMs = 1_000, speechMs = 1_000, stopToDoneMs = 1)
        val dropped = Wp12MetricsFixture.row(today.minusDays(90), "b", wordsFinal = 1, recordMs = 1_000, speechMs = 1_000, stopToDoneMs = 1)
        metrics.add(kept)
        metrics.add(dropped)
        dictation(today.minusDays(10).atTime(12, 0), 1)
        setNow(today.atTime(9, 0))

        assertEquals(1, retention.runNow(7))
        assertEquals(listOf(kept), metrics.since(today.minusDays(400)).first())
        // "Don't keep history" leaves the dashboard numbers alone too.
        assertEquals(0, retention.runNow(0))
        assertEquals(listOf(kept), metrics.since(today.minusDays(400)).first())
    }

    @Test
    fun changingTheZoneKeepsLocalDaySemantics() = runBlocking {
        // Dictated at 23:30 on the 18th in Moscow (20:30 UTC): it belongs to the 18th, wherever the phone goes next.
        val f = dictation(LocalDateTime.of(2026, 11, 18, 23, 30), 1)
        val newYork = ZoneId.of("America/New_York")

        // 00:30 on the 25th in Moscow is still 16:30 on the 24th in New York: the 18th survives there.
        val instant = LocalDateTime.of(2026, 11, 25, 0, 30).atZone(MOSCOW).toInstant()
        clock.instant = instant
        clock.zoneId = newYork
        assertEquals(0, retention.runNow(7))
        assertTrue(f.exists())

        // New York midnight of the 25th: the 18th is deleted, by the local calendar of the zone in use.
        setNow(LocalDateTime.of(2026, 11, 25, 0, 0), newYork)
        assertEquals(1, retention.runNow(7))
        assertFalse(f.exists())
    }

    @Test
    fun movingEastDeletesEarlierThanAtHome() = runBlocking {
        val f = dictation(LocalDateTime.of(2026, 11, 18, 10, 0), 1)
        // 15:00 UTC on the 24th is 18:00 in Moscow (kept) but 00:00 on the 25th in Tokyo (UTC+9): deleted there.
        val tokyo = ZoneId.of("Asia/Tokyo")
        setNow(LocalDateTime.of(2026, 11, 24, 18, 0))
        assertEquals(0, retention.runNow(7))
        clock.zoneId = tokyo
        assertEquals(LocalDate.of(2026, 11, 25), LocalDate.ofInstant(clock.now(), tokyo))
        assertEquals(1, retention.runNow(7))
        assertFalse(f.exists())
    }

    @Test
    fun shorteningSevenToThreeDeletesOlderDaysImmediately() = runBlocking {
        val today = LocalDate.of(2026, 11, 18)
        val files = (0..6).associate { back -> today.minusDays(back.toLong()) to dictation(today.minusDays(back.toLong()).atTime(12, 0), back.toLong()) }
        setNow(today.atTime(15, 0))
        val settings = Observed()
        val scheduler = RetentionScheduler(appContext, retention, settings, clock, scope)
        scheduler.start()
        settings.read.await()
        // The start-up run with 7 days keeps the whole week.
        assertEquals((6 downTo 0).map { today.minusDays(it.toLong()) }, days())

        // The user confirms "Keep history for 3 days": the watcher runs retention at once, before any midnight.
        settings.update { it.copy(retentionDays = 3) }
        withTimeout(5_000) { while (days().size != 3) delay(20) }
        assertEquals(listOf(today.minusDays(2), today.minusDays(1), today), days())
        files.forEach { (day, f) -> assertEquals("$day", !day.isBefore(today.minusDays(2)), f.exists()) }
        // SettingsDeps.retention after the confirm dialog: nothing left to delete.
        assertEquals(0, scheduler.runNow(3))
    }

    @Test
    fun shorteningToZeroDeletesEverythingIncludingToday() = runBlocking {
        val today = LocalDate.of(2026, 11, 18)
        val files = (0..3).map { back -> dictation(today.minusDays(back.toLong()).atTime(8, 0), back.toLong()) }
        val justNow = dictation(today.atTime(14, 59), 9)
        setNow(today.atTime(15, 0))
        val settings = Observed()
        RetentionScheduler(appContext, retention, settings, clock, scope).start()
        settings.read.await()
        assertEquals(5, history.entries().first().size)

        settings.update { it.copy(retentionDays = 0) }
        withTimeout(5_000) { while (history.entries().first().isNotEmpty()) delay(20) }
        (files + justNow).forEach { assertFalse(it.path, it.exists()) }
        assertEquals(emptyList<HistoryRow>(), db.history().allRows())
        assertEquals(emptyList<String>(), audio.dir.list().orEmpty().toList())
    }

    @Test
    fun runNowWithZeroDeletesToday() = runBlocking {
        val f = dictation(LocalDateTime.of(2026, 11, 18, 23, 59), 1)
        setNow(LocalDateTime.of(2026, 11, 18, 23, 59, 30))
        assertEquals(1, retention.runNow(0))
        assertFalse(f.exists())
    }

    @Test
    fun orphanAudioFilesAreDeletedAtStart() = runBlocking {
        val now = LocalDateTime.of(2026, 11, 18, 12, 0)
        setNow(now)
        val withRow = dictation(now.minusHours(3), 1)
        val pendingUndo = dictation(now.minusHours(2), 2)
        history.markDeleted(setOf(history.entries().first().first { it.audio?.path == pendingUndo.path }.id))
        // A file from a crash between encoding and the row insert, and one from an older app version.
        val orphan = File(audio.record(now.minusHours(5).atZone(MOSCOW).toInstant().toEpochMilli(), 3).path)
        val foreign = File(audio.dir, "rec_legacy.ogg").apply { writeBytes(ByteArray(10)) }
        // A dictation that is encoding right now has no row yet: inside the grace period it stays.
        val inFlight = File(audio.record(clock.now().toEpochMilli() - 30_000, 4).path)

        retention.runAtStart(7)

        assertTrue(withRow.exists())
        assertTrue(inFlight.exists())
        assertFalse(orphan.exists())
        assertFalse(foreign.exists())
        // The process died while the Undo snackbar was up: the delete is committed at start, with its audio.
        assertFalse(pendingUndo.exists())
        assertEquals(1, db.history().allRows().size)

        // An hour later the in-flight file still has no row: it is an orphan now.
        clock.advance(Retention.ORPHAN_GRACE_MS)
        assertEquals(1, retention.deleteOrphanAudio())
        assertFalse(inFlight.exists())
        assertTrue(withRow.exists())
    }

    @Test
    fun aRowSpellingItsAudioPathDifferentlyStillKeepsTheFile() = runBlocking {
        val now = LocalDateTime.of(2026, 11, 18, 12, 0)
        setNow(now)
        // The row stores the canonical path (/data/data/… for /data/user/0/…) or a path through "..", the store lists
        // its own spelling: the same file either way.
        val canonical = audio.record(now.minusHours(3).atZone(MOSCOW).toInstant().toEpochMilli(), 1)
        val canonicalFile = File(canonical.path)
        history.insert(entry(now.minusHours(3), audio = canonical.copy(path = canonicalFile.canonicalPath)))
        val dotted = audio.record(now.minusHours(2).atZone(MOSCOW).toInstant().toEpochMilli(), 2)
        val dottedFile = File(dotted.path)
        history.insert(entry(now.minusHours(2), audio = dotted.copy(path = "${audio.dir.path}/../${audio.dir.name}/${dottedFile.name}")))
        val listed = audio.listAll().map { it.path }.toSet()
        assertFalse(db.history().audioPaths().any { it in listed })

        assertEquals(0, retention.deleteOrphanAudio())
        retention.runAtStart(7)

        assertTrue(canonicalFile.exists())
        assertTrue(dottedFile.exists())
        // Deleting through the row's spelling still removes the file.
        history.deleteAll()
        assertFalse(canonicalFile.exists())
        assertFalse(dottedFile.exists())
    }
}
