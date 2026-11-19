package app.murmur.wiring

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import app.murmur.core.dictation.PermissionMonitor
import app.murmur.core.dictation.PermissionState
import app.murmur.platform.a11y.A11yStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The one PermissionMonitor (core/dictation port): microphone and notification permissions, the accessibility service
 * through WP-02's [A11yStatus] (enabled in Settings.Secure, connected now, ever connected, `restrictedLikely` from the
 * recorded trip to the accessibility settings, ADR-054, never the app-op, ADR-027), and the battery optimisation
 * exemption.
 *
 * A plain reader: [refresh] re-reads everything and is cheap (a few system-service queries, no I/O of its own), so the
 * orchestrator's preflight may call it before it refuses a start. It is also called on every resume of MainActivity
 * (after [A11yStatus.onReturnedToApp]), by MurmurApp when the service connects or disconnects, and when the
 * enabled-services setting changes while the app runs.
 */
class AndroidPermissionMonitor(private val context: Context) : PermissionMonitor {
    private val a11y = A11yStatus.get(context)
    private val _state = MutableStateFlow(read())
    override val state: StateFlow<PermissionState> = _state.asStateFlow()

    init {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = refresh()
        }
        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, observer,
        )
    }

    override fun refresh() {
        _state.value = read()
    }

    private fun read(): PermissionState {
        val s = a11y.read()
        return PermissionState(
            micGranted = granted(Manifest.permission.RECORD_AUDIO),
            notificationsGranted = granted(Manifest.permission.POST_NOTIFICATIONS) &&
                context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true,
            a11yEnabled = s.enabledInSettings,
            a11yConnected = s.connectedNow,
            a11yEverConnected = s.everConnected,
            batteryUnrestricted = context.getSystemService(PowerManager::class.java)
                ?.isIgnoringBatteryOptimizations(context.packageName) == true,
            restrictedLikely = s.restrictedLikely,
        )
    }

    private fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
