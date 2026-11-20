package app.murmur

import android.content.Context
import app.murmur.core.catalog.CatalogParser
import app.murmur.core.catalog.DeviceProfile
import app.murmur.core.model.ArtifactId
import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.EntryId
import app.murmur.core.model.ModelId
import app.murmur.core.model.MurmurError
import app.murmur.core.store.AppRef
import app.murmur.core.store.CleanupUse
import app.murmur.core.store.EntryStatus
import app.murmur.core.store.HistoryEntry
import app.murmur.core.store.InsertMethod
import app.murmur.core.store.InstallState
import app.murmur.core.store.ModelUse
import app.murmur.core.store.Settings
import app.murmur.core.store.Timings
import app.murmur.data.models.DefaultDeviceProfileRepository
import app.murmur.feature.onboarding.PackPlanner
import app.murmur.testing.FakeBenchmarkRunner
import app.murmur.testing.FakeCatalogRepository
import app.murmur.testing.FakeDeviceProfileRepository
import app.murmur.testing.FakeAudioCapture
import app.murmur.testing.FakeEngines
import app.murmur.testing.FakeModelResolver
import app.murmur.testing.FakePromptRenderer
import app.murmur.testing.PassThroughGuard
import app.murmur.testing.FakeUnitHealth
import app.murmur.testing.FixedMemoryGate
import app.murmur.testing.InMemoryAudioStore
import app.murmur.testing.InMemoryBenchmarkRepository
import app.murmur.testing.InMemoryHistoryRepository
import app.murmur.testing.InMemoryMetricsRepository
import app.murmur.testing.InMemorySettingsRepository
import app.murmur.testing.contrib.Wp12MetricsFixture
import app.murmur.testing.contrib.Wp15StagedDownloads
import app.murmur.wiring.EngineRecords
import app.murmur.wiring.FakeData
import app.murmur.wiring.Replacements
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking

/**
 * Debug builds only (D6). Two switches, both files in the app's filesDir, read once when the container first needs
 * them (the instrumented tests' runner writes them before the Application starts; by hand:
 * `adb shell run-as app.murmur sh -c 'echo seeded > files/debug-fake-container'`):
 *  - `debug-fake-engines`: inference on :testing fakes, everything else real except what the engines report:
 *    benchmark records and unit health in memory, the device profile cached in its own file (ADR-089).
 *  - `debug-fake-container` = `seeded` | `fresh` (optionally `:fold7` / `:emulator` for the device profile): the whole
 *    data side in memory (FakeAppContainer mode). `seeded` = onboarding done, a day of history (the DESIGN sample
 *    entries), a week of metrics, the recommended pack installed plus rows mid-download and paused. `fresh` = a
 *    first run: onboarding not done, nothing installed, downloads that walk through every install state.
 *    Permissions, app labels and the status notification stay real.
 */
class DebugWiring(private val context: Context, private val scope: CoroutineScope) {
    private fun flag(name: String): String? = File(context.filesDir, name).takeIf { it.exists() }?.readText()?.trim()

    fun replacements(): Replacements {
        val container = flag(FAKE_CONTAINER)
        val engines = flag(FAKE_ENGINES) != null || container != null
        val data = container?.let { fakeData(it) }
        val fakeEngines = if (engines) FakeEngines() else null
        return Replacements(
            engines = fakeEngines,
            data = data,
            renderer = if (engines) FakePromptRenderer() else null,
            guard = if (engines) PassThroughGuard() else null,
            // Engines-only mode: the real data side, but what the fake engines report stays out of the real stores.
            engineRecords = if (fakeEngines != null && data == null) engineRecords(fakeEngines) else null,
        )
    }

    /**
     * ADR-089: benchmark records and unit health in memory; the real device-profile probe over the fake hosts, cached in
     * its own file. The real DefaultBenchmarkRunner and resolver run over them.
     */
    private fun engineRecords(engines: FakeEngines) = EngineRecords(
        benchmarks = InMemoryBenchmarkRepository(),
        unitHealth = FakeUnitHealth(),
        device = DefaultDeviceProfileRepository(
            context, engines.asrHost, engines.llmHost, cacheFile = File(context.noBackupFilesDir, FAKE_ENGINES_PROFILE),
        ),
    )

