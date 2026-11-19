package app.murmur.infer.asr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import app.murmur.core.audio.PcmFormat
import app.murmur.core.audio.VadModel
import app.murmur.core.catalog.RuntimeKeys
import app.murmur.core.engine.LoadSpec
import app.murmur.core.model.ComputeUnit
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineStream
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Silero VAD v5 through the ONNX Runtime Java API of the one ORT 1.32 in the APK (ADR-008). Input = the last 64
 * samples of the previous frame + the 512-sample frame ([1, 576]), state [2, 1, 128], sr = 16000: the framing of the
 * asr-ocr spike, whose probabilities the Segmenter parity fixture holds (the catalog's silero_vad_v5.onnx and the
 * spike's onnx-community graph give identical probabilities: `.cache/spikes/wp-06/scripts/vad_io.py`, max |d| 0.0).
 */
class SileroVadModel(val spec: LoadSpec) : AutoCloseable {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(1)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        session = env.createSession(SherpaConfigs.path(spec, "model"), opts)
        opts.close()
    }

    /** A per-session recurrent state over the shared ORT session. Engine thread only. */
    fun newState(): VadModel = State()

    override fun close() = session.close()

    private inner class State : VadModel {
        private val state = FloatArray(2 * 1 * 128)
        private val input = FloatArray(CONTEXT + FRAME)

        override fun probability(frame: FloatArray): Float {
            require(frame.size == FRAME) { "Silero v5 takes $FRAME-sample frames" }
            System.arraycopy(frame, 0, input, CONTEXT, FRAME)
            val x = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, (CONTEXT + FRAME).toLong()))
            val st = OnnxTensor.createTensor(env, FloatBuffer.wrap(state), longArrayOf(2, 1, 128))
            val sr = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(PcmFormat.SAMPLE_RATE.toLong())), longArrayOf())
            try {
                session.run(mapOf("input" to x, "state" to st, "sr" to sr)).use { out ->
                    val p = (out.get(0) as OnnxTensor).floatBuffer.get(0)
                    (out.get(1) as OnnxTensor).floatBuffer.get(state)
                    // next frame's context = the last 64 samples of this one
                    System.arraycopy(frame, FRAME - CONTEXT, input, 0, CONTEXT)
                    return p
                }
            } finally {
                x.close(); st.close(); sr.close()
            }
        }

        override fun reset() {
            state.fill(0f)
            input.fill(0f)
        }
    }

    companion object {
        const val FRAME = 512
        const val CONTEXT = 64
    }
}

/** A loaded sherpa-onnx recognizer of the :asr process. Engine thread only. */
sealed class SherpaModel(val spec: LoadSpec) : AutoCloseable {
    val unit: ComputeUnit get() = spec.unit
    val streaming: Boolean get() = this is Online

    class Offline(spec: LoadSpec, private var config: OfflineRecognizerConfig) : SherpaModel(spec) {
        private val recognizer = OfflineRecognizer(null, config)

        /**
         * Whisper takes its language in the recognizer config, which every session shares: each decode sets the
         * language of its own session first (null = detect). The engine thread serialises the decodes.
         */
        private val whisperLanguage = ConfigValue(config.modelConfig.whisper.language) { lang ->
            config = config.copy(modelConfig = config.modelConfig.copy(whisper = config.modelConfig.whisper.copy(language = lang)))
            recognizer.setConfig(config)
        }

        fun decoder(): SegmentDecoder = SegmentDecoder { samples, lang ->
            if (spec.runtime == RuntimeKeys.SHERPA_OFFLINE_WHISPER) whisperLanguage.use(lang.orEmpty())
            val stream = recognizer.createStream()
            try {
                if (spec.runtime == RuntimeKeys.SHERPA_OFFLINE_QWEN3_ASR && lang != null) {
                    stream.setOption("language", SherpaConfigs.qwen3Language(lang))
                }
                stream.acceptWaveform(samples, PcmFormat.SAMPLE_RATE)
                recognizer.decode(stream)
                val r = recognizer.getResult(stream)
                Decoded(r.text, normalizeLanguage(r.lang))
            } finally {
                stream.release()
            }
        }

        override fun close() = recognizer.release()
    }

    class Online(spec: LoadSpec, config: com.k2fsa.sherpa.onnx.OnlineRecognizerConfig) : SherpaModel(spec) {
        private val recognizer = OnlineRecognizer(null, config)

        fun stream(language: String?): StreamDecoder {
            val s = recognizer.createStream()
            if (language != null && SherpaConfigs.takesHint(spec.runtime)) s.setOption("language", language)
            return SherpaStream(recognizer, s)
        }

        override fun close() = recognizer.release()
    }

    companion object {
        /** Whisper reports "<|ru|>" or "ru"; anything else (empty, other models) is null. */
        fun normalizeLanguage(raw: String?): String? =
            raw?.removePrefix("<|")?.removeSuffix("|>")?.trim()?.lowercase()?.takeIf { it.matches(Regex("[a-z]{2,3}")) }
    }
}

/** A recognizer setting shared by all sessions: [apply] runs only when a decode needs a different value than the current one. */
class ConfigValue<T>(private var current: T, private val apply: (T) -> Unit) {
    fun use(value: T) {
        if (value == current) return
        apply(value)
        current = value
    }
}

/** sherpa OnlineStream with endpointing off; [finish] = 0.8 s tail padding + inputFinished (asr-ocr spike). */
private class SherpaStream(private val recognizer: OnlineRecognizer, private val stream: OnlineStream) : StreamDecoder {
    override fun accept(samples: FloatArray): String {
        stream.acceptWaveform(samples, PcmFormat.SAMPLE_RATE)
        while (recognizer.isReady(stream)) recognizer.decode(stream)
        return recognizer.getResult(stream).text
    }

    override fun finish(): String {
        stream.acceptWaveform(FloatArray(TAIL_PADDING), PcmFormat.SAMPLE_RATE)
        stream.inputFinished()
        while (recognizer.isReady(stream)) recognizer.decode(stream)
        return recognizer.getResult(stream).text
    }

    override fun release() = stream.release()

    companion object {
        const val TAIL_PADDING = PcmFormat.SAMPLE_RATE * 8 / 10
    }
}
