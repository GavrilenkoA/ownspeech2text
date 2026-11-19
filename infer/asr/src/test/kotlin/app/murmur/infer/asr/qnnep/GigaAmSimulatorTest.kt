package app.murmur.infer.asr.qnnep

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * GigaAM v3 e2e CTC on the HTP (WP-21 acceptance 1, GigaAM part). The WER of the 20 s context on the 12 FLEURS ru
 * clips is measured by native/qnn-contexts/gigaam_eval.py on the x86_64 HTP simulator (~2.5 min per call there) and
 * written to <contexts>/gigaam_eval.json; this suite checks that number (within 1 pp of ORT CPU on the same padded
 * graph) and that the app's Kotlin path ([GigaAmCtcModel]: Kotlin front end, padding, QNN EP session, greedy CTC)
 * produces the same text as that Python run: on the simulator for two clips, on the CPU EP (the fixed fp32 graph the
 * context was compiled from) for all twelve.
 */
class GigaAmSimulatorTest {
    private val eval: JsonObject by lazy { Json.parseToJsonElement(File(Simulator.contexts, "gigaam_eval.json").readText()).jsonObject }

    /** The Python run's text, with runs of spaces as one (CtcGreedy's rule; gigaam_eval.py applies it too since 2026-11-20). */
    private fun pythonText(id: String, row: String): String = eval.getValue("clips").jsonArray.map { it.jsonObject }
        .single { it.getValue("id").jsonPrimitive.content == id }.getValue(row).jsonObject.getValue("text").jsonPrimitive.content
        .replace(Regex(" {2,}"), " ")

    @Test
    fun simulatorWerIsWithinOnePointOfCpu() {
        val s = eval.getValue("summary").jsonObject
        fun wer(row: String) = s.getValue(row).jsonObject.getValue("wer_pct").jsonPrimitive.double
        println(String.format(Locale.ROOT, "sim gigaam WER qnn %.2f %% vs cpu (same padded graph) %.2f %%, cpu unpadded %.2f %%, words %s, qnn run %s s",
            wer("qnn"), wer("cpu_padded"), wer("cpu_exact"), s.getValue("qnn").jsonObject.getValue("words"), s.getValue("qnn").jsonObject.getValue("run_s")))
        assertEquals(12, eval.getValue("clips").jsonArray.size)
        assertTrue("QNN ${wer("qnn")} vs CPU ${wer("cpu_padded")}", abs(wer("qnn") - wer("cpu_padded")) <= 1.0)
    }

    @Test
    fun theKotlinPathOnTheSimulatorGivesThePythonText() {
        GigaAmCtcModel.open(Simulator.spec(ARTIFACT), Simulator.ep).use { m ->
            for (id in listOf("ru_10", "ru_01")) {
                val clip = Fixtures.clips.single { it.id == id }
                val t = System.currentTimeMillis()
                val text = m.transcribe(Fixtures.readWav(clip.wav))
                println("sim gigaam $id kotlin qnn ${System.currentTimeMillis() - t} ms equal=${text == pythonText(id, "qnn")}")
                assertEquals(id, pythonText(id, "qnn"), text)
            }
        }
    }

    @Test
    fun theKotlinPathOnTheCpuGraphGivesThePythonTextOnAllClips() {
        val graph = File(Simulator.contexts, "_work/gigaam_ctc_20s.onnx")
        val session = OrtEnvironment.getEnvironment().createSession(graph.path, OrtSession.SessionOptions())
        GigaAmCtcModel.withSession(Simulator.spec(ARTIFACT), session).use { m ->
            for (clip in Fixtures.clips) assertEquals(clip.id, pythonText(clip.id, "cpu_padded"), m.transcribe(Fixtures.readWav(clip.wav)))
        }
    }

    private companion object {
        const val ARTIFACT = "gigaam-v3-e2e-ctc@qnn-sm8750-20s"
    }
}
