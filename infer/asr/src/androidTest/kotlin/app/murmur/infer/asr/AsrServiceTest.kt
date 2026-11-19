package app.murmur.infer.asr

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.murmur.core.benchmark.WordErrorRate
import app.murmur.core.engine.AsrEvent
import app.murmur.core.engine.AsrSessionOptions
import app.murmur.core.engine.LoadInfo
import app.murmur.core.engine.ProcessMemory
import app.murmur.core.engine.ProcessProbe
import app.murmur.core.engine.Transcript
import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.MurmurError
import app.murmur.infer.api.IAsrSession
import app.murmur.infer.api.InferApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.concurrent.thread

/**
 * WP-06 acceptance on the x86_64 emulator with real models on the CPU, through the real binder service in its own
 * :asr process (the client side is WP-05's; this test talks AIDL directly). Models: `tools/models/fetch
 * gigaam-v3-e2e-rnnt@onnx-int8 parakeet-tdt-0.6b-v3@onnx-int8 nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms
 * t-one@onnx silero-vad-v5@onnx`, copied by infer/asr/tools/push-test-models.sh.
 */
@RunWith(AndroidJUnit4::class)
class AsrServiceTest {
    private val vad = Models.spec("silero-vad-v5@onnx")

    private class Run(val transcript: Transcript, val committedBeforeFinish: Int, val partialsBeforeEof: Int,
                      val finishAfterEofMs: Long, val wallMs: Long, val events: Events)

    /**
     * Streams [clip] through one session in 20 ms chunks. [loadDuring] runs after the session started (the first
     * samples are already in the pipe), so the session must buffer until the model is ready.
     */
    private fun stream(c: AsrConnection, clip: Clip, realTime: Boolean, options: AsrSessionOptions = AsrSessionOptions(),
                       loadDuring: (() -> Unit)? = null): Run {
        val events = Events()
        val (read, write) = ParcelFileDescriptor.createPipe()
        val session: IAsrSession = c.service.startSession(InferApi.json.encodeToString(AsrSessionOptions.serializer(), options), read, events)
        read.close()
        val t0 = SystemClock.elapsedRealtime()
        var eofAt = 0L
        val writer = thread(name = "pcm-writer") { eofAt = writePcm(write, clip.samples, realTime) }
        loadDuring?.invoke()
        writer.join()
        val partials = events.count<AsrEvent.Partial>(eofAt)
        val result = Result()
        val committed = events.count<AsrEvent.Committed>()
        session.finish(result)
        assertTrue("finish answered", result.await(120))
        val t = InferApi.json.decodeFromString(Transcript.serializer(), result.requireOk())
        return Run(t, committed, partials, result.atMs - eofAt, result.atMs - t0, events)
    }

    private fun checkRealTime(model: String, clip: Clip, run: Run) {
        val wer = WordErrorRate.wer(clip.ref, run.transcript.text)
        report("$model/${clip.lang}.wer", "%.4f".format(wer))
        report("$model/${clip.lang}.committedBeforeFinish", run.committedBeforeFinish)
        report("$model/${clip.lang}.finishAfterEofMs", run.finishAfterEofMs)
        report("$model/${clip.lang}.tailDecodeMs", run.transcript.tailDecodeMs)
        report("$model/${clip.lang}.segments", run.transcript.segments)
        assertTrue("$model ${clip.lang}: >= 2 Committed before finish (${run.committedBeforeFinish})", run.committedBeforeFinish >= 2)
        assertTrue("$model ${clip.lang}: WER ${"%.3f".format(wer)} <= 0.15", wer <= 0.15)
        assertTrue("$model ${clip.lang}: finish ${run.finishAfterEofMs} ms after EOF <= 3000", run.finishAfterEofMs <= 3_000)
        assertEquals(ComputeUnit.CPU, run.transcript.unit)
        // every sample written reached the engine (16 kHz: audioMs = samples x 1000 / 16000)
        assertEquals(clip.samples.size * 1000L / 16_000, run.transcript.audioMs)
        assertTrue(run.transcript.speechMs > 10_000)
    }

    /** Fast pass of the same clip with the model warm: processing time / audio time. */
    private fun rtf(c: AsrConnection, model: String, clip: Clip) {
        val run = stream(c, clip, realTime = false)
        report("$model/${clip.lang}.rtf", "%.3f".format(run.wallMs / 1000.0 / clip.seconds))
    }

    private fun peakRss(c: AsrConnection, model: String) {
        val m = InferApi.json.decodeFromString(ProcessMemory.serializer(), c.service.memory())
        report("$model.peakRssMb", m.vmHwmKb / 1024)
        report("$model.pssMb", m.pssKb / 1024)
        report("$model.systemAvailMb", m.systemAvailMb)
    }

