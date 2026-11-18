package app.murmur.infer.api

import android.content.Context
import app.murmur.core.engine.AsrEngine
import app.murmur.core.engine.AsrSession
import app.murmur.core.engine.AsrSessionOptions
import app.murmur.core.engine.Engines
import app.murmur.core.engine.HostState
import app.murmur.core.engine.InferenceHost
import app.murmur.core.engine.LlmEngine
import app.murmur.core.engine.LlmEvent
import app.murmur.core.engine.LlmRequest
import app.murmur.core.engine.LoadInfo
import app.murmur.core.engine.LoadSpec
import app.murmur.core.engine.OcrService
import app.murmur.core.engine.PrefixInfo
import app.murmur.core.engine.PrefixSpec
import app.murmur.core.engine.ProcessMemory
import app.murmur.core.engine.ProcessProbe
import app.murmur.core.model.InferProcess
import app.murmur.core.model.Outcome
import app.murmur.core.ocr.ScreenImage
import app.murmur.core.ocr.ScreenText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * :main-side implementations of the :core:engine contracts over binder (WP-05). PCM goes through a
 * ParcelFileDescriptor pipe written by the capture thread; screenshots through SharedMemory.
 */
class AsrClient(context: Context, timeouts: BinderTimeouts = BinderTimeouts()) : AsrEngine, OcrService, InferenceHost {
    private val connector = ServiceConnector(context, InferProcess.ASR, timeouts)
    override val process = InferProcess.ASR
    override val state: StateFlow<HostState> get() = connector.state

    override suspend fun connect(): Boolean = connector.connect() != null
    override suspend fun probe(): ProcessProbe = TODO("WP-05")
    override suspend fun memory(): ProcessMemory = TODO("WP-05")
    override fun scheduleIdleExit(ms: Long) { TODO("WP-05") }
    override suspend fun shutdown() { TODO("WP-05") }

    override suspend fun load(asr: LoadSpec, vad: LoadSpec?): Outcome<LoadInfo> = TODO("WP-05")
    override fun startSession(options: AsrSessionOptions): AsrSession = TODO("WP-05: createPipe(), startSession(), writer")
    override suspend fun unload() { TODO("WP-05") }

    override suspend fun loadOcr(det: LoadSpec, rec: LoadSpec): Outcome<LoadInfo> = TODO("WP-05")
    override suspend fun unloadOcr() { TODO("WP-05") }
    override suspend fun recognize(image: ScreenImage): Outcome<ScreenText> = TODO("WP-05: SharedMemory.create + mapReadWrite + setProtect(PROT_READ)")
}

class LlmClient(context: Context, timeouts: BinderTimeouts = BinderTimeouts()) : LlmEngine, InferenceHost {
    private val connector = ServiceConnector(context, InferProcess.LLM, timeouts)
    override val process = InferProcess.LLM
    override val state: StateFlow<HostState> get() = connector.state

    override suspend fun connect(): Boolean = connector.connect() != null
    override suspend fun probe(): ProcessProbe = TODO("WP-05")
    override suspend fun memory(): ProcessMemory = TODO("WP-05")
    override fun scheduleIdleExit(ms: Long) { TODO("WP-05") }
    override suspend fun shutdown() { TODO("WP-05") }

    override suspend fun load(spec: LoadSpec): Outcome<LoadInfo> = TODO("WP-05")
    override suspend fun setPrefix(prefix: PrefixSpec): Outcome<PrefixInfo> = TODO("WP-05")
    override fun generate(request: LlmRequest): Flow<LlmEvent> = TODO("WP-05: callbackFlow over ILlmStreamCallback")
    override fun cancel() { TODO("WP-05") }
    override suspend fun unload() { TODO("WP-05") }
    override suspend fun bench(nPrompt: Int, nGen: Int, reps: Int): Outcome<app.murmur.core.engine.SyntheticBench> = TODO("WP-05")
}

/** The real [Engines] of the :main process. */
class BinderEngines(context: Context) : Engines {
    private val asrClient = AsrClient(context)
    private val llmClient = LlmClient(context)
    override val asr: AsrEngine get() = asrClient
    override val ocr: OcrService get() = asrClient
    override val asrHost: InferenceHost get() = asrClient
    override val llm: LlmEngine get() = llmClient
    override val llmHost: InferenceHost get() = llmClient
}
