package app.murmur.data.models

import app.murmur.core.catalog.Artifact
import app.murmur.core.catalog.ArtifactSelector
import app.murmur.core.catalog.BenchLookup
import app.murmur.core.catalog.Candidate
import app.murmur.core.catalog.Catalog
import app.murmur.core.catalog.CatalogRepository
import app.murmur.core.catalog.CatalogValidator
import app.murmur.core.catalog.DeviceProfile
import app.murmur.core.catalog.DeviceProfileRepository
import app.murmur.core.catalog.MemoryEstimate
import app.murmur.core.catalog.ModelEntry
import app.murmur.core.catalog.RuntimeKeys
import app.murmur.core.catalog.Status
import app.murmur.core.catalog.UnitHealth
import app.murmur.core.dictation.ActiveLlm
import app.murmur.core.dictation.ActiveModels
import app.murmur.core.dictation.LlmSpeed
import app.murmur.core.dictation.ModelResolver
import app.murmur.core.engine.LoadOptions
import app.murmur.core.engine.LoadSpec
import app.murmur.core.model.ArtifactId
import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.ModelId
import app.murmur.core.model.ModelKind
import app.murmur.core.model.MurmurError
import app.murmur.core.model.Outcome
import app.murmur.core.model.UnitPreference
import app.murmur.core.store.BenchmarkRecord
import app.murmur.core.store.BenchmarkRepository
import app.murmur.core.store.BenchmarkResultData
import app.murmur.core.store.CleanupChoice
import app.murmur.core.store.InstallState
import app.murmur.core.store.InstalledModelsRepository
import app.murmur.core.store.Settings
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Settings + catalog + install state + device profile + unit health + valid benchmarks -> LoadSpecs
 * (ARCHITECTURE 8.2, 8.4, ADR-050). Primary = first of [ArtifactSelector.ranked] over the installed artifacts, fallback
 * = the next one (the LLM fallback is the same GGUF on the next unit). Never throws.
 */
