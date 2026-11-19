package app.murmur.platform.audio

/**
 * Watches the AudioRecord read loop for a microphone that went away mid-recording. AudioRecord reports a lost input
 * stream in one of three ways, and each one ends the capture with a cause code (CaptureSignal.Lost(MicLost(cause))):
 * - a negative read result (ERROR_DEAD_OBJECT, ERROR_INVALID_OPERATION, ...): "read:<code>";
 * - reads that keep returning 0 samples (a blocking read should never do that): retried with a short back-off, and
 *   "read:0" once they have stayed empty for [maxGapMs];
 * - a read that blocks and delivers nothing: [stalled] turns true after [maxGapMs] without samples ("stall"); the
 *   capture's watchdog polls it.
 * A route change (headset, Bluetooth) is not a loss: AudioRecord keeps delivering from the new device.
 * Pure logic on a caller-supplied clock (JVM-tested).
 */
internal class MicReadWatch(
    private val maxGapMs: Long = MAX_GAP_MS,
    private val emptyBackoffMs: Long = EMPTY_BACKOFF_MS,
) {
    sealed interface Verdict {
        /** Samples arrived: use them. */
        data object Samples : Verdict
        /** Nothing arrived: sleep [ms], then read again. */
        data class Retry(val ms: Long) : Verdict
        /** The input is gone. */
        data class Lost(val cause: String) : Verdict
    }

    @Volatile private var lastSamplesAt = -1L
    private var emptySince = -1L

    /** Arms the stall check: the first samples are awaited from [nowMs] on (the start check owns the first second). */
    fun armed(nowMs: Long) { lastSamplesAt = nowMs }

    /** The read thread, after every AudioRecord.read. */
    fun onRead(result: Int, nowMs: Long): Verdict = when {
        result > 0 -> {
            lastSamplesAt = nowMs
            emptySince = -1L
            Verdict.Samples
        }
        result < 0 -> Verdict.Lost("read:$result")
        else -> {
            if (emptySince < 0) emptySince = nowMs
            if (nowMs - emptySince >= maxGapMs) Verdict.Lost("read:0") else Verdict.Retry(emptyBackoffMs)
        }
    }

    /** Any thread: true once samples were expected and none came for [maxGapMs]. */
    fun stalled(nowMs: Long): Boolean {
        val last = lastSamplesAt
        return last >= 0 && nowMs - last >= maxGapMs
    }

    companion object {
        /** One second without samples loses words; the user is told and what was recorded is processed (ARCHITECTURE 4.4). */
        const val MAX_GAP_MS = 1_000L
        const val EMPTY_BACKOFF_MS = 5L
        /** How often the capture's watchdog asks [stalled]. */
        const val WATCHDOG_PERIOD_MS = 200L
    }
}
