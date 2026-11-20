package app.murmur.infer.asr

import android.app.ActivityManager
import android.app.Service
import android.content.Intent
import android.os.Debug
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import app.murmur.core.catalog.RuntimeKeys
import app.murmur.core.engine.AsrEvent
import app.murmur.core.engine.AsrSessionOptions
import app.murmur.core.engine.LoadInfo
import app.murmur.core.engine.LoadSpec
import app.murmur.core.engine.ProcessMemory
import app.murmur.core.engine.ProcessProbe
import app.murmur.core.engine.Transcript
import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.InferProcess
import app.murmur.core.model.MurmurError
import app.murmur.core.model.Outcome
import app.murmur.infer.api.IAsrService
import app.murmur.infer.api.IAsrSession
import app.murmur.infer.api.IAsrSessionCallback
import app.murmur.infer.api.IResultCallback
import app.murmur.infer.api.InferApi
import app.murmur.infer.api.InferProcessLifecycle
import app.murmur.infer.asr.ocr.OcrHandler
import app.murmur.infer.asr.ocr.OcrHandlers
import app.murmur.infer.asr.qnnep.QnnEpAndroid
import com.k2fsa.sherpa.onnx.VersionInfo
import java.io.File

/**
 * The :asr process (WP-06 ASR, WP-07 OCR through [OcrHandler], WP-06 VAD/segmenter). Threads: one ASR engine thread
 * ([AsrEngineHost]), one OCR thread (WP-07), one pipe-reader thread per session (ARCHITECTURE.md 14). Every binder
 * method returns at once; results go back through the oneway callbacks. Never touches UI or the database.
 * Before the first QNN recognizer: OfflineRecognizer.prependAdspLibraryPath(applicationInfo.nativeLibraryDir)
 * ([QnnProbe]; sherpa 2.0.1 API, keeps the vendor DSP paths).
 */
class AsrService : Service() {
    private lateinit var ocr: OcrHandler
    private lateinit var asr: AsrEngineHost

