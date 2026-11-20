package app.murmur.data.models

import app.murmur.core.catalog.DeviceProfile
import app.murmur.core.catalog.RuntimeKeys
import app.murmur.core.engine.InferenceHost
import app.murmur.core.engine.ProcessProbe
import app.murmur.core.model.ComputeUnit
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable

/** What :main knows without the inference processes. */
data class BaseDeviceInfo(val soc: String?, val abi: String, val totalRamMb: Int)

/**
 * The device-profile probe (ARCHITECTURE 8.4; WP-13 is its only owner), free of Android types:
 *  - [ensure]: this process's profile; else the cached file of this app version; else a probe;
 *  - probe = bind both inference processes, InferenceHost.probe(), merge: units that initialised in either process
 *    (+ CPU), runtime keys of both, `native.build` facts as [DeviceProfile.nativeBuilds] ("asr", "llm"); SoC, ABI and
 *    RAM from [base]. Cached in [cacheFile] with the app version;
 *  - any failure or timeout -> [DeviceProfile.cpuOnly] with the runtimes this build implements, never cached in the
 *    file and never an exception. A fallback is retried: the first [ensure] at least [retryAfterMs] after it probes
 *    again (bounded by [retryTimeoutMs]), so a probe that failed at :main start (boot, a slow first QNN registration)
 *    does not keep a long-lived :main on CPU only (ADR-092);
 *  - after probing, both processes get an idle exit (D5: they are not kept alive by the probe).
 */
class DeviceProfileProber(
    private val cacheFile: File,
    private val appVersion: String,
    private val asrHost: InferenceHost,
    private val llmHost: InferenceHost,
    private val base: () -> BaseDeviceInfo,
    private val timeoutMs: Long = 30_000,
    private val log: DlLog = DlLog.NONE,
    /** How long a cpuOnly fallback is kept before the next [ensure] probes again. */
    private val retryAfterMs: Long = RETRY_AFTER_MS,
    /** Budget of such a retry: it may run inside a press (resolver), so it is shorter than the first probe's. */
    private val retryTimeoutMs: Long = RETRY_TIMEOUT_MS,
    /** Monotonic milliseconds. */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val mutex = Mutex()
    private val _profile = MutableStateFlow<DeviceProfile?>(null)
    val profile: StateFlow<DeviceProfile?> = _profile.asStateFlow()

    /** When the held profile is a cpuOnly fallback: the clock() of that fallback; null for a probed or cached one. */
    private var fallbackAtMs: Long? = null

    suspend fun ensure(): DeviceProfile = mutex.withLock {
        val held = _profile.value
        val fallbackAt = fallbackAtMs
        when {
            held == null -> (readCache() ?: probeOrCpuOnly(timeoutMs)).also { _profile.value = it }
            fallbackAt != null && clock() - fallbackAt >= retryAfterMs -> {
                log.event("probe_retry")
                probeOrCpuOnly(retryTimeoutMs).also { _profile.value = it }
            }
            else -> held
        }
    }

    suspend fun reprobe(): DeviceProfile = mutex.withLock { probeOrCpuOnly(timeoutMs).also { _profile.value = it } }

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
            log.event("probe_failed", "err" to e.javaClass.simpleName)
            null
        }
        if (profile == null) {
            log.event("probe_cpu_only")
            fallbackAtMs = clock()
            return DeviceProfile.cpuOnly(info.abi, info.totalRamMb, RuntimeKeys.implemented)
        }
        fallbackAtMs = null
        writeCache(profile)
        log.event("probe_done", "units" to profile.availableUnits.joinToString(","), "runtimes" to profile.supportedRuntimes.size)
        return profile
    }

    private suspend fun probeOne(host: InferenceHost): ProcessProbe {
        check(host.connect()) { "bind failed" }
        try {
            return host.probe()
        } finally {
            host.scheduleIdleExit(IDLE_AFTER_PROBE_MS)
        }
    }

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
