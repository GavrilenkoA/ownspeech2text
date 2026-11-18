package app.murmur.testing

import app.murmur.core.audio.PcmChunk
import app.murmur.core.audio.PcmFormat
import app.murmur.core.engine.AsrEngine
import app.murmur.core.engine.AsrEvent
import app.murmur.core.engine.AsrSession
import app.murmur.core.engine.AsrSessionOptions
import app.murmur.core.engine.Engines
import app.murmur.core.engine.HostState
import app.murmur.core.engine.InferenceHost
import app.murmur.core.engine.LlmEngine
import app.murmur.core.engine.LlmEvent
import app.murmur.core.engine.LlmRequest
import app.murmur.core.engine.LlmStats
import app.murmur.core.engine.LoadInfo
import app.murmur.core.engine.LoadSpec
import app.murmur.core.engine.OcrService
import app.murmur.core.engine.PrefixInfo
import app.murmur.core.engine.PrefixSpec
import app.murmur.core.engine.ProcessMemory
import app.murmur.core.engine.ProcessProbe
import app.murmur.core.engine.Transcript
import app.murmur.core.model.ComputeUnit
import app.murmur.core.model.InferProcess
import app.murmur.core.model.MurmurError
import app.murmur.core.model.Outcome
import app.murmur.core.ocr.OcrLine
import app.murmur.core.ocr.ScreenImage
import app.murmur.core.ocr.ScreenText
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow

/**
 * Scripted ASR: the transcript is [script] (or the next item of [scripts] per session). Speech is "present"
 * when any sample has |x| >= [speechAbs]; with no speech, finish() returns Err(NoSpeech).
 */
class FakeAsrEngine(
    var script: String = "привет это тестовая диктовка",
    val scripts: ArrayDeque<String> = ArrayDeque(),
    var loadError: MurmurError? = null,
    var finishError: MurmurError? = null,
    private val speechAbs: Int = 500,
) : AsrEngine {
    var loads = 0; private set
    var loaded: LoadSpec? = null; private set
    val sessions = mutableListOf<FakeAsrSession>()

    override suspend fun load(asr: LoadSpec, vad: LoadSpec?): Outcome<LoadInfo> {
        loads++
        loadError?.let { return Outcome.Err(it) }
        loaded = asr
        return Outcome.Ok(LoadInfo(asr.unit, loadMs = 5, deviceName = "fake-${asr.unit.name.lowercase()}"))
    }

    override fun startSession(options: AsrSessionOptions): AsrSession =
        FakeAsrSession(scripts.removeFirstOrNull() ?: script, finishError, speechAbs, loaded?.unit ?: ComputeUnit.CPU)
            .also { sessions += it }

    override suspend fun unload() { loaded = null }
}

class FakeAsrSession(
    private val text: String,
    private val finishError: MurmurError?,
    private val speechAbs: Int,
    private val unit: ComputeUnit,
) : AsrSession {
    private val _events = MutableSharedFlow<AsrEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: Flow<AsrEvent> = _events.asSharedFlow()
    var samples = 0L; private set
    var speechSamples = 0L; private set
    var cancelled = false; private set

    override fun accept(chunk: PcmChunk) {
        samples += chunk.samples.size
        if (chunk.samples.any { kotlin.math.abs(it.toInt()) >= speechAbs }) speechSamples += chunk.samples.size
    }

    override suspend fun finish(): Outcome<Transcript> {
        finishError?.let { return Outcome.Err(it) }
        val audioMs = PcmFormat.samplesToMs(samples)
        val speechMs = PcmFormat.samplesToMs(speechSamples)
        if (speechMs < 500) return Outcome.Err(MurmurError.NoSpeech)
        _events.tryEmit(AsrEvent.Committed(0, text, 0, audioMs, "ru"))
        return Outcome.Ok(Transcript(text, "ru", 1, audioMs, speechMs, tailDecodeMs = 10, unit = unit))
    }

    override fun cancel() { cancelled = true }
}

