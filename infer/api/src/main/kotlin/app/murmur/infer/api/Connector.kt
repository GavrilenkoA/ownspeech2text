package app.murmur.infer.api

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Process
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import app.murmur.core.engine.HostState
import app.murmur.core.model.InferProcess
import app.murmur.core.model.MurmurError
import app.murmur.core.model.Outcome
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Binds one inference process on demand (D5) and owns its lifecycle as seen from :main (ARCHITECTURE.md 4.2, 4.4).
 *
 * - [connect]: `bindService(BIND_AUTO_CREATE or BIND_IMPORTANT)` from [bindContext] (read at every bind: the
 *   accessibility service when connected, so the process inherits its importance, else the application), waits
 *   [BinderTimeouts.bindMs] for the binder, checks `apiVersion()`, then [HostState.Up] with the process pid.
 * - Death: `onServiceDisconnected`, `onBindingDied`, the binder's death recipient or a [DeadObjectException] in a
 *   call -> [HostState.Crashed] (count since the last clean exit), one emission on [deaths], every call or stream
 *   in flight fails with `ProcessDied`, and the dead binding is dropped so that the system does not restart the
 *   service behind the caller's back; the next [connect] binds a fresh process.
 * - Idle exit: [scheduleIdleExit] arms a timer that every use re-arms; it never fires while a call, an ASR session
 *   or a generation is in use. Expiry = unload + unbind (the service exits in `onUnbind`), checked like [shutdown].
 *   A host is never left running without a timer: [DEFAULT_IDLE_MS] applies until the first [scheduleIdleExit].
 * - [shutdown]: unload now, unbind, wait until the process is gone ([BinderTimeouts.exitWaitMs]); a process that is
 *   still there is killed (same UID). `force` (after a timeout) skips the unload and kills at once.
 */
