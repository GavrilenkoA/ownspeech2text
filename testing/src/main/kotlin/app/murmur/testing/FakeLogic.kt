package app.murmur.testing

import app.murmur.core.benchmark.BenchProgress
import app.murmur.core.benchmark.BenchStep
import app.murmur.core.benchmark.BenchmarkRunner
import app.murmur.core.catalog.Candidate
import app.murmur.core.catalog.Catalog
import app.murmur.core.catalog.CatalogParser
import app.murmur.core.catalog.CatalogRepository
import app.murmur.core.catalog.CustomModelRequest
import app.murmur.core.catalog.CustomModelResolver
import app.murmur.core.catalog.DeviceProfile
import app.murmur.core.catalog.DeviceProfileRepository
import app.murmur.core.catalog.ModelEntry
import app.murmur.core.catalog.RuntimeKeys
import app.murmur.core.catalog.UnitHealthRepository
import app.murmur.core.context.A11ySnapshot
import app.murmur.core.context.AppInfo
import app.murmur.core.context.ContextAssembler
import app.murmur.core.context.ContextSettings
import app.murmur.core.context.CtxV2
import app.murmur.core.context.FieldInfo
import app.murmur.core.context.LanguageInfo
import app.murmur.core.context.PrivacyDecision
import app.murmur.core.context.PrivacyLevel
import app.murmur.core.context.Screen
import app.murmur.core.dictation.ActiveLlm
import app.murmur.core.dictation.ActiveModels
import app.murmur.core.dictation.AppNavigator
import app.murmur.core.dictation.LlmSpeed
import app.murmur.core.dictation.ModelResolver
import app.murmur.core.dictation.OpenTarget
import app.murmur.core.dictation.PermissionMonitor
import app.murmur.core.dictation.PermissionState
import app.murmur.core.dictation.StatusNotifier
import app.murmur.core.engine.LoadOptions
import app.murmur.core.engine.LoadSpec
import app.murmur.core.engine.PrefixCacheKey
import app.murmur.core.model.ArtifactId
import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.ModelId
import app.murmur.core.model.ModelKind
import app.murmur.core.model.MurmurError
import app.murmur.core.model.Outcome
import app.murmur.core.ocr.ScreenText
import app.murmur.core.prompt.GuardAction
import app.murmur.core.prompt.GuardVerdict
import app.murmur.core.prompt.OutputGuard
import app.murmur.core.prompt.Precheck
import app.murmur.core.prompt.PromptProfile
import app.murmur.core.prompt.PromptRenderer
import app.murmur.core.prompt.RenderInput
import app.murmur.core.prompt.RenderedPrompt
import app.murmur.core.store.BenchmarkRecord
import app.murmur.core.store.BenchmarkRepository
import app.murmur.core.store.BenchmarkResultData
import app.murmur.core.store.CleanupChoice
import app.murmur.core.store.DeliveryMetrics
import app.murmur.core.store.Settings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject

/** Not byte-exact with render.py: a readable stand-in whose suffix contains the transcript tags the fake LLM reads. */
class FakePromptRenderer : PromptRenderer {
    override fun render(input: RenderInput): RenderedPrompt {
        val prefix = cachePrefix(input.profile, input.template, input.preferences, input.systemOverride)
        val ctx = input.ctx?.let { renderContext(it) } ?: ""
        val suffix = "$ctx\n<transcript>\n${input.transcript}\n</transcript><|im_end|>\n"
        return RenderedPrompt(prefix, suffix, prefixKey = PrefixCacheKey.sha256Hex(prefix))
    }
    override fun renderContext(ctx: CtxV2, ctx2: JsonObject?): String =
        "<context>\nApp: ${ctx.app.label ?: ctx.app.pkg}\n" + (ctx.screen?.lines?.joinToString("\n") { it.text } ?: "") + "\n</context>"
    override fun cachePrefix(profile: PromptProfile, template: String, preferences: String?, systemOverride: String?) =
        "<|im_start|>system\n${systemOverride ?: profile.system}${preferences?.let { "\n$it" } ?: ""}<|im_end|>\n<|im_start|>user\n"
}

