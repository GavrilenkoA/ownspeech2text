package app.murmur.data.models

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import android.text.format.Formatter
import android.util.Log
import app.murmur.core.model.ArtifactId
import app.murmur.core.model.MurmurError
import app.murmur.core.model.Outcome
import app.murmur.core.model.ProductName
import app.murmur.core.store.InstallState
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Implemented by the Application (AppContainer): how the system-created job finds the process's [ModelDownloads]. */
interface ModelJobHost {
    val modelDownloads: ModelDownloads
}

/**
 * User-initiated data transfer job (JobInfo.setUserInitiated, RUN_USER_INITIATED_JOBS; ADR-039, platform spike g):
 * one artifact per job. The work is [ModelDownloads.runJob]; this class adds the Android parts: the progress
 * notification (setNotification with DETACH, then updates), updateTransferredNetworkBytes, stop reasons and
 * rescheduling. Progress lives on disk (.part lengths), so the job resumes after process death.
 */
class ModelDownloadJob : JobService() {
    /** Nothing a job does may take :main down (dictation and the accessibility service live there). */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> Log.e(TAG, "job_uncaught err=${e.javaClass.simpleName}") },
    )
    private val workers = ConcurrentHashMap<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        val downloads = (application as? ModelJobHost)?.modelDownloads
        val id = params.extras.getString(EXTRA_ARTIFACT)?.let(::ArtifactId)
        if (downloads == null || id == null) {
            Log.w(TAG, "job_start_no_host artifact=${id?.value}")
            return false
        }
        val wifiOnly = params.extras.getBoolean(EXTRA_WIFI_ONLY, true)
        val notes = Notifications(this, id, downloads)
        if (params.isUserInitiatedJob) {
            setNotification(params, params.jobId, notes.build(downloads.states.get(id)), JOB_END_NOTIFICATION_POLICY_DETACH)
        }
        workers[params.jobId] = scope.launch {
            val follower = launch {
                downloads.states.transitions.filter { it.first == id }.collect { (_, s) -> quietly("notify") { notes.update(s) } }
            }
            var reschedule = false
            try {
                val r = try {
                    downloads.runJob(id, wifiOnly) { bytes ->
                        if (params.isUserInitiatedJob) quietly("transferred") { updateTransferredNetworkBytes(params, bytes, 0) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // runJob maps its failures itself; this only guards the process.
                    Log.e(TAG, "job_exception artifact=${id.value} err=${e.javaClass.simpleName}")
                    Outcome.Err(MurmurError.Internal("job: ${e.javaClass.simpleName}"))
                }
                reschedule = r is Outcome.Err && wantsRetry(r.error)
                if (r is Outcome.Err && reschedule && r.error !is MurmurError.NoNetwork) downloads.onStopped(id, StopKind.SYSTEM, wifiOnly)
                quietly("finish") { notes.finish(downloads.states.get(id), willRetry = reschedule) }
                Log.i(TAG, "job_end artifact=${id.value} ok=${r is Outcome.Ok} code=${(r as? Outcome.Err)?.error?.code} reschedule=$reschedule")
            } finally {
                follower.cancel()
                // Cancelled = onStopJob ran (stop, pause, delete): the system already knows. Otherwise always report.
                if (isActive) {
                    workers.remove(params.jobId)
                    jobFinished(params, reschedule)
                }
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val id = params.extras.getString(EXTRA_ARTIFACT)?.let(::ArtifactId) ?: return false
        val kind = when (params.stopReason) {
            JobParameters.STOP_REASON_CANCELLED_BY_APP, JobParameters.STOP_REASON_USER -> StopKind.USER
            JobParameters.STOP_REASON_CONSTRAINT_CONNECTIVITY -> StopKind.NETWORK
            else -> StopKind.SYSTEM
        }
        Log.i(TAG, "job_stopped artifact=${id.value} stopReason=${params.stopReason} kind=$kind")
        workers.remove(params.jobId)?.cancel(CancellationException("job stopped"))
        (application as? ModelJobHost)?.modelDownloads?.onStopped(id, kind, params.extras.getBoolean(EXTRA_WIFI_ONLY, true))
        return kind != StopKind.USER
    }

    private inline fun quietly(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "job_side_effect_failed what=$what err=${e.javaClass.simpleName}")
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Network loss, server errors and a catalog that did not load in time are retried by JobScheduler (backoff);
     * integrity and storage need the user.
     */
    private fun wantsRetry(e: MurmurError) = when (e) {
        MurmurError.NoNetwork, is MurmurError.Timeout -> true
        is MurmurError.DownloadFailed -> e.httpStatus.let { it != null && (it >= 500 || it == 408 || it == 429) }
        else -> false
    }

    companion object {
        const val TAG = "MurmurModels"
        const val EXTRA_ARTIFACT = "artifact"
        const val EXTRA_WIFI_ONLY = "wifiOnly"
        const val CHANNEL = "model-downloads"

        /** Stable job (and notification) id per artifact. */
        fun jobId(id: ArtifactId): Int {
            val d = MessageDigest.getInstance("SHA-256").digest(id.value.encodeToByteArray())
            val h = ((d[0].toInt() and 0x0f) shl 24) or ((d[1].toInt() and 0xff) shl 16) or ((d[2].toInt() and 0xff) shl 8) or (d[3].toInt() and 0xff)
            return JOB_ID_BASE + h
        }

        /** Download job ids live in [0x4d000000, 0x4e000000) ("M"). */
        private const val JOB_ID_BASE = 0x4d000000
    }
}

/** [DownloadScheduler] over JobScheduler: UIDT when allowed, else a regular data-transfer job (resumes from .part). */
class JobSchedulerDownloads(private val context: Context) : DownloadScheduler {
    private val js get() = context.getSystemService(JobScheduler::class.java)
    private val component get() = ComponentName(context, ModelDownloadJob::class.java)

    override fun schedule(id: ArtifactId, wifiOnly: Boolean, estimatedBytes: Long): Boolean {
        val extras = PersistableBundle().apply {
            putString(ModelDownloadJob.EXTRA_ARTIFACT, id.value)
            putBoolean(ModelDownloadJob.EXTRA_WIFI_ONLY, wifiOnly)
        }
        fun builder() = JobInfo.Builder(ModelDownloadJob.jobId(id), component)
            .setRequiredNetworkType(if (wifiOnly) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY)
            .setEstimatedNetworkBytes(estimatedBytes, 0)
            .setBackoffCriteria(10_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .setExtras(extras)
        val uidt = try {
            js.schedule(builder().setUserInitiated(true).build()) == JobScheduler.RESULT_SUCCESS
        } catch (e: RuntimeException) {
            // UIDT needs the app to be visible (or an exemption such as the bound accessibility service).
            Log.w(ModelDownloadJob.TAG, "uidt_refused artifact=${id.value} err=${e.javaClass.simpleName}")
            false
        }
        if (uidt) {
            Log.i(ModelDownloadJob.TAG, "scheduled artifact=${id.value} uidt=true wifiOnly=$wifiOnly")
            return true
        }
        val ok = js.schedule(builder().build()) == JobScheduler.RESULT_SUCCESS
        Log.i(ModelDownloadJob.TAG, "scheduled artifact=${id.value} uidt=false ok=$ok wifiOnly=$wifiOnly")
        return ok
    }

    override fun cancel(id: ArtifactId) = js.cancel(ModelDownloadJob.jobId(id))

    override fun pending(): Map<ArtifactId, Boolean> = js.allPendingJobs
        .filter { it.service == component }
        .mapNotNull { j -> j.extras.getString(ModelDownloadJob.EXTRA_ARTIFACT)?.let { ArtifactId(it) to j.extras.getBoolean(ModelDownloadJob.EXTRA_WIFI_ONLY, true) } }
        .toMap()
}

/** Codes and numbers only (D9). */
object AndroidDlLog : DlLog {
    override fun event(name: String, vararg fields: Pair<String, Any?>) {
        Log.i(ModelDownloadJob.TAG, fields.joinToString(" ", prefix = "$name ") { "${it.first}=${it.second}" })
    }
}

/** The job's progress notification (DESIGN 6.1 step 5a "we notify you when done", 6.4 errors). */
private class Notifications(private val context: Context, private val id: ArtifactId, downloads: ModelDownloads) {
    private val nm = context.getSystemService(NotificationManager::class.java)
    private val name = downloads.displayName(id)
    private val notifId = ModelDownloadJob.jobId(id)
    private var lastUpdate = 0L

    init {
        if (nm.getNotificationChannel(ModelDownloadJob.CHANNEL) == null)
            nm.createNotificationChannel(
                NotificationChannel(ModelDownloadJob.CHANNEL, context.getString(R.string.models_dl_channel), NotificationManager.IMPORTANCE_LOW),
            )
    }

    fun build(s: InstallState, ongoing: Boolean = true): Notification {
        val b = Notification.Builder(context, ModelDownloadJob.CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
        when (s) {
            is InstallState.Downloading -> b.setContentTitle(context.getString(R.string.models_dl_downloading, name))
                .setContentText(context.getString(R.string.models_dl_progress, size(s.bytesDone), size(s.bytesTotal)))
                .setProgress(1000, if (s.bytesTotal > 0) (s.bytesDone * 1000 / s.bytesTotal).toInt() else 0, false)
            InstallState.Verifying, InstallState.Extracting -> b.setContentTitle(context.getString(R.string.models_dl_verifying, name)).setProgress(0, 0, true)
            is InstallState.Preparing -> b.setContentTitle(context.getString(R.string.models_dl_preparing, name)).setProgress(100, (s.progress * 100).toInt(), false)
            else -> b.setContentTitle(context.getString(R.string.models_dl_queued, name)).setProgress(0, 0, true)
        }
        return b.build()
    }

    fun update(s: InstallState) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (s is InstallState.Downloading && now - lastUpdate < 1_000) return
        lastUpdate = now
        if (s is InstallState.Downloading || s is InstallState.Preparing || s == InstallState.Verifying || s == InstallState.Extracting)
            nm.notify(notifId, build(s))
    }

    fun finish(s: InstallState, willRetry: Boolean) {
        val b = Notification.Builder(context, ModelDownloadJob.CHANNEL).setAutoCancel(true).setOngoing(false)
        when {
            s is InstallState.Installed -> b.setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(context.getString(R.string.models_dl_done, name))
            s is InstallState.Paused || willRetry -> b.setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(context.getString(R.string.models_dl_paused, name))
                .setContentText(
                    context.getString(
                        if ((s as? InstallState.Paused)?.reason == MurmurError.LowStorage) R.string.models_dl_paused_storage else R.string.models_dl_paused_network,
                    ),
                )
            else -> b.setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(context.getString(R.string.models_dl_failed, name))
                .apply {
                    if ((s as? InstallState.Failed)?.error == MurmurError.ChecksumMismatch)
                        setContentText(context.getString(R.string.models_dl_damaged, ProductName.NAME))
                }
        }
        nm.notify(notifId, b.build())
    }

    private fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
}
