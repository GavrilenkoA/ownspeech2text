package app.murmur.core.catalog

import app.murmur.core.model.ArtifactId
import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.UnitPreference
import kotlinx.coroutines.flow.StateFlow

/**
 * What this phone can run, probed once per app version by the inference processes (ARCHITECTURE.md
 * section 8.4) and cached. On the x86_64 emulator: soc = null, units = {CPU}.
 */
data class DeviceProfile(
    val soc: String?,                     // Build.SOC_MODEL, e.g. "SM8750"
    val abi: String,                      // Build.SUPPORTED_ABIS[0]
    val availableUnits: Set<ComputeUnit>, // units whose runtime initialised (HTP0 registered, OpenCL found, ...)
    val supportedRuntimes: Set<String>,   // RuntimeKeys implemented by the shipped inference processes
    val totalRamMb: Int,
    /** Native runtime build ids from the probes ("llama.cpp b12520 …", "sherpa 2.0.1 …"); part of the prefix cache key. */
    val nativeBuilds: Map<String, String> = emptyMap(),
) {
    companion object {
        /** Used when the probe failed or has not run yet: never throws, never claims an accelerator (ARCHITECTURE 8.4). */
        fun cpuOnly(abi: String, totalRamMb: Int, runtimes: Set<String>) =
            DeviceProfile(soc = null, abi = abi, availableUnits = setOf(ComputeUnit.CPU), supportedRuntimes = runtimes, totalRamMb = totalRamMb)
    }
}

/**
 * Owner of the probe and its cache (WP-13, ARCHITECTURE 8.4). [ensure] binds both inference processes, calls
 * InferenceHost.probe(), caches the result per app version and returns it; on any failure it returns (and caches
 * for this process only) a [DeviceProfile.cpuOnly] profile. It never throws, so callers in :main (resolver,
 * onboarding pack sizes) can always call it.
 */
interface DeviceProfileRepository {
    val profile: StateFlow<DeviceProfile?>
    suspend fun ensure(): DeviceProfile
    /** Re-probe (after an app update, or when the user taps "Run benchmark" on a unit marked failed). */
    suspend fun reprobe(): DeviceProfile
}

/**
 * Real-world health of (artifact, unit) on this phone, learned from loads (ARCHITECTURE 4.4, 8.5). A unit that
 * failed to load is skipped by [ArtifactSelector] until the app version changes or a benchmark of that unit
 * succeeds. "Proven" = one real load (plus a short decode) succeeded on this device (install-time verification).
 */
interface UnitHealth {
    fun failed(artifact: ArtifactId, unit: ComputeUnit): Boolean
    fun proven(artifact: ArtifactId, unit: ComputeUnit): Boolean

    companion object {
        val NONE = object : UnitHealth {
            override fun failed(artifact: ArtifactId, unit: ComputeUnit) = false
            override fun proven(artifact: ArtifactId, unit: ComputeUnit) = false
        }
    }
}

/** Persistent [UnitHealth] (WP-13; one small JSON file in noBackupFilesDir, keyed by app version). */
interface UnitHealthRepository : UnitHealth {
    suspend fun markFailed(artifact: ArtifactId, unit: ComputeUnit, code: String)
    suspend fun markProven(artifact: ArtifactId, unit: ComputeUnit)
    /** A successful benchmark of that unit clears the failure (BenchmarkRunner calls this). */
    suspend fun clear(artifact: ArtifactId, unit: ComputeUnit)
}

/** One way to run a model: artifact x unit. */
data class Candidate(
    val artifact: Artifact,
    val unit: ComputeUnit,
    val usable: Boolean,
    /** Why not usable, for the hub label ("NPU not supported yet", "Needs SM8750", ...). */
    val blocker: Blocker? = null,
)

enum class Blocker {
    RUNTIME_NOT_SHIPPED,
    WRONG_SOC,
    UNIT_NOT_AVAILABLE,
    /** Loading on this unit failed on this phone ([UnitHealth.failed]); hub: "Failed to load on NPU, using CPU". */
    UNIT_FAILED,
}

/**
 * Benchmark summary used for "Auto" (lower score = better: ASR processing ms per audio second, LLM end-to-end ms
 * of the fixed case). Implementations MUST return null for records with `valid == false` (wrong text, empty
 * output, early stop: ARCHITECTURE 10), so that a fast-but-broken unit is never chosen.
 */
fun interface BenchLookup {
    fun score(artifact: ArtifactId, unit: ComputeUnit): Double?
}

/**
 * D2: default to the best available unit, let the user pin one, prefer measured numbers over claims.
 * Pure and deterministic; the hub, the orchestrator and the benchmark use the same function.
 */