/** Accepts every non-empty output; empty output falls back to the transcript; transcripts of fillers only are EMPTY. */
class PassThroughGuard(override val fillers: Set<String> = setOf("э", "ээ", "ну", "эм", "um", "uh")) : OutputGuard {
    override fun precheck(transcript: String) = Precheck(skip = transcript.isBlank(), transcript = transcript.trim(), reason = if (transcript.isBlank()) "empty" else "ok")
    override fun guard(transcript: String, output: String, ctx: CtxV2?) = when {
        transcript.split(' ').filter { it.isNotBlank() }.all { it.lowercase() in fillers } -> GuardVerdict("", GuardAction.EMPTY, "fillers_only")
        output.isBlank() -> GuardVerdict(transcript, GuardAction.FALLBACK, "R1_empty")
        else -> GuardVerdict(output.trim(), GuardAction.ACCEPT, "ok")
    }
}

/** Minimal rules: password -> none + skip LLM; excluded app -> app_only; screen setting off -> app_only. */
class SimpleContextAssembler : ContextAssembler {
    override fun decide(snapshot: A11ySnapshot, settings: ContextSettings): PrivacyDecision = when {
        snapshot.field?.isPassword == true -> PrivacyDecision(PrivacyLevel.NONE, false, "password", skipLlm = true)
        snapshot.pkg in settings.excludedPackages -> PrivacyDecision(PrivacyLevel.APP_ONLY, false, "denylist", skipLlm = false)
        !settings.useScreen -> PrivacyDecision(PrivacyLevel.APP_ONLY, false, "setting_off", skipLlm = false)
        else -> PrivacyDecision(PrivacyLevel.FULL, true, null, skipLlm = false)
    }

    override fun assemble(snapshot: A11ySnapshot, decision: PrivacyDecision, screen: ScreenText?, asrLanguage: String?) = CtxV2(
        app = AppInfo(snapshot.pkg, snapshot.label, null),
        field = snapshot.field?.takeIf { decision.level == PrivacyLevel.FULL }?.let { FieldInfo(kind = "message", hint = it.hint, beforeCursor = it.beforeCursor) },
        screen = screen?.takeIf { decision.level == PrivacyLevel.FULL }?.let { Screen(it.size, it.lines) },
        language = LanguageInfo(asrLanguage),
        privacy = if (decision.level == PrivacyLevel.FULL) null else app.murmur.core.context.Privacy(decision.level, decision.reason),
    )
}

class FakeCatalogRepository(initial: Catalog = CatalogParser.bundled()) : CatalogRepository {
    private val _catalog = MutableStateFlow(initial)
    override val catalog: StateFlow<Catalog> = _catalog.asStateFlow()
    override suspend fun addCustom(entry: ModelEntry) { _catalog.value = _catalog.value.copy(models = _catalog.value.models + entry) }
    override suspend fun removeCustom(id: ModelId) { _catalog.value = _catalog.value.copy(models = _catalog.value.models.filterNot { it.id == id }) }
}

/**
 * Resolves to specs under /fake/models. [npuFirst] = the Fold7 shape: NPU primary + CPU fallback for ASR (two
 * artifacts) and LLM (same artifact, other unit); [cpuFallback] = false drops the fallbacks (no CPU artifact
 * installed). [noAsr] / [noVad] simulate the resolver errors; [cleanup] = false -> llm null with llmUnavailable.
 */
