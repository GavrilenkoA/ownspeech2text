package app.murmur.feature.history

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable

/**
 * Hosts the History tab inside [FrameShell] and rebuilds it in onCreate, so `ActivityScenario.recreate()` exercises a
 * real configuration change (fold continuity). The test sets [config] before launching.
 */
class HistoryTestActivity : ComponentActivity() {
    class Config(
        val world: HistoryWorld,
        val dark: Boolean = false,
        val fontScale: Float = 1f,
        val content: @Composable (HistoryWorld) -> Unit = { HistoryListDetail(it.deps, onTryDictation = {}, bottomBar = { TestNavBar() }) },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        val cfg = checkNotNull(config) { "set HistoryTestActivity.config first" }
        enableEdgeToEdge(
            statusBarStyle = if (cfg.dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = if (cfg.dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent { FrameShell(cfg.dark, cfg.fontScale) { cfg.content(cfg.world) } }
    }

    companion object {
        @Volatile var config: Config? = null
    }
}
