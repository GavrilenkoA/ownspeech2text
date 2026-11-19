package app.murmur.infer.llm

import app.murmur.core.catalog.RuntimeKeys
import app.murmur.core.engine.LlmRequest
import app.murmur.core.engine.LlmStats
import app.murmur.core.engine.LoadOptions
import app.murmur.core.engine.LoadSpec
import app.murmur.core.engine.PrefixSpec
import app.murmur.core.engine.ProcessProbe
import app.murmur.core.model.ArtifactId
import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.ModelId
import app.murmur.core.model.ModelKind
import app.murmur.core.model.MurmurError
import app.murmur.core.model.Outcome
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LlmEngineTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var dir: File
    private lateinit var fake: FakeLlmBackend
    private lateinit var engine: LlmEngine

    @Before fun setUp() {
        dir = tmp.newFolder("qwen3.5-0.8b@q4_0")
        File(dir, "model.gguf").writeText("gguf")
        fake = FakeLlmBackend()
        engine = LlmEngine(fake, "/data/app/x/lib/x86_64")
    }

    private fun spec(unit: ComputeUnit = ComputeUnit.CPU, options: Map<String, String> = mapOf(LoadOptions.N_CTX to "9216")) =
        LoadSpec(ModelKind.LLM, ModelId("qwen3.5-0.8b"), ArtifactId("qwen3.5-0.8b@q4_0"), RuntimeKeys.LLAMA_GGUF, unit,
            dir.path, mapOf("model" to "model.gguf"), options = options)

    private fun <T> ok(o: Outcome<T>): T = (o as? Outcome.Ok)?.value ?: throw AssertionError("expected Ok, got $o")
    private fun errOf(o: Outcome<*>): MurmurError = (o as? Outcome.Err)?.error ?: throw AssertionError("expected Err, got $o")

    private val prefixText = "<|im_start|>system\nПравила очистки диктовки…<|im_end|>\n<|im_start|>user\n"
    private val prefix = PrefixSpec(prefixText, "a".repeat(64))

    // ---------------------------------------------------------------- load

    @Test fun loadPassesCatalogContextAndCpuDefaults() {
        val info = ok(engine.load(spec()))
        assertEquals(ComputeUnit.CPU, info.unit)
        assertEquals("CPU", info.deviceName)
        val l = fake.lastLoad!!
        assertEquals(File(dir, "model.gguf").path, l["path"])
        assertEquals(9216, l["nCtx"])
        assertEquals(LlmLoadOptions.DEFAULT_THREADS, l["threads"])
        assertEquals(LlmLoadOptions.DEFAULT_N_BATCH, l["nBatch"])
        assertEquals(false, l["mmap"])                       // ADR-031
        assertEquals(LlmBackend.KV_F16, l["kv"])
        assertEquals(LlmBackend.DEV_CPU, l["device"])
        // init once, backends and DSP skel both from nativeLibraryDir (useLegacyPackaging), NPU power mode burst
        assertEquals(listOf("/data/app/x/lib/x86_64", "/data/app/x/lib/x86_64", "burst", 0), fake.initArgs)
        ok(engine.load(spec()))
        assertEquals(1, fake.calls.count { it == "init" })
        assertTrue("reload unloads the previous model first", "unload:7" in fake.calls)
    }

    @Test fun contextWindowIsNeverDefaulted() {
        val e = errOf(engine.load(spec(options = emptyMap())))
        assertEquals(MurmurError.EngineLoadFailed(ModelKind.LLM, "option n_ctx"), e)
        assertNull("nothing reached native load", fake.lastLoad)
        assertEquals(MurmurError.EngineLoadFailed(ModelKind.LLM, "option n_ctx"),
            errOf(engine.load(spec(options = mapOf(LoadOptions.N_CTX to "0")))))
    }

    @Test fun optionsAreParsedAndInvalidOnesRejected() {
        ok(engine.load(spec(options = mapOf(LoadOptions.N_CTX to "6144", LoadOptions.THREADS to "6", LoadOptions.N_BATCH to "256",
            LoadOptions.USE_MMAP to "1", LoadOptions.KV_TYPE to "q8_0", LoadOptions.POWER_MODE to "sustained_high_performance"))))
        val l = fake.lastLoad!!
        assertEquals(listOf(6144, 6, 256, true, LlmBackend.KV_Q8_0), listOf(l["nCtx"], l["threads"], l["nBatch"], l["mmap"], l["kv"]))
        assertEquals("sustained_high_performance", fake.initArgs!![2])
        for ((k, v) in listOf(LoadOptions.KV_TYPE to "q4_0", LoadOptions.USE_MMAP to "maybe", LoadOptions.THREADS to "-1",
            LoadOptions.POWER_MODE to "../x")) {
            assertEquals(MurmurError.EngineLoadFailed(ModelKind.LLM, "option $k"),
                errOf(engine.load(spec(options = mapOf(LoadOptions.N_CTX to "9216", k to v)))))
        }
    }

    @Test fun loadErrorsMapToTheUnitOrTheModel() {
        assertEquals(MurmurError.UnitUnavailable(ComputeUnit.NPU), errOf(engine.load(spec(ComputeUnit.NPU))))
        assertEquals(LlmBackend.DEV_NPU, fake.lastLoad!!["device"])
        fake.devices = "CPU|cpu|cpu;HTP0|Hexagon|other;"
        fake.loadRc = LlmBackend.E_LOAD                      // rpcmem / VTCM refused the weights
        assertEquals(MurmurError.UnitUnavailable(ComputeUnit.NPU), errOf(engine.load(spec(ComputeUnit.NPU))))
        assertEquals(MurmurError.UnitUnavailable(ComputeUnit.GPU), errOf(engine.load(spec(ComputeUnit.GPU))))
        assertEquals(MurmurError.EngineLoadFailed(ModelKind.LLM, "load rc=-2"), errOf(engine.load(spec(ComputeUnit.CPU))))
        assertFalse(engine.isLoaded)
    }

    @Test fun hostMemoryRefusedIsOutOfMemoryOnEveryUnitNotAUnitOrModelFailure() {
        fake.devices = "CPU|cpu|cpu;HTP0|Hexagon|other;GPUOpenCL|Adreno|gpu;"
        fake.loadRc = LlmBackend.E_NOMEM                     // murmur_llm saw a CPU buffer / bad_alloc failure
        for (unit in listOf(ComputeUnit.CPU, ComputeUnit.NPU, ComputeUnit.GPU)) {
            assertEquals("$unit", MurmurError.OutOfMemory(ModelKind.LLM), errOf(engine.load(spec(unit))))
        }
        assertFalse(engine.isLoaded)
        fake.loadRc = LlmBackend.E_CTX                       // a context failure without that signal stays as before
        assertEquals(MurmurError.EngineLoadFailed(ModelKind.LLM, "load rc=-4"), errOf(engine.load(spec(ComputeUnit.CPU))))
        assertEquals(MurmurError.UnitUnavailable(ComputeUnit.NPU), errOf(engine.load(spec(ComputeUnit.NPU))))
        fake.loadRc = LlmBackend.OK
        ok(engine.load(spec()))                              // a later load (memory freed) works
    }

    @Test fun missingRuntimeFilesAndForeignRuntimes() {
        assertEquals(MurmurError.EngineRuntimeMissing(RuntimeKeys.LLAMA_GGUF), errOf(LlmEngine(null, "/lib").load(spec())))
        assertEquals(MurmurError.EngineRuntimeMissing(RuntimeKeys.ORT_SILERO_VAD),
            errOf(engine.load(spec().copy(runtime = RuntimeKeys.ORT_SILERO_VAD))))
        File(dir, "model.gguf").delete()
        assertEquals(MurmurError.EngineLoadFailed(ModelKind.LLM, "model file missing"), errOf(engine.load(spec())))
        assertEquals(MurmurError.EngineLoadFailed(ModelKind.LLM, "layout"), errOf(engine.load(spec().copy(layout = emptyMap()))))
    }

    // ---------------------------------------------------------------- probe

    @Test fun probeReportsRegisteredUnitsAndBuildFacts() {
        fake.devices = "CPU|Intel CPU|cpu;HTP0|Hexagon|other;GPUOpenCL|Adreno 830|gpu;"
        fake.runtimeInfo = "cpu_backend=libggml-cpu-android_armv8.6_1.so;htp_arch=v79;"
        val p = engine.probe()
        assertEquals(setOf(RuntimeKeys.LLAMA_GGUF), p.runtimes)
        assertEquals(setOf(ComputeUnit.CPU, ComputeUnit.NPU, ComputeUnit.GPU), p.units)
        assertEquals("llama.cpp b12520 ff6b8d176186 ggml-hexagon cpu-x86_64", p.facts[ProcessProbe.FACT_NATIVE_BUILD])
        assertEquals("libggml-cpu-android_armv8.6_1.so", p.facts[LlmEngine.FACT_CPU_VARIANT])
        assertEquals("v79", p.facts[LlmEngine.FACT_HTP_ARCH])
        assertEquals("CPU,HTP0,GPUOpenCL", p.facts[LlmEngine.FACT_DEVICES])

        assertEquals(setOf(ComputeUnit.CPU), LlmEngine(FakeLlmBackend(), "/lib").probe().units)
        val missing = LlmEngine(null, "/lib").probe()
        assertTrue(missing.units.isEmpty() && missing.runtimes.isEmpty())
    }

    // ---------------------------------------------------------------- prefix cache

    @Test fun prefixIsPrefilledSavedAtomicallyThenRestoredInTheNextProcess() {
        ok(engine.load(spec()))
        val first = ok(engine.setPrefix(engine.nextTicket(), prefix))
        assertFalse(first.restoredFromDisk)
        assertEquals(fake.tokens(prefixText.encodeToByteArray()), first.tokens)
        val state = File(dir, "prefix-${prefix.cacheKey}.state")
        assertTrue(state.isFile)
        assertFalse("no temp file left", File(state.path + ".tmp").exists())
        assertEquals(listOf("prefixSet", "prefixSave"), fake.calls.filter { it.startsWith("prefix") })

        val fake2 = FakeLlmBackend()
        val next = LlmEngine(fake2, "/lib")                  // a new :llm process
        ok(next.load(spec()))
        val second = ok(next.setPrefix(next.nextTicket(), prefix))
        assertTrue(second.restoredFromDisk)
        assertEquals(first.tokens, second.tokens)
        assertEquals(listOf("prefixLoad"), fake2.calls.filter { it.startsWith("prefix") })
    }

    @Test fun aRestoreReportsTheWholeWaitNotOnlyTheNativeRestore() {
        ok(engine.load(spec()))
        ok(engine.setPrefix(engine.nextTicket(), prefix))
        var nowNs = 0L
        val fake2 = FakeLlmBackend().apply { duringPrefixLoad = { nowNs += 38_000_000 } }   // the whole native call
        val next = LlmEngine(fake2, "/lib", nanoTime = { nowNs })
        ok(next.load(spec()))
        val restored = ok(next.setPrefix(next.nextTicket(), prefix))
        assertTrue(restored.restoredFromDisk)
        assertEquals("the wall time of the restore, not the native stats (4 ms in the fake)", 38L, restored.prefillMs)
        assertFalse("no re-tokenisation of the prefix on a restore", "tokenCount" in fake2.calls)
    }

    @Test fun staleOrTruncatedStateIsDeletedAndRebuilt() {
        ok(engine.load(spec()))
        val state = File(dir, "prefix-${prefix.cacheKey}.state")
        state.writeText("")                                   // truncated: the native load fails with MLLM_E_STATE
        val info = ok(engine.setPrefix(engine.nextTicket(), prefix))
        assertFalse(info.restoredFromDisk)
        assertEquals(listOf("prefixLoad", "prefixSet", "prefixSave"), fake.calls.filter { it.startsWith("prefix") })
        assertEquals("${info.tokens}\n$prefixText", state.readText())

        fake.calls.clear()
        state.writeText("${info.tokens}\n${prefixText}x")    // a valid snapshot of another prefix text under this key
        assertFalse(ok(engine.setPrefix(engine.nextTicket(), prefix)).restoredFromDisk)
        assertEquals(listOf("prefixLoad", "prefixSet", "prefixSave"), fake.calls.filter { it.startsWith("prefix") })
        assertEquals("rebuilt for this prefix", "${info.tokens}\n$prefixText", state.readText())
    }

    @Test fun oldSnapshotsArePrunedAndUnsafeKeysRefused() {
        ok(engine.load(spec()))
        for (i in 0 until 5) File(dir, "prefix-old$i.state").apply { writeText("1"); setLastModified(1_000L * i) }
        ok(engine.setPrefix(engine.nextTicket(), prefix))
        val left = dir.list()!!.filter { it.startsWith("prefix-") }.sorted()
        assertEquals(listOf("prefix-${prefix.cacheKey}.state", "prefix-old3.state", "prefix-old4.state"), left)
        assertEquals(MurmurError.Internal("llm.prefix_key"), errOf(engine.setPrefix(engine.nextTicket(), PrefixSpec("x", "../evil"))))
    }

    @Test fun failedPrefixBlocksGenerationUntilANewOneSucceeds() {
        ok(engine.load(spec()))
        fake.prefixSetRc = LlmBackend.E_CANCELLED             // a cancel landed while the prefill ran
        assertEquals(MurmurError.LlmFailed("cancelled"), errOf(engine.setPrefix(engine.nextTicket(), prefix)))
        assertEquals(LlmEngine.Generation.Failed(MurmurError.LlmFailed("prefix")),
            engine.generate(engine.nextTicket(), LlmRequest("x", maxTokens = 8)) { true })
        assertFalse("generate" in fake.calls)
        fake.prefixSetRc = null
        ok(engine.setPrefix(engine.nextTicket(), prefix))
        assertTrue(engine.generate(engine.nextTicket(), LlmRequest("x", maxTokens = 8)) { true } is LlmEngine.Generation.Done)
    }

    @Test fun aCancelCoversAQueuedPrefixBuild() {
        ok(engine.load(spec()))
        val queued = engine.nextTicket()
        engine.cancel()
        assertEquals(MurmurError.LlmFailed("cancelled"), errOf(engine.setPrefix(queued, prefix)))
        assertFalse("prefixSet" in fake.calls)
    }

    // ---------------------------------------------------------------- generate

    @Test fun tooLittleRoomIsAContextOverflowWithoutDecoding() {
        ok(engine.load(spec(options = mapOf(LoadOptions.N_CTX to "1024"))))
        val p = ok(engine.setPrefix(engine.nextTicket(), PrefixSpec("p".repeat(4 * 900), "k1")))   // 900 tokens
        assertEquals(900, p.tokens)
        val suffix = "s".repeat(4 * 100)                                                          // 100 tokens
        val g = engine.generate(engine.nextTicket(), LlmRequest(suffix, maxTokens = 64, minTokens = 25)) { true }
        assertEquals(LlmEngine.Generation.Failed(MurmurError.LlmContextOverflow(900 + 100 + 25, 1024)), g)
        assertFalse("never decoded", "generate" in fake.calls)

        // room = 24: fits minTokens 24, max_tokens clamped from 64 to the room
        val ok = engine.generate(engine.nextTicket(), LlmRequest(suffix, maxTokens = 64, minTokens = 24)) { true }
        assertTrue(ok is LlmEngine.Generation.Done)
        assertEquals(24, fake.lastGenerate!!["maxTokens"])
        // zero room is an overflow even when the caller asked for no minimum
        val full = engine.generate(engine.nextTicket(), LlmRequest("s".repeat(4 * 124), maxTokens = 64)) { true }
        assertEquals(LlmEngine.Generation.Failed(MurmurError.LlmContextOverflow(900 + 124 + 1, 1024)), full)
    }

    @Test fun generateStreamsUtf8PiecesAndReportsStats() {
        ok(engine.load(spec()))
        ok(engine.setPrefix(engine.nextTicket(), prefix))
        fake.pieces = listOf("Спасибо".encodeToByteArray(), " 🙏".encodeToByteArray(), "!".encodeToByteArray())
        val suffix = "Style: casual\n<transcript>\nспасибо 🙏\n</transcript><|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"
        val streamed = mutableListOf<String>()
        val g = engine.generate(engine.nextTicket(), LlmRequest(suffix, maxTokens = 128, stop = listOf("\n\n\n"))) { streamed += it; true }
        g as LlmEngine.Generation.Done
        assertEquals("Спасибо 🙏!", g.text)
        assertEquals(listOf("Спасибо", " 🙏", "!"), streamed)
        // D11: real UTF-8 (4-byte emoji), not JNI modified UTF-8 (two 3-byte surrogates)
        assertArrayEquals(suffix.encodeToByteArray(), fake.lastSuffixBytes)
        assertTrue(fake.lastSuffixBytes!!.toList().windowed(4).any { it == listOf(0xF0, 0x9F, 0x99, 0x8F).map(Int::toByte) })
        assertEquals(listOf("\n\n\n", LlmEngine.IM_END), fake.lastGenerate!!["stops"])
        assertEquals(0f, fake.lastGenerate!!["temperature"])
        assertEquals(LlmStats(fake.tokens(suffix.encodeToByteArray()), 3, 3.0, 200.0, 250.0, 100.0, 400.0, LlmStats.STOP_EOG, ComputeUnit.CPU), g.stats)
    }

    @Test fun cancelBeforeTheEngineThreadStartsSkipsTheRequest() {
        ok(engine.load(spec()))
        val queued = engine.nextTicket()
        engine.cancel()                                       // arrives while the request waits in the executor
        val later = engine.nextTicket()
        val g = engine.generate(queued, LlmRequest("x", maxTokens = 8)) { true } as LlmEngine.Generation.Done
        assertEquals(LlmStats.STOP_CANCELLED, g.stats.stopReason)
        assertFalse("generate" in fake.calls)
        // a request that arrived after the cancel runs normally (the native flag is cleared first)
        val g2 = engine.generate(later, LlmRequest("x", maxTokens = 8)) { true } as LlmEngine.Generation.Done
        assertEquals(LlmStats.STOP_EOG, g2.stats.stopReason)
        assertTrue(fake.calls.indexOf("cancelClear") < fake.calls.indexOf("generate"))
    }

    @Test fun cancelDuringDecodeStopsWithReasonCancelledAndTheNextRequestWorks() {
        ok(engine.load(spec()))
        fake.pieces = List(10) { "a".encodeToByteArray() }
        fake.duringGenerate = { engine.cancel() }            // the binder thread cancels after the first piece
        val g = engine.generate(engine.nextTicket(), LlmRequest("x", maxTokens = 64)) { true } as LlmEngine.Generation.Done
        assertEquals(LlmStats.STOP_CANCELLED, g.stats.stopReason)
        assertEquals("a", g.text)
        assertTrue("cancel" in fake.calls)
        fake.duringGenerate = null
        val g2 = engine.generate(engine.nextTicket(), LlmRequest("x", maxTokens = 64)) { true } as LlmEngine.Generation.Done
        assertEquals(LlmStats.STOP_EOG, g2.stats.stopReason)
        assertEquals("a".repeat(10), g2.text)
    }

    @Test fun aGoneClientStopsTheDecodeAsCancelled() {
        ok(engine.load(spec()))
        fake.pieces = List(5) { "b".encodeToByteArray() }
        val g = engine.generate(engine.nextTicket(), LlmRequest("x", maxTokens = 64)) { false } as LlmEngine.Generation.Done
        assertEquals(LlmStats.STOP_CANCELLED, g.stats.stopReason)
        assertEquals(1, g.stats.generatedTokens)
    }

    @Test fun maxTokensStopIsReportedAndDecodeErrorsAreFailures() {
        ok(engine.load(spec()))
        fake.stopReason = LlmStats.STOP_MAX_TOKENS
        val g = engine.generate(engine.nextTicket(), LlmRequest("x", maxTokens = 2)) { true } as LlmEngine.Generation.Done
        assertEquals(LlmStats.STOP_MAX_TOKENS, g.stats.stopReason)
        fake.generateRc = LlmBackend.E_DECODE
        assertEquals(LlmEngine.Generation.Failed(MurmurError.LlmFailed("generate rc=-5")),
            engine.generate(engine.nextTicket(), LlmRequest("x", maxTokens = 2)) { true })
        assertEquals(LlmEngine.Generation.Failed(MurmurError.Internal("llm.max_tokens")),
            engine.generate(engine.nextTicket(), LlmRequest("x", maxTokens = 0)) { true })
    }

    @Test fun nothingWorksBeforeLoadAndCancelNeverTouchesAnUnloadedHandle() {
        assertEquals(LlmEngine.Generation.Failed(MurmurError.Internal("llm.not_loaded")),
            engine.generate(engine.nextTicket(), LlmRequest("x", maxTokens = 8)) { true })
        assertEquals(MurmurError.Internal("llm.not_loaded"), errOf(engine.setPrefix(engine.nextTicket(), prefix)))
        ok(engine.load(spec()))
        engine.unload()
        assertFalse(engine.isLoaded)
        engine.cancel()
        assertFalse("cancel after unload must not reach native", "cancel" in fake.calls)
        engine.unload()                                       // idempotent
        assertEquals(1, fake.calls.count { it.startsWith("unload") })
    }

    @Test fun benchValidatesArgumentsAgainstTheContext() {
        ok(engine.load(spec(options = mapOf(LoadOptions.N_CTX to "1024"))))
        assertEquals(MurmurError.Internal("llm.bench_args"), errOf(engine.bench(engine.nextTicket(), 1000, 64, 1)))
        val b = ok(engine.bench(engine.nextTicket(), 256, 32, 1))
        assertEquals(300.0, b.ppTps, 0.0)
        assertEquals(30.0, b.tgTps, 0.0)
    }

    @Test fun aCancelledBenchIsLlmFailedCancelledAndTheNextOneRuns() {
        ok(engine.load(spec(options = mapOf(LoadOptions.N_CTX to "1024"))))
        fake.duringBench = { engine.cancel() }               // cancel while the native bench runs (pp or tg phase)
        assertEquals(MurmurError.LlmFailed("cancelled"), errOf(engine.bench(engine.nextTicket(), 256, 32, 1)))
        fake.duringBench = null
        ok(engine.bench(engine.nextTicket(), 256, 32, 1))   // a later request clears the flag
        val queued = engine.nextTicket()
        engine.cancel()                                        // covers a bench still waiting for the engine thread
        val before = fake.calls.count { it == "bench" }
        assertEquals(MurmurError.LlmFailed("cancelled"), errOf(engine.bench(queued, 256, 32, 1)))
        assertEquals("never reached native", before, fake.calls.count { it == "bench" })
    }
}
