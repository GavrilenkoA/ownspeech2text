package app.murmur.core.catalog

import app.murmur.core.model.ArtifactId
import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.UnitPreference

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
)

/** One way to run a model: artifact x unit. */
data class Candidate(
    val artifact: Artifact,
    val unit: ComputeUnit,
    val usable: Boolean,
    /** Why not usable, for the hub label ("NPU not supported yet", "Needs SM8750", ...). */
    val blocker: Blocker? = null,
)

enum class Blocker { RUNTIME_NOT_SHIPPED, WRONG_SOC, UNIT_NOT_AVAILABLE }

/** Benchmark summary used for "Auto" (lower score = better: ASR RTF, LLM end-to-end ms). */
fun interface BenchLookup {
    fun score(artifact: ArtifactId, unit: ComputeUnit): Double?
}

/**
 * D2: default to the best available unit, let the user pin one, prefer measured numbers over claims.
 * Pure and deterministic; the hub, the orchestrator and the benchmark use the same function.
 */
object ArtifactSelector {
    private val unitOrder = listOf(ComputeUnit.NPU, ComputeUnit.GPU, ComputeUnit.CPU)

    fun candidates(entry: ModelEntry, device: DeviceProfile): List<Candidate> =
        entry.artifacts.flatMap { a ->
            a.units.map { u ->
                val blocker = when {
                    a.runtime !in device.supportedRuntimes -> Blocker.RUNTIME_NOT_SHIPPED
                    a.socs.isNotEmpty() && device.soc !in a.socs -> Blocker.WRONG_SOC
                    u !in device.availableUnits -> Blocker.UNIT_NOT_AVAILABLE
                    else -> null
                }
                Candidate(a, u, blocker == null, blocker)
            }
        }

    /**
     * Chosen (artifact, unit) among [installed] artifacts (null = consider all, used for "what would we
     * download"). Returns null when nothing usable exists.
     */
    fun choose(
        entry: ModelEntry,
        device: DeviceProfile,
        pref: UnitPreference,
        bench: BenchLookup = BenchLookup { _, _ -> null },
        installed: Set<ArtifactId>? = null,
    ): Candidate? {
        val usable = candidates(entry, device).filter { it.usable && (installed == null || it.artifact.id in installed) }
        if (usable.isEmpty()) return null
        if (pref is UnitPreference.Pinned) return usable.firstOrNull { it.unit == pref.unit }
        val measured = usable.mapNotNull { c -> bench.score(c.artifact.id, c.unit)?.let { c to it } }
        if (measured.isNotEmpty()) return measured.minBy { it.second }.first
        return usable.sortedWith(
            compareBy<Candidate>({ if (it.artifact.preferredUnit == it.unit) 0 else 1 }, { unitOrder.indexOf(it.unit) }),
        ).first()
    }
}
