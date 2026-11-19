package app.murmur.platform.overlay

import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.View
import app.murmur.core.dictation.Feedback
import app.murmur.core.dictation.HapticKind
import java.util.concurrent.CopyOnWriteArrayList
import app.murmur.core.store.Feedback as FeedbackSetting

/**
 * The [Feedback] port (ADR-044): haptics come from the bubble's own view with View.performHapticFeedback, because the
 * constants are View-based and the bubble view exists only in the overlay. [mode] is Settings.feedback.
 *
 * [sound] is deliberately silent: the earcons are an open design question (ADR-062). Settings keeps the Sound and
 * Both options; when the assets exist, they play here.
 */
class OverlayFeedback(private val mode: () -> FeedbackSetting) : Feedback {
    private val main = Handler(Looper.getMainLooper())

    override fun haptic(kind: HapticKind) {
        if (!vibrates(mode())) return
        main.post { BubbleViews.target()?.performHapticFeedback(constantFor(kind)) }
    }

    override fun sound(kind: HapticKind) = Unit

    companion object {
        /** DESIGN 8: haptics follow the "Sound and vibration" setting (default vibration only). */
        fun vibrates(mode: FeedbackSetting): Boolean = mode == FeedbackSetting.VIBRATION || mode == FeedbackSetting.BOTH

        fun constantFor(kind: HapticKind): Int = when (kind) {
            HapticKind.CONFIRM -> HapticFeedbackConstants.CONFIRM
            HapticKind.CLOCK_TICK -> HapticFeedbackConstants.CLOCK_TICK
            HapticKind.REJECT -> HapticFeedbackConstants.REJECT
        }
    }
}

/**
 * The bubble root views currently attached, one per display. The view that was touched last plays the haptic (the
 * dictation started there); otherwise the first one that is shown, otherwise any attached one.
 */
internal object BubbleViews {
    private class Entry(val view: View, var touchedAt: Long, val shown: () -> Boolean)

    private val entries = CopyOnWriteArrayList<Entry>()

    fun register(view: View, shown: () -> Boolean) {
        unregister(view)
        entries += Entry(view, 0L, shown)
    }

    fun unregister(view: View) {
        entries.removeAll { it.view === view }
    }

    fun touched(view: View, atMs: Long) {
        entries.firstOrNull { it.view === view }?.touchedAt = atMs
    }

    fun target(): View? {
        val attached = entries.filter { it.view.isAttachedToWindow }
        return (attached.filter { it.shown() }.maxByOrNull { it.touchedAt } ?: attached.firstOrNull())?.view
    }
}
