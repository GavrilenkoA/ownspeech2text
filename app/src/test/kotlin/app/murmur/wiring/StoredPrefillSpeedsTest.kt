package app.murmur.wiring

import app.murmur.core.model.ArtifactId
import app.murmur.core.model.ComputeUnit
import app.murmur.core.store.BenchmarkRecord
import app.murmur.core.store.BenchmarkResultData
import app.murmur.testing.InMemoryBenchmarkRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** `BinderEngines.prefillTps` from the benchmark store (ADR-071: the newest record of (artifact, unit) decides). */
@OptIn(ExperimentalCoroutinesApi::class)
class StoredPrefillSpeedsTest {
    private val qwen = ArtifactId("qwen3.5-0.8b@q4_0")
    private val lfm = ArtifactId("lfm3-1.7b@q4_0-qad")

    private fun llm(artifact: ArtifactId, unit: ComputeUnit, at: Long, prefill: Double, valid: Boolean = true) = BenchmarkRecord(
        artifact, unit, "0.1.0", "test", at,
        BenchmarkResultData.Llm(
            decodeTps = 10.0, prefillTps = prefill, ttftMs = 100.0, e2eMs = 1_000.0, loadMsCold = 1, peakMemMb = 1,
            ranOn = unit.name, unitShare = null, valid = valid, invalidReason = if (valid) null else "guard",
        ),
    )

    @Test
    fun aWatchedArtifactAnswersWithItsNewestValidRecordPerUnit() = runTest {
        val repo = InMemoryBenchmarkRepository()
        repo.save(llm(qwen, ComputeUnit.CPU, at = 1, prefill = 60.0))
        repo.save(llm(qwen, ComputeUnit.CPU, at = 2, prefill = 36.0))
        repo.save(llm(qwen, ComputeUnit.NPU, at = 3, prefill = 569.0))
        val speeds = StoredPrefillSpeeds(repo, backgroundScope)
        speeds.watch(qwen)
        runCurrent()
        assertEquals(36.0, speeds(qwen, ComputeUnit.CPU)!!, 0.0)
        assertEquals(569.0, speeds(qwen, ComputeUnit.NPU)!!, 0.0)
        assertNull("no record of that unit", speeds(qwen, ComputeUnit.GPU))
        // A new benchmark replaces the speed without asking again.
        repo.save(llm(qwen, ComputeUnit.CPU, at = 4, prefill = 50.0))
        runCurrent()
        assertEquals(50.0, speeds(qwen, ComputeUnit.CPU)!!, 0.0)
    }

    @Test
    fun anInvalidNewestRecordMeansNoSpeed() = runTest {
        val repo = InMemoryBenchmarkRepository()
        repo.save(llm(lfm, ComputeUnit.CPU, at = 1, prefill = 15.0))
        repo.save(llm(lfm, ComputeUnit.CPU, at = 2, prefill = 900.0, valid = false))
        val speeds = StoredPrefillSpeeds(repo, backgroundScope)
        speeds.watch(lfm)
        runCurrent()
        assertNull(speeds(lfm, ComputeUnit.CPU))
    }

    @Test
    fun anUnwatchedArtifactNeverBlocksAndAnswersOnceItsCopyIsUp() = runTest {
        val repo = InMemoryBenchmarkRepository()
        repo.save(llm(lfm, ComputeUnit.CPU, at = 1, prefill = 15.0))
        val speeds = StoredPrefillSpeeds(repo, backgroundScope)
        assertNull("first ask without a copy: null at once (the client uses its floor)", speeds(lfm, ComputeUnit.CPU))
        runCurrent()
        assertEquals(15.0, speeds(lfm, ComputeUnit.CPU)!!, 0.0)
    }
}
