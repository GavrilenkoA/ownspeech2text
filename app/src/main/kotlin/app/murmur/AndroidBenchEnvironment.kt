package app.murmur

import android.content.Context
import android.os.Build
import android.os.PowerManager
import app.murmur.core.benchmark.BenchEnvironment

/** The benchmark's view of the device (WP-14 `BenchEnvironment`): app version, firmware and thermal status codes. */
class AndroidBenchEnvironment(private val context: Context) : BenchEnvironment {
    override val appVersion: String get() = BuildConfig.VERSION_NAME
    override val buildFingerprint: String get() = Build.FINGERPRINT

    override fun thermalStatus(): String =
        when (context.getSystemService(PowerManager::class.java)?.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "none"
            PowerManager.THERMAL_STATUS_LIGHT -> "light"
            PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
            null -> "unknown"
            else -> "unknown"
        }
}
