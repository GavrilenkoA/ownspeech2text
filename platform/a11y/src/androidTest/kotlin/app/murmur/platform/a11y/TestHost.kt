package app.murmur.platform.a11y

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.Application
import android.os.SystemClock
import android.util.Log
import app.murmur.core.dictation.DictationOrchestrator
import app.murmur.core.dictation.UserInput
import app.murmur.core.store.SettingsRepository
import app.murmur.platform.overlay.VisibilityInput
import app.murmur.testing.FakeWorld
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The A11yServiceHost of the test APK: fake dictation world (FakeWorld from :testing), and recorders for what the
 * service reports (service state, user inputs, the VisibilityInput sequence per display) instead of a real bubble.
 */
class TestHostApp : Application(), A11yServiceHost {
    val world by lazy { FakeWorld() }
    override val dictation: DictationOrchestrator by lazy { DictationOrchestrator(world.deps(), CoroutineScope(SupervisorJob() + Dispatchers.Default)) }
    override val settings: SettingsRepository get() = world.settings

    /** (uptime ms, connected) for every onServiceState call. */
    val serviceStates = CopyOnWriteArrayList<Pair<Long, Boolean>>()
    val inputs = CopyOnWriteArrayList<UserInput>()
    val surfaces = CopyOnWriteArrayList<RecordingSurface>()

    override fun onServiceState(connected: Boolean) {
        serviceStates += SystemClock.uptimeMillis() to connected
        Log.i(TAG, "host service_state connected=$connected")
    }

    override fun onUserInput(input: UserInput) {
        inputs += input
        Log.i(TAG, "host user_input ${input.javaClass.simpleName}")
    }

    /** The targets live in this test APK and play "another app": none of its UI counts as Murmur's own here. */
    override fun ownUiPackages(service: AccessibilityService): Set<String> = emptySet()

    override fun bubbleFor(service: AccessibilityService, displayId: Int): BubbleSurface =
        RecordingSurface(displayId).also { surfaces += it }

    companion object {
        const val TAG = "A11yTestHost"
    }
}

/** Records every VisibilityInput the service pushes, with the uptime at which it arrived. */
class RecordingSurface(val displayId: Int) : BubbleSurface {
    @Volatile var attached = false
    val inputs = CopyOnWriteArrayList<Pair<Long, VisibilityInput>>()
    override fun attach() { attached = true }
    override fun updateVisibility(input: VisibilityInput) { inputs += SystemClock.uptimeMillis() to input }
    override fun detach() { attached = false }
}

/** Murmur's own screen in the restricted-settings test: opens the accessibility settings and notices the return. */
class HostActivity : Activity() {
    override fun onResume() {
        super.onResume()
        A11yStatus.get(this).onReturnedToApp()
        HostActivityTracker.resumed = this
        Log.i(TestHostApp.TAG, "host_activity resumed")
    }

    override fun onPause() {
        HostActivityTracker.resumed = null
        super.onPause()
    }

    fun openSettings() = A11yStatus.get(this).openAccessibilitySettings(this)
}

object HostActivityTracker {
    @Volatile var resumed: HostActivity? = null
}
