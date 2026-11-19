package app.murmur.testing.contrib

import app.murmur.core.model.ArtifactId
import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.EntryId
import app.murmur.core.model.ModelId
import app.murmur.core.model.MurmurError
import app.murmur.core.store.AppRef
import app.murmur.core.store.AudioRef
import app.murmur.core.store.AudioStore
import app.murmur.core.store.CleanupUse
import app.murmur.core.store.EntryStatus
import app.murmur.core.store.HistoryEntry
import app.murmur.core.store.HistoryRepository
import app.murmur.core.store.InsertMethod
import app.murmur.core.store.ModelUse
import app.murmur.core.store.Timings
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * The History frames' data set (docs/design/prototype HIST, `hist*` frames): [TODAY] = Wednesday 18 November 2026,
 * 14:32 in [ZONE]; six dictations today (Telegram · Дима Соколов with its original transcript, Gmail, Slack, Termux
 * Copied, Notes, Chrome Failed "no speech"), three yesterday, three on Monday 16 November (one of them the second
 * "созвон" hit of `hist-search`) and one on Thursday 12 November, the oldest kept day with 7-day retention ("Deletes
 * tonight"). Every entry but the Termux one has a speech-like recording of its frame duration. Shared by WP-16's
 * view-model tests and its instrumented screenshot tests.
 */
object Wp16HistoryFixture {
    val ZONE: ZoneId = ZoneId.of("Europe/Moscow")
    val TODAY: LocalDate = LocalDate.of(2026, 11, 18)
    val NOW: Instant = TODAY.atTime(14, 32, 40).atZone(ZONE).toInstant()

    const val TELEGRAM = "org.telegram.messenger"
    const val GMAIL = "com.google.android.gm"
    const val SLACK = "com.Slack"
    const val TERMUX = "com.termux"
    const val NOTES = "com.samsung.android.app.notes"
    const val CHROME = "com.android.chrome"
    const val DOCS = "com.google.android.apps.docs.editors.docs"

    val LABELS: Map<String, String> = mapOf(
        TELEGRAM to "Telegram", GMAIL to "Gmail", SLACK to "Slack", TERMUX to "Termux", NOTES to "Notes", CHROME to "Chrome",
        DOCS to "Google Docs",
    )

    /** Letter-tile colours and letters of the prototype's APPS map (screenshot tests draw these as launcher icons). */
    val TILES: Map<String, Pair<Long, String>> = mapOf(
        TELEGRAM to (0xFF3C7EBF to "T"), GMAIL to (0xFFB4473C to "G"), SLACK to (0xFF5E3E63 to "S"), NOTES to (0xFFB9861E to "N"),
        CHROME to (0xFF4F7F52 to "C"), TERMUX to (0xFF2A2D33 to ">_"), DOCS to (0xFF3E64B0 to "D"),
    )

    val ASR = ModelUse(ModelId("qwen3-asr-0.6b"), ArtifactId("qwen3-asr-0.6b@npu"), ComputeUnit.NPU)
    val LLM = ModelUse(ModelId("lfm3-1.7b"), ArtifactId("lfm3-1.7b@q4"), ComputeUnit.NPU)
    val MODEL_NAMES: Map<ModelId, String> = mapOf(ASR.modelId to "Qwen3-ASR 0.6B", LLM.modelId to "LFM3 1.7B")

    const val H1_FINAL = "Привет! Буду в офисе к 11, созвон с Сашей переносим на четверг. Скинь, пожалуйста, ссылку на документ."
    const val H1_RAW = "привет э буду в офисе к одиннадцати созвон с сашей ну переносим на четверг скинь пожалуйста ссылку на документ"

    /** One row of the data set. */
    data class Item(
        val day: LocalDate,
        val time: LocalTime,
        val pkg: String,
        val conversation: String?,
        val text: String?,
        val raw: String?,
        val status: EntryStatus = EntryStatus.INSERTED,
        val durationMs: Long,
        val failureCode: String? = null,
        val audio: Boolean = true,
    )

    private fun d(daysAgo: Long) = TODAY.minusDays(daysAgo)
    private fun t(h: Int, m: Int) = LocalTime.of(h, m)

    /** Newest first, in frame order. */
    val ITEMS: List<Item> = listOf(
        Item(TODAY, t(14, 32), TELEGRAM, "Дима Соколов", H1_FINAL, H1_RAW, durationMs = 12_000),
        Item(TODAY, t(13, 5), GMAIL, null, "Hi Laura, thanks for the quick turnaround. The numbers look right to me. Let’s send the Q4 report on Friday and go through the open questions on Monday.",
            "hi laura thanks for the quick turnaround the numbers look right to me lets send the q4 report on friday and go through the open questions on monday", durationMs = 18_000),
        Item(TODAY, t(12, 41), SLACK, null, "Деплой на staging прошёл, но тесты на payments падают. Посмотрю после обеда; если кто-то уже копал, напишите в тред.",
            "деплой на стейджинг прошёл но тесты на пейментс падают посмотрю после обеда если кто-то уже копал напишите в тред", durationMs = 15_000),
        Item(TODAY, t(11, 20), TERMUX, null, "grep -rn \"TODO\" app/src", "grep minus rn todo app src", status = EntryStatus.COPIED, durationMs = 5_000, audio = false),
        Item(TODAY, t(9, 58), NOTES, null, "Список на выходные: купить фильтры для воды, забрать куртку из химчистки, записаться к стоматологу на следующую неделю.",
            "список на выходные купить фильтры для воды забрать куртку из химчистки записаться к стоматологу на следующую неделю", durationMs = 16_000),
        Item(TODAY, t(9, 12), CHROME, null, null, null, status = EntryStatus.FAILED, durationMs = 3_000, failureCode = MurmurError.NoSpeech.code),
        Item(d(1), t(22, 47), TELEGRAM, "Мама", "Да, в субботу приедем к обеду. Возьмём пирог, ничего специально не готовь.",
            "да в субботу приедем к обеду возьмём пирог ничего специально не готовь", durationMs = 9_000),
        Item(d(1), t(18, 30), GMAIL, null, "Following up on the contract draft: section 4.2 still says 30 days, and it should be 45, as we agreed on the call. Could you send a corrected version by Thursday?",
            "following up on the contract draft section four point two still says thirty days and it should be forty five as we agreed on the call could you send a corrected version by thursday", durationMs = 21_000),
        Item(d(1), t(16, 2), SLACK, null, "Quick update: the speech model now loads in under a second on the NPU build. Next up is the cleanup model.",
            "quick update the speech model now loads in under a second on the npu build next up is the cleanup model", durationMs = 11_000),
        Item(d(2), t(19, 14), DOCS, null, "Во втором разделе стоит сократить вступление и сразу перейти к цифрам: читателю важнее, сколько времени экономит диктовка, чем как она устроена.",
            "во втором разделе стоит сократить вступление и сразу перейти к цифрам читателю важнее сколько времени экономит диктовка чем как она устроена", durationMs = 47_000),
        Item(d(2), t(8, 40), TELEGRAM, "Аня", "Выхожу, буду минут через двадцать.", "выхожу буду минут через двадцать", durationMs = 6_000),
        Item(d(2), t(8, 12), TELEGRAM, "Аня", "Давай созвон в 12, у меня до этого встреча.", "давай созвон в двенадцать у меня до этого встреча", durationMs = 8_000),
        Item(d(6), t(15, 10), NOTES, null, "Идея для заметки: почему локальные модели для диктовки уже достаточно хороши и где они всё ещё ошибаются — имена, термины, смешанный русский и английский.",
            "идея для заметки почему локальные модели для диктовки уже достаточно хороши и где они всё ещё ошибаются имена термины смешанный русский и английский", durationMs = 32_000),
    )

    /** [ITEMS] as entries (id 0; repositories assign ids) with their recordings written to [audio]. */
    suspend fun entries(audio: AudioStore?): List<HistoryEntry> = ITEMS.mapIndexed { i, it ->
        val created = it.day.atTime(it.time).atZone(ZONE).toInstant()
        val ref = if (it.audio && audio != null) record(audio, "${created.toEpochMilli()}-${i + 1}", speechLike(it.durationMs, seed = i + 1)) else null
        entry(it, created, ref)
    }

    /** Inserts [ITEMS] into [history] (newest first) and returns the assigned ids in [ITEMS] order. */
    suspend fun seed(history: HistoryRepository, audio: AudioStore?): List<EntryId> = entries(audio).map { history.insert(it) }

    fun entry(it: Item, created: Instant, audio: AudioRef?): HistoryEntry {
        val failed = it.status == EntryStatus.FAILED
        return HistoryEntry(
            id = EntryId(0),
            createdAt = created,
            localDay = it.day,
            app = AppRef(it.pkg, LABELS[it.pkg], it.conversation),
            status = it.status,
            finalText = it.text,
            rawText = it.raw,
            language = if (it.text?.any { c -> c in 'а'..'я' || c in 'А'..'Я' } == true) "ru" else "en",
            asr = ASR,
            cleanup = if (failed) null else CleanupUse(LLM, "default.v4", "accept", if (it.pkg == TELEGRAM) "casual" else null),
            audio = audio,
            timings = Timings(recordMs = it.durationMs, speechMs = it.durationMs - 800, stopToDoneMs = if (failed) null else 1_300, asrTailMs = 400, llmMs = if (failed) null else 900, ocrMs = 120),
            insertMethod = when (it.status) { EntryStatus.INSERTED -> InsertMethod.COMMIT_TEXT; EntryStatus.COPIED -> InsertMethod.CLIPBOARD_ONLY; EntryStatus.FAILED -> null },
            failureCode = it.failureCode,
            contextWords = if (it.pkg == TELEGRAM && it.conversation == "Дима Соколов") 182 else null,
        )
    }

    suspend fun record(audio: AudioStore, key: String, pcm: ShortArray): AudioRef? {
        val w = audio.newWriter(key)
        w.write(pcm, pcm.size)
        return w.close()
    }

    /**
     * Deterministic speech-like PCM (16 kHz): syllables of 120-260 ms with varying loudness, short pauses between
     * words, a voiced 140-220 Hz carrier. Gives the player a waveform with real peaks and quiet gaps.
     */
    fun speechLike(durationMs: Long, seed: Int): ShortArray {
        val n = (durationMs * 16).toInt()
        val out = ShortArray(n)
        var rnd = seed * 7919L + 17
        fun next(): Double { rnd = (rnd * 6364136223846793005L + 1442695040888963407L); return ((rnd ushr 33) and 0x7FFFFFFF).toDouble() / 0x7FFFFFFF }
        var i = (n * 0.02).toInt()
        while (i < n) {
            val syl = ((0.12 + 0.14 * next()) * 16_000).toInt()
            val amp = 2_000 + 14_000 * next() * next()
            val f = 140 + 80 * next()
            for (k in 0 until syl) {
                val at = i + k
                if (at >= n) break
                val env = sin(PI * k / syl)
                out[at] = (amp * env * (0.7 * sin(2 * PI * f * at / 16_000) + 0.3 * sin(2 * PI * 2.7 * f * at / 16_000))).toInt().toShort()
            }
            i += syl + if (next() < 0.25) (0.18 * 16_000 + 0.2 * 16_000 * next()).toInt() else (0.02 * 16_000).toInt()
        }
        return out
    }

    /** Loudest absolute sample (tests). */
    fun peak(pcm: ShortArray): Int = pcm.maxOf { abs(it.toInt()) }
}
