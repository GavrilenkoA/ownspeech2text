package app.murmur.infer.llm

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.murmur.core.catalog.Artifact
import app.murmur.core.catalog.CatalogParser
import app.murmur.core.catalog.LlmTraits
import app.murmur.core.catalog.ModelEntry
import app.murmur.core.catalog.RuntimeKeys
import app.murmur.core.engine.LlmRequest
import app.murmur.core.engine.LlmStats
import app.murmur.core.engine.LoadInfo
import app.murmur.core.engine.LoadOptions
import app.murmur.core.engine.LoadSpec
import app.murmur.core.engine.PrefixCacheKey
import app.murmur.core.engine.PrefixInfo
import app.murmur.core.engine.PrefixSpec
import app.murmur.core.engine.ProcessMemory
import app.murmur.core.engine.ProcessProbe
import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.ModelKind
import app.murmur.core.model.MurmurError
import app.murmur.infer.api.ILlmService
import app.murmur.infer.api.ILlmStreamCallback
import app.murmur.infer.api.IResultCallback
import app.murmur.infer.api.InferApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * WP-10 acceptance on the x86_64 emulator (started with -qemu -cpu host, ADR-038): binds the real [LlmService] in its
 * own `:llm` process and drives it over [ILlmService] with a real GGUF.
 *
 * Before running, push the model into this test APK's storage (it is debuggable, so run-as works):
 *   tools/models/fetch qwen3.5-0.8b@q4_0
 *   adb shell run-as app.murmur.infer.llm.test mkdir -p no_backup/models/qwen3.5-0.8b@q4_0
 *   adb shell "cat /data/local/tmp/model.gguf | run-as app.murmur.infer.llm.test sh -c 'cat > no_backup/models/qwen3.5-0.8b@q4_0/model.gguf'"
 * Instrumentation arguments: `artifact` (default qwen3.5-0.8b@q4_0; also lfm3-1.7b@q4_0-qad), `timeoutScale`
 * (default 1; LFM3's 3.2k-token prefix prefill takes minutes on the emulator, like Settings.debugTimeoutScale).
 * Methods run in name order: each step relies on the state file of the earlier ones.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class LlmServiceTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx: Context = instr.targetContext
    private val args: Bundle = InstrumentationRegistry.getArguments()
    private val artifactId = args.getString("artifact") ?: "qwen3.5-0.8b@q4_0"
    private val scale = args.getString("timeoutScale")?.toDouble() ?: 1.0
    private val catalog = CatalogParser.bundled()
    private val entry: ModelEntry = catalog.models.first { m -> m.artifacts.any { it.id.value == artifactId } }
    private val artifact: Artifact = entry.artifacts.first { it.id.value == artifactId }
    private val llm: LlmTraits = requireNotNull(entry.llm)
    /** Catalog llm.prefixTokens: render.cache_prefix token count, pinned by native/llama/tools/tokenizer_check.py. */
    private val prefixTokens: Int = requireNotNull(llm.prefixTokens)
    private val modelDir = File(ctx.noBackupFilesDir, "models/$artifactId")
    private val assets = ctx.assets
    private val prefixText = asset("prefix.${llm.promptProfile}.${llm.template}.txt")
    /** Built like :main does (ADR-060): model sha, prefix sha, unit, the probe's native.build, n_ctx. */
    private fun prefixKey(s: ILlmService): String = Shared.key ?: run {
        val build = InferApi.decode(ProcessProbe.serializer(), s.probe()).facts.getValue(ProcessProbe.FACT_NATIVE_BUILD)
        PrefixCacheKey.of(artifact.files.first().sha256, PrefixCacheKey.sha256Hex(prefixText), ComputeUnit.CPU, build, llm.nCtx)
    }.also { Shared.key = it }
    private val stateFile: File get() = File(modelDir, "prefix-${Shared.key ?: withService { prefixKey(it) }}.state")
    private val processName = ctx.packageName + ":llm"

    private fun asset(name: String) = assets.open("prompts/$name").use { it.readBytes().decodeToString() }
    private fun suffix(case: String) = asset("suffix.$case.${llm.template}.txt")
    private fun ms(base: Long) = (base * scale).toLong()

    private fun spec(unit: ComputeUnit = ComputeUnit.CPU) = LoadSpec(
        kind = ModelKind.LLM, modelId = entry.id, artifactId = artifact.id, runtime = RuntimeKeys.LLAMA_GGUF, unit = unit,
        dir = modelDir.path, layout = artifact.layout,
        options = mapOf(LoadOptions.N_CTX to llm.nCtx.toString(), LoadOptions.THREADS to "4", LoadOptions.USE_MMAP to "0"),
    )

    // ------------------------------------------------------------------ steps

    @Test fun t01_probeReportsTheBuildAndOnlyTheCpuOnTheEmulator() = withService { s ->
        val p = InferApi.decode(ProcessProbe.serializer(), s.probe())
        report("probe", p.toString())
        assertEquals(setOf(RuntimeKeys.LLAMA_GGUF), p.runtimes)
        assertEquals(setOf(ComputeUnit.CPU), p.units)
        assertTrue(p.facts[ProcessProbe.FACT_NATIVE_BUILD]!!.startsWith("llama.cpp b12520 ff6b8d176186 ggml-hexagon "))
        assertTrue("CPU variant reported: ${p.facts}", p.facts[LlmEngine.FACT_CPU_VARIANT]!!.startsWith("libggml-cpu-"))
        report("cpu_variant", p.facts[LlmEngine.FACT_CPU_VARIANT]!!)
    }

    @Test fun t02_npuOnTheEmulatorIsUnitUnavailable() = withService { s ->
        assertTrue("model pushed to $modelDir", File(modelDir, "model.gguf").isFile)
        assertEquals(MurmurError.UnitUnavailable(ComputeUnit.NPU), call(LoadInfo.serializer(), ms(60_000)) { s.load(json(spec(ComputeUnit.NPU)), it) }.error)
        // the process survives and still serves the CPU
        assertEquals("CPU", load(s).deviceName)
    }

    @Test fun t03_coldPrefixIsPrefilledSavedAndMatchesTheUncachedOutput() = withService { s ->
        modelDir.listFiles { f -> f.name.startsWith("prefix-") }?.forEach { it.delete() }
        val info = load(s)
        assertEquals("CPU", info.deviceName)
        report("load_ms", info.loadMs.toString())
        // baseline without any cache: the whole prompt (prefix + suffix) prefilled in one request
        val uncached = generate(s, LlmRequest(prefixText + suffix("ctxv2_chat_ru"), maxTokens = 256), ms(600_000))
        report("uncached_ttft_ms", uncached.stats.ttftMs.toString())

        val t0 = SystemClock.elapsedRealtime()
        val p = prefix(s)
        report("prefix_prefill_ms", "${p.prefillMs} (wall ${SystemClock.elapsedRealtime() - t0})")
        assertFalse(p.restoredFromDisk)
        assertEquals("prefix tokens = catalog prefixTokens", prefixTokens, p.tokens)
        assertTrue("state file written: $stateFile", stateFile.isFile && stateFile.length() > 0)
        report("state_bytes", stateFile.length().toString())

        val cached = generate(s, LlmRequest(suffix("ctxv2_chat_ru"), maxTokens = 256))
        assertEquals("prefix snapshot + suffix == whole prompt", uncached.text, cached.text)
        Shared.reference = cached.text
        val mem = InferApi.decode(ProcessMemory.serializer(), s.memory())
        report("peak_rss_mb", (mem.vmHwmKb / 1024).toString())
        report("memory", mem.toString())
        assertTrue(mem.vmHwmKb > 100_000)
    }

    @Test fun t04_aSecondProcessRestoresThePrefixFromDiskInUnder100ms() {
        val firstPid = withService { pidOf() }
        val secondPid = withService { s ->
            load(s)
            val p = prefix(s)
            // the restore is one sequential read of the state file plus a copy into the KV / recurrent buffers; on a
            // loaded emulator the read alone can exceed 100 ms, so it is measured next to the restore
            val readMs = rawReadMs(stateFile)
            report("prefix_restore_ms", "${p.prefillMs} (raw read of ${stateFile.length()} B: $readMs ms)")
            assertTrue(p.restoredFromDisk)
            assertEquals(prefixTokens, p.tokens)
            assertTrue("restore ${p.prefillMs} ms, raw read $readMs ms", p.prefillMs < 100 || p.prefillMs < 1.5 * readMs)
            val out = generate(s, LlmRequest(suffix("ctxv2_chat_ru"), maxTokens = 256))
            assertEquals("same output as without cache", Shared.reference, out.text)
            pidOf()
        }
        assertNotEquals("a new :llm process", firstPid, secondPid)
    }

    @Test fun t05_bothCtxV2ExamplesGiveRussianTextWithoutThinking() = withService { s ->
        load(s); prefix(s)
        for (case in listOf("ctxv2_chat_ru", "ctxv2_mail_mixed")) {
            val g = generate(s, LlmRequest(suffix(case), maxTokens = 256))
            val st = g.stats
            val tps = if (st.generatedTokens > 1 && st.decodeMs > 0) 1000.0 * (st.generatedTokens - 1) / st.decodeMs else 0.0
            report("gen.$case", "ttft_ms=${"%.0f".format(st.ttftMs)} decode_tps=${"%.1f".format(tps)} prompt=${st.promptTokens} " +
                "gen=${st.generatedTokens} stop=${st.stopReason} text=${g.text}")
            assertTrue("non-empty", g.text.isNotBlank())
            assertTrue("Cyrillic: ${g.text}", g.text.any { it in '\u0400'..'\u04FF' })
            assertFalse("valid UTF-8 pieces: ${g.text}", g.text.contains(REPLACEMENT))
            assertFalse("thinking off: ${g.text}", g.text.contains("<think>") || g.pieces.first().trimStart().startsWith("<think"))
            assertEquals(LlmStats.STOP_EOG, st.stopReason)
            assertEquals(ComputeUnit.CPU, st.unit)
            assertTrue(st.ttftMs > 0 && tps > 0)
        }
    }

    @Test fun t06_anEmojiSurvivesTheUtf8RoundTrip() = withService { s ->
        load(s); prefix(s)
        val sfx = suffix("emoji")
        val g = generate(s, LlmRequest(sfx, maxTokens = 256))
        report("gen.emoji", "prompt=${g.stats.promptTokens} text=${g.text}")
        // input half: the device tokenises the UTF-8 bytes exactly like the host llama-tokenize (modified UTF-8 from a
        // jstring would turn each emoji into two 3-byte surrogates and change the count)
        assertEquals(hostTokens("suffix.emoji.${llm.template}.txt"), g.stats.promptTokens)
        assertFalse("no replacement characters: ${g.text}", g.text.contains(REPLACEMENT))
    }

    @Test fun t06b_anEmojiComesBackOutOfTheModel() = withService { s ->
        // output half: the cleanup prompt drops emoji, so ask the bare model to echo one (no prefix: the request is
        // the whole prompt) and check that the streamed pieces decode back to the same code points
        load(s)
        val ask = "Repeat exactly, nothing else: ok $THUMBS"
        val prompt = when (llm.template) {
            "lfm3" -> "<|startoftext|><|im_start|>user\n$ask<|im_end|>\n<|im_start|>assistant\n<think></think>"
            else -> "<|im_start|>user\n$ask<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"
        }
        val g = generate(s, LlmRequest(prompt, maxTokens = 16))
        report("gen.echo", g.text)
        assertFalse("no replacement characters: ${g.text}", g.text.contains(REPLACEMENT))
        assertTrue("emoji back: ${g.text}", g.text.contains(THUMBS))
    }

    @Test fun t07_tooLittleRoomIsAContextOverflowInUnderOneSecond() = withService { s ->
        load(s); prefix(s)
        val sfx = suffix("ctxv2_chat_ru")
        val t0 = SystemClock.elapsedRealtime()
        val r = stream(s, LlmRequest(sfx, maxTokens = 256, minTokens = llm.nCtx), ms(10_000))
        val took = SystemClock.elapsedRealtime() - t0
        report("overflow_ms", took.toString())
        val e = r.error as MurmurError.LlmContextOverflow
        assertEquals(llm.nCtx, e.nCtx)
        assertEquals(prefixTokens + hostTokens("suffix.ctxv2_chat_ru.${llm.template}.txt") + llm.nCtx, e.neededTokens)
        assertTrue("no piece streamed", r.pieces.isEmpty())
        assertTrue("answered in $took ms (no decode)", took < 1_000)
    }

    @Test fun t08_cancelDuringDecodeReturnsWithin200msAndTheNextRequestWorks() = withService { s ->
        load(s); prefix(s)
        val req = LlmRequest(suffix("ctxv2_mail_mixed"), maxTokens = 256)
        val events = LinkedBlockingQueue<Event>()
        s.generate(json(req), streamCallback(events))
        assertTrue("first piece", events.poll(ms(120_000), TimeUnit.MILLISECONDS) is Event.Piece)
        val t0 = SystemClock.elapsedRealtime()
        s.cancel()
        var e: Event?
        do { e = events.poll(ms(5_000), TimeUnit.MILLISECONDS) } while (e is Event.Piece)
        val took = SystemClock.elapsedRealtime() - t0
        report("cancel_ms", took.toString())
        val done = e as? Event.Done ?: throw AssertionError("expected onDone after cancel, got $e")
        assertEquals(LlmStats.STOP_CANCELLED, done.stats.stopReason)
        assertTrue("cancel took $took ms", took < 200)
        val next = generate(s, req)
        assertEquals(LlmStats.STOP_EOG, next.stats.stopReason)
        assertTrue(next.text.isNotBlank())
    }

    @Test fun t09_aTruncatedStateFileIsDeletedAndRebuilt() {
        assertTrue(stateFile.isFile)
        val full = stateFile.length()
        RandomAccessFile(stateFile, "rw").use { it.setLength(full / 2) }
        withService { s ->
            load(s)
            val p = prefix(s)
            assertFalse("rebuilt, not restored", p.restoredFromDisk)
            assertEquals(prefixTokens, p.tokens)
            assertEquals("state file rewritten", full, stateFile.length())
            assertEquals(LlmStats.STOP_EOG, generate(s, LlmRequest(suffix("ctxv2_chat_ru"), maxTokens = 256)).stats.stopReason)
        }
        withService { s -> load(s); assertTrue(prefix(s).restoredFromDisk) }
    }

    @Test fun t10_unbindEndsTheProcessWithinTwoSeconds() {
        val pid = withService { s -> load(s); pidOf() }       // withService unbinds at the end
        val t0 = SystemClock.elapsedRealtime()
        while (pidOf() != null && SystemClock.elapsedRealtime() - t0 < 2_000) SystemClock.sleep(50)
        val gone = SystemClock.elapsedRealtime() - t0
        report("exit_after_unbind_ms", gone.toString())
        assertTrue("pid $pid", pid != null)
        assertEquals("pidof $processName after unbind", null, pidOf())
    }

    // ------------------------------------------------------------------ helpers

    private object Shared {
        var key: String? = null
        var reference: String? = null
    }

    private fun load(s: ILlmService): LoadInfo {
        val r = call(LoadInfo.serializer(), ms(120_000)) { s.load(json(spec()), it) }
        return r.value ?: throw AssertionError("load failed: ${r.error}")
    }

    private fun prefix(s: ILlmService): PrefixInfo {
        val timeout = ms(maxOf(60_000L, (2.0 * prefixTokens / 20.0 * 1000).toLong()))
        val r = call(PrefixInfo.serializer(), timeout) { s.setPrefix(json(PrefixSpec(prefixText, prefixKey(s))), it) }
        return r.value ?: throw AssertionError("setPrefix failed: ${r.error}")
    }

    private class Generated(val text: String, val pieces: List<String>, val stats: LlmStats)
    private class Streamed(val pieces: List<String>, val text: String?, val stats: LlmStats?, val error: MurmurError?)

    private sealed interface Event {
        data class Piece(val text: String) : Event
        data class Done(val text: String, val stats: LlmStats) : Event
        data class Failed(val error: MurmurError) : Event
    }

    private fun generate(s: ILlmService, req: LlmRequest, timeoutMs: Long = ms(300_000)): Generated {
        val r = stream(s, req, timeoutMs)
        val stats = r.stats ?: throw AssertionError("generate failed: ${r.error}")
        assertEquals("streamed pieces add up to the final text", r.text, r.pieces.joinToString(""))
        return Generated(r.text!!, r.pieces, stats)
    }

    private fun stream(s: ILlmService, req: LlmRequest, timeoutMs: Long): Streamed {
        val events = LinkedBlockingQueue<Event>()
        s.generate(json(req), streamCallback(events))
        val pieces = mutableListOf<String>()
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            val left = maxOf(deadline - SystemClock.elapsedRealtime(), 0)
            when (val e = events.poll(left, TimeUnit.MILLISECONDS) ?: throw AssertionError("generate timed out after $timeoutMs ms")) {
                is Event.Piece -> pieces += e.text
                is Event.Done -> return Streamed(pieces, e.text, e.stats, null)
                is Event.Failed -> return Streamed(pieces, null, null, e.error)
            }
        }
    }

    private fun streamCallback(events: LinkedBlockingQueue<Event>) = object : ILlmStreamCallback.Stub() {
        override fun onPiece(text: String) { events.put(Event.Piece(text)) }
        override fun onDone(text: String, statsJson: String) { events.put(Event.Done(text, InferApi.decode(LlmStats.serializer(), statsJson))) }
        override fun onError(errorJson: String) { events.put(Event.Failed(InferApi.decodeError(errorJson))) }
    }

    private class Result<T>(val value: T?, val error: MurmurError?)

    private fun <T> call(ser: KSerializer<T>, timeoutMs: Long, start: (IResultCallback) -> Unit): Result<T> {
        val latch = CountDownLatch(1)
        var result: Result<T>? = null
        start(object : IResultCallback.Stub() {
            override fun onResult(json: String) { result = Result(InferApi.decode(ser, json), null); latch.countDown() }
            override fun onError(errorJson: String) { result = Result(null, InferApi.decodeError(errorJson)); latch.countDown() }
        })
        assertTrue("callback within $timeoutMs ms", latch.await(timeoutMs, TimeUnit.MILLISECONDS))
        return result!!
    }

    private fun <T> json(v: T): String = when (v) {
        is LoadSpec -> InferApi.encode(LoadSpec.serializer(), v)
        is PrefixSpec -> InferApi.encode(PrefixSpec.serializer(), v)
        is LlmRequest -> InferApi.encode(LlmRequest.serializer(), v)
        else -> error("no serializer for $v")
    }

    /** Binds :llm, runs [block], unbinds and waits until the process is gone (so the next step starts cold). */
    private fun <R> withService(block: (ILlmService) -> R): R {
        val bound = LinkedBlockingQueue<IBinder>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder) { bound.put(service) }
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        val intent = Intent().setClassName(ctx.packageName, InferApi.LLM_SERVICE)
        assertTrue(ctx.bindService(intent, conn, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT))
        try {
            val binder = bound.poll(10_000, TimeUnit.MILLISECONDS) ?: throw AssertionError("bind timed out")
            val s = ILlmService.Stub.asInterface(binder)
            assertEquals(InferApi.API_VERSION, s.apiVersion())
            return block(s)
        } finally {
            ctx.unbindService(conn)
            val t0 = SystemClock.elapsedRealtime()
            while (pidOf() != null && SystemClock.elapsedRealtime() - t0 < 5_000) SystemClock.sleep(50)
        }
    }

    private fun pidOf(): String? {
        val pfd = instr.uiAutomation.executeShellCommand("pidof $processName")
        val out = android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().decodeToString().trim() }
        return out.ifEmpty { null }
    }

    private fun rawReadMs(f: File): Long = (0 until 2).minOf {
        val t0 = SystemClock.elapsedRealtime()
        f.inputStream().use { input -> val buf = ByteArray(1 shl 20); while (input.read(buf) > 0) Unit }
        SystemClock.elapsedRealtime() - t0
    }

    private fun hostTokens(file: String): Int =
        Json.parseToJsonElement(assets.open("prompts/tokens.json").use { it.readBytes().decodeToString() })
            .jsonObject[file]!!.jsonPrimitive.int

    private fun report(key: String, value: String) {
        Log.i(TAG, "[$artifactId] $key: $value")
        instr.sendStatus(0, Bundle().apply { putString("$artifactId.$key", value) })
    }

    companion object {
        private const val TAG = "LlmServiceTest"
        private const val REPLACEMENT = '\uFFFD'
        private const val THUMBS = "\uD83D\uDC4D"
    }
}
