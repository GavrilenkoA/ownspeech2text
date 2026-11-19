package app.murmur.feature.models

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Number formatting of the hub (DESIGN 6.4 frames): download sizes in SI units ("590 MB", "1.10 GB"), parameter
 * counts ("938M", "1.73B"), benchmark figures. Locale-aware decimal separators (DESIGN 9: NumberFormat). Pure.
 */
object Format {
    private const val MB = 1_000_000.0
    private const val GB = 1_000_000_000.0

    private fun decimals(locale: Locale, min: Int, max: Int) = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = min; maximumFractionDigits = max; isGroupingUsed = true
    }

    /** "938M", "72M", "1.73B" (two decimals in billions, whole millions below). */
    fun params(p: Long, locale: Locale = Locale.getDefault()): String =
        if (p >= 1_000_000_000L) decimals(locale, 2, 2).format(p / 1e9) + "B"
        else "${(p / 1e6).roundToLong()}M"

    /** Download size: whole MB below 1 GB ("590 MB"), two decimals in GB ("1.10 GB"). Returns number and unit. */
    fun size(bytes: Long, locale: Locale = Locale.getDefault()): Pair<String, String> =
        if (bytes >= GB * 0.9995) decimals(locale, 2, 2).format(bytes / GB) to "GB"
        else maxOf(1L, (bytes / MB).roundToLong()).toString() to "MB"

    fun sizeText(bytes: Long, locale: Locale = Locale.getDefault()): String = size(bytes, locale).let { "${it.first} ${it.second}" }

    /** Storage line ("Models use 2.4 GB · 181 GB free"): one decimal below 10 GB, whole GB above; MB below 0.1 GB. */
    fun storageText(bytes: Long, locale: Locale = Locale.getDefault()): String = when {
        bytes < 100 * MB -> "${(bytes / MB).roundToLong()} MB"
        bytes < 10 * GB -> decimals(locale, 1, 1).format(bytes / GB) + " GB"
        else -> "${(bytes / GB).roundToLong()} GB"
    }

    /** Progress meta "89 MB of 144 MB": the same unit for both numbers (the total's). */
    fun progressPair(done: Long, total: Long, locale: Locale = Locale.getDefault()): Pair<String, String> {
        val (t, unit) = size(total, locale)
        val d = if (unit == "GB") decimals(locale, 2, 2).format(done / GB) else (done / MB).roundToLong().toString()
        return "$d $unit" to "$t $unit"
    }

    /** "12.3 MB/s" (one decimal). */
    fun speed(bytesPerSec: Long, locale: Locale = Locale.getDefault()): String =
        decimals(locale, 1, 1).format(bytesPerSec / MB) + " MB/s"

    /** Two significant digits below 10, whole numbers above, trailing zeros dropped: 0.8, 0.21, 1.4, 14. */
    fun sig2(value: Double, locale: Locale = Locale.getDefault()): String {
        if (value >= 10 || value <= 0) return NumberFormat.getIntegerInstance(locale).format(value.roundToLong())
        val bd = BigDecimal(value).round(MathContext(2, RoundingMode.HALF_UP)).stripTrailingZeros()
        return decimals(locale, 0, maxOf(0, bd.scale())).format(bd.toDouble())
    }

    /** Seconds from milliseconds, [sig2] ("0.8", "2.1"). */
    fun seconds(ms: Double, locale: Locale = Locale.getDefault()): String = sig2(ms / 1000.0, locale)

    /** Faster-than-real-time factor: whole number from 10 up, one decimal below ("14", "3.2"). */
    fun rtf(x: Double, locale: Locale = Locale.getDefault()): String =
        if (x >= 10) NumberFormat.getIntegerInstance(locale).format(x.roundToLong()) else decimals(locale, 1, 1).format(x)

    /** tok/s: whole number from 10 up ("31", "520"), one decimal below. */
    fun tps(x: Double, locale: Locale = Locale.getDefault()): String =
        if (x >= 10) NumberFormat.getIntegerInstance(locale).apply { isGroupingUsed = true }.format(x.roundToLong()) else decimals(locale, 1, 1).format(x)

    /** Peak memory: "720" + "MB" below 1,000 MB, else GB with one decimal ("1.5" + "GB"). */
    fun memory(mb: Long, locale: Locale = Locale.getDefault()): Pair<String, String> =
        if (mb < 1000) mb.toString() to "MB" else decimals(locale, 1, 1).format(mb / 1024.0) to "GB"

    /** Phone RAM as sold: "12 GB" from the probe's 11,500 MB (rounded up to whole GB). */
    fun ramGb(totalRamMb: Int): Long = kotlin.math.ceil(totalRamMb / 1024.0).toLong()
}
