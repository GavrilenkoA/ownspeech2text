package app.murmur.platform.overlay

import android.graphics.Bitmap
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/**
 * Screenshots for the by-eye comparison with docs/design/screens (WP-03 acceptance). Written to the test package's
 * external files dir; tools/run_emulator_tests.sh pulls them into .cache/spikes/wp-03/.
 */
object Shots {
    val dir: File by lazy {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        File(requireNotNull(ctx.getExternalFilesDir(null)), "wp03").apply { mkdirs() }
    }

    fun save(name: String, bitmap: Bitmap): File {
        val f = File(dir, "$name.png")
        f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Log.i("WP03", "shot $name ${bitmap.width}x${bitmap.height} -> ${f.absolutePath}")
        return f
    }
}
