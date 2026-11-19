package app.murmur

import android.app.Application
import app.murmur.core.dictation.DictationOrchestrator
import app.murmur.core.dictation.UserInput
import app.murmur.core.store.SettingsRepository
import app.murmur.data.models.ModelDownloads
import app.murmur.data.models.ModelJobHost
import app.murmur.platform.a11y.A11yServiceHost
import app.murmur.platform.audio.StatusAction
import app.murmur.platform.audio.StatusActionHandler
import kotlinx.coroutines.launch

/**
 * One Application class runs in all three processes (ARCHITECTURE.md 4.1). Only :main builds the AppContainer;
 * :asr and :llm start nothing here (their Services are self-contained).
 */
class MurmurApp : Application(), A11yServiceHost, ModelJobHost, StatusActionHandler {
    /** Null in :asr / :llm. */
    var container: AppContainer? = null
        private set

    override fun onCreate() {
        super.onCreate()
        if (Processes.isMain(this)) container = AppContainer(this).also { it.start() }
    }

    private fun main(): AppContainer = checkNotNull(container) { "AppContainer is only available in the :main process" }

    override val dictation: DictationOrchestrator get() = main().orchestrator
    override val settings: SettingsRepository get() = main().settings
    override val modelDownloads: ModelDownloads get() = main().modelDownloads
    override fun onServiceState(connected: Boolean) {
        main().serviceConnected.value = connected
        // ExternalStop(A11Y_OFF) for a running dictation (ARCHITECTURE 4.4); a no-op when Idle.
        if (!connected) runCatching { main().orchestrator.onAccessibilityLost() }
    }

    /** The status and microphone notifications' actions (WP-09 receiver): Settings writes and orchestrator inputs. */
    override fun onStatusAction(action: StatusAction) {
        val c = main()
        when (action) {
            is StatusAction.Hide -> c.appScope.launch { c.settings.update { it.copy(hiddenUntilEpochMs = action.untilEpochMs) } }
            StatusAction.ShowNow -> c.appScope.launch { c.settings.update { it.copy(hiddenUntilEpochMs = null) } }
            StatusAction.StartDictation -> c.orchestrator.onInput(UserInput.NotificationStart)
            // "Stop" on the microphone notification = a tap on the Listening pill (finish).
            StatusAction.StopDictation -> c.orchestrator.onInput(UserInput.Tap)
        }
    }
}

object Processes {
    fun isMain(app: Application): Boolean = Application.getProcessName() == app.packageName
}
