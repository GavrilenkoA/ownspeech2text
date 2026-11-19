package app.murmur.data.db

import app.murmur.core.model.ArtifactId
import app.murmur.core.model.ComputeUnit
import app.murmur.core.store.BenchmarkRecord
import app.murmur.core.store.BenchmarkResultData
import org.junit.Assert.assertEquals
import org.junit.Test

/** BenchmarkRecord <-> BenchmarkRow, including the notes column of schema 2 (ADR-080). */
class BenchmarkRowMappingTest {
    private val record = BenchmarkRecord(
        ArtifactId("lfm3-1.7b@q4_0"), ComputeUnit.GPU, "0.1.0", "fp", 3_000,
        BenchmarkResultData.Llm(40.9, 2_597.0, 310.0, 1_300.0, 2_100, 1_160, "GPU", 0.97),
        notes = mapOf("thermal.before" to "0", "thermal.after" to "1", "wall.ms" to "14500"),
    )

    @Test
    fun notesSurviveTheRow() {
        val row = record.toRow()
        assertEquals(record, row.toRecord())
        assertEquals(record.copy(notes = emptyMap()), record.copy(notes = emptyMap()).toRow().toRecord())
    }

    @Test
    fun aRowWrittenBeforeSchemaTwoReadsAsNoNotes() {
        // The auto-migration fills the column with its default.
        assertEquals(emptyMap<String, String>(), record.toRow().copy(notes = "{}").toRecord().notes)
    }
}
