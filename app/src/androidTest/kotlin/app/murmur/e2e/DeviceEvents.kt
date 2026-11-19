package app.murmur.e2e

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.view.Display
import android.view.accessibility.AccessibilityWindowInfo

/**
 * The platform events the emulator can simulate (ADR-058, WP-20 brief 4), each with a check that it really happened.
 * Shell-side commands run here; console commands (hinge sensor, gsm) go through [HostBridge].
 */
object DeviceEvents {
    /** The Fold7 cover display (and the cover AVD). */
    const val COVER_SIZE = "1080x2520"
    const val CALLER = "5551234"

    private val displays get() = E2e.context.getSystemService(DisplayManager::class.java)
    private val defaultDisplay: Display get() = displays.getDisplay(Display.DEFAULT_DISPLAY)

    /** `wm size`: "Physical size: WxH" and, when set, "Override size: WxH". */
    fun physicalSize(): String = Regex("""Physical size: (\d+x\d+)""").find(E2e.shell("wm size"))!!.groupValues[1]
    fun overrideSize(): String? = Regex("""Override size: (\d+x\d+)""").find(E2e.shell("wm size"))?.groupValues?.get(1)
    fun currentSize(): String = overrideSize() ?: physicalSize()

    /** Real size of the default display as apps see it (after the change reached the window manager). */
    fun displaySize(): String {
        val m = android.util.DisplayMetrics().also {
            @Suppress("DEPRECATION")
            defaultDisplay.getRealMetrics(it)
        }
        val (w, h) = listOf(m.widthPixels, m.heightPixels).sorted()
        return "${w}x$h"
    }

    /** Width in pixels of the default display in its current rotation. */
    fun displayWidth(): Int = android.util.DisplayMetrics().also {
        @Suppress("DEPRECATION")
        defaultDisplay.getRealMetrics(it)
    }.widthPixels

    private fun sameSize(a: String, b: String) = a.split('x').map { it.toInt() }.sorted() == b.split('x').map { it.toInt() }.sorted()

    /** Fold: the inner display becomes the cover size (`wm size 1080x2520`) and the hinge closes (`hinge-angle0 0`). */
    fun fold() {
        E2e.shell("wm size $COVER_SIZE")
        E2e.waitUntil("display size $COVER_SIZE (now ${displaySize()})", 5_000, pollMs = 100) { sameSize(displaySize(), COVER_SIZE) }
        HostBridge.hingeAngle(0)
        HostBridge.awaitDeviceState("CLOSED")
        E2e.note("folded: size=${displaySize()} state=${HostBridge.deviceState()}")
    }

    /** Unfold: back to the physical (inner) size and an open hinge (`hinge-angle0 180`). */
    fun unfold() {
        E2e.shell("wm size reset")
        val physical = physicalSize()
        E2e.waitUntil("display size $physical (now ${displaySize()})", 5_000, pollMs = 100) { sameSize(displaySize(), physical) }
        HostBridge.hingeAngle(180)
        HostBridge.awaitDeviceState("OPENED")
        E2e.note("unfolded: size=${displaySize()} state=${HostBridge.deviceState()}")
    }

    fun rotation(): Int = defaultDisplay.rotation

    /** `settings put system user_rotation <r>` with auto-rotate off; waits until the display really rotated. */
    fun rotate(r: Int) {
        E2e.shell("settings put system accelerometer_rotation 0")
        E2e.shell("settings put system user_rotation $r")
        E2e.waitUntil("display rotation $r (now ${rotation()})", 5_000, pollMs = 100) { rotation() == r }
        E2e.note("rotation=${rotation()}")
    }

    private val audio get() = E2e.context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** Incoming call from the emulator modem; waits until the system rings (audio mode RINGTONE or IN_CALL). */
    fun incomingCall() {
        HostBridge.gsmCall(CALLER)
        E2e.waitUntil("audio mode RINGTONE (now ${audio.mode})", 10_000, pollMs = 50) {
            audio.mode == AudioManager.MODE_RINGTONE || audio.mode == AudioManager.MODE_IN_CALL
        }
        E2e.note("call ringing: audio mode=${audio.mode}")
    }

    fun endCall() {
        HostBridge.gsmCancel(CALLER)
        E2e.waitUntil("audio mode NORMAL (now ${audio.mode})", 10_000, pollMs = 100) { audio.mode == AudioManager.MODE_NORMAL }
    }

    /** True while SystemUI's notification shade window is on screen. */
    fun shadeOpen(): Boolean = E2e.uiAutomation.windows.any { w ->
        w.root?.packageName == "com.android.systemui" && w.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
            android.graphics.Rect().also { w.getBoundsInScreen(it) }.height() > defaultDisplay.mode.physicalHeight / 2
    }

    fun expandShade() {
        E2e.shell("cmd statusbar expand-notifications")
        E2e.waitUntil("notification shade open", 5_000, pollMs = 100) { shadeOpen() }
    }

    fun collapseShade() {
        E2e.shell("cmd statusbar collapse")
        E2e.waitUntil("notification shade closed", 5_000, pollMs = 100) { !shadeOpen() }
    }

    /** Puts the emulator back the way the suite found it (after a failure, too). */
    fun restore() {
        E2e.shell("wm size reset")
        E2e.shell("settings put system user_rotation 0")
        E2e.shell("cmd statusbar collapse")
        if (HostBridge.running) {
            runCatching { HostBridge.hingeAngle(180) }
            runCatching { HostBridge.gsmCancel(CALLER) }
        }
    }
}
