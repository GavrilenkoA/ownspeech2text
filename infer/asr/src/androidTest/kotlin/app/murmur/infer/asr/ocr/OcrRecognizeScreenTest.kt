package app.murmur.infer.asr.ocr

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.IBinder
import android.os.SharedMemory
import android.os.SystemClock
import android.system.OsConstants
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.murmur.core.catalog.RuntimeKeys
import app.murmur.core.engine.LoadInfo
import app.murmur.core.engine.LoadSpec
import app.murmur.core.model.ArtifactId
import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.ModelId
import app.murmur.core.model.ModelKind
import app.murmur.core.model.MurmurError
import app.murmur.core.ocr.OcrGolden
import app.murmur.core.ocr.OcrTolerance
import app.murmur.core.ocr.Rect
import app.murmur.core.ocr.ScreenText
import app.murmur.infer.api.IAsrService
import app.murmur.infer.api.IResultCallback
import app.murmur.infer.api.InferApi
import app.murmur.infer.asr.AsrService
import kotlinx.serialization.builtins.ListSerializer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The real `recognizeScreen` binder path on the emulator: golden PNG -> ARGB ints -> read-only SharedMemory ->
 * IAsrService (the :asr process) -> OrtOcrHandler -> ScreenText JSON, held to the ml/ocr README tolerances.
 *
 * Models: the catalog artifacts pushed by the runner (tools/models/fetch, then
 * `adb push <models>/pp-ocrv6-tiny-det@onnx <models>/pp-ocrv5-cyrillic-mobile-rec@onnx /data/local/tmp/murmur-ocr/`);
 * the test copies them into filesDir/models/<artifactId>/ (the installed layout, ARCHITECTURE 8.3).
 * Pages: assets/ocr (copies of ml/ocr/golden 001, 002, 005, 006: cover/inner x ru/mixed); instrumentation argument
 * `goldenDir` (e.g. a pushed ml/ocr/golden) runs every NNN.json/NNN.png found there instead.
 */
