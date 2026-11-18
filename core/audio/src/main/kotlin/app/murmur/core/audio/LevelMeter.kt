package app.murmur.core.audio

/**
 * Bubble level bars (DESIGN.md 3.5): 7 bars, 30 Hz RMS sampling, per-bar weights, attack 40 ms /
 * release 120 ms, clamped 4..24 dp. No fake animation: silence is flat. Pure; runs in :main on the
 * capture thread output. WP-09 implements [push].
 */
class LevelMeter(
    val weights: FloatArray = floatArrayOf(.35f, .7f, 1f, .6f, .85f, .45f, .25f),
    val attackMs: Int = 40,
    val releaseMs: Int = 120,
) {
    /** Returns bar heights in 0..1 (the bubble maps to 4..24 dp) after a chunk of samples. */
    fun push(samples: ShortArray, count: Int = samples.size): FloatArray = TODO("WP-09")
}
