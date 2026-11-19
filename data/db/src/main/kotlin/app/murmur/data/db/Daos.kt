package app.murmur.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history WHERE pending_delete = 0 ORDER BY created_at DESC, id DESC")
    fun visible(): Flow<List<HistoryRow>>

    @Query("SELECT * FROM history WHERE id = :id AND pending_delete = 0")
    fun visibleById(id: Long): Flow<HistoryRow?>

    @Query("SELECT * FROM history WHERE id = :id")
    suspend fun row(id: Long): HistoryRow?

    /** [pattern] = a LIKE pattern over [HistoryRow.searchText], already folded and escaped with '\'. */
    @Query(
        "SELECT * FROM history WHERE pending_delete = 0 AND (:pkg IS NULL OR pkg = :pkg) " +
            "AND search_text LIKE :pattern ESCAPE '\\' ORDER BY created_at DESC, id DESC",
    )
    fun search(pattern: String, pkg: String?): Flow<List<HistoryRow>>

    @Insert suspend fun insert(row: HistoryRow): Long
    @Update suspend fun update(row: HistoryRow)

    @Query("UPDATE history SET pending_delete = :pending WHERE id IN (:ids)")
    suspend fun setPendingDelete(ids: List<Long>, pending: Boolean)

    @Query("SELECT * FROM history WHERE pending_delete = 1")
    suspend fun pending(): List<HistoryRow>

    /** Every row of a local day before [firstKeptDay] (ISO dates compare as text), pending or not. */
    @Query("SELECT * FROM history WHERE local_day < :firstKeptDay")
    suspend fun before(firstKeptDay: String): List<HistoryRow>

    @Query("SELECT * FROM history")
    suspend fun allRows(): List<HistoryRow>

    @Query("DELETE FROM history WHERE id IN (:ids)")
    suspend fun deleteIds(ids: List<Long>): Int

    /** Audio paths referenced by any row, pending ones included (orphan cleanup). */
    @Query("SELECT audio_path FROM history WHERE audio_path IS NOT NULL")
    suspend fun audioPaths(): List<String>
}

@Dao
interface MetricsDao {
    @Query("SELECT * FROM metrics WHERE local_day >= :day ORDER BY created_at, id")
    fun since(day: String): Flow<List<MetricsRow>>

    @Insert suspend fun insert(row: MetricsRow)

    @Query("DELETE FROM metrics WHERE local_day < :day")
    suspend fun deleteBefore(day: String): Int
}

@Dao
interface BenchmarkDao {
    @Query("SELECT * FROM benchmark WHERE artifact = :artifact ORDER BY at_ms DESC, id DESC")
    fun forArtifact(artifact: String): Flow<List<BenchmarkRow>>

    @Query("SELECT * FROM benchmark WHERE artifact = :artifact AND unit = :unit AND valid = 1 ORDER BY at_ms DESC, id DESC LIMIT 1")
    suspend fun latestValid(artifact: String, unit: String): BenchmarkRow?

    @Insert suspend fun insert(row: BenchmarkRow)
}

/** User-added models (D3); read and written by WP-13's custom-model store through [DbModule.customModels]. */
@Dao
interface CustomModelDao {
    @Query("SELECT * FROM custom_model ORDER BY added_ms")
    fun all(): Flow<List<CustomModelRow>>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(row: CustomModelRow)

    @Query("DELETE FROM custom_model WHERE id = :id")
    suspend fun delete(id: String)
}
