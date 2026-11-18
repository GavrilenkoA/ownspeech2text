package app.murmur

import android.content.Context
import app.murmur.core.engine.Engines
import app.murmur.testing.FakeEngines

/**
 * Debug builds only (D6): when the debug flag file exists, :main talks to :testing fakes instead of the
 * inference processes, so UI and end-to-end tests run without models. Enable with
 *   adb shell run-as app.murmur touch files/debug-fake-engines
 * or from an instrumented test (DebugEngines.FLAG in the app's filesDir). WP-20 wires the flag into Settings.
 */
class DebugEngines(private val context: Context) {
    fun fakeEnginesOrNull(): Engines? = if (java.io.File(context.filesDir, FLAG).exists()) FakeEngines() else null

    companion object {
        const val FLAG = "debug-fake-engines"
    }
}
