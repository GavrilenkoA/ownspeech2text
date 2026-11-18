package app.murmur.data.models

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Writes a GGUF v3 header (no tensors) with the given key/values, for parser and resolver tests. */
class GgufWriter {
    private val kvs = mutableListOf<(ByteArrayOutputStream) -> Unit>()

    private fun le(n: Int) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
    private fun ByteArrayOutputStream.u32(v: Int) = write(le(4).putInt(v).array())
    private fun ByteArrayOutputStream.u64(v: Long) = write(le(8).putLong(v).array())
    private fun ByteArrayOutputStream.str(s: String) { val b = s.encodeToByteArray(); u64(b.size.toLong()); write(b) }

    fun string(key: String, value: String) = apply { kvs += { o -> o.str(key); o.u32(8); o.str(value) } }
    fun u32(key: String, value: Int) = apply { kvs += { o -> o.str(key); o.u32(4); o.u32(value) } }
    fun f32(key: String, value: Float) = apply { kvs += { o -> o.str(key); o.u32(6); o.write(le(4).putFloat(value).array()) } }
    fun i32Array(key: String, values: List<Int>) = apply { kvs += { o -> o.str(key); o.u32(9); o.u32(5); o.u64(values.size.toLong()); values.forEach { o.u32(it) } } }
    fun stringArray(key: String, count: Int, item: (Int) -> String) = apply {
        kvs += { o -> o.str(key); o.u32(9); o.u32(8); o.u64(count.toLong()); for (i in 0 until count) o.str(item(i)) }
    }

    fun build(padTo: Int = 0): ByteArray {
        val o = ByteArrayOutputStream()
        o.u32(GgufHeader.MAGIC); o.u32(3); o.u64(0); o.u64(kvs.size.toLong())
        kvs.forEach { it(o) }
        while (o.size() < padTo) o.write(0)
        return o.toByteArray()
    }

    companion object {
        /** Qwen3.5 0.8B shape (verified from the cached unsloth GGUF): hybrid, full attention every 4th layer. */
        fun qwen35(name: String = "Qwen3.5-0.8B", profile: String? = null, vocab: Int = 1_000) = GgufWriter()
            .string("general.architecture", "qwen35")
            .string("general.name", name)
            .u32("qwen35.block_count", 24)
            .u32("qwen35.embedding_length", 1024)
            .u32("qwen35.attention.head_count", 8)
            .u32("qwen35.attention.head_count_kv", 2)
            .u32("qwen35.attention.key_length", 256)
            .u32("qwen35.attention.value_length", 256)
            .u32("qwen35.full_attention_interval", 4)
            .stringArray("tokenizer.ggml.tokens", vocab) { "tok$it" }
            .apply { if (profile != null) string("murmur.prompt_profile", profile) }
    }
}
