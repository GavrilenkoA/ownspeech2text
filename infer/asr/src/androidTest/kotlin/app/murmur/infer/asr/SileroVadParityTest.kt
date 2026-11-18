package app.murmur.infer.asr

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.murmur.core.audio.PauseSegmenter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Silero VAD v5 through the ORT 1.32 Java API on Android gives the probabilities the asr-ocr spike computed with
 * Python ORT on the host (the Segmenter parity fixture, same audio: assets/asr/ru_pauses.wav = the spike's clip cut
 * at the stop tap), and the segmenter cuts the same segments from them.
 */
@RunWith(AndroidJUnit4::class)
class SileroVadParityTest {
    @Test
    fun sameProbabilitiesAndSegmentsAsTheHost() {
        val vadSpec = Models.spec("silero-vad-v5@onnx")
        Models.requireInstalled(vadSpec)
        val fixture = Json.parseToJsonElement(
            instrumentation.context.assets.open("segmenter/seg_parity.json").bufferedReader().readText(),
        ).jsonObject
        val host = fixture["clips"]!!.jsonArray.map { it.jsonObject }.first { it["id"]!!.jsonPrimitive.content == "long_ru_pauses" }
        val hostProbs = host["probs"]!!.jsonArray.map { it.jsonPrimitive.float }
        val clip = Clip.load("ru_pauses.wav")
        assertEquals(host["samples"]!!.jsonPrimitive.content.toInt(), clip.samples.size)

        val probs = ArrayList<Float>()
        SileroVadModel(vadSpec).use { model ->
            val vad = model.newState()
            val frame = FloatArray(512)
            var i = 0
            while (i + 512 <= clip.samples.size) {
                for (k in 0 until 512) frame[k] = clip.samples[i + k] / 32768f
                probs += vad.probability(frame)
                i += 512
            }
        }
        assertEquals(hostProbs.size, probs.size)
        val maxDiff = probs.indices.maxOf { abs(probs[it] - hostProbs[it]) }
        report("vad.maxAbsDiff", maxDiff)
        assertTrue("max |p_android - p_host| = $maxDiff", maxDiff < 1e-3f)

        fun segments(p: List<Float>) = PauseSegmenter().let { s -> p.mapIndexedNotNull { k, x -> s.onFrame(x, (k + 1) * 512L) } }
        val onDevice = segments(probs)
        assertEquals(segments(hostProbs), onDevice)
        assertEquals(5, onDevice.size)
    }
}
