package app.murmur.infer.asr

import app.murmur.core.catalog.RuntimeKeys
import app.murmur.core.engine.LoadOptions
import app.murmur.core.engine.LoadSpec
import app.murmur.core.model.ComputeUnit
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineToneCtcModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.QnnConfig
import java.io.File

/** Where the QNN libraries of the APK live (nativeLibraryDir, extracted: useLegacyPackaging, ADR-007). */
data class QnnLibs(val backendLib: String, val systemLib: String) {
    companion object {
        fun inDir(dir: String) = QnnLibs("$dir/libQnnHtp.so", "$dir/libQnnSystem.so")
    }
}

/** A LoadSpec that cannot be turned into a recognizer config; [reason] is a code (never a path, D9). */
class SpecException(val reason: String) : Exception(reason)

/**
 * Pure mapping LoadSpec -> sherpa-onnx recognizer config (no native code; unit-tested on the JVM).
 * Paths: `<dir>/<layout.dir>/<layout[role]>` (the NPU tarballs extract into a sub-directory named by `layout.dir`).
 * Provider: NPU -> "qnn" with the context binaries in [QnnConfig.contextBinary] (comma-separated, encoder first);
 * CPU -> "cpu". The model type is read from the ONNX metadata on the CPU (verified for Parakeet v3 and GigaAM v3:
 * `.cache/spikes/wp-06/scripts/mt_check.py`) unless `options["model_type"]` sets it; the QNN transducers need
 * "nemo_transducer" (sherpa picks its Parakeet TDT / Nemotron QNN runner from it).
 */
object SherpaConfigs {
    /** Runtime keys the :asr process implements (probe, ADR-008 contract). */
    val OFFLINE = setOf(
        RuntimeKeys.SHERPA_OFFLINE_TRANSDUCER, RuntimeKeys.SHERPA_OFFLINE_CTC,
        RuntimeKeys.SHERPA_OFFLINE_WHISPER, RuntimeKeys.SHERPA_OFFLINE_QWEN3_ASR,
    )
    val ONLINE = setOf(RuntimeKeys.SHERPA_ONLINE_TRANSDUCER, RuntimeKeys.SHERPA_ONLINE_TONE)
    val SHERPA = OFFLINE + ONLINE

    /** Runtimes with a language hint (catalog `languages.mode = hint`): Whisper, Qwen3-ASR, Nemotron. */
    fun takesHint(runtime: String) = runtime == RuntimeKeys.SHERPA_OFFLINE_WHISPER ||
        runtime == RuntimeKeys.SHERPA_OFFLINE_QWEN3_ASR || runtime == RuntimeKeys.SHERPA_ONLINE_TRANSDUCER

    const val DEFAULT_THREADS = 4
    const val MODEL_TYPE = "model_type"

    fun provider(unit: ComputeUnit): String = when (unit) {
        ComputeUnit.NPU -> "qnn"
        ComputeUnit.CPU -> "cpu"
        ComputeUnit.GPU -> throw SpecException("unit.gpu")
    }

    fun threads(spec: LoadSpec): Int =
        spec.options[LoadOptions.THREADS]?.toIntOrNull()?.coerceIn(1, 8)
            ?: minOf(DEFAULT_THREADS, Runtime.getRuntime().availableProcessors()).coerceAtLeast(1)

    /** Absolute path of a layout role; throws if the role is missing from the layout. */
    fun path(spec: LoadSpec, role: String): String {
        val rel = spec.layout[role] ?: throw SpecException("layout.$role")
        val sub = spec.layout["dir"]?.takeIf { it.isNotBlank() }
        return listOfNotNull(spec.dir.trimEnd('/'), sub, rel).joinToString("/")
    }

    /** Every file the runtime will open, by role; used to report a missing file before sherpa sees it. */
    fun requiredFiles(spec: LoadSpec): Map<String, String> {
        val roles = when (spec.runtime) {
            RuntimeKeys.SHERPA_OFFLINE_TRANSDUCER, RuntimeKeys.SHERPA_ONLINE_TRANSDUCER ->
                listOf("encoder", "decoder", "joiner", "tokens")
            RuntimeKeys.SHERPA_OFFLINE_WHISPER -> listOf("encoder", "decoder", "tokens")
            RuntimeKeys.SHERPA_OFFLINE_CTC, RuntimeKeys.SHERPA_ONLINE_TONE -> listOf("model", "tokens")
            RuntimeKeys.SHERPA_OFFLINE_QWEN3_ASR -> listOf("frontend", "encoder", "decoder", "vocab", "merges", "tokenizerConfig")
            RuntimeKeys.ORT_SILERO_VAD -> listOf("model")
            else -> throw SpecException("runtime")
        }
        return roles.associateWith { path(spec, it) }
    }

    fun missingRole(spec: LoadSpec): String? =
        requiredFiles(spec).entries.firstOrNull { !File(it.value).isFile }?.key

