package app.murmur.data.db

import android.app.job.JobScheduler
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.murmur.core.model.MurmurClock
import app.murmur.core.store.Settings
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone

/**
 * A real ACTION_TIMEZONE_CHANGED from the system (`cmd alarm set-timezone`) reaches [ClockChangeReceiver]: the midnight
 * job moves to the new zone's midnight and retention runs with the new zone's "today".
 */
@RunWith(AndroidJUnit4::class)
class ClockChangeTest {
    private val js = appContext.getSystemService(JobScheduler::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var db: MurmurDatabase
    private lateinit var audio: FileAudioStore
    private lateinit var originalZone: String

    /** 15:30 UTC on 24 November: 18:30 in Moscow, already 00:30 on the 25th in Tokyo. The zone is the device's. */
    private val instant = Instant.parse("2026-11-24T15:30:00Z")
    private val clock = object : MurmurClock {
        override fun now(): Instant = instant
        override fun zone(): ZoneId = ZoneId.systemDefault()
        override fun elapsedMs(): Long = 0
    }

    private fun shell(cmd: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().readText()
    }

    private suspend fun setDeviceZone(id: String) {
        shell("cmd alarm set-timezone $id")
        withTimeout(10_000) { while (TimeZone.getDefault().id != id) delay(50) }
    }

    @Before
    fun setUp() {
        originalZone = TimeZone.getDefault().id
        js.cancel(RetentionScheduler.JOB_ID)
        db = MurmurDatabase.inMemory(appContext)
        audio = FileAudioStore(File(appContext.cacheDir, "clock-test-audio").apply { deleteRecursively() })
    }

    @After
    fun tearDown() = runBlocking {
        RetentionScheduler.active = null
        scope.cancel()
        setDeviceZone(originalZone)
        js.cancel(RetentionScheduler.JOB_ID)
        db.close()
        audio.dir.deleteRecursively()
        Unit
    }

    @Test
    fun timeZoneChangeReschedulesAndRunsInTheNewZone() = runBlocking {
        setDeviceZone("Europe/Moscow")
        val history = RoomHistoryRepository(db, audio)
        val retention = Retention(history, RoomMetricsRepository(db), audio, clock) { db.history().audioPaths().toSet() }
        val dictated = LocalDateTime.of(2026, 11, 18, 10, 0)
        val ref = audio.record(dictated.atZone(MOSCOW).toInstant().toEpochMilli(), 1)
        history.insert(entry(dictated, audio = ref))

        RetentionScheduler.active = RetentionScheduler(appContext, retention, InMemorySettingsRepository(Settings(retentionDays = 7)), clock, scope)
        RetentionScheduler.schedule(appContext, clock)
        // Moscow: 5 h 30 min to midnight, and the 18th is kept until then.
        assertEquals((5 * 60 + 30) * 60_000L, js.getPendingJob(RetentionScheduler.JOB_ID)!!.minLatencyMillis)
        assertEquals(0, retention.runNow(7))

        setDeviceZone("Asia/Tokyo")
        // Tokyo is already on the 25th: the receiver deletes the 18th and the next run is Tokyo's midnight of the 26th.
        withTimeout(15_000) { while (history.entries().first().isNotEmpty()) delay(50) }
        assertFalse(File(ref.path).exists())
        withTimeout(15_000) {
            while (js.getPendingJob(RetentionScheduler.JOB_ID)?.minLatencyMillis != (23 * 60 + 30) * 60_000L) delay(50)
        }
    }
}