    private fun fakeData(spec: String): FakeData {
        val mode = spec.substringBefore(':').ifBlank { SEEDED }
        val profile = when (spec.substringAfter(':', "")) {
            "fold7" -> FakeDeviceProfileRepository.FOLD7
            "emulator" -> FakeDeviceProfileRepository.EMULATOR
            else -> if (mode == SEEDED) FakeDeviceProfileRepository.FOLD7 else FakeDeviceProfileRepository.EMULATOR
        }
        val catalog = FakeCatalogRepository(CatalogParser.bundled())
        val settings = InMemorySettingsRepository(Settings(onboardingDone = mode == SEEDED))
        val history = InMemoryHistoryRepository()
        val metrics = InMemoryMetricsRepository()
        val audioStore = InMemoryAudioStore()
        val benchmarks = InMemoryBenchmarkRepository()
        val unitHealth = FakeUnitHealth()
        // 200 ms steps: a pack takes about 15 s, long enough to watch (and screenshot) every install state.
        val downloads = Wp15StagedDownloads(catalog = { catalog.catalog.value }, scope = scope, stepMs = 200)
        if (mode == SEEDED) runBlocking { seed(history, metrics, downloads, catalog, profile, settings.settings.value) }
        return FakeData(
            settings = settings, history = history, metrics = metrics, benchmarks = benchmarks, audioStore = audioStore,
            catalog = catalog, installed = downloads, device = FakeDeviceProfileRepository(profile), unitHealth = unitHealth,
            resolver = FakeModelResolver(health = unitHealth, npuFirst = ComputeUnit.NPU in profile.availableUnits),
            audio = FakeAudioCapture(store = audioStore), memory = FixedMemoryGate(),
            // Saves its made-up records into the in-memory repository above, never into Room.
            benchmarkRunner = FakeBenchmarkRunner(repo = benchmarks),
        )
    }

