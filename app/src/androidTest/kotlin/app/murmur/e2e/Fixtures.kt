package app.murmur.e2e

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Audio for the debug WAV capture (WP-09's `WavFileCapture` plays `filesDir/debug-capture.wav` in real time; the
 * emulator microphone cannot be fed, ARCHITECTURE 16). Debug fake mode is switched on by `filesDir/debug-fake-engines`.
 */
object Fixtures {
    const val CAPTURE_WAV = "debug-capture.wav"
    const val FAKE_ENGINES_FLAG = "debug-fake-engines"
    /**
     * Pushed by tools/e2e/run_emulator_e2e.sh for the real suites: one FLEURS dev clip per language (CC-BY-4.0, ru and
     * en) with its reference text, `<DEVICE_DIR>/fleurs-<lang>.wav` / `.txt`.
     */
    fun fleursWav(lang: String) = "${E2e.DEVICE_DIR}/fleurs-$lang.wav"
    fun fleursRef(lang: String) = "${E2e.DEVICE_DIR}/fleurs-$lang.txt"

    val captureFile: File get() = File(E2e.context.filesDir, CAPTURE_WAV)
    val fakeFlag: File get() = File(E2e.context.filesDir, FAKE_ENGINES_FLAG)

    /**
     * A 220 Hz tone at amplitude 3000 for [speechMs] in the middle of [totalMs] of silence (16 kHz mono): above the fake
     * ASR's speech threshold (|x| >= 500), so FakeAsrEngine returns its script instead of NoSpeech.
     */
    fun toneBurst(totalMs: Int = 3_000, speechMs: Int = 2_000): ShortArray {
        val n = totalMs * 16
        val s0 = (totalMs - speechMs) / 2 * 16
        val s1 = s0 + speechMs * 16
        return ShortArray(n) { i ->
            if (i in s0 until s1) (3000 * kotlin.math.sin(i * 2 * Math.PI * 220 / 16000)).toInt().toShort() else 0
        }
    }

    /** PCM16 LE mono 16 kHz RIFF/WAVE. */
    fun wav(pcm: ShortArray, rate: Int = 16_000): ByteArray {
        val data = pcm.size * 2
        return ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(data)
            pcm.forEach { putShort(it) }
        }.array()
    }

    fun installCapture(pcm: ShortArray) = captureFile.writeBytes(wav(pcm))

    /** Copies the pushed FLEURS clip of [lang] into the capture slot and returns its duration in ms and the reference text. */
    fun installFleurs(lang: String = "ru"): Pair<Long, String> {
        val wav = fleursWav(lang)
        val bytes = E2e.uiAutomation.executeShellCommand("cat $wav").let { pfd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
        }
        check(bytes.size > 44 && String(bytes, 0, 4) == "RIFF") { "$wav missing: run the real suite through tools/e2e/run_emulator_e2e.sh" }
        captureFile.writeBytes(bytes)
        val ref = E2e.shell("cat ${fleursRef(lang)}").trim()
        check(ref.isNotEmpty()) { "${fleursRef(lang)} missing" }
        val pcmBytes = bytes.size - 44
        return pcmBytes / 32L to ref
    }
}