class DefaultModelResolver(
    private val catalog: CatalogRepository,
    private val installed: InstalledModelsRepository,
    private val device: DeviceProfileRepository,
    private val health: UnitHealth,
    private val benchmarks: BenchmarkRepository,
) : ModelResolver {

    override suspend fun resolve(settings: Settings): Outcome<ActiveModels> = try {
        resolveOrErr(settings)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Outcome.Err(MurmurError.Internal("resolver: ${e.javaClass.simpleName}"))
    }

    private suspend fun resolveOrErr(settings: Settings): Outcome<ActiveModels> {
        // A custom model selected by the user exists only once the stored entries are read (first resolve after start).
        val c = withTimeoutOrNull(CATALOG_WAIT_MS) { catalog.loaded() } ?: catalog.catalog.value
        val dev = device.ensure()

        // Speech model: the user's choice when it may be the speech model (ADR-047), else the language default.
        val asrEntry = speechModel(c, settings) ?: return Outcome.Err(MurmurError.NoAsrModel)
        val asr = rank(asrEntry, dev, settings)
        if (asr.ranked.isEmpty()) return Outcome.Err(MurmurError.NoAsrModel)
        val primary = asr.ranked.first()

        val vad = c.model(c.defaults.vad)?.let { first(it, dev, settings) }
        if (RuntimeKeys.isBatchAsr(primary.artifact.runtime) && vad == null) return Outcome.Err(MurmurError.NoVadModel)
        val asrFallback = ArtifactSelector.fallbackAfter(asr.ranked, primary)
            ?.takeIf { !RuntimeKeys.isBatchAsr(it.artifact.runtime) || vad != null }

        val (ocrDet, ocrRec) = if (!settings.useScreen) null to null else {
            val det = c.model(c.defaults.ocrDet)?.let { first(it, dev, settings) }
            val rec = c.model(c.defaults.ocrRec)?.let { first(it, dev, settings) }
            if (det != null && rec != null) det to rec else null to null   // OCR needs both stages
        }

        val (llm, llmUnavailable) = cleanupModel(c, dev, settings)

        return Outcome.Ok(
            ActiveModels(
                asr = asr.spec(primary),
                asrFallback = asrFallback?.let { asr.spec(it) },
                asrTraits = asrEntry.asr!!,
                vad = vad,
                ocrDet = ocrDet,
                ocrRec = ocrRec,
                llm = llm,
                llmUnavailable = llmUnavailable,
            ),
        )
    }

    private fun speechModel(c: Catalog, settings: Settings): ModelEntry? {
        settings.asrModel?.let { id -> c.model(id)?.takeIf { it.selectableAsAsr }?.let { return it } }
        val key = languageProfile(settings.languages)
        val id = c.defaults.asr[key] ?: c.defaults.asr.values.firstOrNull() ?: return null
        return c.model(id)?.takeIf { it.selectableAsAsr }
    }

    private suspend fun cleanupModel(c: Catalog, dev: DeviceProfile, settings: Settings): Pair<ActiveLlm?, String?> {
        val id: ModelId = when (val choice = settings.cleanup) {
            CleanupChoice.None -> return null to LLM_NONE
            CleanupChoice.Default -> c.defaults.llm ?: return null to LLM_NONE
            is CleanupChoice.Model -> choice.id
        }
        val entry = c.model(id)?.takeIf { it.kind == ModelKind.LLM && it.status == Status.AVAILABLE && it.llm != null }
            ?: return null to LLM_NO_MODEL
        val r = rank(entry, dev, settings)
        val primary = r.ranked.firstOrNull() ?: return null to LLM_NO_MODEL
        val fallback = ArtifactSelector.fallbackAfter(r.ranked, primary)
        val traits = entry.llm!!
        val gguf = ggufFile(primary.artifact) ?: return null to LLM_NO_MODEL
        val speed = (r.records[primary.artifact.id].orEmpty().latestFor(primary.unit)?.result as? BenchmarkResultData.Llm)
            ?.takeIf { it.valid && it.prefillTps > 0 && it.decodeTps > 0 }
            ?.let { LlmSpeed(prefillTps = it.prefillTps, decodeTps = it.decodeTps) }
        return ActiveLlm(
            spec = r.spec(primary),
            fallback = fallback?.let { r.spec(it) },
            template = traits.template,
            profile = traits.promptProfile,
            memoryMb = MemoryEstimate.mb(entry, primary.artifact, primary.unit),
            nCtx = traits.nCtx,
            prefixTokens = traits.prefixTokens,
            modelSha256 = gguf.sha256,
            speed = speed,
        ) to null
    }

    /** Ranked installed candidates of one model; a pinned unit nothing installed serves falls back to Auto. */
    private suspend fun rank(entry: ModelEntry, dev: DeviceProfile, settings: Settings): Ranked {
        val dirs = mutableMapOf<ArtifactId, String>()
        for (a in entry.artifacts) (installed.state(a.id).first() as? InstallState.Installed)?.let { dirs[a.id] = it.dir }
        val records = entry.artifacts.associate { a -> a.id to benchmarks.latest(a.id).first() }
        val bench = BenchScores(records.values.flatten())
        val pref = settings.unitPreference[entry.id.value] ?: UnitPreference.Auto
        var ranked = ArtifactSelector.ranked(entry, dev, pref, bench, dirs.keys, health)
        if (ranked.isEmpty() && pref is UnitPreference.Pinned)
            ranked = ArtifactSelector.ranked(entry, dev, UnitPreference.Auto, bench, dirs.keys, health)
        return Ranked(entry, ranked, dirs, records)
    }

    private suspend fun first(entry: ModelEntry, dev: DeviceProfile, settings: Settings): LoadSpec? =
        rank(entry, dev, settings).let { r -> r.ranked.firstOrNull()?.let { r.spec(it) } }

    private class Ranked(
        val entry: ModelEntry,
        val ranked: List<Candidate>,
        val dirs: Map<ArtifactId, String>,
        val records: Map<ArtifactId, List<BenchmarkRecord>>,
    ) {
        fun spec(c: Candidate): LoadSpec = loadSpec(entry, c.artifact, c.unit, dirs.getValue(c.artifact.id))
    }

    companion object {
        const val LLM_NONE = "none"
        const val LLM_NO_MODEL = "no_model"
        const val LLM_THREADS = 4
        /** Upper bound on waiting for the stored custom entries (Room's first read takes milliseconds). */
        const val CATALOG_WAIT_MS = 2_000L

        /** Settings.languages -> catalog defaults.asr key. */
        fun languageProfile(languages: List<String>): String {
            val ru = "ru" in languages
            val en = "en" in languages
            return when {
                ru && !en -> "ru"
                en && !ru -> "en"
                else -> "ru+en"
            }
        }

        /**
         * The spec an inference process loads. For archives that extract into one folder (layout role "dir") the
         * spec's dir is that folder and the role is dropped, so the layout paths are relative to [LoadSpec.dir].
         */
        fun loadSpec(entry: ModelEntry, a: Artifact, unit: ComputeUnit, installedDir: String): LoadSpec {
            val sub = a.layout[CatalogValidator.LAYOUT_DIR]
            val options = buildMap {
                entry.llm?.let { l ->
                    put(LoadOptions.N_CTX, l.nCtx.toString())
                    put(LoadOptions.THREADS, LLM_THREADS.toString())
                    if (unit == ComputeUnit.CPU) put(LoadOptions.USE_MMAP, "0")   // ADR-031
                }
            }
            return LoadSpec(
                kind = entry.kind,
                modelId = entry.id,
                artifactId = a.id,
                runtime = a.runtime,
                unit = unit,
                dir = if (sub != null) File(installedDir, sub).path else installedDir,
                layout = a.layout - CatalogValidator.LAYOUT_DIR,
                maxWindowSec = a.maxWindowSec,
                options = options,
            )
        }
    }
}

/**
 * [BenchLookup] over stored records: the latest record of (artifact, unit) decides; an invalid latest record scores
 * null (ARCHITECTURE 10: a fast but wrong unit is never chosen, and an older valid run does not outvote it). Score:
 * ASR processing ms per audio second (1000 / rtfx), LLM end-to-end ms of the fixed case, OCR ms per screenshot.
 */
class BenchScores(records: List<BenchmarkRecord>) : BenchLookup {
    private val latest: Map<Pair<ArtifactId, ComputeUnit>, BenchmarkRecord> =
        records.groupBy { it.artifactId to it.unit }.mapValues { (_, l) -> l.maxBy { it.atEpochMs } }

    override fun score(artifact: ArtifactId, unit: ComputeUnit): Double? {
        val r = latest[artifact to unit]?.result ?: return null
        if (!r.valid) return null
        return when (r) {
            is BenchmarkResultData.Asr -> if (r.rtfx > 0) 1000.0 / r.rtfx else null
            is BenchmarkResultData.Llm -> r.e2eMs.takeIf { it > 0 }
            is BenchmarkResultData.Ocr -> r.msPerScreenshot.toDouble().takeIf { it > 0 }
        }
    }
}

private fun List<BenchmarkRecord>.latestFor(unit: ComputeUnit): BenchmarkRecord? = filter { it.unit == unit }.maxByOrNull { it.atEpochMs }
