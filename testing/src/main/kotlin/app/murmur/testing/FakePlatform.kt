package app.murmur.testing

import app.murmur.core.audio.PcmChunk
import app.murmur.core.audio.PcmFormat
import app.murmur.core.context.A11ySnapshot
import app.murmur.core.context.FieldSnapshot
import app.murmur.core.dictation.AudioCapture
import app.murmur.core.dictation.CaptureHandle
import app.murmur.core.dictation.CaptureResult
import app.murmur.core.dictation.CaptureSignal
import app.murmur.core.dictation.InAppTarget
import app.murmur.core.dictation.Feedback
import app.murmur.core.dictation.HapticKind
import app.murmur.core.dictation.InsertResult
import app.murmur.core.dictation.Inserter
import app.murmur.core.dictation.MemoryGate
import app.murmur.core.dictation.SystemConditions
import app.murmur.core.dictation.TargetReader
import app.murmur.core.model.MurmurError
import app.murmur.core.model.Outcome
import app.murmur.core.model.SessionId
import app.murmur.core.ocr.ScreenImage
import app.murmur.core.store.AudioStore
import app.murmur.core.store.AudioWriter
import app.murmur.core.store.InsertMethod
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow

/** A tone burst of [speechMs] at amplitude 3000 surrounded by silence: enough for the fake ASR "speech" check. */
fun syntheticSpeech(totalMs: Long = 3_000, speechMs: Long = 2_000): ShortArray {
    val n = PcmFormat.msToSamples(totalMs).toInt()
    val s0 = PcmFormat.msToSamples((totalMs - speechMs) / 2).toInt()
    val s1 = s0 + PcmFormat.msToSamples(speechMs).toInt()
    return ShortArray(n) { i -> if (i in s0 until s1) (3000 * kotlin.math.sin(i * 2 * Math.PI * 220 / 16000)).toInt().toShort() else 0 }
}

/**
 * Plays [pcm] as 20 ms chunks, paced in coroutine time when [realTime] (virtual time in runTest: a 3 s clip takes 3 s,
 * so stops, calls and crashes land mid-recording), else all at once. [startError] simulates a busy/blocked
 * microphone and [startDelayMs] a slow FGS + first buffer; [signal] injects a mid-recording CaptureSignal into the
 * running handle; [audioKeys] / [keepFlags] record the start arguments. With [store] and keepRecording, the samples
 * handed out are written to `store.newWriter(audioKey)` like the real encoder (stop() closes it, cancel() discards it).
 * The chunk flow is meant for one collector (the orchestrator) and completes after stop() or at the end of [pcm].
 */
class FakeAudioCapture(
    var pcm: ShortArray = syntheticSpeech(),
    var startError: MurmurError? = null,
    var store: AudioStore? = null,
    var startDelayMs: Long = 0,
    var realTime: Boolean = true,
) : AudioCapture {
    var starts = 0; private set
    val audioKeys = mutableListOf<String>()
    val keepFlags = mutableListOf<Boolean>()
    var current: Handle? = null; private set

    override suspend fun start(session: SessionId, keepRecording: Boolean, audioKey: String): Outcome<CaptureHandle> {
        starts++
        audioKeys += audioKey
        keepFlags += keepRecording
        if (startDelayMs > 0) delay(startDelayMs)
        startError?.let { return Outcome.Err(it) }
        val writer = if (keepRecording) store?.newWriter(audioKey) else null
        return Outcome.Ok(Handle(pcm, writer, realTime).also { current = it })
    }

    fun signal(s: CaptureSignal) { current?.signalsFlow?.tryEmit(s) }

    class Handle(private val pcm: ShortArray, private val writer: AudioWriter? = null, private val realTime: Boolean = true) : CaptureHandle {
        var cancelled = false; private set
        var stopped = false; private set
        /** Samples handed to the collector so far ("the audio so far"). */
        var emittedSamples = 0L; private set

        override val chunks: Flow<PcmChunk> = flow {
            var i = 0
            while (i < pcm.size && !stopped && !cancelled) {
                if (realTime) delay(PcmFormat.samplesToMs(PcmFormat.FRAME_SAMPLES.toLong()))
                if (stopped || cancelled) break
                val end = minOf(i + PcmFormat.FRAME_SAMPLES, pcm.size)
                val chunk = PcmChunk(pcm.copyOfRange(i, end), i.toLong())
                writer?.write(chunk.samples, chunk.samples.size)
                emittedSamples = end.toLong()
                emit(chunk)
                i = end
            }
        }
        override val levels: StateFlow<FloatArray> = MutableStateFlow(FloatArray(7))
        internal val signalsFlow = MutableSharedFlow<CaptureSignal>(extraBufferCapacity = 8)
        override val signals: Flow<CaptureSignal> = signalsFlow

        override suspend fun stop(): CaptureResult {
            stopped = true
            val n = if (realTime) emittedSamples.toInt() else pcm.size
            val audio = writer?.close()
            return CaptureResult(PcmFormat.samplesToMs(n.toLong()), audio, (0 until n).maxOfOrNull { kotlin.math.abs(pcm[it].toInt()) } ?: 0, false)
        }

        override fun cancel() {
            cancelled = true
            writer?.discard()
        }
    }
}

