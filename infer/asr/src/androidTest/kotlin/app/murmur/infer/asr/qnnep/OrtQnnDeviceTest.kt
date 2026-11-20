package app.murmur.infer.asr.qnnep

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.murmur.core.benchmark.WordErrorRate
import app.murmur.core.catalog.RuntimeKeys
import app.murmur.core.engine.AsrSessionOptions
import app.murmur.core.engine.LoadInfo
import app.murmur.core.engine.LoadSpec
import app.murmur.core.engine.ProcessProbe
import app.murmur.core.engine.Transcript
import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.MurmurError
import app.murmur.core.ocr.OcrConfig
import app.murmur.core.ocr.OcrGolden
import app.murmur.core.ocr.OcrModels
import app.murmur.core.ocr.OcrPipeline
import app.murmur.core.ocr.OcrTolerance
import app.murmur.core.ocr.ScreenImage
import app.murmur.infer.api.InferApi
import app.murmur.infer.asr.AsrConnection
import app.murmur.infer.asr.Events
import app.murmur.infer.asr.Models
import app.murmur.infer.asr.Result
import app.murmur.infer.asr.context
import app.murmur.infer.asr.instrumentation
import app.murmur.infer.asr.report
import app.murmur.infer.asr.writePcm
import app.murmur.infer.asr.ocr.OrtOcrModels
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * WP-21 on Android. On the x86_64 emulator (and in every build without -Pmurmur.qnn=true): the :asr probe reports no
 * ort-qnn runtime and no NPU, and an ort-qnn artifact is UnitUnavailable(NPU) for both OCR and GigaAM, so the CPU
 * path stays the only one (WP-07's and WP-06's suites cover it).
 *
 * On the phone, [deviceSmoke] is DEVICE_CHECKLIST DC4's measurement, started by tools/device/ort_qnn_smoke.sh (which
 * passes `-e ortQnnSmoke 1` and pushes the CPU and NPU artifacts into filesDir/models, the 26 ml/ocr golden pages into
 * filesDir/ortqnn/golden and the FLEURS clips into filesDir/ortqnn/fleurs-ru): OCR det/rec QNN vs CPU per call (ms,
 * binary-map / argmax agreement) and per page (ms, README tolerance), then each stage alone on the HTP, GigaAM v3 CTC NPU vs the CPU artifact through AsrService (processing ms, WER). One JSON object
 * per row as `ortqnn` status lines; without that argument it is skipped (it needs the phone's HTP).
 */
@RunWith(AndroidJUnit4::class)
class OrtQnnDeviceTest {
    private val args = InstrumentationRegistry.getArguments()

    private fun probe(): ProcessProbe = AsrConnection().use { c -> InferApi.json.decodeFromString(ProcessProbe.serializer(), c.service.probe()) }

    @Test
    fun theProbeReportsTheOrtQnnRuntimesOnlyWhenTheEpRegistered() {
        val p = probe()
        report("ortqnn.probe.units", p.units)
        report("ortqnn.probe.qnnep", "${p.facts["qnnep.stage"]} ${p.facts["qnnep.version"] ?: ""}")
        val stage = p.facts["qnnep.stage"]
        if (stage == "ok" && ComputeUnit.NPU in p.units) {
            assertTrue(p.runtimes.containsAll(RuntimeKeys.qnnEp))
        } else {
            assertTrue("no ort-qnn runtime without the EP: ${p.runtimes}", p.runtimes.none { it in RuntimeKeys.qnnEp })
        }
        if (android.os.Build.SUPPORTED_ABIS.first() == "x86_64") {
            // the emulator: the plugin is an arm64-only library, never packaged for x86_64
            assertEquals("no-libs", stage)
            assertEquals(setOf(ComputeUnit.CPU), p.units)
        }
    }

    @Test
    fun ortQnnArtifactsAreUnitUnavailableWithoutTheEp() {
        val p = probe()
        assumeFalse("the QNN EP is registered here: deviceSmoke measures it", p.facts["qnnep.stage"] == "ok")
        AsrConnection().use { c ->
            val det = npuSpec("pp-ocrv6-tiny-det@qnn-sm8750")
            val rec = npuSpec("pp-ocrv5-cyrillic-mobile-rec@qnn-sm8750")
            val ocr = Result()
            c.service.loadOcr(InferApi.encode(LoadSpec.serializer(), det), InferApi.encode(LoadSpec.serializer(), rec), ocr)
            assertTrue(ocr.await(30))
            assertEquals(MurmurError.UnitUnavailable(ComputeUnit.NPU), ocr.error)
            // and on the CPU unit an ort-qnn artifact is refused as such (it holds no CPU graph)
            val onCpu = Result()
            c.service.loadOcr(InferApi.encode(LoadSpec.serializer(), det.copy(unit = ComputeUnit.CPU)), InferApi.encode(LoadSpec.serializer(), rec), onCpu)
            assertTrue(onCpu.await(30))
            assertEquals(MurmurError.UnitUnavailable(ComputeUnit.CPU), onCpu.error)

            val asr = c.load(npuSpec("gigaam-v3-e2e-ctc@qnn-sm8750-20s"), Models.spec("silero-vad-v5@onnx"), timeoutSec = 30)
            assertEquals(MurmurError.UnitUnavailable(ComputeUnit.NPU), asr.error)
        }
    }

    @Test
    fun deviceSmoke() {
        assumeTrue("started by tools/device/ort_qnn_smoke.sh on the phone", args.getString("ortQnnSmoke") == "1")
        val p = probe()
        row("probe", "units" to p.units.joinToString(","), "qnnep" to (p.facts["qnnep.stage"] ?: ""),
            "qnnep_version" to (p.facts["qnnep.version"] ?: ""), "htp" to (p.facts["htp.arch"] ?: ""), "qnn_build" to (p.facts["qnn.build"] ?: ""))
        assertEquals("QNN EP registered", "ok", p.facts["qnnep.stage"])
        ocrSmoke()
        gigaamSmoke()
    }

    // ------------------------------------------------------------------ OCR, in this process (per-call outputs)

    private class Timed(private val m: OcrModels) : OcrModels by m {
        var detMs = 0.0; var recMs = 0.0; var detCalls = 0; var recCalls = 0
        override fun detect(input: FloatArray, h: Int, w: Int): FloatArray {
            val t = System.nanoTime(); val r = m.detect(input, h, w); detMs += (System.nanoTime() - t) / 1e6; detCalls++; return r
        }
        override fun recognize(input: FloatArray, n: Int, bucketWidth: Int): FloatArray {
            val t = System.nanoTime(); val r = m.recognize(input, n, bucketWidth); recMs += (System.nanoTime() - t) / 1e6; recCalls++; return r
        }
        fun reset() { detMs = 0.0; recMs = 0.0; detCalls = 0; recCalls = 0 }
    }

    /** The QNN output goes on; agreement with the CPU output per call is counted. */
    private class Agreement(private val qnn: OcrModels, private val cpu: OcrModels) : OcrModels by qnn {
        var px = 0L; var pxDiffer = 0L; var steps = 0L; var stepsDiffer = 0L
        override fun detect(input: FloatArray, h: Int, w: Int): FloatArray {
            val q = qnn.detect(input, h, w); val c = cpu.detect(input, h, w)
            for (i in q.indices) { px++; if ((q[i] > 0.2f) != (c[i] > 0.2f)) pxDiffer++ }
            return q
        }
        override fun recognize(input: FloatArray, n: Int, bucketWidth: Int): FloatArray {
            val q = qnn.recognize(input, n, bucketWidth); val c = cpu.recognize(input, n, bucketWidth)
            val k = qnn.classes
            for (s in 0 until q.size / k) { steps++; if (argmax(q, s * k, k) != argmax(c, s * k, k)) stepsDiffer++ }
            return q
        }
        private fun argmax(a: FloatArray, from: Int, n: Int): Int { var b = 0; for (i in 1 until n) if (a[from + i] > a[from + b]) b = i; return b }
    }

    private fun ocrSmoke() {
        val config = OcrConfig()
        val libDir = context.applicationInfo.nativeLibraryDir
        val pages = goldens()
        val t0 = SystemClock.elapsedRealtime()
        val qnn = OrtOcrModels(Models.spec(QNN_DET), Models.spec(QNN_REC), config) { QnnEpAndroid.ep(libDir) }
        val qnnLoadMs = SystemClock.elapsedRealtime() - t0
        val pagesQ = ArrayList<OcrTolerance.Page>(); val pagesC = ArrayList<OcrTolerance.Page>()
        var binmapAll = ""; var argmaxAll = ""
        try {
            val t1 = SystemClock.elapsedRealtime()
            val cpu = OrtOcrModels(Models.spec(CPU_DET), Models.spec(CPU_REC), config)
            row("ocr-load", "qnn_ms" to qnnLoadMs, "cpu_ms" to SystemClock.elapsedRealtime() - t1, "pages" to pages.size, "golden" to goldenSource)
            try {
                val tq = Timed(qnn); val tc = Timed(cpu)
                val pq = OcrPipeline(config, tq); val pc = OcrPipeline(config, tc)
                val agree = Agreement(qnn, cpu)
                val pa = OcrPipeline(config, agree)
                for (page in pages) {
                    val (g, image) = page.load()
                    pq.run(image); pc.run(image) // warm-up
                    tq.reset(); tc.reset()
                    val cpuCalls0 = qnn.detectorCpuCalls
                    val q0 = SystemClock.elapsedRealtime(); val outQ = pq.run(image); val qMs = SystemClock.elapsedRealtime() - q0
                    // a window shape without a context runs the detector on the CPU: never count that time as the HTP's
                    val detUnit = if (qnn.detectorCpuCalls > cpuCalls0) "cpu" else "npu"
                    val c0 = SystemClock.elapsedRealtime(); val outC = pc.run(image); val cMs = SystemClock.elapsedRealtime() - c0
                    val before = agree.pxDiffer to agree.stepsDiffer
                    val px0 = agree.px; val st0 = agree.steps
                    pa.run(image)
                    val pageQ = OcrTolerance.compare(g.name, g.ocr.lines, outQ.lines).also { pagesQ += it }
                    pagesC += OcrTolerance.compare(g.name, g.ocr.lines, outC.lines)
                    row("ocr-page", "page" to g.name, "det_in" to "${outQ.stats?.detInput?.joinToString("x")}",
                        "qnn_ms" to qMs, "cpu_ms" to cMs,
                        "qnn_det_ms" to "%.1f".format(tq.detMs), "cpu_det_ms" to "%.1f".format(tc.detMs),
                        "qnn_rec_ms_per_line" to "%.1f".format(tq.recMs / maxOf(1, tq.recCalls)), "cpu_rec_ms_per_line" to "%.1f".format(tc.recMs / maxOf(1, tc.recCalls)),
                        "lines" to tq.recCalls, "det_unit" to detUnit,
                        "binmap_agree" to "%.6f".format(1.0 - (agree.pxDiffer - before.first).toDouble() / maxOf(1, agree.px - px0)),
                        "argmax_agree" to "%.6f".format(1.0 - (agree.stepsDiffer - before.second).toDouble() / maxOf(1, agree.steps - st0)),
                        "qnn_exact" to pageQ.exact, "qnn_in_tolerance" to pageQ.withinLineTolerance, "qnn_page" to pageQ.toString())
                }
                binmapAll = "%.6f".format(1.0 - agree.pxDiffer.toDouble() / maxOf(1, agree.px))
                argmaxAll = "%.6f".format(1.0 - agree.stepsDiffer.toDouble() / maxOf(1, agree.steps))
            } finally {
                cpu.close()
            }
        } finally {
            qnn.close()
        }
        // one stage on the HTP at a time (ADR-059: which stage could be adopted on its own), each pair opened only
        // after the previous one is closed, so the HTP never holds more than one set of contexts
        val pagesDet = stageOnly(pages, config) { OrtOcrModels(Models.spec(QNN_DET), Models.spec(CPU_REC), config) { QnnEpAndroid.ep(libDir) } }
        val pagesRec = stageOnly(pages, config) { OrtOcrModels(Models.spec(CPU_DET), Models.spec(QNN_REC), config) { QnnEpAndroid.ep(libDir) } }
        val sq = OcrTolerance.Suite(pagesQ); val sc = OcrTolerance.Suite(pagesC)
        val sDet = OcrTolerance.Suite(pagesDet); val sRec = OcrTolerance.Suite(pagesRec)
        row("ocr-total", "pages" to pagesQ.size, "qnn_ok" to sq.ok, "cpu_ok" to sc.ok, "qnn_exact" to pagesQ.count { it.exact },
            "det_only_ok" to sDet.ok, "rec_only_ok" to sRec.ok, "det_only_exact" to pagesDet.count { it.exact },
            "rec_only_exact" to pagesRec.count { it.exact }, "cpu_exact" to pagesC.count { it.exact },
            "qnn_out" to outOfTolerance(pagesQ), "det_only_out" to outOfTolerance(pagesDet), "rec_only_out" to outOfTolerance(pagesRec),
            "qnn_identical" to identical(sq), "det_only_identical" to identical(sDet), "rec_only_identical" to identical(sRec),
            "binmap_agree" to binmapAll, "argmax_agree" to argmaxAll,
            "qnn_suite" to sq.toString().lines().last(), "det_only_suite" to sDet.toString().lines().last(),
            "rec_only_suite" to sRec.toString().lines().last())
        // a measurement for DC4, not a gate here: the table says which stage keeps the README tolerance
    }

    /** Every page through a pair with one stage on the HTP; the pair is closed before this returns. */
    private fun stageOnly(pages: List<GoldenPage>, config: OcrConfig, open: () -> OrtOcrModels): List<OcrTolerance.Page> {
        val models = open()
        try {
            val pipeline = OcrPipeline(config, models)
            return pages.map { page -> page.load().let { (g, image) -> OcrTolerance.compare(g.name, g.ocr.lines, pipeline.run(image).lines) } }
        } finally {
            models.close()
        }
    }

    /** Pages whose line count, boxes or confidences leave the README tolerance ("-" for none). */
    private fun outOfTolerance(pages: List<OcrTolerance.Page>): String =
        pages.filter { !it.withinLineTolerance }.joinToString(" ") { it.name }.ifEmpty { "-" }

    private fun identical(s: OcrTolerance.Suite): String = "%.2f".format(100.0 * s.identical / maxOf(1, s.lines))

    /** One golden page, decoded only when it is used (all 26 at once would not fit the test process's heap). */
    private class GoldenPage(val name: String, val load: () -> Pair<OcrGolden, ScreenImage>)

    /**
     * `-e goldenDir <dir>`: a directory with ml/ocr/golden's NNN.json + NNN.png (a relative path is under this test
     * app's files dir; ort_qnn_smoke.sh pushes all 26 pages to ortqnn/golden). Without it, the 4 pages in the test assets.
     */
    private val goldenDir: File? = args.getString("goldenDir")?.let { File(it).takeIf(File::isAbsolute) ?: File(context.filesDir, it) }
    private val goldenSource: String get() = if (goldenDir != null) "dir" else "assets"

    private fun goldens(): List<GoldenPage> {
        val dir = goldenDir
        val names = if (dir != null) {
            assertTrue("golden dir ${dir.path} missing", dir.isDirectory)
            dir.list()!!.filter { it.matches(Regex("\\d{3}\\.json")) }.sorted()
        } else {
            instrumentation.context.assets.list("ocr")!!.filter { it.endsWith(".json") }.sorted()
        }
        assertTrue("no golden pages", names.isNotEmpty())
        return names.map { n ->
            GoldenPage(n.removeSuffix(".json")) {
                val json = if (dir != null) File(dir, n).readText() else instrumentation.context.assets.open("ocr/$n").bufferedReader().readText()
                val g = OcrGolden.parse(n.removeSuffix(".json"), json)
                val png = n.removeSuffix(".json") + ".png"
                val bmp = (if (dir != null) File(dir, png).inputStream() else instrumentation.context.assets.open("ocr/$png")).use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })!!
                }
                val argb = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
                bmp.recycle()
                g to g.image(bmp.width, bmp.height, argb)
            }
        }
    }

    // ------------------------------------------------------------------ GigaAM, through AsrService (the app's path)

    private fun gigaamSmoke() {
        val dir = File(context.filesDir, "ortqnn/fleurs-ru")
        val meta = Json.parseToJsonElement(File(dir, "mel_ref.json").readText()).jsonObject.getValue("clips").jsonArray.map { it.jsonObject }
        val vad = Models.spec("silero-vad-v5@onnx")
        val results = LinkedHashMap<String, MutableList<Pair<Long, String>>>()
        for (artifact in listOf("gigaam-v3-e2e-ctc@onnx-int8", "gigaam-v3-e2e-ctc@qnn-sm8750-20s")) {
            AsrConnection().use { c ->
                val t0 = SystemClock.elapsedRealtime()
                val load = c.load(Models.spec(artifact), vad, timeoutSec = 180)
                val info = InferApi.json.decodeFromString(LoadInfo.serializer(), load.requireOk())
                row("gigaam-load", "artifact" to artifact, "ms" to SystemClock.elapsedRealtime() - t0, "device" to info.deviceName, "unit" to info.unit)
                val list = results.getOrPut(artifact) { ArrayList() }
                for (m in meta) {
                    val samples = pcm(File(dir, m.getValue("wav").jsonPrimitive.content))
                    val (ms, t) = transcribe(c, samples)
                    list += ms to t.text
                }
                c.service.unloadAll()
            }
        }
        val cpu = results.getValue("gigaam-v3-e2e-ctc@onnx-int8"); val npu = results.getValue("gigaam-v3-e2e-ctc@qnn-sm8750-20s")
        var words = 0; var cpuErr = 0; var npuErr = 0
        meta.forEachIndexed { i, m ->
            val ref = m.getValue("ref").jsonPrimitive.content
            val n = WordErrorRate.normalize(ref).size
            val ce = WordErrorRate.editDistance(WordErrorRate.normalize(ref), WordErrorRate.normalize(cpu[i].second))
            val ne = WordErrorRate.editDistance(WordErrorRate.normalize(ref), WordErrorRate.normalize(npu[i].second))
            words += n; cpuErr += ce; npuErr += ne
            row("gigaam-clip", "clip" to m.getValue("id").jsonPrimitive.content, "frames" to m.getValue("frames").jsonPrimitive.int,
                "cpu_ms" to cpu[i].first, "qnn_ms" to npu[i].first, "cpu_wer_err" to ce, "qnn_wer_err" to ne, "words" to n)
        }
        row("gigaam-total", "clips" to meta.size, "words" to words, "cpu_wer" to "%.2f".format(100.0 * cpuErr / words),
            "qnn_wer" to "%.2f".format(100.0 * npuErr / words), "cpu_ms" to cpu.sumOf { it.first }, "qnn_ms" to npu.sumOf { it.first })
    }

    /** Streams [samples] as fast as the pipe takes them; returns (ms from the first byte to the transcript, transcript). */
    private fun transcribe(c: AsrConnection, samples: ShortArray): Pair<Long, Transcript> {
        val events = Events()
        val (read, write) = ParcelFileDescriptor.createPipe()
        val session = c.service.startSession(InferApi.json.encodeToString(AsrSessionOptions.serializer(), AsrSessionOptions(language = "ru")), read, events)
        read.close()
        val t0 = SystemClock.elapsedRealtime()
        val writer = thread(name = "pcm-writer") { writePcm(write, samples, realTime = false) }
        writer.join()
        val r = Result()
        session.finish(r)
        assertTrue(r.await(300))
        return (r.atMs - t0) to InferApi.json.decodeFromString(Transcript.serializer(), r.requireOk())
    }

    private fun pcm(f: File): ShortArray {
        val b = f.readBytes()
        val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        while (pos + 8 <= b.size) {
            val id = String(b, pos, 4)
            val len = buf.getInt(pos + 4)
            if (id == "data") return ShortArray(len / 2).also { buf.position(pos + 8); buf.asShortBuffer().get(it) }
            pos += 8 + len + (len and 1)
        }
        error("no data chunk in ${f.name}")
    }

    private companion object {
        const val QNN_DET = "pp-ocrv6-tiny-det@qnn-sm8750"
        const val QNN_REC = "pp-ocrv5-cyrillic-mobile-rec@qnn-sm8750"
        const val CPU_DET = "pp-ocrv6-tiny-det@onnx"
        const val CPU_REC = "pp-ocrv5-cyrillic-mobile-rec@onnx"
    }

    private fun npuSpec(artifactId: String): LoadSpec = Models.spec(artifactId).also { assertEquals(ComputeUnit.NPU, it.unit) }

    private fun row(kind: String, vararg fields: Pair<String, Any?>) {
        val o = buildJsonObject {
            put("kind", kind)
            for ((k, v) in fields) when (v) {
                is Number -> put(k, v)
                is Boolean -> put(k, v)
                else -> put(k, v.toString())
            }
        }
        report("ortqnn", o.toString())
    }
}
