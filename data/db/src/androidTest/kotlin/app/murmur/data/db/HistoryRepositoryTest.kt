package app.murmur.data.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.murmur.core.model.EntryId
import app.murmur.core.store.EntryStatus
import app.murmur.core.store.HistoryEntry
import app.murmur.core.store.InsertMethod
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime

@RunWith(AndroidJUnit4::class)
class HistoryRepositoryTest {
    private lateinit var db: MurmurDatabase
    private lateinit var audio: FileAudioStore
    private lateinit var repo: RoomHistoryRepository

    @Before
    fun setUp() {
        db = MurmurDatabase.inMemory(appContext)
        audio = FileAudioStore(File(appContext.cacheDir, "history-test-audio").apply { deleteRecursively() })
        repo = RoomHistoryRepository(db, audio)
    }

    @After
    fun tearDown() {
        db.close()
        audio.dir.deleteRecursively()
    }

    private fun at(day: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 11, day, h, m)

    @Test
    fun insertMapsEveryFieldAndListsNewestFirst() = runBlocking {
        val ref = audio.record(1_763_460_000_000, 1)
        val first = entry(at(17, 9), audio = ref)
        val second = entry(at(18, 14, 32), finalText = "Second", rawText = "second", status = EntryStatus.COPIED)
            .copy(insertMethod = InsertMethod.CLIPBOARD_ONLY, cleanup = null, asr = null, contextWords = null)
        val failed = entry(at(18, 16), finalText = null, rawText = null, status = EntryStatus.FAILED)
            .copy(failureCode = "asr.no_speech", insertMethod = null)
        val ids = listOf(first, second, failed).map { repo.insert(it) }

        val listed = repo.entries().first()
        assertEquals(listOf(ids[2], ids[1], ids[0]), listed.map { it.id })
        assertEquals(first.copy(id = ids[0]), listed[2])
        assertEquals(second.copy(id = ids[1]), listed[1])
        assertEquals(failed.copy(id = ids[2]), listed[0])
        assertEquals(first.copy(id = ids[0]), repo.entry(ids[0]).first())
        assertNull(repo.entry(EntryId(9_999)).first())
    }

    @Test
    fun updateReplacesFinalAndKeepsRaw() = runBlocking {
        val id = repo.insert(entry(at(18, 10)))
        val stored = repo.entry(id).first()!!
        repo.update(stored.copy(finalText = "Привет! Буду через 10 минут.", revision = 2))
        val after = repo.entry(id).first()!!
        assertEquals("Привет! Буду через 10 минут.", after.finalText)
        assertEquals(stored.rawText, after.rawText)
        assertEquals(2, after.revision)
        // The search shadow follows the new final text.
        assertEquals(listOf(id), repo.search("ПРИВЕТ!").first().map { it.id })
    }

    @Test
    fun cyrillicSearchIsCaseInsensitive() = runBlocking {
        val hello = repo.insert(entry(at(18, 9), finalText = "привет, как дела?", rawText = "привет как дела"))
        val tree = repo.insert(entry(at(18, 10), finalText = "Купить ёлку", rawText = "купить елку"))
        val english = repo.insert(entry(at(18, 11), finalText = "Ship the RELEASE today", rawText = "ship the release today", pkg = "com.Slack"))
        val rawOnly = repo.insert(entry(at(18, 12), finalText = "Встреча в четверг.", rawText = "встреча в пятницу нет в четверг"))

        assertEquals(listOf(hello), repo.search("Привет").first().map { it.id })
        assertEquals(listOf(hello), repo.search("ПРИВЕТ").first().map { it.id })
        assertEquals(listOf(tree), repo.search("ЁЛКУ").first().map { it.id })
        assertEquals(listOf(tree), repo.search("елку").first().map { it.id })
        assertEquals(listOf(english), repo.search("release").first().map { it.id })
        // Original transcript only (DESIGN 6.3: search covers Final and Original).
        assertEquals(listOf(rawOnly), repo.search("Пятницу").first().map { it.id })
        // App filter chip.
        assertEquals(listOf(english), repo.search("the", pkg = "com.Slack").first().map { it.id })
        assertEquals(emptyList<EntryId>(), repo.search("the", pkg = "org.telegram.messenger").first().map { it.id })
        // LIKE wildcards are literal.
        assertEquals(emptyList<EntryId>(), repo.search("%").first().map { it.id })
        assertEquals(emptyList<EntryId>(), repo.search("_").first().map { it.id })
        // A match never spans the end of the final text and the start of the original.
        assertEquals(emptyList<EntryId>(), repo.search("today ship").first().map { it.id })
    }

    @Test
    fun deleteUndoCommitRemovesTheAudioFile() = runBlocking {
        val refA = audio.record(1_763_460_000_000, 1)
        val refB = audio.record(1_763_460_100_000, 2)
        val a = repo.insert(entry(at(18, 9), audio = refA))
        val b = repo.insert(entry(at(18, 10), finalText = "keep me", audio = refB))

        repo.markDeleted(setOf(a))
        assertEquals(listOf(b), repo.entries().first().map { it.id })
        assertNull(repo.entry(a).first())
        assertEquals(emptyList<EntryId>(), repo.search("Дима").first().filter { it.id == a })
        assertTrue("undo is still possible: the file stays", File(refA.path).exists())

        repo.restore(setOf(a))
        assertEquals(listOf(b, a), repo.entries().first().map { it.id })

        repo.markDeleted(setOf(a))
        repo.commitDeletes()
        assertFalse(File(refA.path).exists())
        assertTrue(File(refB.path).exists())
        assertEquals(listOf(b), repo.entries().first().map { it.id })
        // Gone for good: restore after commit brings nothing back.
        repo.restore(setOf(a))
        assertEquals(listOf(b), repo.entries().first().map { it.id })
        assertNull(db.history().row(a.value))
    }

    @Test
    fun updateOfAPendingDeleteKeepsItDeleted() = runBlocking {
        val id = repo.insert(entry(at(18, 9)))
        val stored = repo.entry(id).first()!!
        repo.markDeleted(setOf(id))
        repo.update(stored.copy(finalText = "late re-polish"))
        assertEquals(emptyList<HistoryEntry>(), repo.entries().first())
        repo.restore(setOf(id))
        assertEquals("late re-polish", repo.entry(id).first()!!.finalText)
    }

    @Test
    fun deleteAllRemovesEveryRowAndFile() = runBlocking {
        val refs = (1..3).map { audio.record(1_763_460_000_000 + it, it.toLong()) }
        refs.forEachIndexed { i, r -> repo.insert(entry(at(16 + i, 9), audio = r)) }
        repo.markDeleted(setOf(repo.entries().first().first().id))
        repo.deleteAll()
        assertEquals(emptyList<HistoryEntry>(), repo.entries().first())
        assertEquals(emptyList<HistoryRow>(), db.history().allRows())
        refs.forEach { assertFalse(File(it.path).exists()) }
    }

    @Test
    fun deleteBeforeRemovesOlderDaysWithAudio() = runBlocking {
        val old = audio.record(1_763_300_000_000, 1)
        val kept = audio.record(1_763_460_000_000, 2)
        repo.insert(entry(at(11, 23, 59), audio = old))
        repo.insert(entry(at(12, 0, 0), audio = kept))
        assertEquals(1, repo.deleteBefore(LocalDate.of(2026, 11, 12)))
        assertEquals(listOf(LocalDate.of(2026, 11, 12)), repo.entries().first().map { it.localDay })
        assertFalse(File(old.path).exists())
        assertTrue(File(kept.path).exists())
    }
}
