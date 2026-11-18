package app.murmur.data.models

import app.murmur.core.catalog.Catalog
import app.murmur.core.model.ArtifactId
import app.murmur.core.model.MurmurError
import app.murmur.core.store.InstallState
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Install state of every artifact in this process: rebuilt from disk at start (so it survives process death:
 * installed.json, .part lengths and pending jobs), then updated live by the download job. Artifacts that are simply
 * not installed have no entry (= [InstallState.NotInstalled]).
 */
class InstallStateStore(private val storage: ModelStorage) {
    private val _states = MutableStateFlow<Map<ArtifactId, InstallState>>(emptyMap())
    val states: StateFlow<Map<ArtifactId, InstallState>> = _states.asStateFlow()

    /** Every state change in order (the StateFlow conflates; notifications and tests need the sequence). */
    private val _transitions = MutableSharedFlow<Pair<ArtifactId, InstallState>>(
        extraBufferCapacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val transitions: SharedFlow<Pair<ArtifactId, InstallState>> = _transitions.asSharedFlow()

    fun get(id: ArtifactId): InstallState = _states.value[id] ?: InstallState.NotInstalled

    fun set(id: ArtifactId, state: InstallState) {
        var changed = false
        _states.update { m ->
            if (m[id] == state) m
            else {
                changed = true
                if (state == InstallState.NotInstalled) m - id else m + (id to state)
            }
        }
        if (changed) _transitions.tryEmit(id to state)
    }

    /**
     * State of [id] as the disk and the scheduler see it: installed.json -> Installed; .part bytes with a pending
     * job -> Queued; .part bytes without one -> Paused(DownloadPaused) (the job is gone: app update, force stop);
     * nothing -> NotInstalled.
     */
    fun fromDisk(catalog: Catalog, id: ArtifactId, pendingWifiOnly: Boolean?): InstallState {
        storage.readMarker(id)?.let { return InstallState.Installed(storage.dir(id).absolutePath, it.bytesOnDisk) }
        val artifact = catalog.artifact(id)?.second ?: return InstallState.NotInstalled
        if (pendingWifiOnly != null) return InstallState.Queued(pendingWifiOnly)
        val done = storage.bytesDone(artifact)
        return if (done > 0) InstallState.Paused(done, artifact.downloadBytes, MurmurError.DownloadPaused)
        else InstallState.NotInstalled
    }

    /** Rebuilds every entry that is not live in this process. [pending] = artifact -> wifiOnly of a scheduled job. */
    fun refresh(catalog: Catalog, pending: Map<ArtifactId, Boolean>, live: Set<ArtifactId> = emptySet()) {
        val ids = (storage.artifactDirs() + pending.keys).toSet()
        for (id in ids) if (id !in live) set(id, fromDisk(catalog, id, pending[id]))
        for (id in _states.value.keys - ids - live) set(id, InstallState.NotInstalled)
    }
}