    private fun qnn(spec: LoadSpec, libs: QnnLibs, roles: List<String>) =
        QnnConfig(backendLib = libs.backendLib, systemLib = libs.systemLib, contextBinary = roles.joinToString(",") { path(spec, it) })

    fun offline(spec: LoadSpec, qnnLibs: QnnLibs?, language: String? = null): OfflineRecognizerConfig {
        val provider = provider(spec.unit)
        val onNpu = spec.unit == ComputeUnit.NPU
        if (onNpu && qnnLibs == null) throw SpecException("unit.npu")
        val model = OfflineModelConfig(
            numThreads = threads(spec),
            provider = provider,
            debug = false,
            modelType = spec.options[MODEL_TYPE] ?: "",
        )
        when (spec.runtime) {
            RuntimeKeys.SHERPA_OFFLINE_TRANSDUCER -> {
                model.tokens = path(spec, "tokens")
                if (onNpu) {
                    model.transducer = OfflineTransducerModelConfig(qnnConfig = qnn(spec, qnnLibs!!, listOf("encoder", "decoder", "joiner")))
                    if (model.modelType.isEmpty()) model.modelType = "nemo_transducer"
                } else {
                    model.transducer = OfflineTransducerModelConfig(path(spec, "encoder"), path(spec, "decoder"), path(spec, "joiner"))
                }
            }
            RuntimeKeys.SHERPA_OFFLINE_CTC -> {
                if (onNpu) throw SpecException("unit.npu")
                model.tokens = path(spec, "tokens")
                model.nemo = OfflineNemoEncDecCtcModelConfig(path(spec, "model"))
            }
            RuntimeKeys.SHERPA_OFFLINE_WHISPER -> {
                model.tokens = path(spec, "tokens")
                model.whisper = OfflineWhisperModelConfig(
                    language = language.orEmpty(),
                    task = "transcribe",
                    // sherpa's own default (-1 = its built-in padding), as in the asr-ocr host evaluation.
                    tailPaddings = -1,
                )
                if (onNpu) model.whisper.qnnConfig = qnn(spec, qnnLibs!!, listOf("encoder", "decoder"))
                else { model.whisper.encoder = path(spec, "encoder"); model.whisper.decoder = path(spec, "decoder") }
            }
            RuntimeKeys.SHERPA_OFFLINE_QWEN3_ASR -> {
                if (onNpu) throw SpecException("unit.npu")
                model.qwen3Asr = OfflineQwen3AsrModelConfig(
                    convFrontend = path(spec, "frontend"),
                    encoder = path(spec, "encoder"),
                    decoder = path(spec, "decoder"),
                    tokenizer = File(path(spec, "vocab")).parent ?: throw SpecException("layout.vocab"),
                    // the asr-ocr host evaluation's limits (sherpa's defaults cut long segments)
                    maxTotalLen = 1024,
                    maxNewTokens = 512,
                )
            }
            else -> throw SpecException("runtime")
        }
        return OfflineRecognizerConfig(featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80), modelConfig = model)
    }

    /** Endpointing off: the session decides what is final (D4: one text of record per dictation). */
    fun online(spec: LoadSpec, qnnLibs: QnnLibs?): OnlineRecognizerConfig {
        val provider = provider(spec.unit)
        val onNpu = spec.unit == ComputeUnit.NPU
        if (onNpu && qnnLibs == null) throw SpecException("unit.npu")
        val model = OnlineModelConfig(
            tokens = path(spec, "tokens"),
            numThreads = threads(spec),
            provider = provider,
            debug = false,
            modelType = spec.options[MODEL_TYPE] ?: "",
        )
        var feat = FeatureConfig(sampleRate = 16_000, featureDim = 80)
        when (spec.runtime) {
            RuntimeKeys.SHERPA_ONLINE_TRANSDUCER -> if (onNpu) {
                model.transducer = OnlineTransducerModelConfig(qnnConfig = qnn(spec, qnnLibs!!, listOf("encoder", "decoder", "joiner")))
                if (model.modelType.isEmpty()) model.modelType = "nemo_transducer"
            } else {
                model.transducer = OnlineTransducerModelConfig(path(spec, "encoder"), path(spec, "decoder"), path(spec, "joiner"))
            }
            RuntimeKeys.SHERPA_ONLINE_TONE -> {
                if (onNpu) throw SpecException("unit.npu")
                model.toneCtc = OnlineToneCtcModelConfig(path(spec, "model"))
                // T-one runs at 8 kHz: sherpa resamples the 16 kHz input to the feature rate.
                feat = FeatureConfig(sampleRate = 8_000, featureDim = 80)
            }
            else -> throw SpecException("runtime")
        }
        return OnlineRecognizerConfig(featConfig = feat, modelConfig = model, enableEndpoint = false)
    }

    /** Qwen3-ASR's prompt wants the English language name ("language Russian"); ISO 639-1 codes elsewhere. */
    fun qwen3Language(code: String): String = java.util.Locale.forLanguageTag(code).getDisplayLanguage(java.util.Locale.ENGLISH)
}
