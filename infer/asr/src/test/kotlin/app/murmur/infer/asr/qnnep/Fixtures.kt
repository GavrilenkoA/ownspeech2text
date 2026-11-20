package app.murmur.infer.asr.qnnep

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream

/** Paths the unit tests read in place (system properties set by infer/asr/build.gradle.kts). */
object Fixtures {
    val repoRoot = File(System.getProperty("murmur.repoRoot") ?: "../..")
    val modelsDir = File(System.getProperty("murmur.modelsDir") ?: File(repoRoot, ".cache/models").path)

    /** native/qnn-contexts/fixtures/fleurs-ru: 12 FLEURS dev ru clips + the Python front end's features. */
    val fleurs = File(repoRoot, "native/qnn-contexts/fixtures/fleurs-ru")

    class Clip(val id: String, val wav: File, val frames: Int, val offsetFloats: Int, val ref: String)

    val clips: List<Clip> by lazy {
        val meta = Json.parseToJsonElement(File(fleurs, "mel_ref.json").readText()).jsonObject
        meta.getValue("clips").jsonArray.map { it.jsonObject }.map { c: JsonObject ->
            Clip(
                c.getValue("id").jsonPrimitive.content, File(fleurs, c.getValue("wav").jsonPrimitive.content),
                c.getValue("frames").jsonPrimitive.int, c.getValue("offset_floats").jsonPrimitive.int,
                c.getValue("ref").jsonPrimitive.content,
            )
        }
    }

    /** mel_ref.f32.gz: every clip's [64, frames] float32 LE, concatenated. */
    val melRef: FloatArray by lazy {
        val bytes = GZIPInputStream(File(fleurs, "mel_ref.f32.gz").inputStream()).use { it.readBytes() }
        val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        FloatArray(fb.remaining()).also { fb.get(it) }
    }

    /** PCM16 mono 16 kHz WAV -> samples / 32768 (what AsrSessionCore feeds the decoders). */
    fun readWav(f: File): FloatArray {
        DataInputStream(f.inputStream().buffered()).use { s ->
            val all = s.readBytes()
            val b = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN)
            require(String(all, 0, 4, Charsets.US_ASCII) == "RIFF" && String(all, 8, 4, Charsets.US_ASCII) == "WAVE") { "not a WAV" }
            var p = 12
            var format = false
            while (p + 8 <= all.size) {
                val id = String(all, p, 4, Charsets.US_ASCII)
                val len = b.getInt(p + 4)
                if (id == "fmt ") {
                    require(b.getShort(p + 8).toInt() == 1 && b.getShort(p + 10).toInt() == 1 && b.getInt(p + 12) == 16_000 &&
                        b.getShort(p + 22).toInt() == 16) { "need PCM16 mono 16 kHz" }
                    format = true
                }
                if (id == "data") {
                    require(format) { "data before fmt" }
                    val n = len / 2
                    return FloatArray(n) { b.getShort(p + 8 + 2 * it) / 32768f }
                }
                p += 8 + len + (len and 1)
            }
            error("no data chunk")
        }
    }
}
