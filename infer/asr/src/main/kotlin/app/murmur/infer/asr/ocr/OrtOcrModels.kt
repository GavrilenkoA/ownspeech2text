package app.murmur.infer.asr.ocr

import app.murmur.core.engine.LoadSpec
import app.murmur.core.ocr.OcrModels

/** PP-OCR det/rec ONNX sessions on ORT 1.32 default CPU EP (no XNNPACK: segfaults, asr-ocr A7). WP-07. */
class OrtOcrModels(private val det: LoadSpec, private val rec: LoadSpec) : OcrModels {
    override fun detect(input: FloatArray, h: Int, w: Int): FloatArray = TODO("WP-07")
    override fun recognize(input: FloatArray, n: Int, bucketWidth: Int): FloatArray = TODO("WP-07")
    override val classes: Int get() = TODO("WP-07")
    override val dictionary: List<String> get() = TODO("WP-07")
}