    @Test
    fun parakeetCpuTranscribesRussianAndEnglishWhileTheUserSpeaks() {
        val asr = Models.spec("parakeet-tdt-0.6b-v3@onnx-int8")
        Models.requireInstalled(asr); Models.requireInstalled(vad)
        val ru = Clip.load("ru_pauses.wav")
        val en = Clip.load("en_pauses.wav")
        AsrConnection().use { c ->
            var load: Result? = null
            // cold: the session starts first, the load runs while audio already flows
            val first = stream(c, ru, realTime = true) { load = c.load(asr, vad) }
            val info = InferApi.json.decodeFromString(LoadInfo.serializer(), load!!.requireOk())
            report("parakeet.loadMs", info.loadMs)
            assertEquals(ComputeUnit.CPU, info.unit)
            checkRealTime("parakeet", ru, first)
            checkRealTime("parakeet", en, stream(c, en, realTime = true))
            rtf(c, "parakeet", ru)
            rtf(c, "parakeet", en)
            peakRss(c, "parakeet")
        }
    }

    @Test
    fun gigaamCpuTranscribesRussianWhileTheUserSpeaks() {
        val asr = Models.spec("gigaam-v3-e2e-rnnt@onnx-int8")
        Models.requireInstalled(asr); Models.requireInstalled(vad)
        val ru = Clip.load("ru_pauses.wav")
        AsrConnection().use { c ->
            var load: Result? = null
            val run = stream(c, ru, realTime = true) { load = c.load(asr, vad) }
            report("gigaam.loadMs", InferApi.json.decodeFromString(LoadInfo.serializer(), load!!.requireOk()).loadMs)
            checkRealTime("gigaam", ru, run)
            rtf(c, "gigaam", ru)
            peakRss(c, "gigaam")
        }
    }

    @Test
    fun audioWrittenBeforeTheLoadIsNotLost() {
        val asr = Models.spec("gigaam-v3-e2e-rnnt@onnx-int8")
        Models.requireInstalled(asr); Models.requireInstalled(vad)
        val ru = Clip.load("ru_pauses.wav")
        AsrConnection().use { c ->
            // the whole clip is in the pipe before loadAsr is even called
            val run = stream(c, ru, realTime = false) {
                SystemClock.sleep(3_000)
                c.load(asr, vad).requireOk()
            }
            report("buffering.samplesIn", ru.samples.size)
            report("buffering.audioMs", run.transcript.audioMs)
            assertEquals(ru.samples.size * 1000L / 16_000, run.transcript.audioMs)
            assertTrue(WordErrorRate.wer(ru.ref, run.transcript.text) <= 0.15)
        }
    }

    @Test
    fun nemotronStreamsPartialsWhileAudioFlows() {
        val asr = Models.spec("nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms")
        Models.requireInstalled(asr)
        val en = Clip.load("en_pauses.wav")
        val first15s = Clip(en.file, en.lang, en.ref, en.samples.copyOf(15 * 16_000))
        Models.requireInstalled(vad)
        AsrConnection().use { c ->
            c.load(asr, vad).requireOk()
            val run = stream(c, first15s, realTime = true, AsrSessionOptions(language = "en", emitPartials = true))
            report("nemotron.partialsBeforeEof", run.partialsBeforeEof)
            report("nemotron.finishAfterEofMs", run.finishAfterEofMs)
            report("nemotron.speechMs", run.transcript.speechMs)
            // the VAD runs beside the streaming recognizer: real speech time, not 0 (ARCHITECTURE 5.2 row 26)
            assertTrue("speechMs ${run.transcript.speechMs}", run.transcript.speechMs in 5_000L..run.transcript.audioMs)
            assertTrue(run.events.count<AsrEvent.Speech>() >= 2)
            assertTrue("Partial events while audio flows (${run.partialsBeforeEof})", run.partialsBeforeEof >= 3)
            assertTrue(run.transcript.text.isNotBlank())
            assertEquals(ComputeUnit.CPU, run.transcript.unit)
            // the final text continues the last partial (native streaming: no re-decode)
            val lastPartial = (run.events.list.last { it.second is AsrEvent.Partial }.second as AsrEvent.Partial).text
            assertTrue(run.transcript.text.startsWith(lastPartial.take(20)))
            peakRss(c, "nemotron")
        }
    }

