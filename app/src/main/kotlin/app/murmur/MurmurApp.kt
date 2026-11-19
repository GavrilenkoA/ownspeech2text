package app.murmur

import android.app.Application
import app.murmur.core.dictation.DictationOrchestrator
import app.murmur.core.store.SettingsRepository
import app.murmur.data.models.ModelDownloads
import app.murmur.data.models.ModelJobHost
import app.murmur.platform.a11y.A11yServiceHost

/**
 * One Application class runs in all three processes (ARCHITECTURE.md 4.1). Only :main builds the
 * AppContainer; :asr and :llm start nothing here (their Services are self-contained).
 */
class MurmurApp : Application(), A11yServiceHost, ModelJobHost {
    /** Null in :asr / :llm. */
    var container: AppContainer? = null
        private set

    override fun onCreate() {
        super.onCreate()
        if (Processes.isMain(this)) container = AppContainer(this)
    }

    private fun main(): AppContainer = checkNotNull(container) { "AppContainer is only available in the :main process" }

    override val dictation: DictationOrchestrator get() = main().orchestrator
    override val settings: SettingsRepository get() = main().settings
    override val modelDownloads: ModelDownloads get() = main().modelDownloads
    override fun onServiceState(connected: Boolean) {
        main().serviceConnected.value = connected
        main().permissions.refresh()
        // ExternalStop(A11Y_OFF) for a running dictation (ARCHITECTURE 4.4); a no-op when Idle. WP-11 implements.
        if (!connected) runCatching { main().orchestrator.onAccessibilityLost() }
    }
}

object Processes {
    fun isMain(app: Application): Boolean = Application.getProcessName() == app.packageName
}
