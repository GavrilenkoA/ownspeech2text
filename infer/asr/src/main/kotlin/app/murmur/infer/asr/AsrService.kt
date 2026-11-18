package app.murmur.infer.asr

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import app.murmur.core.catalog.RuntimeKeys
import app.murmur.core.engine.ProcessProbe
import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.InferProcess
import app.murmur.infer.api.IAsrService
import app.murmur.infer.api.IAsrSession
import app.murmur.infer.api.IAsrSessionCallback
import app.murmur.infer.api.IResultCallback
import app.murmur.infer.api.InferApi
import app.murmur.infer.api.InferProcessLifecycle

/**
 * The :asr process (WP-06 ASR, WP-07 OCR, WP-06 VAD/segmenter). Threads: one ASR engine thread, one OCR
 * thread, one pipe-reader thread per session (ARCHITECTURE.md 12). Never touches UI or the database.
 */
class AsrService : Service() {
    override fun onCreate() {
        super.onCreate()
        InferProcessLifecycle.installQuietCrashHandler("murmur-asr")
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean {
        // D5: unload everything, then exit so that ORT/QNN memory and HTP contexts are really released.
        runCatching { binder.unloadAll() }
        InferProcessLifecycle.exitNow()
        return false
    }

    private val binder = object : IAsrService.Stub() {
        override fun apiVersion(): Int = InferApi.API_VERSION

        override fun probe(): String = InferApi.encode(
            ProcessProbe.serializer(),
            // WP-06 reports what really initialised (sherpa lib loaded? QNN HTP backend created?).
            ProcessProbe(InferProcess.ASR, runtimes = emptySet(), units = setOf(ComputeUnit.CPU),
                facts = mapOf("skeleton" to "true", "known" to RuntimeKeys.known.size.toString())),
        )

        override fun memory(): String = TODO("WP-06: /proc/self/status VmHWM + Debug.getMemoryInfo")
        override fun loadAsr(asrSpecJson: String?, vadSpecJson: String?, cb: IResultCallback?) = TODO("WP-06")
        override fun loadOcr(detSpecJson: String?, recSpecJson: String?, cb: IResultCallback?) = TODO("WP-07")
        override fun startSession(optionsJson: String?, pcmRead: ParcelFileDescriptor?, cb: IAsrSessionCallback?): IAsrSession = TODO("WP-06")
        override fun recognizeScreen(pixels: SharedMemory?, width: Int, height: Int, excludeJson: String?, cb: IResultCallback?) = TODO("WP-07")
        override fun unloadAll() { /* WP-06/07: release sherpa recognizers, VAD and ORT sessions */ }
    }
}
