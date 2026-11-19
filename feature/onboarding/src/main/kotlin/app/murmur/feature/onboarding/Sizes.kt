package app.murmur.feature.onboarding

import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Byte and time figures of the models step (DESIGN 6.1: "1.7 GB", "0.78 of 1.69 GB", "190 of 1,100 MB",
 * "18.4 MB/s", "about 50 s left"). Decimal units (1 GB = 10^9 bytes), as the frames add them up
 * (590 MB + 1,100 MB = 1.69 GB). Digits are grouped for the locale (DESIGN 9: 1,284 vs 1 284).
 */
object ByteFormat {
    private const val MB = 1_000_000.0
    private const val GB = 1_000_000_000.0

    private fun nf(locale: Locale, fraction: Int) = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = fraction
        maximumFractionDigits = fraction
    }

    /** A pack total: always GB with one decimal ("1.7 GB", "0.6 GB"). */
    fun packGb(bytes: Long, locale: Locale): String = nf(locale, 1).format(bytes / GB)

    /** Whole download: two decimals in GB ("0.78" of "1.69"). */
    fun gb2(bytes: Long, locale: Locale): String = nf(locale, 2).format(bytes / GB)

    /** One item: whole MB with grouping ("590", "1,100"). */
    fun mb(bytes: Long, locale: Locale): String = nf(locale, 0).format((bytes / MB).roundToLong())

    /** Speed with one decimal ("18.4"). */
    fun mbPerSec(bytesPerSec: Long, locale: Locale): String = nf(locale, 1).format(bytesPerSec / MB)

    /** Free space in whole GB ("181"); below 10 GB one decimal ("3.4"). */
    fun freeGb(bytes: Long, locale: Locale): String = if (bytes >= 10 * GB) nf(locale, 0).format((bytes / GB).roundToLong()) else nf(locale, 1).format(bytes / GB)

    /** Percent of the whole download, rounded down so that 100 % means done. */
    fun percent(fraction: Float): Int = (fraction * 100).toInt().coerceIn(0, 100)
}

/** "about 50 s left" / "about 3 min left": seconds under 90 s, else whole minutes rounded up. */
sealed interface TimeLeft {
    data class Seconds(val s: Long) : TimeLeft
    data class Minutes(val min: Long) : TimeLeft

    companion object {
        fun of(seconds: Long): TimeLeft = if (seconds < 90) Seconds(seconds.coerceAtLeast(1)) else Minutes((seconds + 59) / 60)
    }
}
