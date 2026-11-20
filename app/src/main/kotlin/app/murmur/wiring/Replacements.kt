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
 * run). [renderer] / [guard]: with fake engines the pipeline uses the :testing stand-ins (the real Kotlin ports of render.py / guard.py come with WP-04).
 * [engineRecords]: with fake engines but the real data side, where what the engines report is kept (ADR-089).
 */
class Replacements(
    val engines: Engines? = null,
    val data: FakeData? = null,
    /** Prompt renderer and output guard for fake inference (the fake LLM reads the fake renderer's transcript tags). */
    val renderer: PromptRenderer? = null,
    val guard: OutputGuard? = null,
    val engineRecords: EngineRecords? = null,
) {
    companion object {
        val NONE = Replacements()
    }
}

/**
 * ADR-089: the stores that hold what the engines report (benchmark records, unit health, the probed device profile),
 * for the debug fake-engines mode with the real data side. Fake engines report made-up timings, failures and units; the
 * real resolver ranks compute units from these stores, so they must never reach the real database, the real unit-health
 * file or the real device-profile cache (a later normal run would rank and pick units from them). They last for the
 * process (benchmarks, unit health) or live in a cache file of their own (device profile).
 */
class EngineRecords(
    val benchmarks: BenchmarkRepository,
    val unitHealth: UnitHealthRepository,
    val device: DeviceProfileRepository,
)

/**
 * The data-side fakes of the debug fake container (all in memory; nothing touches Room, DataStore or the network).
 * [benchmarkRunner] is the :testing runner, which saves made-up records into [benchmarks]: it belongs to the fake
 * container only, so that it can never write into the real database that the real resolver ranks units from.
 */
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
    val benchmarkRunner: BenchmarkRunner,
)