@RunWith(AndroidJUnit4::class)
class OcrRecognizeScreenTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val args = InstrumentationRegistry.getArguments()
    private lateinit var service: IAsrService
    private lateinit var connection: ServiceConnection

    @Before
    fun bind() {
        val latch = CountDownLatch(1)
        var binder: IBinder? = null
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, b: IBinder?) { binder = b; latch.countDown() }
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        assertTrue(context.bindService(Intent(context, AsrService::class.java), connection, Context.BIND_AUTO_CREATE))
        assertTrue("bind", latch.await(10, TimeUnit.SECONDS))
        service = IAsrService.Stub.asInterface(binder)
        assertEquals(InferApi.API_VERSION, service.apiVersion())
    }

    @After
    fun unbind() = context.unbindService(connection)

    private class Reply {
        val latch = CountDownLatch(1)
        var result: String? = null
        var error: String? = null
        val cb = object : IResultCallback.Stub() {
            override fun onResult(json: String?) { result = json; latch.countDown() }
            override fun onError(errorJson: String?) { error = errorJson; latch.countDown() }
        }

        fun await(seconds: Long): Reply {
            assertTrue("no reply in $seconds s", latch.await(seconds, TimeUnit.SECONDS))
            return this
        }
    }

    private fun installed(artifact: String, layout: Map<String, String>, kind: ModelKind, model: String, runtime: String): LoadSpec {
        val src = File(args.getString("ocrModelsDir") ?: "/data/local/tmp/murmur-ocr", artifact)
        val dst = File(context.filesDir, "models/$artifact").apply { mkdirs() }
        for (name in layout.values) {
            val from = File(src, name)
            assertTrue("$from missing: push the OCR artifacts (see the class comment)", from.canRead())
            val to = File(dst, name)
            if (!to.exists() || to.length() != from.length()) from.copyTo(to, overwrite = true)
        }
        return LoadSpec(kind, ModelId(model), ArtifactId(artifact), runtime, ComputeUnit.CPU, dst.absolutePath, layout)
    }

    private val det by lazy {
        installed("pp-ocrv6-tiny-det@onnx", mapOf("model" to "det.onnx"), ModelKind.OCR_DET, "pp-ocrv6-tiny-det", RuntimeKeys.ORT_PPOCR_DET)
    }
    private val rec by lazy {
        installed("pp-ocrv5-cyrillic-mobile-rec@onnx", mapOf("model" to "rec.onnx", "dict" to "rec.yml"), ModelKind.OCR_REC,
            "pp-ocrv5-cyrillic-mobile-rec", RuntimeKeys.ORT_PPOCR_REC)
    }

    private fun load(): LoadInfo {
        val r = Reply()
        service.loadOcr(InferApi.encode(LoadSpec.serializer(), det), InferApi.encode(LoadSpec.serializer(), rec), r.cb)
        r.await(120)
        assertEquals("load error ${r.error}", null, r.error)
        return InferApi.decode(LoadInfo.serializer(), r.result!!)
    }

    /** :main's side of the contract (WP-05): ints little-endian in a SharedMemory, then read-only. */
    private fun frame(argb: IntArray): SharedMemory {
        val shm = SharedMemory.create("murmur-frame", argb.size * 4)
        val buf = shm.mapReadWrite()
        buf.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(argb)
        SharedMemory.unmap(buf)
        assertTrue(shm.setProtect(OsConstants.PROT_READ))
        return shm
    }

    private fun recognize(w: Int, h: Int, argb: IntArray, exclude: List<Rect>): Pair<Reply, Long> {
        val shm = frame(argb)
        val r = Reply()
        val t0 = SystemClock.elapsedRealtime()
        try {
            service.recognizeScreen(shm, w, h, InferApi.encode(ListSerializer(Rect.serializer()), exclude), r.cb)
            r.await(120)
        } finally {
            shm.close()
        }
        return r to SystemClock.elapsedRealtime() - t0
    }

    private class Page(val golden: OcrGolden, val png: () -> ByteArray)

    private fun pages(): List<Page> {
        val dir = args.getString("goldenDir")
        if (dir != null) {
            val files = File(dir).listFiles { f -> f.name.matches(Regex("\\d{3}\\.json")) }!!.sortedBy { it.name }
            return files.map { f ->
                Page(OcrGolden.parse(f.nameWithoutExtension, f.readText())) { File(f.parentFile, f.nameWithoutExtension + ".png").readBytes() }
            }
        }
        val assets = instrumentation.context.assets
        return assets.list("ocr")!!.filter { it.endsWith(".json") }.sorted().map { name ->
            val stem = name.removeSuffix(".json")
            Page(OcrGolden.parse(stem, assets.open("ocr/$name").use { it.readBytes().decodeToString() })) {
                assets.open("ocr/$stem.png").use { it.readBytes() }
            }
        }
    }

    private fun decode(png: ByteArray): Triple<Int, Int, IntArray> {
        val bmp = BitmapFactory.decodeByteArray(png, 0, png.size, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
            inPremultiplied = false
        })
        val argb = IntArray(bmp.width * bmp.height)
        bmp.getPixels(argb, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return Triple(bmp.width, bmp.height, argb).also { bmp.recycle() }
    }

    @Test
    fun goldenPagesThroughRecognizeScreenMeetReadmeTolerances() {
        val info = load()
        assertEquals(ComputeUnit.CPU, info.unit)
        Log.i(TAG, "load ms=${info.loadMs} device=${info.deviceName}")
        val results = ArrayList<OcrTolerance.Page>()
        for (page in pages()) {
            val g = page.golden
            val (w, h, argb) = decode(page.png())
            assertEquals(g.ocr.size, listOf(w, h))
            val (reply, wallMs) = recognize(w, h, argb, g.exclude)
            assertEquals("${g.name}: ${reply.error}", null, reply.error)
            val out = InferApi.decode(ScreenText.serializer(), reply.result!!)
            assertEquals(g.ocr.size, out.size)
            val s = out.stats!!
            assertEquals(listOf(g.detInput.first, g.detInput.second), s.detInput)
            val diff = OcrTolerance.compare(g.name, g.ocr.lines, out.lines)
            results += diff
            Log.i(TAG, String.format(Locale.ROOT,
                "page %s %dx%d det_in=%dx%d det_ms=%.0f rec_lines=%d rec_ms=%.0f (%.1f/line) total_ms=%.0f binder_wall_ms=%d | %s",
                g.name, w, h, s.detInput[0], s.detInput[1], s.detMs, s.recLines, s.recMs, s.recMsPerLine, s.totalMs, wallMs, diff))
        }
        val suite = OcrTolerance.Suite(results)
        suite.toString().lines().forEach { Log.i(TAG, it) }
        assertTrue(suite.toString(), suite.ok)
    }

    @Test
    fun exclusionRectanglesTravelWithTheFrame() {
        load()
        val page = pages().first()
        val (w, h, argb) = decode(page.png())
        val all = InferApi.decode(ScreenText.serializer(), recognize(w, h, argb, emptyList()).first.result!!)
        val cut = InferApi.decode(ScreenText.serializer(), recognize(w, h, argb, listOf(Rect(0, 0, w, h / 2))).first.result!!)
        assertEquals(0, all.stats!!.excluded)
        assertTrue(cut.stats!!.excluded > 0)
        assertTrue(cut.lines.all { it.box[1] >= h / 2 - it.box.let { b -> b[3] - b[1] } })
        assertTrue(cut.lines.size < all.lines.size)
    }

    @Test
    fun errorsComeBackAsMurmurErrors() {
        // After unloadAll the handler answers not_loaded; after a load, a frame smaller than
        // width*height*4 is rejected. Both replies are MurmurError JSON, never a crash of the process.
        service.unloadAll()
        val notLoaded = recognize(4, 4, IntArray(16), emptyList()).first
        assertEquals(MurmurError.OcrFailed("not_loaded"), InferApi.decodeError(notLoaded.error!!))
        load()
        val shm = frame(IntArray(16))
        val r = Reply()
        try {
            service.recognizeScreen(shm, 100, 100, "[]", r.cb)
            r.await(30)
        } finally {
            shm.close()
        }
        assertEquals(MurmurError.OcrFailed("frame_size"), InferApi.decodeError(r.error!!))
        val wrongRuntime = Reply()
        service.loadOcr(InferApi.encode(LoadSpec.serializer(), det.copy(runtime = RuntimeKeys.ORT_PPOCR_REC)),
            InferApi.encode(LoadSpec.serializer(), rec), wrongRuntime.cb)
        assertEquals(MurmurError.EngineRuntimeMissing(RuntimeKeys.ORT_PPOCR_REC), InferApi.decodeError(wrongRuntime.await(30).error!!))
        assertEquals(InferApi.API_VERSION, service.apiVersion()) // the process survived every error
    }

    companion object {
        const val TAG = "MurmurOcrTest"
    }
}
