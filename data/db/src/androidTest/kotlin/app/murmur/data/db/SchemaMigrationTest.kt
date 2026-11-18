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
 * Migration scaffold. Version 1 is the only schema; the exported JSON (data/db/schemas/…/1.json, packaged as test
 * assets) must match what Room generates, and a database created from that JSON must open with the app's Room
 * classes. A future version N adds `MIGRATION_1_N`, `helper.runMigrationsAndValidate(NAME, N, true, MIGRATION_1_N)`
 * and the same open-and-read check.
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
    fun versionOneToOneValidatesAgainstTheExportedSchema() {
        helper.createDatabase(name, 1).use { db ->
            db.execSQL(
                "INSERT INTO history (created_at, local_day, pkg, status, final_text, raw_text, record_ms, speech_ms, revision, " +
                    "pending_delete, search_text) VALUES (1763460000000, '2026-11-18', 'org.telegram.messenger', 'INSERTED', " +
                    "'Привет', 'привет', 3000, 2500, 1, 0, 'привет')",
            )
        }
        // Throws when the tables, columns or indices differ from 1.json.
        helper.runMigrationsAndValidate(name, 1, true).close()

        val room = Room.databaseBuilder(appContext, MurmurDatabase::class.java, name).build()
        try {
            val entries = runBlocking { RoomHistoryRepository(room, FileAudioStore(appContext.cacheDir.resolve("mig"))).entries().first() }
            assertEquals(listOf("Привет"), entries.map { it.finalText })
            assertEquals(LocalDate.of(2026, 11, 18), entries.single().localDay)
        } finally {
            room.close()
        }
    }
}
