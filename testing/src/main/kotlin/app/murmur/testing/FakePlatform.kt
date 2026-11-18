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
import app.murmur.core.dictation.TargetReader
import app.murmur.core.model.MurmurError
import app.murmur.core.model.Outcome
import app.murmur.core.model.SessionId
import app.murmur.core.ocr.ScreenImage
import app.murmur.core.store.InsertMethod
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asFlow

/** A tone burst of [speechMs] at amplitude 3000 surrounded by silence: enough for the fake ASR "speech" check. */
fun syntheticSpeech(totalMs: Long = 3_000, speechMs: Long = 2_000): ShortArray {
    val n = PcmFormat.msToSamples(totalMs).toInt()
    val s0 = PcmFormat.msToSamples((totalMs - speechMs) / 2).toInt()
    val s1 = s0 + PcmFormat.msToSamples(speechMs).toInt()
    return ShortArray(n) { i -> if (i in s0 until s1) (3000 * kotlin.math.sin(i * 2 * Math.PI * 220 / 16000)).toInt().toShort() else 0 }
}

/**
 * Plays [pcm] as 20 ms chunks. [startError] simulates a busy/blocked microphone; [signal] injects a mid-recording
 * CaptureSignal (call, mic lost) into the running handle (ExternalStop tests); [audioKeys] records the file names.
 */
class FakeAudioCapture(var pcm: ShortArray = syntheticSpeech(), var startError: MurmurError? = null) : AudioCapture {
    var starts = 0; private set
    val audioKeys = mutableListOf<String>()
    var current: Handle? = null; private set

    override suspend fun start(session: SessionId, keepRecording: Boolean, audioKey: String): Outcome<CaptureHandle> {
        starts++
        audioKeys += audioKey
        startError?.let { return Outcome.Err(it) }
        return Outcome.Ok(Handle(pcm).also { current = it })
    }

    fun signal(s: CaptureSignal) { current?.signalsFlow?.tryEmit(s) }

    class Handle(private val pcm: ShortArray) : CaptureHandle {
        override val chunks: Flow<PcmChunk> =
            (pcm.indices step PcmFormat.FRAME_SAMPLES).map { i ->
                PcmChunk(pcm.copyOfRange(i, minOf(i + PcmFormat.FRAME_SAMPLES, pcm.size)), i.toLong())
            }.asFlow()
        override val levels: StateFlow<FloatArray> = MutableStateFlow(FloatArray(7))
        internal val signalsFlow = MutableSharedFlow<CaptureSignal>(extraBufferCapacity = 8)
        override val signals: Flow<CaptureSignal> = signalsFlow
        var cancelled = false; private set
        var stopped = false; private set
        override suspend fun stop(): CaptureResult {
            stopped = true
            return CaptureResult(PcmFormat.samplesToMs(pcm.size.toLong()), null, pcm.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0, false)
        }
        override fun cancel() { cancelled = true }
    }
}

class FakeTargetReader(
    var target: A11ySnapshot? = defaultTarget(),
    var screenshotError: MurmurError? = null,
    var currentOverride: A11ySnapshot? = null,
) : TargetReader {
    var screenshots = 0; private set
    var shadeWaits = 0; private set
    override suspend fun snapshot(): A11ySnapshot? = target
    override suspend fun snapshotAfterShade(timeoutMs: Long): A11ySnapshot? { shadeWaits++; return target }
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

/** Records what would be inserted; [result] decides the outcome. */
class RecordingInserter(var result: InsertResult = InsertResult.Inserted(InsertMethod.COMMIT_TEXT)) : Inserter {
    val inserted = mutableListOf<String>()
    val clipboard = mutableListOf<String>()
    override suspend fun insert(target: A11ySnapshot, text: String): InsertResult { inserted += text; return result }
    override fun copyToClipboard(text: String): Boolean { clipboard += text; return true }
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

class FixedMemoryGate(var mb: Long = 6_000) : MemoryGate { override fun availableMb() = mb }
