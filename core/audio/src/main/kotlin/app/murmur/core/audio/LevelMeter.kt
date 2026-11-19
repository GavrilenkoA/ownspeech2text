package app.murmur.core.audio

import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Bubble level bars (DESIGN.md 3.5): 7 bars, 30 Hz RMS sampling, each bar = RMS of a sliding window x a per-bar
 * weight, smoothed with an attack of 40 ms and a release of 120 ms. No fake animation: silence is flat (every bar
 * exactly 0). Pure; runs on the capture thread, fed with every captured chunk.
 *
 * The meter ticks on the sample clock, not on wall time: every 1/30 s of audio ([tickSamples]) it takes the RMS of the
 * last [windowMs] of samples, maps it from dBFS to 0..1 ([floorDb] -> 0, [ceilDb] -> 1), moves the smoothed level
 * toward it with the one-pole constant of the direction (attack when rising, release when falling) and multiplies it
 * by each bar's weight. Heights are 0..1; the bubble maps them to 4..24 dp.
 */
class LevelMeter(
    val weights: FloatArray = floatArrayOf(.35f, .7f, 1f, .6f, .85f, .45f, .25f),
    val attackMs: Int = 40,
    val releaseMs: Int = 120,
    val rateHz: Int = 30,
    val windowMs: Int = 50,
    val floorDb: Double = -55.0,
    val ceilDb: Double = -10.0,
    val sampleRate: Int = PcmFormat.SAMPLE_RATE,
) {
    init {
        require(weights.isNotEmpty()) { "weights" }
        require(attackMs > 0 && releaseMs > 0 && rateHz > 0 && windowMs > 0) { "time constants" }
        require(ceilDb > floorDb) { "ceilDb must be above floorDb" }
    }

    /** Samples per 30 Hz tick (533.33 at 16 kHz). Tick k falls on sample ceil(k x rate / 30): integer, no drift. */
    val tickSamples: Double = sampleRate.toDouble() / rateHz
    private val windowSamples = (sampleRate.toLong() * windowMs / 1000).toInt().coerceAtLeast(1)
    private val tickSeconds = 1.0 / rateHz

    /** Fraction of the remaining distance covered per tick: 1 - e^(-dt/tau). */
    val attackStep: Double = 1 - exp(-tickSeconds * 1000 / attackMs)
    val releaseStep: Double = 1 - exp(-tickSeconds * 1000 / releaseMs)

    private val ring = ShortArray(windowSamples)
    private var ringPos = 0
    private var ringFill = 0
    private var sumSquares = 0.0
    private var samplesSeen = 0L
    private var nextTickAt = tickDue(1)
    private var smoothed = 0.0
    private val bars = FloatArray(weights.size)

    /** Number of 30 Hz updates so far; the capture publishes [bars] to the bubble whenever this advances. */
    var ticks: Long = 0
        private set

    /** The smoothed, unweighted level 0..1 after the last tick. */
    val level: Double get() = smoothed

    /** Consumes [count] samples and returns the bar heights (0..1) after the last tick inside them (a fresh copy). */
    fun push(samples: ShortArray, count: Int = samples.size): FloatArray {
        require(count in 0..samples.size) { "count" }
        for (i in 0 until count) {
            val s = samples[i]
            val old = ring[ringPos]
            if (ringFill == windowSamples) sumSquares -= old.toDouble() * old else ringFill++
            sumSquares += s.toDouble() * s
            ring[ringPos] = s
            ringPos = (ringPos + 1) % windowSamples
            samplesSeen++
            if (samplesSeen >= nextTickAt) {
                tick()
                nextTickAt = tickDue(ticks + 1)
            }
        }
        return bars.copyOf()
    }

    /** Current bar heights (a fresh copy). */
    fun bars(): FloatArray = bars.copyOf()

    fun reset() {
        ring.fill(0); ringPos = 0; ringFill = 0; sumSquares = 0.0; samplesSeen = 0; nextTickAt = tickDue(1)
        smoothed = 0.0; bars.fill(0f); ticks = 0
    }

    private fun tick() {
        // Recompute the window sum exactly now and then: the running sum accumulates rounding error over minutes.
        if (ticks % 300 == 0L) sumSquares = ring.sumOf { it.toDouble() * it }
        val target = levelOf(sqrt((sumSquares / ringFill).coerceAtLeast(0.0)))
        smoothed += (target - smoothed) * (if (target > smoothed) attackStep else releaseStep)
        if (smoothed < SNAP_TO_ZERO) smoothed = 0.0
        for (i in bars.indices) bars[i] = (smoothed * weights[i]).toFloat().coerceIn(0f, 1f)
        ticks++
    }

    private fun tickDue(k: Long): Long = (k * sampleRate + rateHz - 1) / rateHz

    /** dBFS of an RMS amplitude mapped linearly to 0..1 between [floorDb] and [ceilDb]; exact silence is 0. */
    fun levelOf(rms: Double): Double {
        if (rms <= 0.0) return 0.0
        val db = 20 * log10(rms / FULL_SCALE)
        return ((db - floorDb) / (ceilDb - floorDb)).coerceIn(0.0, 1.0)
    }

    private companion object {
        const val FULL_SCALE = 32768.0
        /** Below this the release tail is invisible (0.024 dp of the 20 dp range): snap so that silence is flat. */
        const val SNAP_TO_ZERO = 1e-3
    }
}