class FakeModelResolver(
    var noAsr: Boolean = false,
    var noVad: Boolean = false,
    var cleanup: Boolean = true,
    var npuFirst: Boolean = false,
    var cpuFallback: Boolean = true,
    var llmSpeed: LlmSpeed? = LlmSpeed(prefillTps = 569.0, decodeTps = 83.8),
    var nCtx: Int = 9_216,
    var prefixTokens: Int? = 3_129,
) : ModelResolver {
    var calls = 0; private set

    override suspend fun resolve(settings: Settings): Outcome<ActiveModels> {
        calls++
        if (noAsr) return Outcome.Err(MurmurError.NoAsrModel)
        if (noVad) return Outcome.Err(MurmurError.NoVadModel)
        fun spec(kind: ModelKind, id: String, rt: String, unit: ComputeUnit = ComputeUnit.CPU, opts: Map<String, String> = emptyMap()) =
            LoadSpec(kind, ModelId(id.substringBefore('@')), ArtifactId(id), rt, unit, "/fake/models/$id", emptyMap(), options = opts)
        val asrCpu = spec(ModelKind.ASR, "parakeet-tdt-0.6b-v3@onnx-int8", RuntimeKeys.SHERPA_OFFLINE_TRANSDUCER)
        val asrNpu = spec(ModelKind.ASR, "parakeet-tdt-0.6b-v3@qnn-sm8750-20s", RuntimeKeys.SHERPA_OFFLINE_TRANSDUCER, ComputeUnit.NPU)
        val llmOpts = mapOf(LoadOptions.N_CTX to nCtx.toString())
        val llmCpu = spec(ModelKind.LLM, "qwen3.5-0.8b@q4_0", "llama/gguf", ComputeUnit.CPU, llmOpts + (LoadOptions.USE_MMAP to "0"))
        val llmNpu = spec(ModelKind.LLM, "qwen3.5-0.8b@q4_0", "llama/gguf", ComputeUnit.NPU, llmOpts)
        val llmOn = cleanup && settings.cleanup != CleanupChoice.None
        return Outcome.Ok(ActiveModels(
            asr = if (npuFirst) asrNpu else asrCpu,
            asrFallback = if (npuFirst && cpuFallback) asrCpu else null,
            asrTraits = app.murmur.core.catalog.AsrTraits(streaming = false, punctuation = true, casing = true),
            vad = spec(ModelKind.VAD, "silero-vad-v5@onnx", "ort/silero-vad-v5"),
            ocrDet = spec(ModelKind.OCR_DET, "pp-ocrv6-tiny-det@onnx", "ort/ppocr-det"),
            ocrRec = spec(ModelKind.OCR_REC, "pp-ocrv5-cyrillic-mobile-rec@onnx", "ort/ppocr-rec"),
            llm = if (!llmOn) null else ActiveLlm(
                spec = if (npuFirst) llmNpu else llmCpu,
                fallback = if (npuFirst && cpuFallback) llmCpu else null,
                template = "qwen3.5", profile = "default.v4", memoryMb = 770, nCtx = nCtx, prefixTokens = prefixTokens,
                modelSha256 = "0".repeat(64), speed = llmSpeed,
            ),
            llmUnavailable = if (llmOn) null else if (settings.cleanup == CleanupChoice.None) "none" else "no_model",
        ))
    }
}

class FakeDeviceProfileRepository(
    initial: DeviceProfile = EMULATOR,
    var failProbe: Boolean = false,
) : DeviceProfileRepository {
    private val _profile = MutableStateFlow<DeviceProfile?>(null)
    private val real = initial
    override val profile: StateFlow<DeviceProfile?> = _profile.asStateFlow()
    var probes = 0; private set
    override suspend fun ensure(): DeviceProfile = _profile.value ?: reprobe()
    override suspend fun reprobe(): DeviceProfile {
        probes++
        val p = if (failProbe) DeviceProfile.cpuOnly(real.abi, real.totalRamMb, real.supportedRuntimes) else real
        _profile.value = p
        return p
    }

    companion object {
        val EMULATOR = DeviceProfile(null, "x86_64", setOf(ComputeUnit.CPU), RuntimeKeys.known, 3_072, mapOf("llm" to "fake-1"))
        val FOLD7 = DeviceProfile("SM8750", "arm64-v8a", setOf(ComputeUnit.NPU, ComputeUnit.GPU, ComputeUnit.CPU), RuntimeKeys.known, 11_500, mapOf("llm" to "fake-1"))
    }
}

class FakeUnitHealth : UnitHealthRepository {
    val failedUnits = mutableMapOf<Pair<ArtifactId, ComputeUnit>, String>()
    val provenUnits = mutableSetOf<Pair<ArtifactId, ComputeUnit>>()
    override fun failed(artifact: ArtifactId, unit: ComputeUnit) = (artifact to unit) in failedUnits
    override fun proven(artifact: ArtifactId, unit: ComputeUnit) = (artifact to unit) in provenUnits
    override suspend fun markFailed(artifact: ArtifactId, unit: ComputeUnit, code: String) { failedUnits[artifact to unit] = code }
    override suspend fun markProven(artifact: ArtifactId, unit: ComputeUnit) { provenUnits += artifact to unit }
    override suspend fun clear(artifact: ArtifactId, unit: ComputeUnit) { failedUnits -= artifact to unit }
}

/** Returns [result] (or a CUSTOM copy of the first catalog entry of the requested kind). */
class FakeCustomModelResolver(var result: Outcome<ModelEntry>? = null, private val catalog: Catalog = CatalogParser.bundled()) : CustomModelResolver {
    val requests = mutableListOf<CustomModelRequest>()
    override suspend fun resolve(request: CustomModelRequest): Outcome<ModelEntry> {
        requests += request
        result?.let { return it }
        val base = catalog.models.first { it.kind == request.kind && it.artifacts.isNotEmpty() }
        return Outcome.Ok(base.copy(id = ModelId("custom-" + request.repo.substringAfter('/').lowercase()), displayName = request.displayName ?: request.repo,
            origin = app.murmur.core.catalog.Origin.CUSTOM))
    }
}

