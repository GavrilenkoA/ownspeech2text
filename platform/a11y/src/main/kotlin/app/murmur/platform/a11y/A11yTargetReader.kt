package app.murmur.platform.a11y

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import app.murmur.core.context.A11ySnapshot
import app.murmur.core.dictation.TargetReader
import app.murmur.core.model.MurmurError
import app.murmur.core.model.Outcome
import app.murmur.core.ocr.Rect
import app.murmur.core.ocr.ScreenImage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * [TargetReader] over the connected service (ARCHITECTURE 9.1). All node and screenshot calls run on "a11y-io".
 * Logs carry codes and numbers only (D9).
 */
class A11yTargetReader internal constructor(private val service: MurmurAccessibilityService) : TargetReader {
    private val nodes get() = service.nodes
    private val limiter = RateLimiter(SystemClock::uptimeMillis)
    private val lane get() = service.lane

    /** Call time, finish time, path and platform error codes of the latest [screenshot] (no pixels). */
    @Volatile var lastShot: ShotRecord? = null
        private set

    private val shots = ArrayDeque<ShotRecord>()

    /** The latest [screenshot] records, oldest first (at most [SHOT_HISTORY]). */
    val recentShots: List<ShotRecord> get() = synchronized(shots) { shots.toList() }

    override suspend fun snapshot(): A11ySnapshot? = lane.call(null) {
        val t0 = SystemClock.uptimeMillis()
        val ws = nodes.windows()
        val snap = WindowChoice.target(ws)?.let { nodes.snapshot(it, ws) }
        Log.i(TAG, "snapshot ms=${SystemClock.uptimeMillis() - t0} window=${snap?.windowId} field=${snap?.field != null}")
        snap
    }

    override suspend fun current(): A11ySnapshot? = snapshot()

    override suspend fun snapshotAfterShade(timeoutMs: Long): A11ySnapshot? {
        val t0 = SystemClock.uptimeMillis()
        val target = withTimeoutOrNull(timeoutMs) { awaitAppWindowAfterShade() }
        Log.i(TAG, "after_shade waited_ms=${SystemClock.uptimeMillis() - t0} found=${target != null}")
        if (target == null) return null
        return lane.call(null) {
            val ws = nodes.windows()
            nodes.snapshot(WindowChoice.reResolve(ws, target.id, target.pkg ?: "") ?: target, ws)
        }
    }

    /**
     * Returns at once when a focused non-SystemUI app window exists, else re-checks after each WINDOWS_CHANGED; null
     * when the service ended meanwhile.
     */
    private suspend fun awaitAppWindowAfterShade(): WinInfo? {
        while (true) {
            val version = service.windowsVersion.value
            val ws = lane.call(null) { nodes.windows() } ?: return null
            WindowChoice.afterShade(ws)?.let { return it }
            service.windowsVersion.first { it > version || !lane.open }
            if (!lane.open) return null
        }
    }

    override suspend fun screenshot(target: A11ySnapshot): Outcome<ScreenImage> = lane.call(Outcome.Err(MurmurError.AccessibilityOff)) {
        val chain = ScreenshotChain(TargetShooter(target), limiter, SystemClock::uptimeMillis)
        val (outcome, record) = chain.capture(target.pkg, target.windowId, target.displayId)
        lastShot = record
        synchronized(shots) { shots.addLast(record); while (shots.size > SHOT_HISTORY) shots.removeFirst() }
        Log.i(TAG, "screenshot path=${record.path} ms=${record.finishedAtMs - record.calledAtMs} errors=${record.errors} result=${record.error ?: "ok"}")
        outcome
    }

    private inner class TargetShooter(private val target: A11ySnapshot) : Shooter {
        override suspend fun window(windowId: Int): ShotResult = capture { cb -> service.takeScreenshotOfWindow(windowId, lane.executor, cb) }
        override suspend fun display(displayId: Int): ShotResult = capture { cb -> service.takeScreenshot(displayId, lane.executor, cb) }
        override suspend fun resolveWindow(): Int? = WindowChoice.reResolve(nodes.windows(), target.windowId, target.pkg)?.id
        override suspend fun inputFocus(): FocusWindow? = nodes.inputFocus()
        override suspend fun frontPackage(displayId: Int): String? =
            WindowChoice.target(nodes.windows().filter { it.displayId == displayId })?.pkg
        override suspend fun exclusionRects(displayId: Int, width: Int, height: Int): List<Rect> =
            ExclusionRects.of(nodes.windows(), service.packageName, displayId, width, height, targetPkg = target.pkg)
    }

    private suspend fun capture(call: (AccessibilityService.TakeScreenshotCallback) -> Unit): ShotResult =
        suspendCancellableCoroutine { cont ->
            call(object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val frame = toImage(result)
                    if (cont.isActive) cont.resume(frame?.let { ShotResult.Frame(it) } ?: ShotResult.Failed(ShotError.INTERNAL))
                }

                override fun onFailure(errorCode: Int) {
                    if (cont.isActive) cont.resume(ShotResult.Failed(errorCode))
                }
            })
        }

    /** HardwareBuffer -> software ARGB_8888 -> int pixels (Bitmap layout = ScreenImage layout). Never PNG. */
    private fun toImage(result: AccessibilityService.ScreenshotResult): ScreenImage? {
        val hb = result.hardwareBuffer
        try {
            val hw = Bitmap.wrapHardwareBuffer(hb, result.colorSpace) ?: return null
            val sw = hw.copy(Bitmap.Config.ARGB_8888, false)
            hw.recycle()
            sw ?: return null
            val px = IntArray(sw.width * sw.height)
            sw.getPixels(px, 0, sw.width, 0, 0, sw.width, sw.height)
            val img = ScreenImage(sw.width, sw.height, px)
            sw.recycle()
            return img
        } finally {
            hb.close()
        }
    }

    companion object {
        private const val TAG = "MurmurA11y"
        const val SHOT_HISTORY = 8
    }
}
