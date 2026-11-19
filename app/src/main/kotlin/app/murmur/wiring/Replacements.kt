package app.murmur.wiring

import app.murmur.core.benchmark.BenchmarkRunner
import app.murmur.core.catalog.CatalogRepository
import app.murmur.core.catalog.DeviceProfileRepository
import app.murmur.core.catalog.UnitHealthRepository
import app.murmur.core.dictation.AudioCapture
import app.murmur.core.dictation.MemoryGate
import app.murmur.core.dictation.ModelResolver
import app.murmur.core.engine.Engines
import app.murmur.core.prompt.OutputGuard
import app.murmur.core.prompt.PromptRenderer
import app.murmur.core.store.AudioStore
import app.murmur.core.store.BenchmarkRepository
import app.murmur.core.store.HistoryRepository
import app.murmur.core.store.InstalledModelsRepository
import app.murmur.core.store.MetricsRepository
import app.murmur.core.store.SettingsRepository

/**
 * What a debug build swaps in for AppContainer (D6; `src/debug/.../DebugWiring.kt` builds it from `:testing`, the
 * release variant returns [NONE]). [engines]: inference on deterministic fakes. [data]: the whole data side in memory
 * (the "fake container" for screenshot and shell tests: seeded history, metrics and install states, or a fresh first
 * run). [benchmarkRunner]: used while the real runner (WP-14) is not wired. [renderer] / [guard]: with fake engines the
 * pipeline uses the :testing stand-ins (the real Kotlin ports of render.py / guard.py come with WP-04).
 */
class Replacements(
    val engines: Engines? = null,
    val data: FakeData? = null,
    val benchmarkRunner: ((BenchmarkRepository, UnitHealthRepository) -> BenchmarkRunner)? = null,
    /** Prompt renderer and output guard for fake inference (the fake LLM reads the fake renderer's transcript tags). */
    val renderer: PromptRenderer? = null,
    val guard: OutputGuard? = null,
) {
    companion object {
        val NONE = Replacements()
    }
}

/** The data-side fakes of the debug fake container (all in memory; nothing touches Room, DataStore or the network). */
class FakeData(
    val settings: SettingsRepository,
    val history: HistoryRepository,
    val metrics: MetricsRepository,
    val benchmarks: BenchmarkRepository,
    val audioStore: AudioStore,
    val catalog: CatalogRepository,
    val installed: InstalledModelsRepository,
    val device: DeviceProfileRepository,
    val unitHealth: UnitHealthRepository,
    val resolver: ModelResolver,
    val audio: AudioCapture,
    val memory: MemoryGate,
)
