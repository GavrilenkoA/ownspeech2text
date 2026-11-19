package app.murmur.infer.asr.qnnep

import android.system.ErrnoException
import android.system.Os

/**
 * Android side of [QnnEp]: the FastRPC loader finds libQnnHtpV79Skel.so through ADSP_LIBRARY_PATH, which must name
 * the app's real nativeLibraryDir (ADR-007, useLegacyPackaging) before the first QNN backend is created in the process.
 * The rule of sherpa-onnx 2.0.1's `PrependAdspLibraryPath` (jni/common.cc, used by QnnProbe): the app directory first,
 * then the inherited value (the vendor DSP directories, ';'-separated); when nothing is inherited, the system DSP
 * directories instead of nothing. A no-op when the directory is already first (the probe or an earlier load did it).
 */
object QnnEpAndroid {
    private const val VAR = "ADSP_LIBRARY_PATH"
    private const val VENDOR = "/vendor/lib/rfsa/adsp;/vendor/dsp/cdsp;/system/lib/rfsa/adsp;/system/vendor/lib/rfsa/adsp;/dsp"

    /** The process's QNN EP for the APK's libraries, with ADSP_LIBRARY_PATH set first. */
    @Synchronized
    fun ep(nativeLibraryDir: String): QnnEp {
        val ep = QnnEp.of(QnnEpLibs.inDir(nativeLibraryDir))
        if (ep.status.available) prependAdspLibraryPath(nativeLibraryDir)
        return ep
    }

    /** "<dir>;<current or vendor default>" unless <dir> is already the first entry. */
    fun adspPath(dir: String, current: String?): String {
        val cur = current?.takeIf { it.isNotBlank() } ?: VENDOR
        return if (cur.split(';').firstOrNull() == dir) cur else "$dir;$cur"
    }

    private fun prependAdspLibraryPath(dir: String) {
        try {
            Os.setenv(VAR, adspPath(dir, Os.getenv(VAR)), true)
        } catch (e: ErrnoException) {
            // The backend then fails to open its DSP session: the session creation reports it (UnitUnavailable).
        }
    }
}
