package app.murmur.data.models

import android.app.job.JobParameters
import android.app.job.JobService

/**
 * UIDT job (JobInfo.setUserInitiated, RUN_USER_INITIATED_JOBS): downloads every file of one artifact,
 * verifies, extracts, writes installed.json. setNotification(DETACH) for progress; updateTransferredNetworkBytes.
 * Lifted from spikes/platform DownloadJob.kt + per-attempt HF re-resolve + fsync (WP-13).
 */
class ModelDownloadJob : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean = false   // WP-13
    override fun onStopJob(params: JobParameters?): Boolean = true     // WP-13: keep .part, reschedule
}
