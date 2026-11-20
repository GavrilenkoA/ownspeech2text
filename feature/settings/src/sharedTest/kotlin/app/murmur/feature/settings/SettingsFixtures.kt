package app.murmur.feature.settings

import app.murmur.core.dictation.PermissionState
import app.murmur.core.model.EntryId
import app.murmur.core.model.Outcome
import app.murmur.core.prompt.ClasspathPromptAssets
import app.murmur.core.prompt.RenderPyPort
import app.murmur.core.store.AppRef
import app.murmur.core.store.EntryStatus
import app.murmur.core.store.HistoryEntry
import app.murmur.core.store.PromptOverride
import app.murmur.core.store.RetentionPolicy
import app.murmur.core.store.Settings
import app.murmur.core.store.Timings
import app.murmur.testing.FakeCatalogRepository
import app.murmur.testing.FakePermissionMonitor
import app.murmur.testing.InMemoryHistoryRepository
import app.murmur.testing.InMemorySettingsRepository
import app.murmur.ui.design.AppInfoProvider
import app.murmur.ui.design.StaticAppInfoProvider
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalDate
import java.time.ZoneOffset

/** The data behind the settings frames (prototype `settingsGroups`, `toneScreen`, `retentionSheet`). */
object SettingsFixtures {
    const val TELEGRAM = "org.telegram.messenger"
    const val MESSAGES = "com.google.android.apps.messaging"
    const val SLACK = "com.Slack"
    const val GMAIL = "com.google.android.gm"
    const val DOCS = "com.google.android.apps.docs.editors.docs"
    const val TERMUX = "com.termux"
    const val CHROME = "com.android.chrome"
    const val SBER = "ru.sberbankmobile"
    const val TBANK = "com.idamob.tinkoff.android"
    const val VTB = "ru.vtb24.mobilebanking.android"

    val TODAY: LocalDate = LocalDate.of(2026, 11, 19)

    /** Launcher apps: the frame's six tone apps (in their categories), a browser and three banks. */
    val apps = StaticAppInfoProvider(
        mapOf(
            TELEGRAM to "Telegram", MESSAGES to "Messages", SLACK to "Slack", GMAIL to "Gmail", DOCS to "Google Docs",
            TERMUX to "Termux", CHROME to "Chrome", SBER to "СберБанк", TBANK to "T-Bank", VTB to "ВТБ",
        ),
    )

    /** The prototype's placeholder prompt (DESIGN 6.5: "The default prompt text shown in the prototype is a placeholder"). */
    val PROTOTYPE_PROMPT = """
        You clean up dictated text. Return only the cleaned text.

        - Keep the speaker's words and meaning. Do not add facts.
        - Remove filler words, false starts and repetitions.
        - Fix punctuation, casing and obvious recognition errors.
        - Keep the original language. Keep mixed Russian and English as spoken.
        - Write numbers, dates and times as digits.
        - Tone: {tone}.

        App: {app}
        Text on screen (for names and terms only):
        {screen_text}

        Dictation:
        {transcript}
    """.trimIndent()

    /**
     * The prompt-err frame's text. The frame's error ({transcript} missing) does not exist after ADR-019; the markup
     * error does: a chat-template token, which [app.murmur.core.prompt.PromptEditRules] rejects.
     */
    val PROTOTYPE_PROMPT_MARKUP = PROTOTYPE_PROMPT.replace("Dictation:\n{transcript}", "Dictation:\n<|im_start|>")

    /** settings / settings-off / settings-dark: an edited prompt ("Edited"), three apps hidden ("3 apps"). */
    val frameSettings = Settings(
        promptOverride = PromptOverride(PROTOTYPE_PROMPT, "default.v1"),
        hiddenInPackages = setOf(SBER, TBANK, VTB),
    )

    /** Settings > Permissions as in the prototype: everything allowed, background use restricted. */
    val framePermissions = PermissionState(
        micGranted = true, notificationsGranted = true, a11yEnabled = true, a11yConnected = true,
        a11yEverConnected = true, batteryUnrestricted = false, restrictedLikely = false,
    )

    fun entry(day: LocalDate, n: Int, pkg: String = TELEGRAM): HistoryEntry = HistoryEntry(
        id = EntryId(0),
        createdAt = day.atTime(9, n % 60).toInstant(ZoneOffset.UTC),
        localDay = day,
        app = AppRef(pkg, null, null),
        status = EntryStatus.INSERTED,
        finalText = "Text $n",
        rawText = "text $n",
        language = "ru",
        asr = null,
        cleanup = null,
        audio = null,
        timings = Timings(recordMs = 3_000, speechMs = 2_000, stopToDoneMs = 1_000, asrTailMs = null, llmMs = null, ocrMs = null),
        insertMethod = null,
        failureCode = null,
    )

    /**
     * History over the last 7 days: 3 dictations per day from 6 days ago to 2 days ago (15) and 2 each yesterday and
     * today, 19 in all. "3 days" keeps today, yesterday and the day before: 12 older ones are deleted, as in the
     * frame's "Delete 12 dictations older than 3 days?".
     */
    suspend fun history(today: LocalDate = TODAY): InMemoryHistoryRepository = InMemoryHistoryRepository().apply {
        var n = 0
        for (back in 6 downTo 0) {
            val perDay = if (back >= 2) 3 else 2
            repeat(perDay) { insert(entry(today.minusDays(back.toLong()), n++)) }
        }
    }

    /** Everything a test can look at after the screen ran. */
    class World(
        val settings: InMemorySettingsRepository,
        val history: InMemoryHistoryRepository,
        val permissions: FakePermissionMonitor,
        val catalog: FakeCatalogRepository = FakeCatalogRepository(),
        val connected: MutableStateFlow<Boolean> = MutableStateFlow(true),
        var tryResult: Outcome<String> = Outcome.Ok("Скинь мне отчёт до пятницы, и созвонимся в понедельник в 10:00."),
        val today: LocalDate = TODAY,
        val appInfo: AppInfoProvider = apps,
    ) {
        val tryCalls = mutableListOf<Triple<String, String?, String?>>()
        val retentionCalls = mutableListOf<Int>()
        val prompts = ClasspathPromptAssets()

        /** Like WP-12's immediate run: deletes what the new period no longer keeps. */
        val deps = SettingsDeps(
            settings = settings,
            prompts = prompts,
            renderer = RenderPyPort(prompts),
            catalog = catalog,
            history = history,
            serviceConnected = connected,
            tryCleanup = { sample, override, prefs -> tryCalls += Triple(sample, override, prefs); tryResult },
            retention = { days -> retentionCalls += days; history.deleteBefore(RetentionPolicy.firstKeptDay(today, days)) },
            apps = appInfo,
            permissions = permissions,
        )
    }

    suspend fun world(
        settings: Settings = Settings(),
        history: InMemoryHistoryRepository? = null,
        permissions: PermissionState = framePermissions,
        connected: Boolean = true,
        apps: AppInfoProvider = this.apps,
    ): World = World(
        InMemorySettingsRepository(settings),
        history ?: history(),
        FakePermissionMonitor(permissions),
        connected = MutableStateFlow(connected),
        appInfo = apps,
    )
}
