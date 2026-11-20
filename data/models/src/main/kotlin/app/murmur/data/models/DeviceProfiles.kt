package app.murmur.data.models

import app.murmur.core.catalog.DeviceProfile
import app.murmur.core.catalog.RuntimeKeys
import app.murmur.core.engine.HostState
import app.murmur.core.engine.InferenceHost
import app.murmur.core.engine.ProcessProbe
import app.murmur.core.model.ComputeUnit
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable

/** What :main knows without the inference processes. */
data class BaseDeviceInfo(val soc: String?, val abi: String, val totalRamMb: Int)

/**
 * The device-profile probe (ARCHITECTURE 8.4; WP-13 is its only owner), free of Android types:
 *  - [ensure]: this process's profile; else the cached file of this app version; else a probe. Once a profile is held,
 *    [ensure] returns it at once and never probes: it runs inside a press (resolver), ADR-093;
 *  - probe = bind both inference processes, InferenceHost.probe(), merge: units that initialised in either process
 *    (+ CPU), runtime keys of both, `native.build` facts as [DeviceProfile.nativeBuilds] ("asr", "llm"); SoC, ABI and
 *    RAM from [base]. Cached in [cacheFile] with the app version;
 *  - any failure or timeout, including a process whose reply carries [ProcessProbe.FACT_ERROR] (a PROBE call that timed
 *    out), -> [DeviceProfile.cpuOnly] with the runtimes this build implements, never cached in the file and never an
 *    exception;
 *  - a fallback is retried (ADR-092/093), so a probe that failed at :main start (boot, a slow first QNN registration)
 *    does not keep a long-lived :main on CPU only: [retryAfterMs] after it, doubling after each failed retry up to
 *    [retryMaxMs], in [retryScope] and through [runRetry] (the app runs it in the orchestrator's background slot: never
 *    during a dictation, a press cancels it, it runs again after); bounded by [retryTimeoutMs];
 *  - after probing, a process the probe itself started gets the short idle exit through [releaseAfterProbe] (D5: it is
 *    not kept alive by the probe); a process that was already up keeps the idle timer its user set (ADR-086).
 */
