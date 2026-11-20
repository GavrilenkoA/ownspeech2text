package app.murmur.wiring

import app.murmur.core.store.SettingsRepository
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.map

/**
 * Debug builds: the Settings switch "Use fake engines" (`Settings.debugFakeEngines`, WP-19's debug group) and the flag
 * file the container reads once when it is built (`files/debug-fake-engines`, DebugWiring; end-to-end and shell tests
 * write the file before the app starts).
 *
 * The file decides the mode of a process: the engines, the resolver and the engine-derived stores are chosen when the
 * container is built and cannot change under a running orchestrator. So [run] first makes the switch show the mode this
 * process runs in ([runningOnFakes]), and from then on writes every change of the switch to the file ([writeFlag]),
 * which applies at the next start of the app. Debug source set only: release builds do not contain it.
 */
class FakeEnginesSwitch(
    private val runningOnFakes: Boolean,
    private val writeFlag: (Boolean) -> Unit,
) {
    /** Suspends for the life of [settings]' flow; launch it in the application scope. */
    suspend fun run(settings: SettingsRepository) {
        if (settings.current().debugFakeEngines != runningOnFakes) {
            settings.update { it.copy(debugFakeEngines = runningOnFakes) }
        }
        settings.settings
            .map { it.debugFakeEngines }
            .distinctUntilChanged()
            // Values from before the alignment above (the stored document's) are not the user's: skip up to the
            // running mode, then that value itself; what follows is a user's toggle.
            .dropWhile { it != runningOnFakes }
            .drop(1)
            .collect { writeFlag(it) }
    }
}
