package app.murmur.infer.asr.ocr

import android.content.Context
import android.os.SharedMemory
import app.murmur.infer.api.IResultCallback
import app.murmur.infer.asr.qnnep.QnnEpAndroid

/**
 * The seam between AsrService (WP-06) and OCR (WP-07): AsrService forwards loadOcr / recognizeScreen / unloadAll to
 * this handler and never touches OCR code itself, so the two packages never edit the same file
 * (review 2026-11-19). Calls arrive on binder threads; the implementation runs the work on its own OCR thread and
 * replies through [IResultCallback] (LoadInfo JSON / ScreenText JSON / MurmurError JSON).
 */
interface OcrHandler {
    fun load(detSpecJson: String, recSpecJson: String, cb: IResultCallback)
    /** [pixels]: read-only ARGB_8888 frame created by :main; close/unmap it when done (success or error). */
    fun recognize(pixels: SharedMemory, width: Int, height: Int, excludeJson: String, cb: IResultCallback)
    fun unload()
}

object OcrHandlers {
    /**
     * ORT + the Kotlin port of ml/ocr ([OrtOcrHandler]): CPU EP for `ort/ppocr-*` artifacts, Qualcomm's QNN plugin EP
     * from the APK's nativeLibraryDir for `ort-qnn/ppocr-*` artifacts (WP-21).
     */
    fun create(context: Context): OcrHandler {
        val libDir = context.applicationInfo.nativeLibraryDir
        return OrtOcrHandler(qnn = { QnnEpAndroid.ep(libDir) })
    }
}