/**
 * [shadeDelayMs] = how long the notification shade takes to collapse (snapshotAfterShade waits min(that, timeout);
 * past the timeout it returns null like the real reader without an application window). [currentOverride] = the
 * focus at delivery (password field, other app).
 */
class FakeTargetReader(
    var target: A11ySnapshot? = defaultTarget(),
    var screenshotError: MurmurError? = null,
    var currentOverride: A11ySnapshot? = null,
    var shadeDelayMs: Long = 0,
) : TargetReader {
    var screenshots = 0; private set
    var shadeWaits = 0; private set
    var snapshots = 0; private set
    var lastShadeTimeoutMs: Long? = null; private set
    override suspend fun snapshot(): A11ySnapshot? { snapshots++; return target }
    override suspend fun snapshotAfterShade(timeoutMs: Long): A11ySnapshot? {
        shadeWaits++
        lastShadeTimeoutMs = timeoutMs
        delay(minOf(shadeDelayMs, timeoutMs))
        return if (shadeDelayMs > timeoutMs) null else target
    }
    override suspend fun screenshot(target: A11ySnapshot): Outcome<ScreenImage> {
        screenshots++
        screenshotError?.let { return Outcome.Err(it) }
        return Outcome.Ok(ScreenImage(4, 2, IntArray(8)))
    }
    override suspend fun current(): A11ySnapshot? = currentOverride ?: target

    companion object {
        fun defaultTarget(pkg: String = "org.telegram.messenger") = A11ySnapshot(
            pkg = pkg, label = "Telegram", windowTitle = "Дима Соколов", windowId = 7, displayId = 0,
            field = FieldSnapshot(inputType = 0x00000001 or 0x00020000, imeOptions = 0, hint = "Сообщение", beforeCursor = "",
                boundsInScreen = app.murmur.core.ocr.Rect(40, 2400, 940, 2490), isPassword = false, isMultiline = true),
            excludeRects = emptyList(), capturedAtMs = 0,
        )
    }
}

/**
 * Records what would be inserted; [result] decides the outcome. [calls] keeps the order ("clipboard", "insert").
 * [insertDelayMs] = how long the ladder takes (coroutine time; the DELIVERY timer test).
 */
class RecordingInserter(var result: InsertResult = InsertResult.Inserted(InsertMethod.COMMIT_TEXT), var insertDelayMs: Long = 0) : Inserter {
    val inserted = mutableListOf<String>()
    val clipboard = mutableListOf<String>()
    val calls = mutableListOf<String>()
    override suspend fun insert(target: A11ySnapshot, text: String): InsertResult {
        calls += "insert"
        if (insertDelayMs > 0) delay(insertDelayMs)
        inserted += text
        return result
    }
    override fun copyToClipboard(text: String): Boolean { calls += "clipboard"; clipboard += text; return true }
}

class RecordingFeedback : Feedback {
    val haptics = mutableListOf<HapticKind>()
    val sounds = mutableListOf<HapticKind>()
    override fun haptic(kind: HapticKind) { haptics += kind }
    override fun sound(kind: HapticKind) { sounds += kind }
}

/** Onboarding practice field (StartMode.IN_APP). */
class FakeInAppTarget(override val snapshot: A11ySnapshot = FakeTargetReader.defaultTarget("app.murmur")) : InAppTarget {
    val inserted = mutableListOf<String>()
    override fun insert(text: String) { inserted += text }
}

/** [source] overrides [mb] (e.g. "more memory once :asr is down"). */
class FixedMemoryGate(var mb: Long = 6_000, var source: (() -> Long)? = null) : MemoryGate { override fun availableMb() = source?.invoke() ?: mb }

class FakeSystemConditions(var freeBytes: Long = 181_000_000_000, var thermalCritical: Boolean = false) : SystemConditions {
    override fun freeStorageBytes() = freeBytes
    override fun thermalCritical() = thermalCritical
}
