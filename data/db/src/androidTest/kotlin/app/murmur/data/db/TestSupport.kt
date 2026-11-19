package app.murmur.data.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.murmur.core.model.ArtifactId
import app.murmur.core.model.AudioKey
import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.EntryId
import app.murmur.core.model.ModelId
import app.murmur.core.model.SessionId
import app.murmur.core.store.AppRef
import app.murmur.core.store.AudioRef
import app.murmur.core.store.AudioStore
import app.murmur.core.store.AudioWriter
import app.murmur.core.store.CleanupUse
import app.murmur.core.store.EntryStatus
import app.murmur.core.store.HistoryEntry
import app.murmur.core.store.InsertMethod
import app.murmur.core.store.ModelUse
import app.murmur.core.store.Timings
import java.io.DataOutputStream
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

val appContext: Context get() = ApplicationProvider.getApplicationContext()

/** Real files on the device, like WP-09's store (content is raw PCM here; encoding is not what these tests check). */
class FileAudioStore(val dir: File) : AudioStore {
    init { dir.mkdirs() }

    override fun newWriter(key: String): AudioWriter = object : AudioWriter {
        private val file = File(dir, "$key.ogg")
        private val out = DataOutputStream(file.outputStream().buffered())
        private var samples = 0
        override fun write(samples: ShortArray, count: Int) { for (i in 0 until count) out.writeShort(samples[i].toInt()); this.samples += count }
        override suspend fun close(): AudioRef? {
            out.close()
            if (samples == 0) { file.delete(); return null }
            return AudioRef(file.absolutePath, samples * 1000L / 16_000, file.length(), "opus")
        }
        override fun discard() { out.close(); file.delete() }
    }

    override suspend fun delete(ref: AudioRef) { File(ref.path).delete() }
    override suspend fun readPcm(ref: AudioRef): ShortArray? = null
    override suspend fun exists(ref: AudioRef): Boolean = File(ref.path).exists()
    override suspend fun listAll(): List<AudioRef> =
        dir.listFiles().orEmpty().filter { it.isFile }.map { AudioRef(it.absolutePath, 0, it.length(), "opus") }

    /** A one-second recording keyed like the orchestrator keys it. */
    suspend fun record(createdAtMs: Long, session: Long): AudioRef {
        val w = newWriter(AudioKey.of(createdAtMs, SessionId(session)).value)
        w.write(ShortArray(16_000) { (it % 200).toShort() }, 16_000)
        return checkNotNull(w.close())
    }
}

val MOSCOW: ZoneId = ZoneId.of("Europe/Moscow")

fun entry(
    at: LocalDateTime,
    zone: ZoneId = MOSCOW,
    finalText: String? = "Привет, Дима! Буду через 10 минут.",
    rawText: String? = "ну привет дима буду через десять минут",
    pkg: String = "org.telegram.messenger",
    status: EntryStatus = EntryStatus.INSERTED,
    audio: AudioRef? = null,
) = HistoryEntry(
    id = EntryId(0),
    createdAt = at.atZone(zone).toInstant(),
    localDay = at.toLocalDate(),
    app = AppRef(pkg, "Telegram", "Дима Соколов"),
    status = status,
    finalText = finalText,
    rawText = rawText,
    language = "ru",
    asr = ModelUse(ModelId("gigaam-v3-rnnt"), ArtifactId("gigaam-v3-rnnt@cpu-int8"), ComputeUnit.CPU),
    cleanup = CleanupUse(ModelUse(ModelId("lfm3-1.7b"), ArtifactId("lfm3-1.7b@q4_0"), ComputeUnit.NPU), "default.v4", "accept", "casual"),
    audio = audio,
    timings = Timings(recordMs = 12_000, speechMs = 9_500, stopToDoneMs = 1_300, asrTailMs = 400, llmMs = 900, ocrMs = 350),
    insertMethod = InsertMethod.COMMIT_TEXT,
    failureCode = null,
    revision = 1,
    contextWords = 182,
)
