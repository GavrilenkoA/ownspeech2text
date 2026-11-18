package app.murmur.spike

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.PersistableBundle
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * User-initiated data transfer job: one large file, HTTP Range resume from <name>.part,
 * SHA-256 check, atomic rename. Progress is the .part length on disk, so it survives process death.
 */
class DownloadJob : JobService() {
    @Volatile private var stop = false
    private var worker: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val ex = params.extras
        val url = ex.getString("url")!!; val name = ex.getString("name")!!
        val sha = ex.getString("sha256"); val size = ex.getLong("size")
        ensureChannel(this)
        val notif = Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading $name").setOngoing(true).build()
        setNotification(params, 11, notif, JOB_END_NOTIFICATION_POLICY_DETACH)
        Ev.log("uidt_start", "userInitiated" to params.isUserInitiatedJob, "name" to name,
            "partBytes" to File(dir(this), "$name.part").length())
        stop = false
        worker = Thread({ run(params, url, name, sha, size) }, "uidt").also { it.start() }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        stop = true
        Ev.log("uidt_stopped", "stopReason" to params.stopReason, "partBytes" to
            File(dir(this), params.extras.getString("name") + ".part").length())
        return true // reschedule
    }

    private fun run(params: JobParameters, url: String, name: String, sha: String?, size: Long) {
        val part = File(dir(this), "$name.part"); val done = File(dir(this), name)
        try {
            val have = part.length()
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true; connectTimeout = 15_000; readTimeout = 30_000
                if (have > 0) setRequestProperty("Range", "bytes=$have-")
            }
            val code = c.responseCode
            val append = code == 206
            Ev.log("uidt_http", "code" to code, "rangeFrom" to have, "contentRange" to c.getHeaderField("Content-Range"),
                "finalHost" to c.url.host, "len" to c.contentLengthLong)
            if (code != 200 && code != 206) { jobFinished(params, true); return }
            var written = if (append) have else 0L
            var lastLog = 0L; var lastUpdate = 0L; val t0 = SystemClock.elapsedRealtime()
            c.inputStream.use { inp -> FileOutputStream(part, append).use { out ->
                val buf = ByteArray(256 * 1024)
                while (!stop) {
                    val n = inp.read(buf); if (n < 0) break
                    out.write(buf, 0, n); written += n
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastUpdate > 1000) { updateTransferredNetworkBytes(params, written, 0); lastUpdate = now }
                    if (written - lastLog > size / 10) { lastLog = written
                        Ev.log("uidt_progress", "bytes" to written, "of" to size, "mbps" to (written - (if (append) have else 0)) * 8.0 / 1e3 / (now - t0 + 1)) }
                }
                out.fd.sync()
            } }
            if (stop) return
            val md = MessageDigest.getInstance("SHA-256")
            part.inputStream().use { val b = ByteArray(1 shl 20); while (true) { val n = it.read(b); if (n < 0) break; md.update(b, 0, n) } }
            val got = md.digest().joinToString("") { "%02x".format(it) }
            val ok = (sha == null || got == sha) && (size <= 0 || part.length() == size)
            if (ok) part.renameTo(done)
            Ev.log("uidt_done", "ok" to ok, "sha256" to got, "bytes" to done.length().coerceAtLeast(part.length()))
            jobFinished(params, !ok)
        } catch (e: Exception) {
            Ev.log("uidt_error", "err" to e.toString(), "partBytes" to part.length())
            jobFinished(params, true)
        }
    }

    companion object {
        const val CHANNEL = "dl"
        const val JOB_ID = 4711
        fun dir(c: Context) = File(c.noBackupFilesDir, "models").also { it.mkdirs() }
        fun ensureChannel(c: Context) {
            val nm = c.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null)
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW))
        }
        /** Must be called while the app is visible (UIDT rule). Returns JobScheduler result or the exception. */
        fun schedule(c: Context, url: String, name: String, sha: String?, size: Long): String = try {
            val extras = PersistableBundle().apply {
                putString("url", url); putString("name", name); putString("sha256", sha); putLong("size", size)
            }
            val job = JobInfo.Builder(JOB_ID, ComponentName(c, DownloadJob::class.java))
                .setUserInitiated(true)
                .setRequiredNetwork(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build())
                .setEstimatedNetworkBytes(size, 0)
                .setBackoffCriteria(5_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .setExtras(extras).build()
            val r = c.getSystemService(JobScheduler::class.java).schedule(job)
            if (r == JobScheduler.RESULT_SUCCESS) "RESULT_SUCCESS" else "RESULT_FAILURE"
        } catch (e: Exception) { e.toString() }
    }
}