    @Test
    fun tOneReportsTheSpeechTimeOfItsStreamingSession() {
        val asr = Models.spec("t-one@onnx")
        Models.requireInstalled(asr); Models.requireInstalled(vad)
        val ru = Clip.load("ru_pauses.wav")
        AsrConnection().use { c ->
            c.load(asr, vad).requireOk()
            val run = stream(c, ru, realTime = false, AsrSessionOptions(language = "ru"))
            val wer = WordErrorRate.wer(ru.ref, run.transcript.text)
            report("tone.wer", "%.4f".format(wer))
            report("tone.speechMs", run.transcript.speechMs)
            report("tone.audioMs", run.transcript.audioMs)
            assertEquals(ru.samples.size * 1000L / 16_000, run.transcript.audioMs)
            assertTrue("speechMs ${run.transcript.speechMs}", run.transcript.speechMs in 10_000L..run.transcript.audioMs)
            assertTrue("WER ${"%.3f".format(wer)} <= 0.15", wer <= 0.15)
            assertEquals(ComputeUnit.CPU, run.transcript.unit)
        }
    }

    /**
     * A session started after a failed load does not inherit that error; a finish() after the session was aborted
     * (its model replaced before the stop tap) is answered at once with the abort reason.
     */
    @Test
    fun loadErrorsAndAbortsReachOnlyTheSessionsTheyConcern() {
        val asr = Models.spec("gigaam-v3-e2e-rnnt@onnx-int8")
        val other = Models.spec("nemotron-3.5-asr-streaming-0.6b@onnx-int8-560ms")
        Models.requireInstalled(asr); Models.requireInstalled(other); Models.requireInstalled(vad)
        val ru = Clip.load("ru_pauses.wav")
        AsrConnection().use { c ->
            val broken = asr.copy(dir = asr.dir + ".absent")
            assertTrue(c.load(broken, vad).error is MurmurError.EngineLoadFailed)

            val events = Events()
            val (read, write) = ParcelFileDescriptor.createPipe()
            val session = c.service.startSession(InferApi.json.encodeToString(AsrSessionOptions.serializer(), AsrSessionOptions()), read, events)
            read.close()
            writePcm(write, ru.samples.copyOf(5 * 16_000), realTime = false)
            SystemClock.sleep(500)
            assertEquals("no Failure from the earlier load", 0, events.count<AsrEvent.Failure>())

            c.load(asr, vad).requireOk()                    // attaches to the waiting session
            c.load(other, null).requireOk()                 // replaces the model before the stop tap
            val result = Result()
            val t0 = SystemClock.elapsedRealtime()
            session.finish(result)
            assertTrue("finish answered", result.await(5))
            report("replaced.finishMs", result.atMs - t0)
            assertEquals(MurmurError.AsrFailed("model_replaced"), result.error)
            assertTrue(result.atMs - t0 <= 1_000)
            assertEquals(1, events.count<AsrEvent.Failure>())
        }
    }

    @Test
    fun npuOnTheEmulatorIsUnitUnavailableWithinTwoSecondsAndTheProcessLives() {
        val asr = Models.spec("parakeet-tdt-0.6b-v3@qnn-sm8750-20s")
        assertEquals(ComputeUnit.NPU, asr.unit)
        AsrConnection().use { c ->
            val pid = asrPid()
            val t0 = SystemClock.elapsedRealtime()
            val r = c.load(asr, vad, timeoutSec = 10)
            val ms = r.atMs - t0
            report("npu.unavailableMs", ms)
            assertEquals(MurmurError.UnitUnavailable(ComputeUnit.NPU), r.error)
            assertTrue("answered in $ms ms", ms <= 2_000)
            assertEquals(InferApi.API_VERSION, c.service.apiVersion())
            assertEquals(pid, asrPid())
        }
    }

    @Test
    fun probeReportsCpuOnlyAndTheNativeBuild() {
        AsrConnection().use { c ->
            val p = InferApi.json.decodeFromString(ProcessProbe.serializer(), c.service.probe())
            report("probe.units", p.units)
            report("probe.facts", p.facts)
            assertEquals(setOf(ComputeUnit.CPU), p.units)
            assertTrue(p.runtimes.containsAll(SherpaConfigs.SHERPA + "ort/silero-vad-v5" + "ort/ppocr-det" + "ort/ppocr-rec"))
            assertEquals("sherpa-onnx 2.0.1 af2d8d81 + ORT 1.32.0", p.facts[ProcessProbe.FACT_NATIVE_BUILD])
            assertEquals("no-libs", p.facts["qnn.stage"])
        }
    }

    @Test
    fun unloadAllAndUnbindEndTheAsrProcessWithinTwoSeconds() {
        val c = AsrConnection()
        val pid = asrPid()
        assertNotEquals("", pid)
        c.service.unloadAll()
        val t0 = SystemClock.elapsedRealtime()
        c.close()
        var gone = false
        while (SystemClock.elapsedRealtime() - t0 < 2_000) {
            if (asrPid().isEmpty()) { gone = true; break }
            SystemClock.sleep(50)
        }
        report("exit.ms", SystemClock.elapsedRealtime() - t0)
        assertTrue("pidof $asrProcessName empty within 2 s", gone)
    }
}