object ArtifactSelector {
    private val unitOrder = listOf(ComputeUnit.NPU, ComputeUnit.GPU, ComputeUnit.CPU)

    fun candidates(entry: ModelEntry, device: DeviceProfile, health: UnitHealth = UnitHealth.NONE): List<Candidate> =
        entry.artifacts.flatMap { a ->
            a.units.map { u ->
                val blocker = when {
                    a.runtime !in device.supportedRuntimes -> Blocker.RUNTIME_NOT_SHIPPED
                    a.socs.isNotEmpty() && device.soc !in a.socs -> Blocker.WRONG_SOC
                    u !in device.availableUnits -> Blocker.UNIT_NOT_AVAILABLE
                    health.failed(a.id, u) -> Blocker.UNIT_FAILED
                    else -> null
                }
                Candidate(a, u, blocker == null, blocker)
            }
        }

    /**
     * Chosen (artifact, unit) among [installed] artifacts (null = consider all, used for "what would we
     * download"). Returns null when nothing usable exists. A [UnitPreference.Pinned] unit whose artifact is not
     * installed returns null here; the caller (resolver / hub) then offers that artifact's download instead of
     * failing (WP-13, WP-18).
     */
    fun choose(
        entry: ModelEntry,
        device: DeviceProfile,
        pref: UnitPreference,
        bench: BenchLookup = BenchLookup { _, _ -> null },
        installed: Set<ArtifactId>? = null,
        health: UnitHealth = UnitHealth.NONE,
    ): Candidate? = ranked(entry, device, pref, bench, installed, health).firstOrNull()

    /**
     * Every usable candidate in preference order. [choose] = first; the load fallback (ARCHITECTURE 4.4) = the
     * first entry after the one that failed. A pinned unit comes first, then the rest in Auto order, so a failing
     * pinned unit still falls back.
     */
    fun ranked(
        entry: ModelEntry,
        device: DeviceProfile,
        pref: UnitPreference,
        bench: BenchLookup = BenchLookup { _, _ -> null },
        installed: Set<ArtifactId>? = null,
        health: UnitHealth = UnitHealth.NONE,
    ): List<Candidate> {
        val usable = candidates(entry, device, health).filter { it.usable && (installed == null || it.artifact.id in installed) }
        if (usable.isEmpty()) return emptyList()
        // Valid benchmark score first; then the catalog's artifact order (the default artifact first, so a fallback
        // prefers the same file on the next unit: no download); then the artifact's preferredUnit; then NPU > GPU > CPU.
        val artifactIndex = entry.artifacts.withIndex().associate { (i, a) -> a.id to i }
        val auto = usable.sortedWith(
            compareBy<Candidate>(
                { c -> bench.score(c.artifact.id, c.unit) ?: Double.MAX_VALUE },
                { c -> artifactIndex[c.artifact.id] ?: Int.MAX_VALUE },
                { c -> if (c.artifact.preferredUnit == c.unit) 0 else 1 },
                { c -> unitOrder.indexOf(c.unit) },
            ),
        )
        return if (pref is UnitPreference.Pinned) {
            val pinned = auto.filter { it.unit == pref.unit }
            if (pinned.isEmpty()) emptyList() else pinned + (auto - pinned.toSet())
        } else auto
    }

    /** The next candidate after [failed] (same order as [ranked]); null = no fallback installed. */
    fun fallbackAfter(ranked: List<Candidate>, failed: Candidate): Candidate? =
        ranked.dropWhile { !(it.artifact.id == failed.artifact.id && it.unit == failed.unit) }.drop(1).firstOrNull()
}

/**
 * Memory the gate subtracts for one loaded artifact (ARCHITECTURE 7). Catalog value when present, else the
 * fallback formula: LLM = file bytes x 1.2 + KV(nCtx); ASR/OCR/VAD = file bytes x 1.5. Custom models always use the
 * formula. Pure.
 */
object MemoryEstimate {
    private const val MIB = 1024.0 * 1024.0

    fun mb(entry: ModelEntry, artifact: Artifact, unit: ComputeUnit): Int {
        artifact.memoryMb[unit]?.let { return it }
        val files = artifact.downloadBytes / MIB
        val llm = entry.llm
        return if (llm != null) kotlin.math.ceil(files * 1.2 + kvMb(llm)).toInt()
        else kotlin.math.ceil(files * 1.5).toInt()
    }

    /** KV cache (+ recurrent state) for the full context window; null kvBytesPerToken = assume 12 KiB/token (hybrids). */
    fun kvMb(llm: LlmTraits): Double = llm.nCtx.toDouble() * (llm.kvBytesPerToken ?: 12_288) / MIB
}
