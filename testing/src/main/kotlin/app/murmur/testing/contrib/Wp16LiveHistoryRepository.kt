package app.murmur.testing.contrib

import app.murmur.core.model.EntryId
import app.murmur.core.store.HistoryEntry
import app.murmur.core.store.HistoryRepository
import app.murmur.testing.InMemoryHistoryRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import java.time.LocalDate

/**
 * [InMemoryHistoryRepository] whose flows also re-emit after [markDeleted], [restore] and [commitDeletes], as the Room
 * store's do. (The shared fake used to re-assign an equal row list after a soft delete, which its StateFlow conflated;
 * fixed in the shared fake at integration pass 6, so this wrapper is now only an extra re-emission.) Everything else
 * is delegated unchanged.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Wp16LiveHistoryRepository(val inner: InMemoryHistoryRepository = InMemoryHistoryRepository()) : HistoryRepository by inner {
    private val version = MutableStateFlow(0L)

    override fun entries(): Flow<List<HistoryEntry>> = version.flatMapLatest { inner.entries() }
    override fun entry(id: EntryId): Flow<HistoryEntry?> = version.flatMapLatest { inner.entry(id) }
    override fun search(query: String, pkg: String?): Flow<List<HistoryEntry>> = version.flatMapLatest { inner.search(query, pkg) }

    override suspend fun markDeleted(ids: Set<EntryId>) { inner.markDeleted(ids); version.value++ }
    override suspend fun restore(ids: Set<EntryId>) { inner.restore(ids); version.value++ }
    override suspend fun commitDeletes() { inner.commitDeletes(); version.value++ }
    override suspend fun deleteBefore(firstKeptDay: LocalDate): Int = inner.deleteBefore(firstKeptDay).also { version.value++ }
    override suspend fun deleteAll() { inner.deleteAll(); version.value++ }

    fun snapshot(): List<HistoryEntry> = inner.snapshot()
}
