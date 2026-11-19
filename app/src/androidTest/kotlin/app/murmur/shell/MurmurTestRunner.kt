package app.murmur.shell

import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner
import java.io.File

/**
 * AndroidJUnitRunner that can switch the debug fake container before the Application (and its AppContainer) is
 * created: `-e murmurFake seeded|fresh[:fold7|:emulator]` writes `files/debug-fake-container`, `-e murmurFake off`
 * deletes it, no argument leaves the app as it is (WP-20's e2e tests run on the real container).
 * Instrumentation.onCreate runs before Application.onCreate, so the container reads the flag of this run.
 */
class MurmurTestRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle?) {
        arguments?.getString(ARG)?.let { mode ->
            val flag = File(targetContext.filesDir, FLAG)
            if (mode == "off") flag.delete() else flag.writeText(mode)
        }
        super.onCreate(arguments)
    }

    companion object {
        const val ARG = "murmurFake"
        /** Same name as DebugWiring.FAKE_CONTAINER (src/debug). */
        const val FLAG = "debug-fake-container"
    }
}
