package app.murmur.infer.api

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import app.murmur.core.engine.HostState
import app.murmur.core.model.InferProcess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Binds one inference process on demand (D5) from the accessibility-service context, so that the bound
 * process inherits the service's importance (platform spike: BIND_AUTO_CREATE | BIND_IMPORTANT).
 * Death handling (ARCHITECTURE.md 4.4): onServiceDisconnected / onBindingDied / DeadObjectException ->
 * HostState.Crashed; the caller retries the current step once after a re-bind.
 *
 * WP-05 implements connect/await/unbind; the skeleton only holds the state.
 */
class ServiceConnector(
    /** Read at every bind (accessibility service when connected, else the application). */
    private val bindContext: () -> Context,
    val process: InferProcess,
    private val timeouts: BinderTimeouts = BinderTimeouts(),
) {
    private val _state = MutableStateFlow<HostState>(HostState.Down)
    val state: StateFlow<HostState> = _state.asStateFlow()

    @Volatile var binder: IBinder? = null
        private set

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) { binder = service }
        override fun onServiceDisconnected(name: ComponentName?) { binder = null }
        override fun onBindingDied(name: ComponentName?) { binder = null }
    }

    fun intent(): Intent = bindContext().let { Intent().setClassName(it.packageName, InferApi.serviceClass(process)) }

    /** Bind and wait up to [BinderTimeouts.bindMs] for the binder; checks apiVersion. */
    suspend fun connect(): IBinder? = TODO("WP-05: bindService(intent(), connection, BIND_AUTO_CREATE or BIND_IMPORTANT) + await")

    /** Unbind; the service's onUnbind unloads runtimes and kills its process (InferProcessLifecycle). */
    fun disconnect() { TODO("WP-05") }
}
