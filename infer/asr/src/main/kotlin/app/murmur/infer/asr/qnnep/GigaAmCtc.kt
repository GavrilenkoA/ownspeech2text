package app.murmur.infer.asr.qnnep

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import app.murmur.core.engine.LoadSpec
import app.murmur.core.model.ComputeUnit
import app.murmur.infer.asr.Decoded
import app.murmur.infer.asr.SegmentDecoder
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * GigaAM's CTC vocabulary (v3_e2e_ctc_vocab.txt: "<token> <index>" per line, 257 entries): index 0 "<unk>",
 * "▁" marks a word start, the last index is the CTC blank ("<blk>").
 */
class CtcVocabulary(val tokens: List<String>) {
    val blank: Int get() = tokens.size - 1

    init {
        require(tokens.size >= 2) { "vocabulary too small" }
    }

    companion object {
        fun parse(text: String): CtcVocabulary {
            val out = ArrayList<String>()
            for (line in text.lineSequence()) {
                if (line.isEmpty()) continue
                val cut = line.lastIndexOf(' ')
                require(cut > 0) { "vocabulary line ${out.size} malformed" }
                require(line.substring(cut + 1).toInt() == out.size) { "vocabulary index ${out.size} out of order" }
                out += line.substring(0, cut)
            }
            return CtcVocabulary(out)
        }
    }
}

/** Greedy CTC over log-probabilities [steps, classes] (row-major): argmax per step, repeats collapsed, blanks dropped. */
object CtcGreedy {
    fun decode(logProbs: FloatArray, steps: Int, classes: Int, vocab: CtcVocabulary): String {
        require(classes == vocab.tokens.size) { "model has $classes classes, vocabulary ${vocab.tokens.size}" }
        require(logProbs.size >= steps * classes) { "log-probabilities shorter than $steps steps" }
        val sb = StringBuilder()
        var prev = -1
        for (t in 0 until steps) {
            var best = 0
            var bestV = logProbs[t * classes]
            for (k in 1 until classes) {
                val v = logProbs[t * classes + k]
                if (v > bestV) { bestV = v; best = k }
            }
            if (best != prev && best != vocab.blank) sb.append(vocab.tokens[best])
            prev = best
        }
        return sb.toString().replace(WORD_START, ' ').trim().replace(SPACES, " ")
    }

    private const val WORD_START = '▁'
    private val SPACES = Regex(" {2,}")
}

/** A model call on the QNN EP failed (an HTP execute error); the decode of that segment fails, not the process. */
class QnnRunException(cause: Throwable) : RuntimeException(cause)

/**
 * GigaAM v3 e2e CTC on the HTP (runtime `ort-qnn/gigaam-ctc`): one EPContext graph with a fixed 20 s window
 * (features [1, 64, 2000] + feature_lengths [1] -> log_probs [1, 500, 257]). A batch model like sherpa's offline
 * ones: the :asr engine feeds it the VAD pause segments of WP-06's Segmenter (window 20 s, hard cut at 19.5 s), and
 * each segment is one call: [GigaAmFrontEnd] features padded with silence to 2000 frames, the real frame count as
 * feature_lengths, greedy CTC over the (frames - 1) / 4 + 1 valid steps. A segment longer than the window (none when
 * the Segmenter's hard limit holds) is decoded window by window and joined. Layout roles: "model" (the EPContext
 * .onnx, its _qnn.bin next to it), "vocab". Engine thread only.
 */