    override fun onCreate() {
        super.onCreate()
        InferProcessLifecycle.installQuietCrashHandler("murmur-asr")
        ocr = OcrHandlers.create(this)
        asr = AsrEngineHost(applicationInfo.nativeLibraryDir)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean {
        // D5: unload everything, then exit so that ORT/QNN memory and HTP contexts are really released.
        runCatching { binder.unloadAll() }
        InferProcessLifecycle.exitNow()
        return false
    }

    private fun probe(): ProcessProbe {
        val facts = LinkedHashMap<String, String>()
        val runtimes = LinkedHashSet<String>()
        val units = linkedSetOf(ComputeUnit.CPU)
        // ONNX Runtime comes from the AAR (always packaged): the Silero VAD runtime exists even without sherpa.
        runtimes += RuntimeKeys.ORT_SILERO_VAD
        // The OCR handler lives in :asr on the same packaged ORT (OcrHandlers.create): without these keys the device
        // profile has no OCR runtime and the resolver never selects the OCR pair (no screen context, WP-20 finding).
        runtimes += RuntimeKeys.ORT_PPOCR_DET
        runtimes += RuntimeKeys.ORT_PPOCR_REC
        if (Sherpa.loaded) {
            runtimes += SherpaConfigs.SHERPA
            val qnn = QnnProbe.status(applicationInfo.nativeLibraryDir)
            if (qnn.available) units += ComputeUnit.NPU
            val build = buildString {
                append("sherpa-onnx ").append(VersionInfo.version).append(' ').append(sherpaCommit())
                append(" + ORT ").append(VersionInfo.onnxruntimeVersion)
                qnn.version?.takeIf { qnn.available }?.let { append(" + QNN ").append(it) }
            }
            facts[ProcessProbe.FACT_NATIVE_BUILD] = build
            facts["sherpa.versionSha"] = VersionInfo.gitSha1
            facts["qnn.stage"] = qnn.stage
            if (qnn.rc != 0L) facts["qnn.rc"] = qnn.rc.toString()
            qnn.buildId?.let { facts["qnn.build"] = it }
            qnn.htpArch?.let { facts["htp.arch"] = "v$it" }
            qnn.socModel?.let { facts["qnn.soc"] = it.toString() }
        } else {
            facts["sherpa"] = "missing"
        }
        // WP-21: the ORT QNN plugin EP runtimes exist only when the plugin registered and offers its EP device, and
        // only with an HTP behind it (the NPU unit comes from the QNN probe above; ADR-059: off unless packaged).
        val ep = QnnEpAndroid.ep(applicationInfo.nativeLibraryDir).status
        facts["qnnep.stage"] = ep.stage
        ep.epVersion?.let { facts["qnnep.version"] = it }
        if (ep.available && ComputeUnit.NPU in units) runtimes += RuntimeKeys.qnnEp
        facts["abi"] = android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        return ProcessProbe(InferProcess.ASR, runtimes, units, facts)
    }

    /**
     * The source commit of the JNI library. native/sherpa/build-android.sh builds the v2.0.1 tag (af2d8d81); upstream's
     * version.cc of that tag still names the commit it was generated at (96255a1b), so for 2.0.1 the pinned tag commit
     * is reported, for anything else the library's own value.
     */
    private fun sherpaCommit(): String =
        if (VersionInfo.version == SHERPA_VERSION && VersionInfo.gitSha1 == SHERPA_VERSION_FILE_SHA) SHERPA_COMMIT else VersionInfo.gitSha1.take(8)

    private fun memory(): ProcessMemory {
        var hwm = 0L
        var rss = 0L
        File("/proc/self/status").forEachLine { line ->
            fun kb() = line.substringAfter(':').trim().substringBefore(' ').toLongOrNull() ?: 0L
            when {
                line.startsWith("VmHWM:") -> hwm = kb()
                line.startsWith("VmRSS:") -> rss = kb()
            }
        }
        val mi = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        val sys = ActivityManager.MemoryInfo().also { getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        return ProcessMemory(vmHwmKb = hwm, vmRssKb = rss, pssKb = mi.totalPss.toLong(), systemAvailMb = sys.availMem / (1024 * 1024))
    }

    private val binder = object : IAsrService.Stub() {
        override fun apiVersion(): Int = InferApi.API_VERSION

        override fun probe(): String = InferApi.encode(ProcessProbe.serializer(), this@AsrService.probe())

        override fun memory(): String = InferApi.encode(ProcessMemory.serializer(), this@AsrService.memory())

        override fun loadAsr(asrSpecJson: String?, vadSpecJson: String?, cb: IResultCallback?) {
            val callback = requireNotNull(cb)
            val asrSpec = decodeSpec(asrSpecJson) ?: return callback.onError(InferApi.encodeError(MurmurError.Internal("spec")))
            val vadSpec = vadSpecJson?.takeIf { it.isNotBlank() && it != "null" }?.let { decodeSpec(it) ?: return callback.onError(InferApi.encodeError(MurmurError.Internal("spec"))) }
            asr.post {
                when (val r = asr.load(asrSpec, vadSpec)) {
                    is Outcome.Ok -> callback.onResult(InferApi.encode(LoadInfo.serializer(), r.value))
                    is Outcome.Err -> callback.onError(InferApi.encodeError(r.error))
                }
            }
        }

        override fun loadOcr(detSpecJson: String?, recSpecJson: String?, cb: IResultCallback?) =
            ocr.load(detSpecJson.orEmpty(), recSpecJson.orEmpty(), requireNotNull(cb))

        override fun startSession(optionsJson: String?, pcmRead: ParcelFileDescriptor?, cb: IAsrSessionCallback?): IAsrSession {
            val options = optionsJson?.takeIf { it.isNotBlank() && it != "null" }
                ?.let { runCatching { InferApi.decode(AsrSessionOptions.serializer(), it) }.getOrNull() } ?: AsrSessionOptions()
            val events = requireNotNull(cb)
            val handle = asr.startSession(options, requireNotNull(pcmRead)) { e ->
                runCatching { events.onEvent(InferApi.encode(AsrEvent.serializer(), e)) }
            }
            return object : IAsrSession.Stub() {
                override fun finish(cb: IResultCallback?) {
                    val reply = requireNotNull(cb)
                    handle.finish { r ->
                        runCatching {
                            when (r) {
                                is Outcome.Ok -> reply.onResult(InferApi.encode(Transcript.serializer(), r.value))
                                is Outcome.Err -> reply.onError(InferApi.encodeError(r.error))
                            }
                        }
                    }
                }

                override fun cancel() = handle.cancel()
            }
        }

        override fun recognizeScreen(pixels: SharedMemory?, width: Int, height: Int, excludeJson: String?, cb: IResultCallback?) =
            ocr.recognize(requireNotNull(pixels), width, height, excludeJson ?: "[]", requireNotNull(cb))

        override fun unloadAll() {
            ocr.unload()
            asr.unloadAllBlocking(UNLOAD_WAIT_MS)
        }
    }

    private fun decodeSpec(json: String?): LoadSpec? =
        json?.let { runCatching { InferApi.decode(LoadSpec.serializer(), it) }.getOrNull() }

    companion object {
        /** Below the 2 s client timeout of unloadAll(); process exit on unbind is the real guarantee (D5). */
        private const val UNLOAD_WAIT_MS = 1_500L
        private const val SHERPA_VERSION = "2.0.1"
        private const val SHERPA_COMMIT = "af2d8d81"
        private const val SHERPA_VERSION_FILE_SHA = "96255a1b"
    }
}
