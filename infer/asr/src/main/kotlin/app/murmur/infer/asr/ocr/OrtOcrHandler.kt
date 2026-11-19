package app.murmur.infer.asr.ocr

import android.os.RemoteException
import android.os.SharedMemory
import android.os.SystemClock
import android.util.Log
import app.murmur.core.catalog.RuntimeKeys
import app.murmur.core.engine.LoadInfo
import app.murmur.core.engine.LoadSpec
import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.ModelKind
import app.murmur.core.model.MurmurError
import app.murmur.core.ocr.OcrCancelledException
import app.murmur.core.ocr.OcrConfig
import app.murmur.core.ocr.OcrPipeline
import app.murmur.core.ocr.OcrRunControl
import app.murmur.core.ocr.OcrStats
import app.murmur.core.ocr.Rect
import app.murmur.core.ocr.ScreenImage
import app.murmur.core.ocr.ScreenText
import app.murmur.infer.api.IResultCallback
import app.murmur.infer.api.InferApi
import kotlinx.serialization.builtins.ListSerializer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The :asr side of OCR: LoadSpec JSON -> [OrtOcrModels] + [OcrPipeline]; SharedMemory frame -> [ScreenImage] ->
 * [OcrPipeline.run] -> ScreenText JSON. Everything runs on one OCR thread (ARCHITECTURE.md 14): binder threads only
 * enqueue (and answer a frame they supersede), so a recognize that arrives while the models load waits for them. The SharedMemory is unmapped and closed
 * in every path. Logs carry codes and numbers only (D9): never text, never paths.
 *
 * Newest frame wins: a frame is wanted by the dictation that sent it, and the client stops waiting after
 * `ocrBudgetMs`. When a new frame arrives, an older one still queued is answered `OcrFailed("superseded")` at once
 * (its SharedMemory closed), and one that is running stops at its next model call with the same answer, so a late
 * page never delays the next dictation's OCR by more than one model call.
 *
 * Timing side channel for the benchmark (WP-14): every reply carries [ScreenText.stats] ([OcrStats]: detector input,
 * det ms, boxes, excluded, rec lines, rec ms, calls, total ms), and the same numbers are logged under [TAG].
 */
