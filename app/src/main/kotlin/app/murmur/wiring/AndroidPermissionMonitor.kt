package app.murmur.wiring

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import app.murmur.core.dictation.PermissionMonitor
import app.murmur.core.dictation.PermissionState
import app.murmur.platform.a11y.MurmurAccessibilityService
import app.murmur.platform.a11y.RestrictedSettingsProbe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The PermissionMonitor port (core/dictation) over the platform (WP-15): microphone and notification permissions,
 * the accessibility service enabled in Settings.Secure / connected now ([serviceConnected], from A11yServiceHost) / ever
 * connected (persisted), battery optimisation exemption, and restricted settings (ADR-054, never the app-op, ADR-027):
 * [RestrictedSettingsProbe.likelyBlocked] = the user came back from the accessibility settings screen
 * ([noteAccessibilitySettingsOpened] then [refresh] on resume), the service is still not connected, and the APK was
 * installed from a file (InstallSourceInfo.packageSource).
 *
 * [state] is re-read on [refresh] (every resume of the app's screens), whenever the service connects or disconnects,
 * and when the enabled-services setting changes.
 */
class AndroidPermissionMonitor(
    private val context: Context,
    private val serviceConnected: StateFlow<Boolean>,
    scope: CoroutineScope,
) : PermissionMonitor {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val pm = context.packageManager
    private val service = ComponentName(context, MurmurAccessibilityService::class.java)

    @Volatile private var openedA11ySettings = false
    @Volatile private var returnedFromA11ySettings = false

    private val _state = MutableStateFlow(read())
    override val state: StateFlow<PermissionState> = _state.asStateFlow()

    init {
        scope.launch {
            serviceConnected.collect { connected ->
                if (connected) {
                    prefs.edit().putBoolean(KEY_EVER_CONNECTED, true).apply()
                    returnedFromA11ySettings = false
                    openedA11ySettings = false
                }
                publish()
            }
        }
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = publish()
        }
        context.contentResolver.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, observer)
    }

    /** Onboarding opened the accessibility settings: the next [refresh] (on resume) counts as the return from it. */
    fun noteAccessibilitySettingsOpened() {
        openedA11ySettings = true
    }

    override fun refresh() {
        if (openedA11ySettings) returnedFromA11ySettings = true
        publish()
    }

    private fun publish() { _state.value = read() }

    private fun read(): PermissionState {
        val connected = serviceConnected.value
        val enabled = a11yEnabled()
        return PermissionState(
            micGranted = granted(Manifest.permission.RECORD_AUDIO),
            notificationsGranted = granted(Manifest.permission.POST_NOTIFICATIONS) &&
                context.getSystemService(NotificationManager::class.java).areNotificationsEnabled(),
            a11yEnabled = enabled,
            a11yConnected = connected,
            a11yEverConnected = connected || prefs.getBoolean(KEY_EVER_CONNECTED, false),
            batteryUnrestricted = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName),
            restrictedLikely = RestrictedSettingsProbe.likelyBlocked(returnedFromA11ySettings, connected, packageSource()),
        )
    }

    private fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** ENABLED_ACCESSIBILITY_SERVICES is a ':'-separated list of flattened component names (short or full class form). */
    private fun a11yEnabled(): Boolean {
        val list = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabledIn(list, service.packageName, service.className)
    }

    /** PackageInstaller.PACKAGE_SOURCE_* of this APK (API 33); UNSPECIFIED when the installer info is unreadable. */
    private fun packageSource(): Int = try {
        pm.getInstallSourceInfo(context.packageName).packageSource
    } catch (e: PackageManager.NameNotFoundException) {
        RestrictedSettingsProbe.PACKAGE_SOURCE_UNSPECIFIED
    }

    companion object {
        private const val PREFS = "permissions"
        private const val KEY_EVER_CONNECTED = "a11y_ever_connected"

        /** Pure parse of the secure setting (PermissionsTest): "pkg/.Cls" and "pkg/full.Cls" both name the service. */
        fun enabledIn(setting: String, pkg: String, cls: String): Boolean = setting.split(':').any { e ->
            val parts = e.trim().split('/', limit = 2)
            parts.size == 2 && parts[0] == pkg && (parts[1] == cls || (parts[1].startsWith('.') && pkg + parts[1] == cls))
        }
    }
}
