package app.murmur.data.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GgufHeaderTest {
    @Test
    fun readsScalarsSkipsTokenArraysAndKeepsSmallNumericArrays() {
        val bytes = GgufWriter.qwen35(profile = "finetuned.v1").f32("x.f", 1.5f).i32Array("lfm3.attention.head_count_kv", listOf(0, 4, 0, 4)).build()
        val m = GgufHeader.parse(bytes)
        assertTrue(m.complete)
        assertEquals("qwen35", m.string("general.architecture"))
        assertEquals("finetuned.v1", m.string("murmur.prompt_profile"))
        assertEquals(24L, m.long("qwen35.block_count"))
        assertEquals(listOf(0L, 4L, 0L, 4L), m.longs("lfm3.attention.head_count_kv"))
        assertNull(m.values["tokenizer.ggml.tokens"])
        assertEquals(bytes.size, m.bytesUsed)
    }

    @Test
    fun truncatedPrefixIsIncompleteButKeepsWhatCameBefore() {
        val bytes = GgufWriter.qwen35(vocab = 50_000, profile = "default.v4").build()
        val m = GgufHeader.parse(bytes.copyOf(bytes.size / 2))
        assertFalse(m.complete)
        assertEquals("qwen35", m.string("general.architecture"))
        assertNull(m.string("murmur.prompt_profile"))
    }

    @Test
    fun kvBytesPerTokenMatchesTheCatalogForQwen35AndCountsLfmAttentionLayers() {
        assertEquals(12_288, GgufHeader.kvBytesPerToken(GgufHeader.parse(GgufWriter.qwen35().build())))
        // LFM3 1.7B dims (cached GGUF): 24 blocks, KV heads 4 on 11 attention layers, head dim 128. From the header alone
        // this is an upper bound: the bundled catalog's 12,288 comes from a measured snapshot (llm-npu.md #35).
        val lfm3 = GgufWriter().string("general.architecture", "lfm3").u32("lfm3.block_count", 24).u32("lfm3.embedding_length", 2048)
            .u32("lfm3.attention.head_count", 16).u32("lfm3.attention.key_length", 128).u32("lfm3.attention.value_length", 128)
            .i32Array("lfm3.attention.head_count_kv", List(24) { if (it >= 2 && it % 2 == 0) 4 else 0 }).build()
        assertEquals(11 * 4 * 256 * 2, GgufHeader.kvBytesPerToken(GgufHeader.parse(lfm3)))
        // Qwen3 0.6B (full attention): 28 layers x 8 KV heads x (128 + 128) x 2 B = 114,688 (catalog value).
        val qwen3 = GgufWriter().string("general.architecture", "qwen3").u32("qwen3.block_count", 28).u32("qwen3.embedding_length", 1024)
            .u32("qwen3.attention.head_count", 16).u32("qwen3.attention.head_count_kv", 8).u32("qwen3.attention.key_length", 128)
            .u32("qwen3.attention.value_length", 128).build()
        assertEquals(114_688, GgufHeader.kvBytesPerToken(GgufHeader.parse(qwen3)))
        assertNull(GgufHeader.kvBytesPerToken(GgufHeader.parse(GgufWriter().string("general.architecture", "x").build())))
    }

    @Test(expected = GgufHeader.NotGguf::class)
    fun notAGgufFile() {
        GgufHeader.parse("PK\u0003\u0004 not a gguf file at all, a zip".encodeToByteArray())
    }
}
