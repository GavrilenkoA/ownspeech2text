package app.murmur.data.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * Schema migrations. The exported JSON (data/db/schemas/…/N.json, packaged as test assets) must match what Room
 * generates, and a database created from an older JSON must migrate and open with the app's Room classes.
 * Version 2 adds benchmark.notes (ADR-080, Room auto-migration 1 -> 2). A future version N adds its migration here
 * with the same open-and-read check.
 */
@RunWith(AndroidJUnit4::class)
class SchemaMigrationTest {
    private val name = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), MurmurDatabase::class.java)

    @After
    fun tearDown() {
        appContext.deleteDatabase(name)
    }

    @Test
    fun versionOneMigratesToTwoAndKeepsTheRows() {
        helper.createDatabase(name, 1).use { db ->
            db.execSQL(
                "INSERT INTO history (created_at, local_day, pkg, status, final_text, raw_text, record_ms, speech_ms, revision, " +
                    "pending_delete, search_text) VALUES (1763460000000, '2026-11-18', 'org.telegram.messenger', 'INSERTED', " +
                    "'Привет', 'привет', 3000, 2500, 1, 0, 'привет')",
            )
            db.execSQL(
                "INSERT INTO benchmark (artifact, unit, app_version, fingerprint, at_ms, valid, invalid_reason, result) VALUES " +
                    "('lfm3-1.7b@q4_0', 'GPU', '0.1.0', 'fp', 3000, 1, NULL, " +
                    "'{\"type\":\"llm\",\"decodeTps\":40.9,\"prefillTps\":2597.0,\"ttftMs\":310.0,\"e2eMs\":1300.0," +
                    "\"loadMsCold\":1160,\"peakMemMb\":2100,\"ranOn\":\"GPU\",\"unitShare\":null}')",
            )
        }
        // Throws when the tables, columns or indices after the migration differ from 2.json.
        helper.runMigrationsAndValidate(name, 2, true).close()

        val room = Room.databaseBuilder(appContext, MurmurDatabase::class.java, name).build()
        try {
            val entries = runBlocking { RoomHistoryRepository(room, FileAudioStore(appContext.cacheDir.resolve("mig"))).entries().first() }
            assertEquals(listOf("Привет"), entries.map { it.finalText })
            assertEquals(LocalDate.of(2026, 11, 18), entries.single().localDay)
            val bench = runBlocking { RoomBenchmarkRepository(room).latest(app.murmur.core.model.ArtifactId("lfm3-1.7b@q4_0")).first() }
            assertEquals(1, bench.size)
            assertEquals(emptyMap<String, String>(), bench.single().notes)
        } finally {
            room.close()
        }
    }
}
