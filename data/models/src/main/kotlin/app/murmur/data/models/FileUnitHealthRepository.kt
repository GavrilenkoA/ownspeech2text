package app.murmur.data.models

import app.murmur.core.catalog.UnitHealthRepository
import app.murmur.core.model.ArtifactId
import app.murmur.core.model.ComputeUnit
import kotlinx.serialization.Serializable

/**
 * UnitHealth persisted as JSON in noBackupFilesDir/models/unit-health.json (ARCHITECTURE 4.4, 8.3, ADR-050).
 *  - a failed (artifact, unit) is skipped by ArtifactSelector until the app version changes (the file is reset) or
 *    [clear] is called by the benchmark after a valid record on that unit, or a real load proves it again
 *    ([markProven] clears the failure);
 *  - reads are served from memory (the selector calls [failed] in a sort), writes are atomic.
 * Failure codes are MurmurError codes, never text.
 */
class FileUnitHealthRepository(private val storage: ModelStorage, private val appVersion: String) : UnitHealthRepository {
    private val lock = Any()
    private var state: HealthFile? = null

    override fun failed(artifact: ArtifactId, unit: ComputeUnit): Boolean = synchronized(lock) { key(artifact, unit) in load().failed }

    override fun proven(artifact: ArtifactId, unit: ComputeUnit): Boolean = synchronized(lock) { key(artifact, unit) in load().proven }

    /** Failure code of (artifact, unit), for the hub ("Failed to load on NPU, using CPU"); null = not failed. */
    fun failureCode(artifact: ArtifactId, unit: ComputeUnit): String? = synchronized(lock) { load().failed[key(artifact, unit)] }

    override suspend fun markFailed(artifact: ArtifactId, unit: ComputeUnit, code: String) = update {
        it.copy(failed = it.failed + (key(artifact, unit) to code), proven = it.proven - key(artifact, unit))
    }

    override suspend fun markProven(artifact: ArtifactId, unit: ComputeUnit) = update {
        it.copy(proven = it.proven + key(artifact, unit), failed = it.failed - key(artifact, unit))
    }

    override suspend fun clear(artifact: ArtifactId, unit: ComputeUnit) = update { it.copy(failed = it.failed - key(artifact, unit)) }

    private fun update(f: (HealthFile) -> HealthFile) = synchronized(lock) {
        val next = f(load())
        if (next != state) {
            ModelStorage.writeAtomically(storage.unitHealthFile, ModelStorage.json.encodeToString(HealthFile.serializer(), next))
            state = next
        }
    }

    private fun load(): HealthFile {
        state?.let { return it }
        val fresh = HealthFile(appVersion)
        val loaded = try {
            storage.unitHealthFile.takeIf { it.isFile }
                ?.let { ModelStorage.json.decodeFromString(HealthFile.serializer(), it.readText()) }
                ?.takeIf { it.appVersion == appVersion }
        } catch (e: Exception) {
            null   // unreadable: start clean (the worst case is one more failed load, then it is marked again)
        }
        return (loaded ?: fresh).also { state = it }
    }

    private fun key(artifact: ArtifactId, unit: ComputeUnit) = "${artifact.value}|${unit.name}"

    @Serializable
    private data class HealthFile(
        val appVersion: String,
        val failed: Map<String, String> = emptyMap(),
        val proven: Set<String> = emptySet(),
    )
}
