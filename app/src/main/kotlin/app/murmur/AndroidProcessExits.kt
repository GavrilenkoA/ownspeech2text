package app.murmur

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import app.murmur.core.engine.ProcessExits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * ADR-083: was the inference process [pid] killed by the system for memory? Reads this package's exit records
 * (`getHistoricalProcessExitReasons`, API 30+). The record can arrive shortly after the binder death the client saw,
 * so it is polled every [stepMs] up to [waitMs]; no record by then counts as "not for memory" (the death then judges
 * the unit, as before ADR-083).
 */
internal class AndroidProcessExits(
    private val reasonOf: (pid: Int) -> Int?,
    private val waitMs: Long = 2_000,
    private val stepMs: Long = 200,
) : ProcessExits {
    constructor(context: Context) : this(reasonOf = { pid ->
        context.getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(context.packageName, pid, 1)
            .firstOrNull { it.pid == pid }?.reason
    })

    override suspend fun killedForMemory(pid: Int): Boolean = withContext(Dispatchers.IO) {
        var waited = 0L
        var reason = read(pid)
        while (reason == null && waited < waitMs) {
            delay(stepMs)
            waited += stepMs
            reason = read(pid)
        }
        reason == ApplicationExitInfo.REASON_LOW_MEMORY
    }

    private fun read(pid: Int): Int? = runCatching { reasonOf(pid) }.getOrNull()
}
