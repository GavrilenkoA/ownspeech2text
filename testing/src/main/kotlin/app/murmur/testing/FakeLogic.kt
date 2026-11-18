package app.murmur.testing

import app.murmur.core.catalog.Catalog
import app.murmur.core.catalog.CatalogParser
import app.murmur.core.catalog.CatalogRepository
import app.murmur.core.catalog.ModelEntry
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
import app.murmur.core.dictation.ModelResolver
import app.murmur.core.engine.LoadSpec
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
import app.murmur.core.store.CleanupChoice
import app.murmur.core.store.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

/** Not byte-exact with render.py: a readable stand-in whose suffix contains the transcript tags the fake LLM reads. */
class FakePromptRenderer : PromptRenderer {
    override fun render(input: RenderInput): RenderedPrompt {
        val prefix = cachePrefix(input.profile, input.template, input.preferences, input.systemOverride)
        val ctx = input.ctx?.let { renderContext(it) } ?: ""
        val suffix = "$ctx\n<transcript>\n${input.transcript}\n</transcript><|im_end|>\n"
        return RenderedPrompt(prefix, suffix, prefixKey = prefix.hashCode().toString())
    }
    override fun renderContext(ctx: CtxV2, ctx2: JsonObject?): String =
        "<context>\nApp: ${ctx.app.label ?: ctx.app.pkg}\n" + (ctx.screen?.lines?.joinToString("\n") { it.text } ?: "") + "\n</context>"
    override fun cachePrefix(profile: PromptProfile, template: String, preferences: String?, systemOverride: String?) =
        "<|im_start|>system\n${systemOverride ?: profile.system}<|im_end|>\n<|im_start|>user\n"
}

/** Accepts every non-empty output; empty output falls back to the transcript. */
class PassThroughGuard : OutputGuard {
    override fun precheck(transcript: String) = Precheck(skip = transcript.isBlank(), transcript = transcript.trim(), reason = if (transcript.isBlank()) "empty" else "ok")
    override fun guard(transcript: String, output: String, ctx: CtxV2?) =
        if (output.isBlank()) GuardVerdict(transcript, GuardAction.FALLBACK, "empty") else GuardVerdict(output.trim(), GuardAction.ACCEPT, "ok")
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
    override val catalog: StateFlow<Catalog> = _catalog.asStateFlow2()
    override suspend fun addCustom(entry: ModelEntry) { _catalog.value = _catalog.value.copy(models = _catalog.value.models + entry) }
    override suspend fun removeCustom(id: ModelId) { _catalog.value = _catalog.value.copy(models = _catalog.value.models.filterNot { it.id == id }) }
    private fun <T> MutableStateFlow<T>.asStateFlow2(): StateFlow<T> = this
}

/** Resolves to CPU specs under /fake/models; [noAsr] simulates "Download a speech model first". */
class FakeModelResolver(var noAsr: Boolean = false, var cleanup: Boolean = true) : ModelResolver {
    override suspend fun resolve(settings: Settings): Outcome<ActiveModels> {
        if (noAsr) return Outcome.Err(MurmurError.NoAsrModel)
        fun spec(kind: ModelKind, id: String, rt: String) =
            LoadSpec(kind, ModelId(id.substringBefore('@')), ArtifactId(id), rt, ComputeUnit.CPU, "/fake/models/$id", emptyMap())
        val llmOn = cleanup && settings.cleanup != CleanupChoice.None
        return Outcome.Ok(ActiveModels(
            asr = spec(ModelKind.ASR, "parakeet-tdt-0.6b-v3@onnx-int8", "sherpa/offline-transducer"),
            asrTraits = app.murmur.core.catalog.AsrTraits(streaming = false, punctuation = true, casing = true),
            vad = spec(ModelKind.VAD, "silero-vad-v5@onnx", "ort/silero-vad-v5"),
            ocrDet = spec(ModelKind.OCR_DET, "pp-ocrv6-tiny-det@onnx", "ort/ppocr-det"),
            ocrRec = spec(ModelKind.OCR_REC, "pp-ocrv5-cyrillic-mobile-rec@onnx", "ort/ppocr-rec"),
            llm = if (llmOn) ActiveLlm(spec(ModelKind.LLM, "qwen3.5-0.8b@q4_0", "llama/gguf"), "qwen3.5", "default.v4", 700, 800) else null,
        ))
    }
}