    /** DESIGN 6.3 sample day (hist frames) for today, two entries yesterday; the metrics week moved to this week. */
    private suspend fun seed(
        history: InMemoryHistoryRepository,
        metrics: InMemoryMetricsRepository,
        downloads: Wp15StagedDownloads,
        catalog: FakeCatalogRepository,
        profile: DeviceProfile,
        settings: Settings,
    ) {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        fun entry(day: LocalDate, at: LocalTime, pkg: String, label: String, who: String?, status: EntryStatus, text: String?, raw: String?, durMs: Long) =
            HistoryEntry(
                id = EntryId(0), createdAt = day.atTime(at).atZone(zone).toInstant(), localDay = day,
                app = AppRef(pkg, label, who), status = status, finalText = text, rawText = raw, language = "ru",
                asr = ModelUse(ModelId("parakeet-tdt-0.6b-v3"), ArtifactId("parakeet-tdt-0.6b-v3@qnn-sm8750-20s"), ComputeUnit.NPU),
                cleanup = if (status == EntryStatus.FAILED) null else CleanupUse(
                    ModelUse(ModelId("lfm3-1.7b"), ArtifactId("lfm3-1.7b@q4_0"), ComputeUnit.NPU), "default.v1", "accept", "casual",
                ),
                audio = null,
                timings = Timings(recordMs = durMs, speechMs = durMs - 1_000, stopToDoneMs = 1_300, asrTailMs = 400, llmMs = 900, ocrMs = 500),
                insertMethod = when (status) {
                    EntryStatus.INSERTED -> InsertMethod.COMMIT_TEXT
                    EntryStatus.COPIED -> InsertMethod.CLIPBOARD_ONLY
                    EntryStatus.FAILED -> null
                },
                failureCode = if (status == EntryStatus.FAILED) MurmurError.NoSpeech.code else null,
                contextWords = if (status == EntryStatus.FAILED) null else 182,
            )
        val y = today.minusDays(1)
        listOf(
            entry(y, LocalTime.of(18, 40), "com.google.android.keep", "Keep", null, EntryStatus.INSERTED,
                "Купить батарейки AA и фильтр для воды.", "купить батарейки эй эй и фильтр для воды", 6_000),
            entry(y, LocalTime.of(12, 10), "org.telegram.messenger", "Telegram", "Саша", EntryStatus.INSERTED,
                "Созвон переносим на четверг, 11:00.", "созвон переносим на четверг одиннадцать ноль ноль", 5_000),
            entry(today, LocalTime.of(9, 12), "com.android.chrome", "Chrome", null, EntryStatus.FAILED, null, null, 1_800),
            entry(today, LocalTime.of(9, 58), "com.samsung.android.app.notes", "Notes", null, EntryStatus.INSERTED,
                "Список на выходные: купить фильтры для воды, забрать куртку из химчистки, записаться к стоматологу на следующую неделю.",
                "список на выходные купить фильтры для воды забрать куртку из химчистки записаться к стоматологу на следующую неделю", 9_000),
            entry(today, LocalTime.of(11, 20), "com.termux", "Termux", null, EntryStatus.COPIED, "grep -rn \"TODO\" app/src", "grep -rn todo app src", 3_000),
            entry(today, LocalTime.of(12, 41), "com.Slack", "Slack", null, EntryStatus.INSERTED,
                "Деплой на staging прошёл, но тесты на payments падают. Посмотрю после обеда; если кто-то уже копал, напишите в тред.",
                "деплой на стейджинг прошёл но тесты на пейментс падают посмотрю после обеда если кто-то уже копал напишите в тред", 11_000),
            entry(today, LocalTime.of(13, 5), "com.google.android.gm", "Gmail", null, EntryStatus.INSERTED,
                "Hi Laura, thanks for the quick turnaround. The numbers look right to me. Let’s send the Q4 report on Friday and go through the open questions on Monday.",
                "hi laura thanks for the quick turnaround the numbers look right to me lets send the q4 report on friday and go through the open questions on monday", 14_000),
            entry(today, LocalTime.of(14, 32), "org.telegram.messenger", "Telegram", "Дима Соколов", EntryStatus.INSERTED,
                "Привет! Буду в офисе к 11, созвон с Сашей переносим на четверг. Скинь, пожалуйста, ссылку на документ.",
                "привет буду в офисе к одиннадцати созвон с сашей переносим на четверг скинь пожалуйста ссылку на документ", 12_000),
        ).forEach { history.insert(it) }

        // The WP-12 metrics week, moved so that its "today" is today.
        val shift = ChronoUnit.DAYS.between(Wp12MetricsFixture.TODAY, today)
        Wp12MetricsFixture.week.forEach { m ->
            val day = m.localDay.plusDays(shift)
            metrics.add(m.copy(localDay = day, createdAt = m.createdAt.plus(shift, ChronoUnit.DAYS)))
        }

        // Install states: the recommended pack installed; one model mid-download and one paused (hub row states).
        val c = catalog.catalog.value
        val pack = PackPlanner.defaultChoice(PackPlanner.options(c, profile, settings))
        pack?.artifacts?.forEach { downloads.seed(it, InstallState.Installed("/fake/models/$it", 1)) }
        val others = c.models.filter { m -> m.artifacts.none { it.id in (pack?.artifacts ?: emptyList()) } && m.artifacts.isNotEmpty() }
        others.getOrNull(0)?.artifacts?.firstOrNull()?.let {
            downloads.seed(it.id, InstallState.Downloading(it.downloadBytes * 62 / 100, it.downloadBytes, 12_300_000))
        }
        others.getOrNull(1)?.artifacts?.firstOrNull()?.let {
            downloads.seed(it.id, InstallState.Paused(it.downloadBytes * 23 / 100, it.downloadBytes, MurmurError.NoNetwork))
        }
    }

    companion object {
        const val FAKE_ENGINES = "debug-fake-engines"
        /** The fake-engines mode's device-profile cache (noBackupFilesDir), next to the real device-profile.json. */
        const val FAKE_ENGINES_PROFILE = "debug-fake-engines-device-profile.json"
        const val FAKE_CONTAINER = "debug-fake-container"
        const val SEEDED = "seeded"
        const val FRESH = "fresh"
    }
}