class DeviceProfileProber(
    private val cacheFile: File,
    private val appVersion: String,
    private val asrHost: InferenceHost,
    private val llmHost: InferenceHost,
    private val base: () -> BaseDeviceInfo,
    private val timeoutMs: Long = 30_000,
    private val log: DlLog = DlLog.NONE,
    /** Wait before the first retry of a cpuOnly fallback. */
    private val retryAfterMs: Long = RETRY_AFTER_MS,
    /** Each failed retry doubles the wait, up to this. */
    private val retryMaxMs: Long = RETRY_MAX_MS,
    /** Budget of one retry (it holds the background slot meanwhile). */
    private val retryTimeoutMs: Long = RETRY_TIMEOUT_MS,
    /** Where retries wait; null = no retries (a fallback is kept until [reprobe]). */
    private val retryScope: CoroutineScope? = null,
    /** Runs one retry; the app passes the orchestrator's background slot (`whenNoDictation`). */
    private val runRetry: suspend (suspend () -> Unit) -> Unit = { it() },
    /** Context of a retry's binder and file work (tests: none, for virtual time). */
    private val retryContext: CoroutineContext = Dispatchers.IO,
    /** The idle exit of a process the probe started; the app routes it through the orchestrator (no-op in a dictation). */
    private val releaseAfterProbe: (InferenceHost) -> Unit = { it.scheduleIdleExit(IDLE_AFTER_PROBE_MS) },
) {
    private val mutex = Mutex()
    private val _profile = MutableStateFlow<DeviceProfile?>(null)
    val profile: StateFlow<DeviceProfile?> = _profile.asStateFlow()

    /** Guarded by [mutex]: whether the held profile is a cpuOnly fallback, and the wait before its next retry. */
    private var fallback = false
    private var retryWaitMs = retryAfterMs
    private var retryJob: Job? = null

    suspend fun ensure(): DeviceProfile {
        _profile.value?.let { return it }
        return mutex.withLock { _profile.value ?: (readCache() ?: probeOrCpuOnly(timeoutMs)).also { publish(it) } }
    }

    suspend fun reprobe(): DeviceProfile = mutex.withLock {
        retryWaitMs = retryAfterMs
        probeOrCpuOnly(timeoutMs).also { publish(it) }
    }

    /** The scheduled retry, if any (tests). */
    internal val pendingRetry: Job? get() = retryJob

    private fun publish(p: DeviceProfile) {
        _profile.value = p
        if (fallback) scheduleRetry() else { retryJob?.cancel(); retryJob = null; retryWaitMs = retryAfterMs }
    }

    private fun scheduleRetry() {
        val scope = retryScope ?: return
        val wait = retryWaitMs
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(wait)
            runRetry { retryNow() }
        }
    }

    private suspend fun retryNow() = withContext(retryContext) {
        mutex.withLock {
            if (!fallback) return@withLock   // a reprobe got there first
            log.event("probe_retry", "after_ms" to retryWaitMs)
            val p = probeOrCpuOnly(retryTimeoutMs)
            if (fallback) retryWaitMs = (retryWaitMs * 2).coerceAtMost(retryMaxMs)
            // Not publish(): this coroutine is the retry job; the next one is scheduled by a new launch.
            _profile.value = p
            retryJob = null
            if (fallback) scheduleRetry() else retryWaitMs = retryAfterMs
        }
    }

    private suspend fun probeOrCpuOnly(budgetMs: Long): DeviceProfile {
        val info = safeBase()
        val profile = try {
            withTimeout(budgetMs) {
                val asr = probeOne(asrHost)
                val llm = probeOne(llmHost)
                merge(info, asr, llm)
            }
        } catch (e: CancellationException) {
            if (e is kotlinx.coroutines.TimeoutCancellationException) null else throw e
        } catch (e: Exception) {
            log.event("probe_failed", "err" to e.javaClass.simpleName, "detail" to (e as? ProbeFailed)?.message)
            null
        }
        if (profile == null) {
            log.event("probe_cpu_only")
            fallback = true
            return DeviceProfile.cpuOnly(info.abi, info.totalRamMb, RuntimeKeys.implemented)
        }
        fallback = false
        writeCache(profile)
        log.event("probe_done", "units" to profile.availableUnits.joinToString(","), "runtimes" to profile.supportedRuntimes.size)
        return profile
    }

    private suspend fun probeOne(host: InferenceHost): ProcessProbe {
        // A process that is up or starting belongs to someone else's use (a dictation, preparation, a warm keep): its
        // idle timer stays theirs. Only a process this probe starts gets the short exit.
        val started = host.state.value.let { it is HostState.Down || it is HostState.Crashed }
        check(host.connect()) { "bind failed" }
        try {
            val p = host.probe()
            p.facts[ProcessProbe.FACT_ERROR]?.let { throw ProbeFailed(host.process.name, it) }
            return p
        } finally {
            if (started) releaseAfterProbe(host)
        }
    }

    private class ProbeFailed(process: String, code: String) : Exception("$process: $code")

    private fun safeBase(): BaseDeviceInfo = try { base() } catch (e: Exception) { BaseDeviceInfo(null, "unknown", 0) }

    private fun readCache(): DeviceProfile? = try {
        cacheFile.takeIf { it.isFile }
            ?.let { ModelStorage.json.decodeFromString(CachedProfile.serializer(), it.readText()) }
            ?.takeIf { it.appVersion == appVersion }
            ?.toProfile()
    } catch (e: Exception) {
        null
    }

    private fun writeCache(p: DeviceProfile) = try {
        ModelStorage.writeAtomically(cacheFile, ModelStorage.json.encodeToString(CachedProfile.serializer(), CachedProfile.of(appVersion, p)))
    } catch (e: Exception) {
        log.event("probe_cache_write_failed", "err" to e.javaClass.simpleName)
    }

    companion object {
        const val IDLE_AFTER_PROBE_MS = 5_000L
        const val RETRY_AFTER_MS = 5 * 60_000L
        const val RETRY_MAX_MS = 60 * 60_000L
        const val RETRY_TIMEOUT_MS = 10_000L

        fun merge(info: BaseDeviceInfo, asr: ProcessProbe, llm: ProcessProbe): DeviceProfile = DeviceProfile(
            soc = info.soc,
            abi = info.abi,
            availableUnits = asr.units + llm.units + ComputeUnit.CPU,
            supportedRuntimes = asr.runtimes + llm.runtimes,
            totalRamMb = info.totalRamMb,
            nativeBuilds = buildMap {
                asr.facts[ProcessProbe.FACT_NATIVE_BUILD]?.let { put("asr", it) }
                llm.facts[ProcessProbe.FACT_NATIVE_BUILD]?.let { put("llm", it) }
            },
        )
    }
}

@Serializable
private data class CachedProfile(
    val appVersion: String,
    val soc: String?,
    val abi: String,
    val units: Set<ComputeUnit>,
    val runtimes: Set<String>,
    val totalRamMb: Int,
    val nativeBuilds: Map<String, String>,
) {
    fun toProfile() = DeviceProfile(soc, abi, units, runtimes, totalRamMb, nativeBuilds)

    companion object {
        fun of(v: String, p: DeviceProfile) =
            CachedProfile(v, p.soc, p.abi, p.availableUnits, p.supportedRuntimes, p.totalRamMb, p.nativeBuilds)
    }
}
