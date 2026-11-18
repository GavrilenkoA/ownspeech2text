package app.murmur.infer.asr

import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import java.io.File

/** Result of the QNN HTP probe; codes and numbers only (D9). */
data class QnnStatus(
    val available: Boolean,
    /** "ok", "no-libs", "no-probe", "dlopen", "symbol", "providers", "log", "backend", "device", "context". */
    val stage: String,
    val rc: Long = 0,
    /** libQnnHtp build id, e.g. "v2.52.0.261104115025". */
    val buildId: String? = null,
    val socModel: Int? = null,
    /** HTP architecture number (79 = V79, SM8750). */
    val htpArch: Int? = null,
    val libs: QnnLibs? = null,
) {
    /** "2.52" from "v2.52.0.261104115025". */
    val version: String? get() = buildId?.removePrefix("v")?.split('.')?.take(2)?.takeIf { it.size == 2 }?.joinToString(".")

    companion object {
        /** Parses murmur_qnn_probe.cc's "stage=..;rc=..;build=..;soc=..;arch=.." line. */
        fun parse(line: String, libs: QnnLibs): QnnStatus {
            val kv = line.split(';').mapNotNull { it.split('=', limit = 2).takeIf { p -> p.size == 2 } }.associate { it[0] to it[1] }
            val stage = kv["stage"] ?: "probe"
            return QnnStatus(
                available = stage == "ok",
                stage = stage,
                rc = kv["rc"]?.toLongOrNull() ?: 0,
                buildId = kv["build"]?.takeIf { it.isNotEmpty() },
                socModel = kv["soc"]?.toIntOrNull()?.takeIf { it != 0 },
                htpArch = kv["arch"]?.toIntOrNull()?.takeIf { it != 0 },
                libs = libs,
            )
        }
    }
}

/**
 * Does the QNN HTP backend initialise in this process? sherpa-onnx ends the process on any QNN error, so the :asr
 * process asks `libmurmur-qnn-probe.so` (native/sherpa/qnn-probe) first: backend, device and an empty context are
 * created and freed. Done once per process. The QNN libraries are packaged only with -Pmurmur.qnn=true (ADR-048);
 * without them, or on x86_64 (no probe library), the answer is "not available" within milliseconds.
 */
object QnnProbe {
    private const val TAG = "murmur-asr"
    @Volatile private var cached: QnnStatus? = null

    @Synchronized
    fun status(nativeLibraryDir: String): QnnStatus {
        cached?.let { return it }
        val result = run(nativeLibraryDir)
        cached = result
        Log.i(TAG, "qnn probe stage=${result.stage} rc=${result.rc} arch=${result.htpArch ?: 0}")
        return result
    }

    private fun run(dir: String): QnnStatus {
        val libs = QnnLibs.inDir(dir)
        if (!File(libs.backendLib).isFile || !File(libs.systemLib).isFile) return QnnStatus(false, "no-libs")
        try {
            System.loadLibrary("murmur-qnn-probe")
        } catch (e: UnsatisfiedLinkError) {
            return QnnStatus(false, "no-probe")
        }
        // sherpa 2.0.1 API (offline-recognizer.cc:731): prepends the app's lib dir and keeps the vendor DSP paths,
        // so that the FastRPC loader finds libQnnHtpV79Skel.so. Must precede the first QNN backend in the process.
        if (!Sherpa.loaded) return QnnStatus(false, "no-sherpa")
        OfflineRecognizer.prependAdspLibraryPath(dir)
        return QnnStatus.parse(nativeProbe(libs.backendLib), libs)
    }

    @JvmStatic private external fun nativeProbe(backendLib: String): String
}

/** Whether libsherpa-onnx-jni.so (and with it libonnxruntime.so) loads in this process. */
object Sherpa {
    val loaded: Boolean by lazy {
        try {
            System.loadLibrary("sherpa-onnx-jni")
            true
        } catch (e: UnsatisfiedLinkError) {
            false
        }
    }
}