class GigaAmCtcModel private constructor(
    val spec: LoadSpec,
    private val session: OrtSession,
    private val vocab: CtcVocabulary,
) : AutoCloseable {
    val unit: ComputeUnit get() = spec.unit
    private val featuresName: String
    private val lengthsName: String
    private val frames: Int
    private val classes: Int

    init {
        val inputs = session.inputInfo
        featuresName = inputs.keys.firstOrNull { it == FEATURES } ?: error("no '$FEATURES' input")
        lengthsName = inputs.keys.firstOrNull { it == LENGTHS } ?: error("no '$LENGTHS' input")
        val shape = (inputs.getValue(featuresName).info as TensorInfo).shape
        require(shape.size == 3 && shape[0] == 1L && shape[1] == GigaAmFrontEnd.N_MELS.toLong() && shape[2] > 0) { "features shape" }
        frames = shape[2].toInt()
        classes = (session.outputInfo.values.first().info as TensorInfo).shape.last().toInt()
        require(classes == vocab.tokens.size) { "model has $classes classes, vocabulary ${vocab.tokens.size}" }
    }

    /** Samples per call (20.01 s for 2000 frames). */
    val windowSamples: Int get() = GigaAmFrontEnd.samplesFor(frames)

    fun decoder(): SegmentDecoder = SegmentDecoder { samples, _ -> Decoded(transcribe(samples), null) }

    /** Text of [samples] (16 kHz, [-1, 1]); "" for less than one frame. */
    fun transcribe(samples: FloatArray): String {
        val parts = ArrayList<String>()
        var from = 0
        while (from < samples.size) {
            val to = minOf(samples.size, from + windowSamples)
            val n = GigaAmFrontEnd.frames(to - from)
            if (n == 0) break
            val text = window(GigaAmFrontEnd.features(samples, from, to, frames), n)
            if (text.isNotEmpty()) parts += text
            from += GigaAmFrontEnd.samplesFor(n) - (GigaAmFrontEnd.WIN - GigaAmFrontEnd.HOP)
        }
        return parts.joinToString(" ")
    }

    /** One call of the graph; an ORT error is unchecked, so the session fails with AsrFailed("decode:QnnRunException"). */
    private fun window(features: FloatArray, n: Int): String {
        val logp = try {
            val env = OrtEnvironment.getEnvironment()
            OnnxTensor.createTensor(env, FloatBuffer.wrap(features), longArrayOf(1, GigaAmFrontEnd.N_MELS.toLong(), frames.toLong())).use { f ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(n.toLong())), longArrayOf(1)).use { l ->
                    session.run(mapOf(featuresName to f, lengthsName to l)).use { r ->
                        val out = (r.get(0) as OnnxTensor).floatBuffer
                        FloatArray(out.remaining()).also { out.get(it) }
                    }
                }
            }
        } catch (e: OrtException) {
            throw QnnRunException(e)
        }
        return CtcGreedy.decode(logp, minOf(validSteps(n), logp.size / classes), classes, vocab)
    }

    override fun close() = session.close()

    companion object {
        const val ROLE_MODEL = "model"
        const val ROLE_VOCAB = "vocab"
        private const val FEATURES = "features"
        private const val LENGTHS = "feature_lengths"
        private const val SUBSAMPLING = 4

        /** Output steps that carry the audio of [frames] input frames (conv1d subsampling x4). */
        fun validSteps(frames: Int): Int = if (frames <= 0) 0 else (frames - 1) / SUBSAMPLING + 1

        /** Opens the context of [spec] on [ep]; the session is closed again if the graph does not fit the vocabulary. */
        fun open(spec: LoadSpec, ep: QnnEp): GigaAmCtcModel {
            ep.requireAvailable()
            val model = file(spec, ROLE_MODEL)
            val vocab = CtcVocabulary.parse(file(spec, ROLE_VOCAB).readText())
            val session = ep.open(model)
            try {
                return GigaAmCtcModel(spec, session, vocab)
            } catch (t: Throwable) {
                session.close()
                throw t
            }
        }

        /** The same model over any session of the graph (the host tests run the fixed fp32 graph on the CPU EP). */
        internal fun withSession(spec: LoadSpec, session: OrtSession): GigaAmCtcModel =
            GigaAmCtcModel(spec, session, CtcVocabulary.parse(file(spec, ROLE_VOCAB).readText()))

        private fun file(spec: LoadSpec, role: String): File {
            val rel = requireNotNull(spec.layout[role]) { "layout role '$role' missing" }
            return File(spec.dir, rel).also { require(it.isFile) { "artifact file for role '$role' missing" } }
        }
    }
}
