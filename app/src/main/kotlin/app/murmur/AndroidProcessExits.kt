package app.murmur

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.util.Log
import app.murmur.core.engine.ProcessExits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * ADR-083: was the inference process [pid] killed by the system for memory? Reads this package's exit records
 * (`getHistoricalProcessExitReasons`, API 30+). The record can arrive shortly after the binder death the client saw,
 * so it is polled every [stepMs] up to [waitMs]; no record by then counts as "not for memory" (the death then judges
 * the unit, as before ADR-083).
 *
 * Where the device does not report lmkd kills ([lowMemoryKillReported] false,
 * `ActivityManager.isLowMemoryKillReportSupported()`), an lmkd kill is recorded as `REASON_SIGNALED` and can never be
 * told apart: the record is read once without waiting and the death judges the unit. Every classified death writes
 * one code-only line ([deathLine], rule 10 / D9) so a device run can tell which record was seen.
 */
internal class AndroidProcessExits(
    private val recordOf: (pid: Int) -> Exit?,
    private val lowMemoryKillReported: Boolean = true,
    private val log: (String) -> Unit = {},
    private val waitMs: Long = 2_000,
    private val stepMs: Long = 200,
) : ProcessExits {
    /** The raw exit record fields (`ApplicationExitInfo.reason` / `.status`). */
    data class Exit(val reason: Int, val status: Int)

    constructor(context: Context) : this(
        recordOf = { pid ->
            context.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(context.packageName, pid, 1)
                .firstOrNull { it.pid == pid }?.let { Exit(it.reason, it.status) }
        },
        lowMemoryKillReported = ActivityManager.isLowMemoryKillReportSupported(),
        log = { Log.i(TAG, it) },
    )

    override suspend fun killedForMemory(pid: Int): Boolean = withContext(Dispatchers.IO) {
        var waited = 0L
        var exit = read(pid)
        while (exit == null && lowMemoryKillReported && waited < waitMs) {
            delay(stepMs)
            waited += stepMs
            exit = read(pid)
        }
        val forMemory = exit?.reason == ApplicationExitInfo.REASON_LOW_MEMORY
        log(deathLine(exit, lowMemoryKillReported, waited, forMemory))
        forMemory
    }

    private fun read(pid: Int): Exit? = runCatching { recordOf(pid) }.getOrNull()

    companion object {
        const val TAG = "MurmurEngines"

        /** `load_death reason=<int|none> status=<int|none> lmk_report=<bool> waited_ms=<n> oom=<bool>`: numbers only. */
        fun deathLine(exit: Exit?, lowMemoryKillReported: Boolean, waitedMs: Long, forMemory: Boolean): String =
            "load_death reason=${exit?.reason ?: "none"} status=${exit?.status ?: "none"} " +
                "lmk_report=$lowMemoryKillReported waited_ms=$waitedMs oom=$forMemory"
    }
}