class OrtOcrHandler(private val config: OcrConfig = OcrConfig()) : OcrHandler {
    private val thread: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "murmur-ocr") }

    /** Touched only on the OCR thread. */
    private var loaded: Loaded? = null

    private class Loaded(val det: LoadSpec, val rec: LoadSpec, val models: OrtOcrModels, val pipeline: OcrPipeline)

    /** A failure with the error to send (no user data in it). */
    private class Fail(val error: MurmurError) : Exception(error.code)

    /**
     * One recognizeScreen request. Exactly one party answers it and closes its SharedMemory: whoever [claim]s it
     * first (the OCR thread that runs it, or the newer frame that supersedes it while it is still queued).
     */
    private class Frame(
        val pixels: SharedMemory,
        val width: Int,
        val height: Int,
        val excludeJson: String,
        val cb: IResultCallback,
    ) : OcrRunControl {
        private val owned = AtomicBoolean(false)
        @Volatile var superseded = false
        /** The model the run is in, for OutOfMemory(kind); the frame read belongs to the detector stage. */
        @Volatile var stage: ModelKind = ModelKind.OCR_DET

        fun claim() = owned.compareAndSet(false, true)
        override fun entering(stage: ModelKind) { this.stage = stage }
        override val cancelled get() = superseded
    }

    /** The newest frame not yet finished. */
    private val newest = AtomicReference<Frame?>(null)

    override fun load(detSpecJson: String, recSpecJson: String, cb: IResultCallback) {
        submit(cb, oomKind = { ModelKind.OCR_REC }, onRejected = { true }) {
            val t0 = SystemClock.elapsedRealtime()
            val det = spec(detSpecJson, RuntimeKeys.ORT_PPOCR_DET)
            val rec = spec(recSpecJson, RuntimeKeys.ORT_PPOCR_REC)
            val current = loaded
            if (current == null || current.det != det || current.rec != rec) {
                release()
                val models = try {
                    OrtOcrModels(det, rec, config)
                } catch (e: OrtOcrModels.LoadException) {
                    val error = if (e.cause is OutOfMemoryError) MurmurError.OutOfMemory(e.kind)
                    else MurmurError.EngineLoadFailed(e.kind, e.cause?.javaClass?.simpleName)
                    throw Fail(error)
                }
                val pipeline = try {
                    OcrPipeline(config, models)
                } catch (e: IllegalArgumentException) {
                    models.close()
                    throw Fail(MurmurError.EngineLoadFailed(ModelKind.OCR_REC, "dictionary_classes"))
                } catch (e: OutOfMemoryError) {
                    models.close()
                    throw e // OutOfMemory(OCR_REC): the pipeline holds the recogniser's dictionary
                }
                loaded = Loaded(det, rec, models, pipeline)
            }
            val ms = SystemClock.elapsedRealtime() - t0
            Log.i(TAG, "load ms=$ms")
            reply(cb, InferApi.encode(LoadInfo.serializer(), LoadInfo(ComputeUnit.CPU, ms, DEVICE_NAME)))
        }
    }

    override fun recognize(pixels: SharedMemory, width: Int, height: Int, excludeJson: String, cb: IResultCallback) {
        val frame = Frame(pixels, width, height, excludeJson, cb)
        newest.getAndSet(frame)?.let(::supersede)
        submit(cb, oomKind = { frame.stage }, onRejected = {
            newest.compareAndSet(frame, null)
            // Not claimable: a newer frame already answered and closed it.
            frame.claim().also { if (it) pixels.close() }
        }) {
            if (frame.claim()) {
                try {
                    recognize(frame)
                } finally {
                    newest.compareAndSet(frame, null)
                }
            }
        }
    }

    /** A newer frame arrived: [old] is answered now if still queued; if running, it stops at its next model call. */
    private fun supersede(old: Frame) {
        old.superseded = true
        if (old.claim()) {
            old.pixels.close()
            Log.i(TAG, "error ocr.failed superseded queued")
            error(old.cb, MurmurError.OcrFailed(SUPERSEDED))
        }
    }

    /** On the OCR thread, [frame] claimed. The SharedMemory is closed as soon as it is copied (before any answer). */
    private fun recognize(frame: Frame) {
        val (pipeline, image) = try {
            val pipeline = loaded?.pipeline ?: throw Fail(MurmurError.OcrFailed("not_loaded"))
            val exclude = try {
                InferApi.decode(ListSerializer(Rect.serializer()), frame.excludeJson)
            } catch (e: IllegalArgumentException) {
                throw Fail(MurmurError.OcrFailed("exclude_json"))
            }
            pipeline to ScreenImage(frame.width, frame.height, readPixels(frame.pixels, frame.width, frame.height), exclude)
        } finally {
            frame.pixels.close()
        }
        val out = try {
            pipeline.run(image, frame)
        } catch (e: OcrCancelledException) {
            throw Fail(MurmurError.OcrFailed(SUPERSEDED))
        }
        out.stats?.let { Log.i(TAG, timing(it, out.lines.size)) }
        reply(frame.cb, InferApi.encode(ScreenText.serializer(), out))
    }

    override fun unload() {
        try {
            thread.execute { release() }
        } catch (e: RejectedExecutionException) {
            // Already shut down: nothing is loaded.
        }
    }

    private fun release() {
        loaded?.models?.close()
        loaded = null
    }

    private fun spec(json: String, runtime: String): LoadSpec {
        val spec = try {
            InferApi.decode(LoadSpec.serializer(), json)
        } catch (e: IllegalArgumentException) {
            throw Fail(MurmurError.Internal("ocr_spec_json"))
        }
        if (spec.runtime != runtime) throw Fail(MurmurError.EngineRuntimeMissing(spec.runtime))
        if (spec.unit != ComputeUnit.CPU) throw Fail(MurmurError.UnitUnavailable(spec.unit))
        return spec
    }

    /** ARGB_8888 ints (0xAARRGGBB), little-endian, width*height of them (IAsrService.recognizeScreen). */
    private fun readPixels(pixels: SharedMemory, width: Int, height: Int): IntArray {
        if (width <= 0 || height <= 0 || width.toLong() * height > MAX_PIXELS) throw Fail(MurmurError.OcrFailed("frame_size"))
        val n = width * height
        if (pixels.size < n * 4L) throw Fail(MurmurError.OcrFailed("frame_size"))
        val buf = pixels.mapReadOnly()
        try {
            val argb = IntArray(n)
            buf.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(argb)
            return argb
        } finally {
            SharedMemory.unmap(buf)
        }
    }

    /**
     * Runs [work] on the OCR thread and turns its failures into MurmurError replies; [oomKind] names the model whose
     * stage ran out of memory. [onRejected] runs when the executor is shut down and returns whether [cb] is still to
     * be answered (shut_down).
     */
    private fun submit(cb: IResultCallback, oomKind: () -> ModelKind, onRejected: () -> Boolean, work: () -> Unit) {
        try {
            thread.execute {
                try {
                    work()
                } catch (f: Fail) {
                    Log.w(TAG, "error ${f.error.code} ${f.error.detail}")
                    error(cb, f.error)
                } catch (e: OutOfMemoryError) {
                    val kind = oomKind()
                    Log.w(TAG, "error oom ${kind.name}")
                    error(cb, MurmurError.OutOfMemory(kind))
                } catch (e: Exception) {
                    Log.w(TAG, "error ${e.javaClass.simpleName}")
                    error(cb, MurmurError.OcrFailed(e.javaClass.simpleName))
                }
            }
        } catch (e: RejectedExecutionException) {
            if (onRejected()) error(cb, MurmurError.OcrFailed("shut_down"))
        }
    }

    private fun reply(cb: IResultCallback, json: String) {
        try { cb.onResult(json) } catch (e: RemoteException) { Log.w(TAG, "client gone") }
    }

    private fun error(cb: IResultCallback, e: MurmurError) {
        try { cb.onError(InferApi.encodeError(e)) } catch (r: RemoteException) { Log.w(TAG, "client gone") }
    }

    companion object {
        const val TAG = "MurmurOcr"
        /** LoadInfo.deviceName: ORT's default CPU execution provider. */
        const val DEVICE_NAME = "CPU"
        /** OcrFailed detail for a frame that a newer one replaced. */
        const val SUPERSEDED = "superseded"
        /** Larger than any phone display (Fold7 inner 1968x2184 = 4.3 MP). */
        private const val MAX_PIXELS = 4096L * 4096L

        /** The benchmark's timing line: numbers only. */
        fun timing(s: OcrStats, lines: Int): String = String.format(
            Locale.ROOT,
            "run det_in=%dx%d det_ms=%.1f boxes=%d excluded=%d rec_lines=%d rec_ms=%.1f rec_ms_per_line=%.1f rec_calls=%d lines=%d total_ms=%.1f",
            s.detInput[0], s.detInput[1], s.detMs, s.boxes, s.excluded, s.recLines, s.recMs, s.recMsPerLine, s.recCalls, lines, s.totalMs,
        )
    }
}