/** Deterministic "cleanup": collapse spaces, capitalise the first letter, end with a period. */
class FakeLlmEngine(var loadError: MurmurError? = null, var output: ((String) -> String)? = null) : LlmEngine {
    var prefix: PrefixSpec? = null; private set
    var requests = mutableListOf<LlmRequest>()
    var cancelled = 0; private set

    override suspend fun load(spec: LoadSpec): Outcome<LoadInfo> =
        loadError?.let { Outcome.Err(it) } ?: Outcome.Ok(LoadInfo(spec.unit, 5, "fake-llm"))

    override suspend fun setPrefix(prefix: PrefixSpec): Outcome<PrefixInfo> {
        this.prefix = prefix
        return Outcome.Ok(PrefixInfo(prefix.text.length / 4, 1, restoredFromDisk = false, bytes = 0))
    }

    override fun generate(request: LlmRequest): Flow<LlmEvent> = flow {
        requests += request
        val transcript = request.suffix.substringAfter("<transcript>\n", request.suffix).substringBefore("\n</transcript>")
        val out = output?.invoke(transcript) ?: cleanup(transcript)
        emit(LlmEvent.Piece(out))
        emit(LlmEvent.Done(out, LlmStats(request.suffix.length / 4, out.length / 4, 0.1, 1.0, 2.0, 3.0, 5.0, 0, ComputeUnit.CPU)))
    }

    override fun cancel() { cancelled++ }
    override suspend fun unload() { prefix = null }
    override suspend fun bench(nPrompt: Int, nGen: Int, reps: Int): Outcome<app.murmur.core.engine.SyntheticBench> =
        Outcome.Ok(app.murmur.core.engine.SyntheticBench(ppTps = 500.0, tgTps = 50.0, ppMs = nPrompt * 2.0, tgMs = nGen * 20.0))

    companion object {
        fun cleanup(t: String): String {
            val s = t.trim().replace(Regex("\\s+"), " ")
            if (s.isEmpty()) return s
            val c = s.replaceFirstChar { it.uppercase() }
            return if (c.last() in ".!?") c else "$c."
        }
    }
}

class FakeOcrService(var lines: List<OcrLine> = listOf(OcrLine("Лёша Козлов", 0.97, listOf(156, 212, 498, 268)))) : OcrService {
    var calls = 0; private set
    override suspend fun loadOcr(det: LoadSpec, rec: LoadSpec): Outcome<LoadInfo> = Outcome.Ok(LoadInfo(ComputeUnit.CPU, 1, "fake-ocr"))
    override suspend fun recognize(image: ScreenImage): Outcome<ScreenText> {
        calls++
        return Outcome.Ok(ScreenText(listOf(image.width, image.height), lines))
    }
    override suspend fun unloadOcr() {}
}

class FakeInferenceHost(override val process: InferProcess, private val units: Set<ComputeUnit> = setOf(ComputeUnit.CPU)) : InferenceHost {
    private val _state = MutableStateFlow<HostState>(HostState.Down)
    override val state: StateFlow<HostState> = _state.asStateFlow()
    var idleExitMs: Long? = null; private set
    var shutdowns = 0; private set

    override suspend fun connect(): Boolean { _state.value = HostState.Up(4242); return true }
    override suspend fun probe() = ProcessProbe(process, app.murmur.core.catalog.RuntimeKeys.known, units)
    override suspend fun memory() = ProcessMemory(100_000, 90_000, 80_000, 4_000)
    override fun scheduleIdleExit(ms: Long) { idleExitMs = ms }
    override suspend fun shutdown() { shutdowns++; _state.value = HostState.Down }
    /** Simulate a native crash of the process. */
    fun crash() { _state.value = HostState.Crashed(1) }
}

class FakeEngines(
    override val asr: FakeAsrEngine = FakeAsrEngine(),
    override val llm: FakeLlmEngine = FakeLlmEngine(),
    override val ocr: FakeOcrService = FakeOcrService(),
    override val asrHost: FakeInferenceHost = FakeInferenceHost(InferProcess.ASR),
    override val llmHost: FakeInferenceHost = FakeInferenceHost(InferProcess.LLM),
) : Engines
