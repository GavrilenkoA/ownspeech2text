package app.murmur.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history WHERE pending_delete = 0 ORDER BY created_at DESC")
    fun all(): Flow<List<HistoryRow>>

    @Query("SELECT * FROM history WHERE id = :id AND pending_delete = 0")
    fun byId(id: Long): Flow<HistoryRow?>

    @Query(
        "SELECT * FROM history WHERE pending_delete = 0 AND (:pkg IS NULL OR pkg = :pkg) AND " +
            "(final_text LIKE '%' || :q || '%' OR raw_text LIKE '%' || :q || '%') ORDER BY created_at DESC",
    )
    fun search(q: String, pkg: String?): Flow<List<HistoryRow>>

    @Insert suspend fun insert(row: HistoryRow): Long
    @Update suspend fun update(row: HistoryRow)

    @Query("UPDATE history SET pending_delete = :pending WHERE id IN (:ids)")
    suspend fun setPendingDelete(ids: List<Long>, pending: Boolean)

    @Query("SELECT audio_path FROM history WHERE pending_delete = 1 AND audio_path IS NOT NULL")
    suspend fun pendingAudio(): List<String>

    @Query("DELETE FROM history WHERE pending_delete = 1")
    suspend fun deletePending(): Int

    @Query("SELECT audio_path FROM history WHERE local_day < :firstKeptDay AND audio_path IS NOT NULL")
    suspend fun audioBefore(firstKeptDay: String): List<String>

    @Query("DELETE FROM history WHERE local_day < :firstKeptDay")
    suspend fun deleteBefore(firstKeptDay: String): Int

    @Query("SELECT audio_path FROM history WHERE audio_path IS NOT NULL")
    suspend fun allAudio(): List<String>

    @Query("DELETE FROM history")
    suspend fun deleteAll()
}

@Dao
interface MetricsDao {
    @Query("SELECT * FROM metrics WHERE local_day >= :day ORDER BY created_at")
    fun since(day: String): Flow<List<MetricsRow>>

    @Insert suspend fun insert(row: MetricsRow)

    @Query("DELETE FROM metrics WHERE local_day < :day")
    suspend fun deleteBefore(day: String): Int
}

@Dao
interface BenchmarkDao {
    @Query("SELECT * FROM benchmark WHERE artifact = :artifact ORDER BY at_ms DESC")
    fun forArtifact(artifact: String): Flow<List<BenchmarkRow>>

    @Insert suspend fun insert(row: BenchmarkRow)
}

@Dao
interface CustomModelDao {
    @Query("SELECT * FROM custom_model ORDER BY added_ms")
    fun all(): Flow<List<CustomModelRow>>

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE) suspend fun put(row: CustomModelRow)

    @Query("DELETE FROM custom_model WHERE id = :id")
    suspend fun delete(id: String)
}
