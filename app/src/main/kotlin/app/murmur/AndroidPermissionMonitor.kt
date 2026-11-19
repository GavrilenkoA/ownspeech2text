package app.murmur

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import app.murmur.core.dictation.PermissionMonitor
import app.murmur.core.dictation.PermissionState
import app.murmur.platform.a11y.A11yStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The system facts the orchestrator's preflight and the screens read (microphone, notifications, accessibility service
 * via WP-02's [A11yStatus], battery). A plain reader: [refresh] re-reads everything; the service's connect/disconnect
 * also refreshes it ([AppContainer.serviceConnected]). WP-15 owns the onboarding flows around it.
 */
class AndroidPermissionMonitor(private val context: Context) : PermissionMonitor {
    private val _state = MutableStateFlow(read())
    override val state: StateFlow<PermissionState> = _state.asStateFlow()

    override fun refresh() {
        _state.value = read()
    }

    private fun read(): PermissionState {
        val a11y = A11yStatus.get(context).read()
        return PermissionState(
            micGranted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
            notificationsGranted = context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true,
            a11yEnabled = a11y.enabledInSettings,
            a11yConnected = a11y.connectedNow,
            a11yEverConnected = a11y.everConnected,
            batteryUnrestricted = context.getSystemService(PowerManager::class.java)
                ?.isIgnoringBatteryOptimizations(context.packageName) == true,
            restrictedLikely = a11y.restrictedLikely,
        )
    }
}
