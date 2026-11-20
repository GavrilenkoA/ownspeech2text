package app.murmur.infer.asr.qnnep

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtEpDevice
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import java.io.File

/**
 * Where Qualcomm's ORT plugin EP and the QNN HTP backend live: the APK's nativeLibraryDir (arm64-v8a, packaged only
 * with -Pmurmur.qnn=true, ADR-048) or, in host tests, the onnxruntime-qnn wheel (x86_64 HTP simulator).
 */
data class QnnEpLibs(val plugin: String, val backend: String) {
    companion object {
        const val PLUGIN = "libonnxruntime_providers_qnn.so"
        const val BACKEND = "libQnnHtp.so"

        fun inDir(dir: String): QnnEpLibs = QnnEpLibs(File(dir, PLUGIN).path, File(dir, BACKEND).path)
    }
}

/** Result of registering the plugin EP in this process; codes and numbers only (D9). */
data class QnnEpStatus(
    val available: Boolean,
    /** "ok", "no-libs" (plugin or backend not packaged), "register" (ORT refused the library), "no-device". */
    val stage: String,
    /** The plugin's own version from its EP metadata, e.g. "2.7.0" (Android AAR) or "2.8.0" (host wheel). */
    val epVersion: String? = null,
)

/** A QNN EP session could not be created or run; [stage] is a code ("register", "no-device", "session", ...). */
class QnnEpException(val stage: String, cause: Throwable? = null) : Exception(stage, cause)

/**
 * The ORT QNN plugin EP in this process (ONNX Runtime 1.32 plugin-EP API, verified on the host build with the
 * desktop ORT 1.32.0 jar + onnxruntime-qnn 2.8.0 + the x86_64 HTP simulator, the same Java API as the Android AAR):
 *
 *     env.registerExecutionProviderLibrary("QNNExecutionProvider", "<dir>/libonnxruntime_providers_qnn.so")
 *     devices = env.getEpDevices().filter { it.epName == "QNNExecutionProvider" }
 *     options.addConfigEntry("session.disable_cpu_ep_fallback", "1")
 *     options.addExecutionProvider(devices, mapOf("backend_path" to "<dir>/libQnnHtp.so", "htp_performance_mode" to …))
 *     env.createSession("<artifact>/<graph>.onnx", options)   // an EPContext model; its _qnn.bin sits next to it
 *
 * Registration is process-wide (one OrtEnvironment per process; a second registration under the same name fails
 * in ORT), so [of] caches one instance per plugin path. Sessions are EPContext models only: with the CPU fallback
 * disabled a session exists only if its whole graph runs on the HTP, so nothing ever runs on the CPU silently.
 */
class QnnEp private constructor(
    val libs: QnnEpLibs,
    val status: QnnEpStatus,
    private val devices: List<OrtEpDevice>,
) {
    private val env: OrtEnvironment get() = OrtEnvironment.getEnvironment()

    /** Throws the registration failure ([QnnEpException] with the [QnnEpStatus.stage]) unless the EP is usable. */
    fun requireAvailable(): QnnEp {
        if (!status.available) throw QnnEpException(status.stage)
        return this
    }

    /**
     * Session options for one EPContext model on the HTP. [performance] = QNN EP `htp_performance_mode`
     * (burst: short latency-critical calls, the OCR and the decode at the stop tap; DEVICE_CHECKLIST DC4 compares).
     */
    fun options(performance: String = PERF_BURST): OrtSession.SessionOptions {
        requireAvailable()
        val so = OrtSession.SessionOptions()
        try {
            so.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR)
            so.addConfigEntry(DISABLE_CPU_FALLBACK, "1")
            so.addExecutionProvider(devices, mapOf(OPT_BACKEND_PATH to libs.backend, OPT_PERFORMANCE to performance))
        } catch (e: OrtException) {
            so.close()
            throw QnnEpException("options", e)
        }
        return so
    }

    /** Opens the EPContext model [model] on the HTP (its context binary is resolved relative to [model]). */
    fun open(model: File, performance: String = PERF_BURST): OrtSession {
        val so = options(performance)
        try {
            return env.createSession(model.path, so)
        } catch (e: OrtException) {
            throw QnnEpException("session", e)
        } finally {
            so.close()
        }
    }

    companion object {
        const val EP_NAME = "QNNExecutionProvider"
        const val DISABLE_CPU_FALLBACK = "session.disable_cpu_ep_fallback"
        const val OPT_BACKEND_PATH = "backend_path"
        const val OPT_PERFORMANCE = "htp_performance_mode"
        const val PERF_BURST = "burst"

        private val registered = HashMap<String, QnnEp>()

        /** The process's QNN EP for [libs]: registered on first use, never throws (a failure is in [status]). */
        @Synchronized
        fun of(libs: QnnEpLibs): QnnEp = registered.getOrPut(libs.plugin) { register(libs) }

        private fun register(libs: QnnEpLibs): QnnEp {
            if (!File(libs.plugin).isFile || !File(libs.backend).isFile) return QnnEp(libs, QnnEpStatus(false, "no-libs"), emptyList())
            val env = OrtEnvironment.getEnvironment()
            try {
                env.registerExecutionProviderLibrary(EP_NAME, libs.plugin)
            } catch (e: OrtException) {
                return QnnEp(libs, QnnEpStatus(false, "register"), emptyList())
            } catch (e: UnsatisfiedLinkError) {
                return QnnEp(libs, QnnEpStatus(false, "register"), emptyList())
            }
            val devices = try {
                env.epDevices.filter { it.epName == EP_NAME }
            } catch (e: OrtException) {
                emptyList()
            }
            if (devices.isEmpty()) return QnnEp(libs, QnnEpStatus(false, "no-device"), emptyList())
            val version = devices.first().epMetadata["version"]
            return QnnEp(libs, QnnEpStatus(true, "ok", version), devices)
        }
    }
}
