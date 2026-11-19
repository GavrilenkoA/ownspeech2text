package app.murmur.data.models

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.murmur.core.catalog.CatalogValidator
import app.murmur.core.catalog.CustomModelRequest
import app.murmur.core.catalog.ModelEntry
import app.murmur.core.catalog.RuntimeKeys
import app.murmur.core.model.ModelKind
import app.murmur.core.model.MurmurError
import app.murmur.core.model.Outcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** HfCustomModelResolver against the live Hugging Face API from the emulator (WP-13 acceptance, network on). */
@RunWith(AndroidJUnit4::class)
class CustomModelLiveTest {
    private val resolver = HfCustomModelResolver(UrlConnectionTransport(), AndroidDlLog)
    private fun ok(r: Outcome<ModelEntry>) = (r as? Outcome.Ok)?.value ?: error("expected Ok, got $r")

    @Test
    fun parakeetSherpaRepoWithoutRevisionGetsTheTransducerLayoutAndAPinnedSha(): Unit = runBlocking {
        val e = ok(resolver.resolve(CustomModelRequest(ModelKind.ASR, "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8")))
        val a = e.artifacts.single()
        assertEquals(RuntimeKeys.SHERPA_OFFLINE_TRANSDUCER, a.runtime)
        assertEquals(setOf("encoder", "decoder", "joiner", "tokens"), a.layout.keys)
        assertTrue(a.files.all { Regex("^[0-9a-f]{40}$").matches(it.revision) && Regex("^[0-9a-f]{64}$").matches(it.sha256) })
        assertEquals(emptyList<String>(), CatalogValidator.validateEntry(e))
        Log.i("MurmurModelsTest", "custom_asr id=${e.id} rev=${a.files.first().revision} files=${a.files.map { "${it.name}:${it.bytes}" }}")
    }

    @Test
    fun qwen35GgufRepoGetsQ40TemplateAndProfileFromTheHeaderOverRange(): Unit = runBlocking {
        val e = ok(resolver.resolve(CustomModelRequest(ModelKind.LLM, "unsloth/Qwen3.5-0.8B-GGUF")))
        val a = e.artifacts.single()
        assertEquals("q4_0", a.quant)
        assertTrue(a.files.single().path, a.files.single().path.endsWith("Q4_0.gguf"))
        assertEquals("qwen3.5", e.llm!!.template)
        assertEquals("default.v4", e.llm!!.promptProfile)
        assertEquals(12_288, e.llm!!.kvBytesPerToken)
        assertEquals(emptyList<String>(), CatalogValidator.validateEntry(e))
        Log.i("MurmurModelsTest", "custom_llm id=${e.id} rev=${a.files.single().revision} sha256=${a.files.single().sha256} headerBytes=${resolver.lastHeaderBytes}")
        assertTrue(resolver.lastHeaderBytes in 1..(16L shl 20))
    }

    @Test
    fun unsupportedArchitectureIsRejectedClearly(): Unit = runBlocking {
        val r = resolver.resolve(CustomModelRequest(ModelKind.LLM, "Qwen/Qwen2.5-0.5B-Instruct-GGUF"))
        val detail = ((r as Outcome.Err).error as MurmurError.CatalogInvalid).detail!!
        Log.i("MurmurModelsTest", "custom_rejected detail=$detail headerBytes=${resolver.lastHeaderBytes}")
        assertTrue(detail, detail.startsWith("unsupported GGUF architecture 'qwen2'"))
    }
}
