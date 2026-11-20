package app.murmur.wiring

import app.murmur.core.benchmark.BenchScores
import app.murmur.core.model.ArtifactId
import app.murmur.core.model.ComputeUnit
import app.murmur.core.store.BenchmarkRecord
import app.murmur.core.store.BenchmarkRepository
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * `BinderEngines.prefillTps`: the stored benchmark's prefill speed of (artifact, unit), which scales the timeout of the
 * prefix prefill (`BinderTimeouts.prefixMs`; without it the 40 tok/s floor applies and a slow CPU model such as LFM3 at
 * ~15 tok/s would be killed in the middle of a working prefill). The speed follows ADR-071 through
 * [BenchScores.llmSpeed]: the newest record of (artifact, unit) decides, an invalid one means no speed.
 *
 * The client asks from a binder call path and must not block, so the records come from a hot copy per artifact:
 * [watch] starts one ahead of time (the container watches every catalog LLM at start), and an artifact asked for
 * without a copy (a custom model added later) gets one now and answers null this once (the client then uses the speed
 * of its own last benchmark or the floor).
 */
class StoredPrefillSpeeds(
    private val records: BenchmarkRepository,
    private val scope: CoroutineScope,
) : (ArtifactId, ComputeUnit) -> Double? {
    private val copies = ConcurrentHashMap<ArtifactId, StateFlow<List<BenchmarkRecord>>>()

    fun watch(artifact: ArtifactId): StateFlow<List<BenchmarkRecord>> =
        copies.getOrPut(artifact) { records.latest(artifact).stateIn(scope, SharingStarted.Eagerly, emptyList()) }

    override fun invoke(artifact: ArtifactId, unit: ComputeUnit): Double? =
        BenchScores.llmSpeed(watch(artifact).value, artifact, unit)?.prefillTps
}