/**
 * Scripted benchmark (WP-18 sheets, WP-14 consumers): emits DESIGN bench-run steps per usable candidate with
 * [stepMs] of virtual time each, then Finished with sample records ([valid] false simulates "Results look wrong").
 */
class FakeBenchmarkRunner(
    var stepMs: Long = 300,
    var valid: Boolean = true,
    private val repo: BenchmarkRepository? = null,
) : BenchmarkRunner {
    var stopped = false; private set
    override fun run(entry: ModelEntry, candidates: List<Candidate>): Flow<BenchProgress> = flow {
        stopped = false
        val records = mutableListOf<BenchmarkRecord>()
        val steps = when (entry.kind) {
            ModelKind.ASR -> listOf(BenchStep.LOAD, BenchStep.SAMPLE_RU, BenchStep.SAMPLE_EN, BenchStep.UNLOAD_MEMORY)
            ModelKind.LLM -> listOf(BenchStep.LOAD, BenchStep.CLEANUP_CASE, BenchStep.UNLOAD_MEMORY)
            else -> listOf(BenchStep.LOAD, BenchStep.OCR_PAGE, BenchStep.UNLOAD_MEMORY)
        }
        for (c in candidates.filter { it.usable }) {
            var t = 0L
            for (s in steps) {
                if (stopped) return@flow
                emit(BenchProgress.Step(s, c.unit, t, done = false)); delay(stepMs); t += stepMs
                emit(BenchProgress.Step(s, c.unit, t, done = true))
            }
            val reason = if (valid) null else "wer_high"
            val data = when (entry.kind) {
                ModelKind.ASR -> BenchmarkResultData.Asr(14.2, 180, 800, 120, 1_100, c.unit.name, emptyList(), valid, reason)
                ModelKind.LLM -> BenchmarkResultData.Llm(40.9, 2_597.0, 310.0, 1_300.0, 2_100, 1_160, c.unit.name, 0.97, valid = valid, invalidReason = reason)
                else -> BenchmarkResultData.Ocr(450, 21, 90, 140, c.unit.name, valid, reason)
            }
            records += BenchmarkRecord(c.artifact.id, c.unit, "0.1.0", "fake/fingerprint", 0, data).also { repo?.save(it) }
        }
        emit(BenchProgress.Finished(records))
    }
    override fun stop() { stopped = true }
}

/** Plain reference implementation for orchestrator tests (WP-12 owns the real one in core:store). */
class SimpleDeliveryMetrics : DeliveryMetrics {
    private fun words(s: String) = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
    override fun wordCount(text: String) = words(text).size
    override fun editDistanceWords(raw: String, final: String): Int {
        val a = words(raw); val b = words(final)
        val d = IntArray(b.size + 1) { it }
        for (i in 1..a.size) { var prev = d[0]; d[0] = i
            for (j in 1..b.size) { val t = d[j]; d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1); prev = t } }
        return d[b.size]
    }
    override fun fillersRemoved(raw: String, final: String, fillers: Set<String>) =
        (words(raw).count { it in fillers } - words(final).count { it in fillers }).coerceAtLeast(0)
    override fun fixes(raw: String, final: String, fillers: Set<String>) =
        (editDistanceWords(raw, final) - fillersRemoved(raw, final, fillers)).coerceAtLeast(0)
    override fun mixedLanguage(text: String): Boolean {
        val w = words(text)
        val cyr = w.count { t -> t.any { it in 'Ѐ'..'ӿ' } }
        val lat = w.count { t -> t.any { it in 'a'..'z' } }
        return minOf(cyr, lat) >= 2
    }
}

class RecordingNavigator : AppNavigator {
    val opened = mutableListOf<OpenTarget>()
    override fun open(target: OpenTarget) { opened += target }
}

class FakePermissionMonitor(
    initial: PermissionState = PermissionState(micGranted = true, notificationsGranted = true, a11yEnabled = true,
        a11yConnected = true, a11yEverConnected = true, batteryUnrestricted = true, restrictedLikely = false),
) : PermissionMonitor {
    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<PermissionState> = _state.asStateFlow()
    var refreshes = 0; private set
    fun set(transform: (PermissionState) -> PermissionState) { _state.value = transform(_state.value) }
    override fun refresh() { refreshes++ }
}

class RecordingStatusNotifier : StatusNotifier {
    var shown: Pair<String, Long?>? = null; private set
    override fun show(modelsLine: String, hiddenUntilEpochMs: Long?) { shown = modelsLine to hiddenUntilEpochMs }
    override fun hide() { shown = null }
}
