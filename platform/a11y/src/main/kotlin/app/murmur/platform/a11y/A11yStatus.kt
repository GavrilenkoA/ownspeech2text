package app.murmur.platform.a11y

// Shim of WP-02's A11yStatus (main, integration pass 2, ADR-079) on this branch's base: the same API, so that WP-15's
// PermissionMonitor and onboarding are built on it now. At the merge main's file replaces this one
// (RestrictedSettingsProbe stays in Context.kt on this base).

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/** Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES: "pkg/cls:pkg/.Short:..." (ComponentName flattened forms). */
object EnabledServices {
    fun contains(setting: String?, pkg: String, cls: String): Boolean {
        if (setting.isNullOrBlank()) return false
        return setting.split(':').any { entry ->
            val slash = entry.indexOf('/')
            if (slash <= 0) return@any false
            val p = entry.substring(0, slash).trim()
            var c = entry.substring(slash + 1).trim()
            if (c.startsWith('.')) c = p + c
            p.equals(pkg, ignoreCase = true) && c.equals(cls, ignoreCase = true)
        }
    }
}

/** What WP-15's PermissionMonitor (and the onboarding `ob-restricted` step, DESIGN 6.1) reads about the service. */
data class A11yState(
    val enabledInSettings: Boolean,
    val connectedNow: Boolean,
    val everConnected: Boolean,
    /** PackageInstaller.PACKAGE_SOURCE_* of this install (getInstallSourceInfo). */
    val packageSource: Int,
    /** The user was sent to the accessibility settings screen and has come back since. */
    val returnedFromA11ySettings: Boolean,
) {
    /**
     * Once the service has connected, the restricted setting was allowed for this install (Android keeps that per
     * install), so a later "off after a trip to settings" is a crash or a manual switch-off, not the restriction.
     */
    val restrictedLikely: Boolean
        get() = !everConnected && RestrictedSettingsProbe.likelyBlocked(returnedFromA11ySettings, connectedNow, packageSource)
    val enabledButDead: Boolean get() = RestrictedSettingsProbe.enabledButDead(enabledInSettings, everConnected)
}

/**
 * Records the trip to the accessibility settings screen and the service's connections (ADR-054). The trip survives
 * process death (the system may kill :main while the user is in Settings), so it is kept in private preferences.
 * Call [openAccessibilitySettings] (or [onSentToAccessibilitySettings] when the caller starts the screen itself) and
 * [onReturnedToApp] from the screen that resumes afterwards.
 */
class A11yStatus private constructor(private val app: Context) {
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val component = ComponentName(app, MurmurAccessibilityService::class.java)

    fun openAccessibilitySettings(from: Context) {
        onSentToAccessibilitySettings()
        val i = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        if (from !is android.app.Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        from.startActivity(i)
    }

    fun onSentToAccessibilitySettings() {
        prefs.edit().putBoolean(KEY_SENT, true).putBoolean(KEY_RETURNED, false).apply()
    }

    /** The app's screen resumed. Counts as a return only after a trip to the settings screen. */
    fun onReturnedToApp() {
        if (prefs.getBoolean(KEY_SENT, false)) prefs.edit().putBoolean(KEY_SENT, false).putBoolean(KEY_RETURNED, true).apply()
    }

    internal fun onServiceConnected() {
        prefs.edit().putBoolean(KEY_EVER_CONNECTED, true).putBoolean(KEY_RETURNED, false).apply()
    }

    fun enabledInSettings(): Boolean = EnabledServices.contains(
        Settings.Secure.getString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
        component.packageName, component.className,
    )

    fun packageSource(): Int = runCatching {
        app.packageManager.getInstallSourceInfo(app.packageName).packageSource
    }.getOrElse {
        Log.w(TAG, "install_source_failed ${it.javaClass.simpleName}")
        RestrictedSettingsProbe.PACKAGE_SOURCE_UNSPECIFIED
    }

    fun read(): A11yState = A11yState(
        enabledInSettings = enabledInSettings(),
        connectedNow = A11yBridge.service.value != null,
        everConnected = prefs.getBoolean(KEY_EVER_CONNECTED, false),
        packageSource = packageSource(),
        returnedFromA11ySettings = prefs.getBoolean(KEY_RETURNED, false),
    )

    companion object {
        private const val TAG = "MurmurA11y"
        private const val PREFS = "a11y_status"
        private const val KEY_SENT = "sent_to_settings"
        private const val KEY_RETURNED = "returned_from_settings"
        private const val KEY_EVER_CONNECTED = "ever_connected"

        @Volatile private var instance: A11yStatus? = null

        fun get(context: Context): A11yStatus =
            instance ?: synchronized(this) { instance ?: A11yStatus(context.applicationContext).also { instance = it } }
    }
}
