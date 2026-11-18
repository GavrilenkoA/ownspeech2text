package app.murmur.data.db

import android.app.job.JobScheduler
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.murmur.core.store.Settings
import app.murmur.testing.FakeClock
import app.murmur.testing.InMemorySettingsRepository
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime

/** The real JobScheduler on the device: the midnight job is pending with the right delay, runs, and reschedules itself. */
@RunWith(AndroidJUnit4::class)
class RetentionJobTest {
    private val js = appContext.getSystemService(JobScheduler::class.java)
    private val clock = FakeClock(zoneId = MOSCOW)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var db: MurmurDatabase
    private lateinit var audio: FileAudioStore

    @Before
    fun setUp() {
        js.cancel(RetentionScheduler.JOB_ID)
        db = MurmurDatabase.inMemory(appContext)
        audio = FileAudioStore(File(appContext.cacheDir, "job-test-audio").apply { deleteRecursively() })
    }

    @After
    fun tearDown() {
        js.cancel(RetentionScheduler.JOB_ID)
        RetentionScheduler.active = null
        scope.cancel()
        db.close()
        audio.dir.deleteRecursively()
    }

    private fun shell(cmd: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().readText()
    }

    @Test
    fun pendingJobWaitsUntilTheNextLocalMidnight() {
        clock.instant = LocalDateTime.of(2026, 11, 18, 21, 15).atZone(MOSCOW).toInstant()
        RetentionScheduler.schedule(appContext, clock)
        val job = js.getPendingJob(RetentionScheduler.JOB_ID)
        assertNotNull(job)
        assertEquals((2 * 60 + 45) * 60_000L, job!!.minLatencyMillis)
        assertEquals(RetentionJob::class.java.name, job.service.className)
        // Scheduling again (start, clock change) replaces the job instead of adding one.
        clock.instant = LocalDateTime.of(2026, 11, 18, 23, 0).atZone(MOSCOW).toInstant()
        RetentionScheduler.schedule(appContext, clock)
        assertEquals(60 * 60_000L, js.getPendingJob(RetentionScheduler.JOB_ID)!!.minLatencyMillis)
        assertEquals(1, js.allPendingJobs.count { it.id == RetentionScheduler.JOB_ID })
    }

    @Test
    fun forcedRunDeletesExpiredDaysAndReschedules() = runBlocking {
        val history = RoomHistoryRepository(db, audio)
        val retention = Retention(history, RoomMetricsRepository(db), audio, clock) { db.history().audioPaths().toSet() }
        val old = LocalDateTime.of(2026, 11, 11, 12, 0)
        val oldRef = audio.record(old.atZone(MOSCOW).toInstant().toEpochMilli(), 1)
        history.insert(entry(old, audio = oldRef))
        history.insert(entry(LocalDateTime.of(2026, 11, 18, 0, 5)))
        clock.instant = LocalDateTime.of(2026, 11, 18, 0, 0, 1).atZone(MOSCOW).toInstant()

        // Registered as start() would do, without start()'s own run, so that only the job can delete.
        RetentionScheduler.active = RetentionScheduler(appContext, retention, InMemorySettingsRepository(Settings(retentionDays = 7)), clock, scope)
        RetentionScheduler.schedule(appContext, clock)
        assertEquals(2, history.entries().first().size)

        val out = shell("cmd jobscheduler run -f ${appContext.packageName} ${RetentionScheduler.JOB_ID}")
        assertTrue(out, out.contains("Running job"))
        withTimeout(10_000) { while (history.entries().first().size != 1) delay(50) }
        assertEquals(listOf(LocalDate.of(2026, 11, 18)), history.entries().first().map { it.localDay })
        assertFalse(File(oldRef.path).exists())

        // After finishing, the job put itself back for the following midnight (24 h minus the second already gone).
        withTimeout(10_000) {
            while (js.getPendingJob(RetentionScheduler.JOB_ID)?.minLatencyMillis != 24 * 3_600_000L - 1_000) delay(50)
        }
    }
}
