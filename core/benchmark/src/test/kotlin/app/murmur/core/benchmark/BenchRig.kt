@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.murmur.core.benchmark

import app.murmur.core.catalog.ArtifactSelector
import app.murmur.core.catalog.Candidate
import app.murmur.core.catalog.CatalogParser
import app.murmur.core.catalog.DeviceProfile
import app.murmur.core.catalog.ModelEntry
import app.murmur.core.engine.Engines
import app.murmur.core.engine.LoadSpec
import app.murmur.core.model.ModelId
import app.murmur.core.model.ModelKind
import app.murmur.core.model.Outcome
import app.murmur.core.prompt.ClasspathPromptAssets
import app.murmur.core.store.BenchmarkRecord
import app.murmur.testing.FakeDeviceProfileRepository
import app.murmur.testing.FakeEngines
import app.murmur.testing.FakePromptRenderer
import app.murmur.testing.FakeUnitHealth
import app.murmur.testing.InMemoryBenchmarkRepository
import app.murmur.testing.PassThroughGuard
import app.murmur.testing.VirtualClock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope

/** Catalog entries and a runner wired to the :testing fakes, on the test's virtual clock. */
internal class BenchRig(
    scope: TestScope,
    val engines: Engines = FakeEngines(),
    val health: FakeUnitHealth = FakeUnitHealth(),
    val repo: InMemoryBenchmarkRepository = InMemoryBenchmarkRepository(),
    val environment: TestEnvironment = TestEnvironment(),
    config: BenchConfig = BenchConfig(),
    val exits: app.murmur.testing.FakeProcessExits = app.murmur.testing.FakeProcessExits(),
) {
    val clock = VirtualClock { scope.testScheduler.currentTime }
    val specRequests = mutableListOf<Pair<ModelId, LoadSpec>>()

    val runner = DefaultBenchmarkRunner(
        engines = engines,
        specs = BenchSpecProvider { entry, c ->
            fun spec(e: ModelEntry, a: app.murmur.core.catalog.Artifact, u: app.murmur.core.model.ComputeUnit) =
                LoadSpec(e.kind, e.id, a.id, a.runtime, u, "/fake/models/${a.id}", a.layout)
            val main = spec(entry, c.artifact, c.unit).also { specRequests += entry.id to it }
            val vad = if (entry.kind == ModelKind.ASR && entry.asr?.streaming == false) model("silero-vad-v5").let { spec(it, it.artifacts.first(), c.unit) } else null
            val partner = when (entry.kind) {
                ModelKind.OCR_DET -> model("pp-ocrv5-cyrillic-mobile-rec")
                ModelKind.OCR_REC -> model("pp-ocrv6-tiny-det")
                else -> null
            }?.let { spec(it, it.artifacts.first(), c.unit) }
            Outcome.Ok(BenchSpecs(main, vad, partner, modelSha256 = c.artifact.files.firstOrNull()?.sha256))
        },
        records = repo,
        health = health,
        clock = clock,
        environment = environment,
        renderer = FakePromptRenderer(),
        guard = PassThroughGuard(),
        assets = ClasspathPromptAssets(),
        inputs = { INPUTS },
        config = config,
        exits = exits,
    )

    val fake: FakeEngines get() = engines as FakeEngines

    /**
     * What the hub passes: candidates of the installed artifacts. [installed] defaults to every artifact of an ASR or
     * OCR entry (Parakeet: the NPU and the CPU artifact) and to the default (first) artifact of an LLM entry.
     */
    fun candidates(
        entry: ModelEntry,
        device: DeviceProfile = FakeDeviceProfileRepository.EMULATOR,
        installed: Set<app.murmur.core.model.ArtifactId> = defaultInstalled(entry),
    ): List<Candidate> = ArtifactSelector.candidates(entry, device, health).filter { it.artifact.id in installed }

    suspend fun run(entry: ModelEntry, device: DeviceProfile = FakeDeviceProfileRepository.EMULATOR): List<BenchProgress> =
        runner.run(entry, candidates(entry, device)).toList()

    private fun defaultInstalled(entry: ModelEntry) =
        if (entry.kind == ModelKind.LLM) setOf(entry.artifacts.first().id) else entry.artifacts.map { it.id }.toSet()

    suspend fun saved(entry: ModelEntry): List<BenchmarkRecord> = entry.artifacts.flatMap { repo.latest(it.id).first() }

    class TestEnvironment(var thermal: String = "none") : BenchEnvironment {
        override val appVersion = "1.0.0-test"
        override val buildFingerprint = "samsung/q7q/test:16/BP2A/1:user/release-keys"
        override fun thermalStatus() = thermal
    }

    companion object {
        val CATALOG = CatalogParser.bundled()
        val INPUTS: BenchInputs by lazy { BenchInputs.bundled() }
        fun model(id: String): ModelEntry = CATALOG.model(ModelId(id)) ?: error("no $id in the bundled catalog")
        val PARAKEET get() = model("parakeet-tdt-0.6b-v3")
        val GIGAAM get() = model("gigaam-v3-e2e-rnnt")
        val QWEN get() = model("qwen3.5-0.8b")
        val T_ONE get() = model("t-one")
        val OCR_DET get() = model("pp-ocrv6-tiny-det")

        fun clip(id: String) = INPUTS.clips.single { it.id == id }

        /** Transcripts in the order the runner opens sessions for [entry]: warm-up + measured pass per clip. */
        fun perfectScripts(entry: ModelEntry): List<String> =
            INPUTS.clips.filter { it.runsOn(entry.languages.codes) }.flatMap { listOf(it.reference, it.reference) }
    }
}

internal fun List<BenchProgress>.steps(): List<BenchProgress.Step> = filterIsInstance<BenchProgress.Step>()

/** Distinct checklist rows in order of their start event. */
internal fun List<BenchProgress>.startedSteps(): List<BenchStep> = steps().filter { !it.done }.map { it.step }
