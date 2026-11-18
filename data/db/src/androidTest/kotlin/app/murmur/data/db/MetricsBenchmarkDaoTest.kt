package app.murmur.data.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.murmur.core.model.ArtifactId
import app.murmur.core.model.ComputeUnit
import app.murmur.core.store.BenchmarkRecord
import app.murmur.core.store.BenchmarkResultData
import app.murmur.core.store.ClipResult
import app.murmur.core.store.DefaultStatsCalculator
import app.murmur.testing.contrib.Wp12MetricsFixture
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MetricsBenchmarkDaoTest {
    private lateinit var db: MurmurDatabase

    @Before fun setUp() { db = MurmurDatabase.inMemory(appContext) }
    @After fun tearDown() { db.close() }

    @Test
    fun metricsRoundTripAndFeedTheDashboard() = runBlocking {
        val repo = RoomMetricsRepository(db)
        Wp12MetricsFixture.week.forEach { repo.add(it) }
        val today = Wp12MetricsFixture.TODAY
        val since = repo.since(today.minusDays(6)).first()
        // Rows from the 12th on (the 11th is before), oldest first, exactly as written.
        assertEquals(Wp12MetricsFixture.week.filter { !it.localDay.isBefore(today.minusDays(6)) }.sortedBy { it.createdAt }, since)
        val calc = DefaultStatsCalculator()
        assertEquals(calc.compute(Wp12MetricsFixture.week, today, 40), calc.compute(since, today, 40))

        assertEquals(1, repo.deleteBefore(today.minusDays(6)))
        assertEquals(Wp12MetricsFixture.week.size - 1, repo.since(today.minusDays(400)).first().size)
    }

    private fun asr(unit: ComputeUnit, at: Long, valid: Boolean, reason: String? = null) = BenchmarkRecord(
        artifactId = ArtifactId("parakeet-tdt-0.6b-v3@qnn-sm8750"), unit = unit, appVersion = "0.1.0",
        buildFingerprint = "samsung/q7q/q7q:16/BP2A/1:user/release-keys", atEpochMs = at,
        result = BenchmarkResultData.Asr(
            rtfx = 14.2, tailMs = 180, loadMsCold = 800, loadMsWarm = 120, peakMemMb = 1_100, ranOn = unit.name,
            perClip = listOf(ClipResult("fleurs-ru-1", 11_200, 790, 0.08), ClipResult("fleurs-en-1", 10_400, 700, null)),
            valid = valid, invalidReason = reason,
        ),
    )

    @Test
    fun benchmarkLatestPerUnitAndLatestValid() = runBlocking {
        val repo = RoomBenchmarkRepository(db)
        val npuOldValid = asr(ComputeUnit.NPU, 1_000, valid = true)
        val npuNewInvalid = asr(ComputeUnit.NPU, 2_000, valid = false, reason = "wer_high")
        val cpuValid = asr(ComputeUnit.CPU, 1_500, valid = true)
        val llm = BenchmarkRecord(ArtifactId("lfm3-1.7b@q4_0"), ComputeUnit.GPU, "0.1.0", "fp", 3_000,
            BenchmarkResultData.Llm(40.9, 2_597.0, 310.0, 1_300.0, 2_100, 1_160, "GPU", 0.97, guardAction = "accept", stopReason = 1))
        listOf(npuOldValid, cpuValid, npuNewInvalid, llm).forEach { repo.save(it) }

        val parakeet = ArtifactId("parakeet-tdt-0.6b-v3@qnn-sm8750")
        // The hub shows the newest run of each unit, the invalid one included ("Results look wrong on NPU").
        assertEquals(listOf(npuNewInvalid, cpuValid), repo.latest(parakeet).first())
        // Selection never uses an invalid run, and an invalid run does not hide the older valid one.
        assertEquals(npuOldValid, repo.latestValid(parakeet, ComputeUnit.NPU))
        assertEquals(cpuValid, repo.latestValid(parakeet, ComputeUnit.CPU))
        assertNull(repo.latestValid(parakeet, ComputeUnit.GPU))
        assertEquals(llm, repo.latestValid(ArtifactId("lfm3-1.7b@q4_0"), ComputeUnit.GPU))

        // A newer valid run takes over.
        val npuNewest = asr(ComputeUnit.NPU, 4_000, valid = true)
        repo.save(npuNewest)
        assertEquals(npuNewest, repo.latestValid(parakeet, ComputeUnit.NPU))
        // The validity columns mirror the JSON.
        val rows = db.query("SELECT valid, invalid_reason FROM benchmark WHERE at_ms = 2000", null)
        rows.use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)); assertEquals("wer_high", c.getString(1)) }
    }

    @Test
    fun customModelsPutReplaceDelete() = runBlocking {
        val dao = DbModule.customModels(db)
        dao.put(CustomModelRow("custom-a", """{"id":"custom-a"}""", 1))
        dao.put(CustomModelRow("custom-b", """{"id":"custom-b"}""", 2))
        dao.put(CustomModelRow("custom-a", """{"id":"custom-a","v":2}""", 1))
        assertEquals(listOf("custom-a" to """{"id":"custom-a","v":2}""", "custom-b" to """{"id":"custom-b"}"""), dao.all().first().map { it.id to it.json })
        dao.delete("custom-a")
        assertEquals(listOf("custom-b"), dao.all().first().map { it.id })
    }
}
