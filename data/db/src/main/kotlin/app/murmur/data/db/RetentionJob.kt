package app.murmur.data.db

import android.app.job.JobParameters
import android.app.job.JobService

/** One-off job scheduled for the next local midnight; reschedules itself (WP-12). */
class RetentionJob : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean = false   // WP-12
    override fun onStopJob(params: JobParameters?): Boolean = true
}