class ServiceConnector(
    private val bindContext: () -> Context,
    val process: InferProcess,
    private val timeouts: () -> BinderTimeouts,
    /** Reads `apiVersion()` from the service binder (the AIDL interface differs per process). */
    private val apiVersionOf: (IBinder) -> Int,
    /** Unloads every runtime in the process (`unloadAll()` / `unload()`); called before a clean exit. */
    private val unloadAll: (IBinder) -> Unit,
    private val serviceClassName: String = InferApi.serviceClass(process),
) {
    private val tag = "murmur-ipc-${process.name.lowercase()}"
    private val _state = MutableStateFlow<HostState>(HostState.Down)
    val state: StateFlow<HostState> = _state.asStateFlow()

    private val _deaths = MutableSharedFlow<Int>(extraBufferCapacity = 16)
    /** Emits the crash count each time the process dies unexpectedly (WP-11's executor watches this or [state]). */
    val deaths: SharedFlow<Int> = _deaths.asSharedFlow()

    /** Detached scope for idle timers, abandoned blocking binder calls and fire-and-forget calls. */
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val callbacks: Executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "murmur-bind-${process.name.lowercase()}").apply { isDaemon = true }
    }
    private val lifecycle = Mutex()
    private val lock = Any()
    @Volatile private var current: Binding? = null
    private var crashes = 0

    private val inUse = AtomicInteger()
    @Volatile private var idleMs: Long = DEFAULT_IDLE_MS
    private var idleJob: Job? = null

    /** One bindService() call; callbacks of a binding that is no longer [current] change nothing. */
    internal inner class Binding(val ctx: Context, val processName: String?) : ServiceConnection, IBinder.DeathRecipient {
        /** Completes with the binder, or null when the process died or refused before connecting. */
        val ready = CompletableDeferred<IBinder?>()
        val dead = CompletableDeferred<Unit>()
        @Volatile var binder: IBinder? = null
        @Volatile var pid = 0
        /** Set before an unbind or kill of our own: the death that follows is not a crash. */
        @Volatile var intentional = false
        /** The service returned no binder from onBind. */
        @Volatile var refused = false
        /** connect() has not finished with this binding: a death now is handled there, not counted here. */
        @Volatile var starting = false
        @Volatile private var unbound = false
        private val watchers = ConcurrentHashMap.newKeySet<(MurmurError) -> Unit>()

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (service == null || binder != null) return
            try {
                // Throws for a binder whose process is already gone (a stale binder right after a kill).
                service.linkToDeath(this, 0)
            } catch (_: RemoteException) {
                died(); return
            }
            binder = service
            ready.complete(service)
        }

        override fun onServiceDisconnected(name: ComponentName?) = died()
        override fun onBindingDied(name: ComponentName?) = died()
        override fun onNullBinding(name: ComponentName?) {
            refused = true
            ready.complete(null)
        }
        override fun binderDied() = died()

        /** [w] runs once if the process dies (now, if it is already dead). Returns a handle for [unwatch]. */
        fun watch(w: (MurmurError) -> Unit): (MurmurError) -> Unit {
            watchers += w
            if (dead.isCompleted) { watchers -= w; w(MurmurError.ProcessDied(process)) }
            return w
        }

        fun unwatch(w: (MurmurError) -> Unit) { watchers -= w }

        fun unbind() {
            if (unbound) return
            unbound = true
            runCatching { ctx.unbindService(this) }
        }

        fun died() {
            if (!dead.complete(Unit)) return
            ready.complete(null)
            runCatching { binder?.unlinkToDeath(this, 0) }
            unbind()
            // State first: whoever receives ProcessDied below already sees Crashed.
            onBindingDead(this)
            val error = MurmurError.ProcessDied(process)
            watchers.toList().forEach { runCatching { it(error) } }
            watchers.clear()
        }
    }

    fun intent(ctx: Context): Intent = Intent().setClassName(ctx.packageName, serviceClassName)

    /** True while a live, version-checked binder is held. */
    val isConnected: Boolean get() = current?.let { it.binder?.isBinderAlive == true && !it.dead.isCompleted } == true

    /** Bind (starting the process) and return the live binder; a no-op when already connected. */
    suspend fun connect(): Outcome<IBinder> = withContext(Dispatchers.IO) { lifecycle.withLock { connectLocked() } }

    private suspend fun connectLocked(): Outcome<IBinder> {
        current?.let { b ->
            val binder = b.binder
            if (binder != null && binder.isBinderAlive && !b.dead.isCompleted) return Outcome.Ok(binder)
            b.intentional = true
            b.unbind()
            current = null
        }
        val t = timeouts()
        _state.value = HostState.Starting
        val ctx = bindContext() // once per connect: every attempt below is the same logical bind
        val deadline = SystemClock.elapsedRealtime() + t.scaled(t.bindMs)
        var attempt = 0
        while (true) {
            attempt++
            when (val r = attemptBind(ctx, t, deadline)) {
                is Attempt.Done -> return r.result
                is Attempt.Died -> {
                    // Right after a kill the system can still hand out the dying process's binder; bind again.
                    if (attempt < MAX_BIND_ATTEMPTS && SystemClock.elapsedRealtime() + RETRY_DELAY_MS < deadline) {
                        delay(RETRY_DELAY_MS)
                        continue
                    }
                    return Outcome.Err(startupDeath(r.binding))
                }
            }
        }
    }

    private sealed interface Attempt {
        class Done(val result: Outcome<IBinder>) : Attempt
        /** The process (or the binder handed to us) died before the connection was usable. */
        class Died(val binding: Binding) : Attempt
    }

    private suspend fun attemptBind(ctx: Context, t: BinderTimeouts, deadline: Long): Attempt {
        val b = Binding(ctx, processNameOf(ctx))
        b.starting = true
        current = b
        val bound = try {
            ctx.bindService(intent(ctx), Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT, callbacks, b)
        } catch (e: SecurityException) {
            Log.w(tag, "bind refused: ${e.javaClass.simpleName}")
            false
        }
        if (!bound) return Attempt.Done(fail(b, MurmurError.Internal("bind")))
        val binder = withTimeoutOrNull((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1)) { b.ready.await() }
        if (binder == null) {
            if (b.dead.isCompleted) return Attempt.Died(b)
            // Refused (null binding) or not up in time.
            terminate(b, force = true)
            return Attempt.Done(Outcome.Err(if (b.refused) MurmurError.Internal("bind") else MurmurError.ProcessDied(process)))
        }
        val version = blocking(t.scaled(t.callMs)) { apiVersionOf(binder) }
        if (version !is Outcome.Ok) {
            if (b.dead.isCompleted || (version as Outcome.Err).error is MurmurError.ProcessDied) {
                b.died()
                return Attempt.Died(b)
            }
            terminate(b, force = true)
            return Attempt.Done(version.asError())
        }
        if (version.value != InferApi.API_VERSION) {
            Log.w(tag, "api version ${version.value} != ${InferApi.API_VERSION}")
            // Another build of the service: no other call is safe, not even unload.
            terminate(b, force = true)
            return Attempt.Done(Outcome.Err(MurmurError.Internal("api")))
        }
        b.pid = b.processName?.let { pidOf(ctx, it) } ?: 0
        synchronized(lock) {
            b.starting = false
            if (current === b && !b.dead.isCompleted) {
                _state.value = HostState.Up(b.pid)
                return Attempt.Done(Outcome.Ok(binder))
            }
        }
        return Attempt.Died(b)
    }

    /** Every bind attempt ended in a death: that is a crash of the process, like a death after it was up. */
    private fun startupDeath(b: Binding): MurmurError {
        val count: Int
        synchronized(lock) {
            if (current === b) current = null
            crashes++
            count = crashes
            _state.value = HostState.Crashed(count)
        }
        Log.w(tag, "process died while starting (crash $count)")
        _deaths.tryEmit(count)
        return MurmurError.ProcessDied(process)
    }

    private fun fail(b: Binding, error: MurmurError): Outcome<Nothing> {
        b.intentional = true
        b.unbind()
        synchronized(lock) { if (current === b) { current = null; _state.value = HostState.Down } }
        return Outcome.Err(error)
    }

    /** Called once per binding when its process is gone. */
    private fun onBindingDead(b: Binding) {
        val count: Int
        synchronized(lock) {
            if (current !== b || b.starting) return
            current = null
            if (b.intentional) {
                _state.value = HostState.Down
                return
            }
            crashes++
            count = crashes
            _state.value = HostState.Crashed(count)
            idleJob?.cancel()
        }
        Log.w(tag, "process died (crash $count)")
        _deaths.tryEmit(count)
    }

    /** The current binding and its live binder, connecting first if needed. */
    internal suspend fun acquire(): Outcome<Pair<Binding, IBinder>> = withContext(Dispatchers.IO) {
        lifecycle.withLock {
            when (val r = connectLocked()) {
                is Outcome.Ok -> current?.let { Outcome.Ok(it to r.value) } ?: Outcome.Err(MurmurError.ProcessDied(process))
                is Outcome.Err -> r
            }
        }
    }

    /** The current binding if its binder is alive; never binds. */
    internal fun live(): Pair<Binding, IBinder>? = current?.let { b ->
        b.binder?.takeIf { it.isBinderAlive && !b.dead.isCompleted }?.let { b to it }
    }

    /** After a timeout: kill the process of [b] unless it is already gone or replaced by a newer binding. */
    internal suspend fun kill(b: Binding) = withContext(NonCancellable + Dispatchers.IO) {
        lifecycle.withLock { if (current === b) terminate(b, force = true) }
    }

    /** Marks the binding dead after a [DeadObjectException] (the death callbacks may arrive later or not at all). */
    internal fun onDeadObject(b: Binding) = b.died()

    // --- use counting and idle exit ------------------------------------------------------------------------------

    /** A call, session or stream starts: the idle timer stops until the matching [endUse]. */
    fun beginUse() {
        inUse.incrementAndGet()
        synchronized(lock) { idleJob?.cancel(); idleJob = null }
    }

    fun endUse() {
        if (inUse.decrementAndGet() <= 0) {
            inUse.set(0)
            armIdle()
        }
    }

    /** Keep the process for [ms] after the last use, then unload and exit. Re-armed by every use. */
    fun scheduleIdleExit(ms: Long) {
        idleMs = ms.coerceAtLeast(0)
        // Called after every Done/Failed: the crash count is per dictation.
        synchronized(lock) { crashes = 0 }
        armIdle()
    }

    private fun armIdle() {
        synchronized(lock) {
            idleJob?.cancel()
            idleJob = null
            if (inUse.get() > 0 || current == null) return
            val ms = idleMs
            idleJob = scope.launch {
                delay(ms)
                // Past this point a new use waits for the exit to finish instead of cancelling it half-way.
                withContext(NonCancellable) { shutdownInternal(force = false, idle = true) }
            }
        }
    }

    // --- exit ----------------------------------------------------------------------------------------------------

    /** Unload now, unbind, wait until the process is gone; kill it if it is still there after the wait. */
    suspend fun shutdown(force: Boolean = false) {
        synchronized(lock) { idleJob?.cancel(); idleJob = null }
        shutdownInternal(force, idle = false)
    }

    private suspend fun shutdownInternal(force: Boolean, idle: Boolean) = withContext(NonCancellable + Dispatchers.IO) {
        lifecycle.withLock {
            if (idle && inUse.get() > 0) return@withLock
            val b = current
            if (b == null) {
                _state.value = HostState.Down
                return@withLock
            }
            terminate(b, force)
        }
    }

    /** Caller holds [lifecycle]. Ends [b] for good: state Down once the process is gone. Not cancellable. */
    private suspend fun terminate(b: Binding, force: Boolean) = withContext(NonCancellable) {
        val t = timeouts()
        b.intentional = true
        val binder = b.binder
        if (!force && binder != null && !b.dead.isCompleted) {
            blocking(t.scaled(t.callMs)) { unloadAll(binder) }
        }
        val pid = b.pid.takeIf { it > 0 } ?: b.processName?.let { pidOf(b.ctx, it) } ?: 0
        b.unbind()
        if (force && pid > 0) Process.killProcess(pid)
        if (pid > 0 && !awaitExit(b, pid, t.exitWaitMs)) {
            Log.w(tag, "process still alive after ${t.exitWaitMs} ms; killing")
            Process.killProcess(pid)
            awaitExit(b, pid, KILL_WAIT_MS)
        }
        synchronized(lock) {
            if (current === b) current = null
            _state.value = HostState.Down
        }
        if (!b.dead.isCompleted) b.died()
    }

    /** Waits for the binder death notification and for the pid to leave /proc. */
    private suspend fun awaitExit(b: Binding, pid: Int, ms: Long): Boolean = withTimeoutOrNull(ms) {
        if (b.binder != null) b.dead.await()
        while (isAlive(pid, b.processName)) delay(EXIT_POLL_MS)
        true
    } ?: false

    // --- blocking binder calls -----------------------------------------------------------------------------------

    /**
     * Runs a synchronous binder call on an IO thread and waits at most [timeoutMs]. A binder transaction cannot be
     * interrupted, so on timeout the thread is abandoned (it returns once the process is killed) and the result is
     * [TIMEOUT_MARKER], which the caller maps to its [BinderCall] stage.
     */
    internal suspend fun <T> blocking(timeoutMs: Long, call: () -> T): Outcome<T> {
        val d = scope.async(Dispatchers.IO) { runCatching(call) }
        val r = withTimeoutOrNull(timeoutMs) { d.await() } ?: return Outcome.Err(TIMEOUT_MARKER)
        return r.fold({ Outcome.Ok(it) }, { Outcome.Err(binderError(it)) })
    }

    internal fun binderError(e: Throwable): MurmurError = when (e) {
        is DeadObjectException -> MurmurError.ProcessDied(process)
        else -> MurmurError.Internal("binder:${e.javaClass.simpleName}")
    }

    private fun processNameOf(ctx: Context): String? = runCatching {
        ctx.packageManager.getServiceInfo(
            ComponentName(ctx.packageName, serviceClassName), PackageManager.ComponentInfoFlags.of(0),
        ).processName
    }.getOrNull()

    private fun <T> Outcome<T>.asError(): Outcome<Nothing> = when (this) {
        is Outcome.Err -> if (error === TIMEOUT_MARKER) Outcome.Err(MurmurError.Timeout(BinderCall.API_VERSION.stage)) else this
        is Outcome.Ok -> Outcome.Err(MurmurError.Internal("unexpected"))
    }

    companion object {
        /** D5 default (DictationConfig.idleUnloadMs) until the orchestrator sets Settings.idleUnloadSec. */
        const val DEFAULT_IDLE_MS = 60_000L
        private const val KILL_WAIT_MS = 1_000L
        private const val MAX_BIND_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 100L
        private const val EXIT_POLL_MS = 20L

        /** Returned by [blocking] when the call did not finish in time; callers map it to their [BinderCall]. */
        internal val TIMEOUT_MARKER = MurmurError.Internal("binder:timeout")

        /** Pid of our own process [name] (same UID: visible in getRunningAppProcesses). */
        fun pidOf(ctx: Context, name: String): Int? =
            ctx.getSystemService(ActivityManager::class.java)?.runningAppProcesses
                ?.firstOrNull { it.processName == name }?.pid

        /** True while /proc/<pid> belongs to the process [name] (guards against pid reuse). */
        fun isAlive(pid: Int, name: String?): Boolean {
            val cmd = runCatching { File("/proc/$pid/cmdline").readBytes() }.getOrNull() ?: return false
            if (cmd.isEmpty()) return false
            if (name == null) return true
            val argv0 = String(cmd, Charsets.UTF_8).substringBefore('\u0000')
            return argv0 == name
        }
    }
}
