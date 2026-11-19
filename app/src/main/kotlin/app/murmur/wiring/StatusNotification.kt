package app.murmur.wiring

import app.murmur.core.catalog.CatalogRepository
import app.murmur.core.dictation.ModelResolver
import app.murmur.core.dictation.PermissionMonitor
import app.murmur.core.dictation.StatusNotifier
import app.murmur.core.model.Outcome
import app.murmur.core.store.InstalledModelsRepository
import app.murmur.core.store.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch

/**
 * When the idle status notification shows (ADR-055, DESIGN 5.1): while the accessibility service is connected and
 * notifications are allowed; it carries the active models ("Parakeet TDT 0.6B v3 · LFM3 1.7B", from the resolver) and
 * the hidden state (Settings.hiddenUntilEpochMs: "Show now" instead of "Hide 1 h"). Pure decision in [decide].
 */
class StatusNotificationController(
    private val notifier: StatusNotifier,
    private val serviceConnected: Flow<Boolean>,
    private val permissions: PermissionMonitor,
    private val settings: SettingsRepository,
    private val installed: InstalledModelsRepository,
    private val resolver: ModelResolver,
    private val catalog: CatalogRepository,
) {
    data class Shown(val modelsLine: String, val hiddenUntilEpochMs: Long?)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun start(scope: CoroutineScope): Job = scope.launch {
        // The models line follows the settings (model choice) and the install states (a download finishing). It is
        // computed only while the notification is due: resolving reads the device profile, whose first probe binds
        // the inference processes, which must not happen just because the app started.
        val models: Flow<String> = combine(settings.settings, installed.states, catalog.catalog) { s, _, _ -> s }
            .mapLatest { s -> modelsLine(s) }
            .distinctUntilChanged()
        combine(
            serviceConnected.distinctUntilChanged(),
            permissions.state.map { it.notificationsGranted }.distinctUntilChanged(),
            settings.settings.map { it.hiddenUntilEpochMs }.distinctUntilChanged(),
        ) { connected, allowed, hidden -> Triple(connected, allowed, hidden) }
            .flatMapLatest { (connected, allowed, hidden) ->
                if (decide(connected, allowed, "", hidden) == null) flowOf(null)
                else models.map { line -> decide(connected, allowed, line, hidden) }
            }
            .distinctUntilChanged()
            .collect { shown -> if (shown == null) notifier.hide() else notifier.show(shown.modelsLine, shown.hiddenUntilEpochMs) }
    }

    private suspend fun modelsLine(s: app.murmur.core.store.Settings): String {
        val c = catalog.catalog.value
        return when (val r = resolver.resolve(s)) {
            is Outcome.Ok -> listOfNotNull(r.value.asr.modelId, r.value.llm?.spec?.modelId)
                .joinToString(" · ") { id -> c.model(id)?.displayName ?: id.value }
            // No speech model yet: the notification still offers "Hide 1 h" / "Start dictation" (the bubble explains).
            is Outcome.Err -> ""
        }
    }

    companion object {
        fun decide(connected: Boolean, notificationsAllowed: Boolean, modelsLine: String, hiddenUntil: Long?): Shown? =
            if (connected && notificationsAllowed) Shown(modelsLine, hiddenUntil) else null
    }
}
