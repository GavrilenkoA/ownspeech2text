package app.murmur.data.db

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import app.murmur.core.model.MurmurClock
import app.murmur.core.model.MurmurError
import app.murmur.core.model.SystemMurmurClock
import app.murmur.core.store.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException

/**
 * When retention runs (ARCHITECTURE 12): at app start ([start]), at the next local midnight ([RetentionJob], which
 * reschedules itself after every run), on a time-zone or wall-clock change ([ClockChangeReceiver]: reschedule + run),
 * and right after the period is shortened ([start] watches the setting; [runNow] is also what `SettingsDeps.retention`
 * calls). Built once per :main process by [DbModule.retention].
 */
class RetentionScheduler internal constructor(
    private val context: Context,
    private val retention: Retention,
    private val settings: SettingsRepository,
    internal val clock: MurmurClock,
    internal val scope: CoroutineScope,
) {
    /**
     * Call once when :main starts (AppContainer init): registers this instance for the job and the clock receiver,
     * schedules the next midnight run, runs the start-up retention (pending Undo deletes, the period, orphan audio) and
     * from then on runs it immediately whenever the period gets shorter.
     */
    fun start(): Job {
        active = this
        schedule(context, clock)
        return scope.launch(Dispatchers.IO) {
            var last = settings.current().retentionDays
            guarded("retention_start") { retention.runAtStart(last) }
            settings.settings.collect { s ->
                val days = s.retentionDays
                if (days != last) {
                    if (shortens(last, days)) guarded("retention_shorten") { retention.runNow(days) }
                    last = days
                }
            }
        }
    }

    /** History by [days] and metrics by 90 days, now; returns the history entries deleted (`SettingsDeps.retention`). */
    suspend fun runNow(days: Int): Int = retention.runNow(days)

    internal suspend fun onMidnight() {
        guarded("retention_job") { retention.runScheduled(settings.current().retentionDays) }
    }

    internal suspend fun onClockChanged() {
        schedule(context, clock)
        guarded("retention_clock") { retention.runScheduled(settings.current().retentionDays) }
    }

    private suspend fun guarded(where: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DbLog.code(MurmurError.StorageFailed(null).code, where)
        }
    }

    companion object {
        /** JobScheduler id of the midnight run (unique within the app). */
        const val JOB_ID = 0x4D52_0C01

        /** The instance of this process, set by [start]; read by [RetentionJob] and [ClockChangeReceiver]. */
        @Volatile
        internal var active: RetentionScheduler? = null

        /** A period gets shorter: 7 -> 3, or anything -> 0 ("Don't keep history"); 0 -> N keeps more. */
        internal fun shortens(old: Int, new: Int): Boolean = old != new && (new <= 0 || (old > 0 && new < old))

        /** Milliseconds from [now] to the next 00:00 in [zone] (start of tomorrow; DST gaps resolve forward). */
        fun delayToNextMidnight(now: Instant, zone: ZoneId): Long {
            val next = LocalDate.ofInstant(now, zone).plusDays(1).atStartOfDay(zone).toInstant()
            return Duration.between(now, next).toMillis()
        }

        /** (Re)schedules the one-off midnight job; replaces a pending one. */
        fun schedule(context: Context, clock: MurmurClock) {
            val js = context.getSystemService(JobScheduler::class.java) ?: return
            val info = JobInfo.Builder(JOB_ID, ComponentName(context, RetentionJob::class.java))
                .setMinimumLatency(delayToNextMidnight(clock.now(), clock.zone()))
                .build()
            if (js.schedule(info) != JobScheduler.RESULT_SUCCESS) DbLog.code(MurmurError.StorageFailed(null).code, "retention_schedule")
        }
    }
}

/**
 * The local-midnight run. Runs retention through the process's [RetentionScheduler], finishes, then schedules the next
 * midnight (scheduling the same id while the job is still running would stop it). Without a registered scheduler (the
 * process was started for the job but :main wiring did not run) it only reschedules.
 */
class RetentionJob : JobService() {
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val host = RetentionScheduler.active
        work = (host?.scope ?: orphanScope).launch(Dispatchers.IO) {
            host?.onMidnight()
            jobFinished(params, false)
            RetentionScheduler.schedule(applicationContext, host?.clock ?: SystemMurmurClock)
        }
        return true
    }

    /** Stopped by the system (e.g. Doze): retry with JobScheduler's backoff. */
    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        return true
    }

    private companion object {
        val orphanScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

/**
 * ACTION_TIMEZONE_CHANGED and ACTION_TIME_CHANGED (both exempt from the implicit-broadcast limits): the next local
 * midnight moved, and "today" may have changed, so reschedule and run.
 */
class ClockChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_TIMEZONE_CHANGED && intent.action != Intent.ACTION_TIME_CHANGED) return
        val host = RetentionScheduler.active
        if (host == null) {
            RetentionScheduler.schedule(context.applicationContext, SystemMurmurClock)
            return
        }
        val pending = goAsync()
        host.scope.launch(Dispatchers.IO) {
            try {
                host.onClockChanged()
            } finally {
                pending.finish()
            }
        }
    }
}
