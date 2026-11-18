package app.murmur.data.models

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads the metadata key/values of a GGUF file (v2/v3) from a prefix of its bytes: what a custom model needs
 * (`general.architecture`, `general.name`, `murmur.prompt_profile`, attention shape for the KV estimate) is in the
 * header, before the tensor data. String arrays (the tokenizer vocabulary, megabytes) are skipped, numeric arrays of
 * up to [MAX_NUMERIC_ARRAY] items are kept (per-layer KV head counts of hybrid models).
 *
 * [Meta.complete] = every key/value was inside the prefix; when false, keys after the cut are unknown (the caller can
 * fetch more bytes and parse again).
 */
object GgufHeader {
    const val MAGIC = 0x46554747 // "GGUF" little-endian
    const val MAX_NUMERIC_ARRAY = 4_096

    data class Meta(val version: Int, val kvCount: Long, val values: Map<String, Any>, val complete: Boolean, val bytesUsed: Int) {
        fun string(key: String): String? = values[key] as? String
        fun long(key: String): Long? = (values[key] as? Number)?.toLong()
        @Suppress("UNCHECKED_CAST")
        fun longs(key: String): List<Long>? = (values[key] as? List<Number>)?.map { it.toLong() }
    }

    class NotGguf(msg: String) : Exception(msg)

    fun parse(bytes: ByteArray, length: Int = bytes.size): Meta {
        val b = ByteBuffer.wrap(bytes, 0, length).order(ByteOrder.LITTLE_ENDIAN)
        if (length < 24 || b.getInt(0) != MAGIC) throw NotGguf("bad magic")
        b.position(4)
        val version = b.int
        if (version !in 2..3) throw NotGguf("unsupported version $version")
        b.long // tensor count
        val kvCount = b.long
        if (kvCount < 0 || kvCount > 100_000) throw NotGguf("implausible key count")
        val values = LinkedHashMap<String, Any>()
        var lastGood = b.position()
        try {
            for (i in 0 until kvCount) {
                val key = str(b)
                val type = b.int
                val value = value(b, type)
                if (value != null) values[key] = value
                lastGood = b.position()
            }
        } catch (e: java.nio.BufferUnderflowException) {
            return Meta(version, kvCount, values, complete = false, bytesUsed = lastGood)
        }
        return Meta(version, kvCount, values, complete = true, bytesUsed = lastGood)
    }

    private fun str(b: ByteBuffer): String {
        val n = b.long
        if (n < 0 || n > 1L shl 24) throw NotGguf("implausible string length")
        if (n > b.remaining()) throw java.nio.BufferUnderflowException()
        val arr = ByteArray(n.toInt())
        b.get(arr)
        return arr.decodeToString()
    }

    /** Value of [type]; null when it is skipped (string arrays, long arrays). */
    private fun value(b: ByteBuffer, type: Int): Any? = when (type) {
        0 -> b.get().toLong() and 0xff
        1 -> b.get().toLong()
        2 -> b.short.toLong() and 0xffff
        3 -> b.short.toLong()
        4 -> b.int.toLong() and 0xffffffffL
        5 -> b.int.toLong()
        6 -> b.float.toDouble()
        7 -> b.get().toInt() != 0
        8 -> str(b)
        9 -> array(b)
        10, 11 -> b.long
        12 -> b.double
        else -> throw NotGguf("unknown value type $type")
    }

    private fun array(b: ByteBuffer): Any? {
        val itemType = b.int
        val n = b.long
        if (n < 0 || n > 1L shl 32) throw NotGguf("implausible array length")
        val fixed = when (itemType) { 0, 1, 7 -> 1; 2, 3 -> 2; 4, 5, 6 -> 4; 10, 11, 12 -> 8; else -> 0 }
        if (fixed > 0 && n > MAX_NUMERIC_ARRAY) {
            val skip = n * fixed
            if (skip > b.remaining()) throw java.nio.BufferUnderflowException()
            b.position(b.position() + skip.toInt())
            return null
        }
        if (itemType == 8) {
            for (i in 0 until n) {
                val len = b.long
                if (len < 0 || len > 1L shl 24) throw NotGguf("implausible string length")
                if (len > b.remaining()) throw java.nio.BufferUnderflowException()
                b.position(b.position() + len.toInt())
            }
            return null
        }
        if (itemType == 9) throw NotGguf("nested arrays")
        return List(n.toInt()) { value(b, itemType)!! }
    }

    /**
     * F16 KV cache bytes per token from the attention shape: sum over layers of kv_heads x (key_length + value_length)
     * x 2 bytes. Per-layer head counts (hybrid models list 0 for recurrent layers) are summed as given. null when the
     * header lacks the keys (MemoryEstimate then assumes 12 KiB/token).
     */
    fun kvBytesPerToken(m: Meta): Int? {
        val arch = m.string("general.architecture") ?: return null
        val layers = m.long("$arch.block_count") ?: return null
        val heads = m.long("$arch.attention.head_count") ?: m.longs("$arch.attention.head_count")?.maxOrNull() ?: return null
        val embd = m.long("$arch.embedding_length") ?: return null
        if (heads <= 0 || layers <= 0) return null
        val k = m.long("$arch.attention.key_length") ?: (embd / heads)
        val v = m.long("$arch.attention.value_length") ?: (embd / heads)
        // Hybrids: LFM2/LFM3 list 0 KV heads for their convolution layers; Qwen3.5 has full attention every
        // `full_attention_interval` layers (DeltaNet in between, constant-size state).
        val attnLayers = m.long("$arch.full_attention_interval")?.takeIf { it > 0 }?.let { layers / it } ?: layers
        val kvHeadsTotal = m.longs("$arch.attention.head_count_kv")?.sum()
            ?: m.long("$arch.attention.head_count_kv")?.let { it * attnLayers }
            ?: (heads * attnLayers)
        val bytes = kvHeadsTotal * (k + v) * 2
        return bytes.takeIf { it in 1..Int.MAX_VALUE }?.toInt()
    }
}
