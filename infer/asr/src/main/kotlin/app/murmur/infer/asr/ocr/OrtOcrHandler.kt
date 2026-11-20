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
import app.murmur.core.ocr.OcrConfig
import app.murmur.core.ocr.OcrPipeline
import app.murmur.core.ocr.OcrRunControl
import app.murmur.core.ocr.OcrStats
import app.murmur.core.ocr.Rect
import app.murmur.core.ocr.ScreenImage
import app.murmur.core.ocr.ScreenText
import app.murmur.infer.api.IResultCallback
import app.murmur.infer.api.InferApi
import app.murmur.infer.asr.qnnep.QnnEp
import app.murmur.infer.asr.qnnep.QnnEpException
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
 * (its SharedMemory closed), and one that is running stops inside its model call (RunOptions terminate, WP-21) with
 * the same answer, so a late page never delays the next dictation's OCR by more than the step it is in.
 *
 * Timing side channel for the benchmark (WP-14): every reply carries [ScreenText.stats] ([OcrStats]: detector input,
 * det ms, boxes, excluded, rec lines, rec ms, calls, total ms), and the same numbers are logged under [TAG].
 */
class OrtOcrHandler(
    private val config: OcrConfig = OcrConfig(),
    /** The process's QNN plugin EP, asked only when a stage is an ort-qnn artifact (WP-21). */
    private val qnn: () -> QnnEp = { throw QnnEpException("no-libs") },
) : OcrHandler {
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
    ) : OcrRunControl, OcrFrameRunner.Frame {
        private val owned = AtomicBoolean(false)
        @Volatile override var superseded = false
            private set
        /** The model the run is in, for OutOfMemory(kind); the frame read belongs to the detector stage. */
        @Volatile var stage: ModelKind = ModelKind.OCR_DET

        override fun markSuperseded() { superseded = true }
        override fun claim() = owned.compareAndSet(false, true)
        override fun entering(stage: ModelKind) { this.stage = stage }
        override val cancelled get() = superseded
    }

    /** The newest frame not yet finished. */
    private val newest = AtomicReference<Frame?>(null)

    /** Stops a superseded frame inside its model call (WP-21). */
    private val runner = OcrFrameRunner()

    override fun load(detSpecJson: String, recSpecJson: String, cb: IResultCallback) {
        submit(cb, oomKind = { ModelKind.OCR_REC }, onRejected = { true }) {
            val t0 = SystemClock.elapsedRealtime()
            val det = spec(detSpecJson, RuntimeKeys.ORT_PPOCR_DET, RuntimeKeys.ORT_QNN_PPOCR_DET)
            val rec = spec(recSpecJson, RuntimeKeys.ORT_PPOCR_REC, RuntimeKeys.ORT_QNN_PPOCR_REC)
            val current = loaded
            if (current == null || current.det != det || current.rec != rec) {
                release()
                val models = try {
                    OrtOcrModels(det, rec, config, qnn)
                } catch (e: OrtOcrModels.LoadException) {
                    throw Fail(loadError(e))
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
            reply(cb, InferApi.encode(LoadInfo.serializer(), loadInfo(det.unit, rec.unit, ms)))
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

    /**
     * A newer frame arrived: [old] is answered now if still queued; if running, its model call in flight is terminated
     * and any later call starts terminated ([OrtOcrModels.stopRequested]), so it answers without finishing a call.
     */
    private fun supersede(old: Frame) {
        if (runner.supersede(old)) {
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
        val current = loaded ?: throw Fail(MurmurError.OcrFailed("not_loaded"))
        val detCpu0 = current.models.detectorCpuCalls
        val out = try {
            runner.run(current.models, frame) { pipeline.run(image, frame) }
        } catch (e: OcrFrameRunner.Superseded) {
            throw Fail(MurmurError.OcrFailed(SUPERSEDED))
        }
        // the unit the detector really ran on: an NPU detector runs a shape without a context on the CPU
        val detUnit = if (current.models.detectorCpuCalls > detCpu0) ComputeUnit.CPU else current.det.unit
        out.stats?.let { Log.i(TAG, timing(it, out.lines.size, detUnit)) }
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

    /** [cpu] runs on the CPU unit only, [npu] (the QNN EP artifact) on the NPU unit only. */
    private fun spec(json: String, cpu: String, npu: String): LoadSpec {
        val spec = try {
            InferApi.decode(LoadSpec.serializer(), json)
        } catch (e: IllegalArgumentException) {
            throw Fail(MurmurError.Internal("ocr_spec_json"))
        }
        val unit = when (spec.runtime) {
            cpu -> ComputeUnit.CPU
            npu -> ComputeUnit.NPU
            else -> throw Fail(MurmurError.EngineRuntimeMissing(spec.runtime))
        }
        if (spec.unit != unit) throw Fail(MurmurError.UnitUnavailable(spec.unit))
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
        /** LoadInfo.deviceName when both stages run on Qualcomm's QNN EP (HTP); "QNN+CPU" when only one does. */
        const val DEVICE_NAME_QNN = "QNN"

        /** LoadInfo of a pair: NPU when either stage is on the HTP (the pair is benchmarked as the NPU artifact's run). */
        fun loadInfo(det: ComputeUnit, rec: ComputeUnit, ms: Long): LoadInfo {
            val npu = listOf(det, rec).count { it == ComputeUnit.NPU }
            return when (npu) {
                0 -> LoadInfo(ComputeUnit.CPU, ms, DEVICE_NAME)
                2 -> LoadInfo(ComputeUnit.NPU, ms, DEVICE_NAME_QNN)
                else -> LoadInfo(ComputeUnit.NPU, ms, "$DEVICE_NAME_QNN+$DEVICE_NAME")
            }
        }

        /**
         * A failed load: OutOfMemory by stage; no QNN EP in this process (plugin or backend not packaged, the plugin
         * refused, no EP device) -> UnitUnavailable(NPU); anything else (a context that would not open) ->
         * EngineLoadFailed(kind) with a code. Both NPU errors judge the unit (LoadFailures.judgesUnit).
         */
        fun loadError(e: OrtOcrModels.LoadException): MurmurError {
            val cause = e.cause
            return when {
                cause is OutOfMemoryError -> MurmurError.OutOfMemory(e.kind)
                cause is QnnEpException && cause.stage in QNN_EP_MISSING -> MurmurError.UnitUnavailable(ComputeUnit.NPU)
                cause is QnnEpException -> MurmurError.EngineLoadFailed(e.kind, "qnn:${cause.stage}")
                else -> MurmurError.EngineLoadFailed(e.kind, cause?.javaClass?.simpleName)
            }
        }

        private val QNN_EP_MISSING = setOf("no-libs", "register", "no-device")
        /** OcrFailed detail for a frame that a newer one replaced. */
        const val SUPERSEDED = "superseded"
        /** Larger than any phone display (Fold7 inner 1968x2184 = 4.3 MP). */
        private const val MAX_PIXELS = 4096L * 4096L

        /**
         * The benchmark's timing line: numbers and codes only. [detUnit] is the unit the detector call ran on for this
         * frame (CPU when an NPU detector had no context for the window shape), so NPU numbers can be told apart.
         */
        fun timing(s: OcrStats, lines: Int, detUnit: ComputeUnit = ComputeUnit.CPU): String = String.format(
            Locale.ROOT,
            "run det_in=%dx%d det_ms=%.1f boxes=%d excluded=%d rec_lines=%d rec_ms=%.1f rec_ms_per_line=%.1f rec_calls=%d lines=%d total_ms=%.1f det_unit=%s",
            s.detInput[0], s.detInput[1], s.detMs, s.boxes, s.excluded, s.recLines, s.recMs, s.recMsPerLine, s.recCalls, lines, s.totalMs,
            detUnit.name.lowercase(Locale.ROOT),
        )
    }
}
