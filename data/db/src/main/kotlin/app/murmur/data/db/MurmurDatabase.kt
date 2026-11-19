package app.murmur.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/** Single Room database of the :main process (ADR-012). Schema JSON exported to data/db/schemas (one file per version). */
@Database(
    entities = [HistoryRow::class, MetricsRow::class, BenchmarkRow::class, CustomModelRow::class],
    version = 2,
    exportSchema = true,
    // 2: benchmark.notes (BenchmarkRecord.notes, ADR-080).
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class MurmurDatabase : RoomDatabase() {
    abstract fun history(): HistoryDao
    abstract fun metrics(): MetricsDao
    abstract fun benchmarks(): BenchmarkDao
    abstract fun customModels(): CustomModelDao

    companion object {
        const val NAME = "murmur.db"

        fun create(context: Context): MurmurDatabase =
            Room.databaseBuilder(context.applicationContext, MurmurDatabase::class.java, NAME).build()

        fun inMemory(context: Context): MurmurDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, MurmurDatabase::class.java).build()
    }
}
