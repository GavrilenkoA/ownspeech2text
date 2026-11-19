package app.murmur.core.benchmark

import app.murmur.core.catalog.ArtifactSelector
import app.murmur.core.model.ArtifactId
import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.UnitPreference
import app.murmur.core.store.BenchmarkRecord
import app.murmur.core.store.BenchmarkResultData
import app.murmur.core.store.ClipResult
import app.murmur.testing.FakeDeviceProfileRepository
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BenchScoresTest {
    private val parakeetNpu = ArtifactId("parakeet-tdt-0.6b-v3@qnn-sm8750-20s")
    private val parakeetCpu = ArtifactId("parakeet-tdt-0.6b-v3@onnx-int8")
    private val qwen = ArtifactId("qwen3.5-0.8b@q4_0")

    private fun asr(id: ArtifactId, unit: ComputeUnit, at: Long, rtfx: Double, valid: Boolean = true) = BenchmarkRecord(
        id, unit, "1.0", "fp", at,
        BenchmarkResultData.Asr(rtfx, 120, 900, 300, 700, unit.name, listOf(ClipResult("ru", 10_080, 1_000, 0.04)),
            valid, if (valid) null else "wer_high"),
    )

    private fun llm(unit: ComputeUnit, at: Long, e2e: Double, prefill: Double, decode: Double, valid: Boolean = true) = BenchmarkRecord(
        qwen, unit, "1.0", "fp", at,
        BenchmarkResultData.Llm(decode, prefill, 300.0, e2e, 2_000, 1_100, unit.name, null, valid = valid,
            invalidReason = if (valid) null else "wrong_script"),
    )

    @Test
    fun asrScoreIsProcessingMsPerAudioSecond() {
        val lookup = BenchScores.lookup(listOf(asr(parakeetCpu, ComputeUnit.CPU, 1, rtfx = 8.0)))
        assertEquals(125.0, lookup.score(parakeetCpu, ComputeUnit.CPU)!!, 1e-9)
        assertNull(lookup.score(parakeetCpu, ComputeUnit.NPU))
        assertNull(lookup.score(parakeetNpu, ComputeUnit.NPU))
    }

    @Test
    fun latestRecordDecidesAndAnInvalidOneNeverScores() {
        val records = listOf(
            asr(parakeetNpu, ComputeUnit.NPU, at = 1, rtfx = 40.0),
            asr(parakeetNpu, ComputeUnit.NPU, at = 2, rtfx = 60.0, valid = false), // after an update the NPU went wrong
            asr(parakeetCpu, ComputeUnit.CPU, at = 1, rtfx = 5.0, valid = false),
            asr(parakeetCpu, ComputeUnit.CPU, at = 3, rtfx = 8.0),
        )
        val lookup = BenchScores.lookup(records)
        assertNull(lookup.score(parakeetNpu, ComputeUnit.NPU))
        assertEquals(125.0, lookup.score(parakeetCpu, ComputeUnit.CPU)!!, 1e-9)
    }

    @Test
    fun selectorPrefersTheMeasuredUnitAndIgnoresAFastInvalidOne() {
        val entry = BenchRig.PARAKEET
        val device = FakeDeviceProfileRepository.FOLD7
        // Without a benchmark the catalog order wins (NPU artifact first).
        assertEquals(ComputeUnit.NPU, ArtifactSelector.choose(entry, device, UnitPreference.Auto)!!.unit)
        // A valid CPU record and a faster but invalid NPU record: CPU is chosen.
        val lookup = BenchScores.lookup(listOf(
            asr(parakeetCpu, ComputeUnit.CPU, 1, rtfx = 8.0),
            asr(parakeetNpu, ComputeUnit.NPU, 2, rtfx = 80.0, valid = false),
        ))
        assertEquals(ComputeUnit.CPU, ArtifactSelector.choose(entry, device, UnitPreference.Auto, lookup)!!.unit)
    }

    @Test
    fun llmScoreIsEndToEndMsAndSpeedComesFromTheLatestValidRecord() {
        val records = listOf(
            llm(ComputeUnit.CPU, at = 1, e2e = 2_400.0, prefill = 569.0, decode = 83.8),
            llm(ComputeUnit.CPU, at = 2, e2e = 900.0, prefill = 9_000.0, decode = 900.0, valid = false),
            llm(ComputeUnit.NPU, at = 1, e2e = 1_300.0, prefill = 2_597.0, decode = 40.9),
        )
        val lookup = BenchScores.lookup(records)
        assertNull(lookup.score(qwen, ComputeUnit.CPU)) // latest CPU record is invalid
        assertEquals(1_300.0, lookup.score(qwen, ComputeUnit.NPU)!!, 1e-9)
        assertEquals(MeasuredLlmSpeed(569.0, 83.8), BenchScores.llmSpeed(records, qwen, ComputeUnit.CPU))
        assertEquals(MeasuredLlmSpeed(2_597.0, 40.9), BenchScores.llmSpeed(records, qwen, ComputeUnit.NPU))
        assertNull(BenchScores.llmSpeed(records, qwen, ComputeUnit.GPU))
    }

    @Test
    fun recordsWithNotesSurviveTheJsonRoundTrip() {
        val r = asr(parakeetCpu, ComputeUnit.CPU, 7, 12.5).copy(notes = mapOf(BenchNotes.THERMAL_BEFORE to "light"))
        val json = Json.encodeToString(BenchmarkRecord.serializer(), r)
        assertEquals(r, Json.decodeFromString(BenchmarkRecord.serializer(), json))
        // A record stored before notes existed still reads.
        val old = json.replace(Regex(",\"notes\":\\{[^}]*\\}"), "")
        assertEquals(r.copy(notes = emptyMap()), Json.decodeFromString(BenchmarkRecord.serializer(), old))
    }
}
