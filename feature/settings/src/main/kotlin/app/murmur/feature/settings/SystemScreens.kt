package app.murmur.feature.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.provider.Settings as AndroidSettings
import app.murmur.core.dictation.PermissionState

/** The system screens the Permissions rows and the "bubble is off" banner open. */
enum class SystemScreen { APP_DETAILS, ACCESSIBILITY, NOTIFICATIONS, BATTERY }

/** Status of one Permissions row (DESIGN 6.5: Allowed in ok, Restricted in warn, Off in err). */
enum class PermissionStatus { ALLOWED, ON, RESTRICTED, OFF }

/** One row of Settings > Permissions. */
data class PermissionRow(val kind: Kind, val status: PermissionStatus, val opens: SystemScreen) {
    enum class Kind { MIC, ACCESSIBILITY, NOTIFICATIONS, BATTERY }
}

object PermissionRows {
    /**
     * Rows from [PermissionState] (`deps.permissions`). The accessibility service is On while it is connected,
     * Restricted when Android's "restricted setting" block is likely (then App info, where "Allow restricted
     * settings" lives, DESIGN 6.1 ob-restricted), else Off (Accessibility settings). Background use is Allowed when
     * battery use is unrestricted, else Restricted (App info -> Battery).
     */
    fun of(p: PermissionState): List<PermissionRow> = listOf(
        PermissionRow(PermissionRow.Kind.MIC, if (p.micGranted) PermissionStatus.ALLOWED else PermissionStatus.OFF, SystemScreen.APP_DETAILS),
        when {
            p.a11yConnected -> PermissionRow(PermissionRow.Kind.ACCESSIBILITY, PermissionStatus.ON, SystemScreen.ACCESSIBILITY)
            p.restrictedLikely -> PermissionRow(PermissionRow.Kind.ACCESSIBILITY, PermissionStatus.RESTRICTED, SystemScreen.APP_DETAILS)
            else -> PermissionRow(PermissionRow.Kind.ACCESSIBILITY, PermissionStatus.OFF, SystemScreen.ACCESSIBILITY)
        },
        PermissionRow(
            PermissionRow.Kind.NOTIFICATIONS,
            if (p.notificationsGranted) PermissionStatus.ALLOWED else PermissionStatus.OFF, SystemScreen.NOTIFICATIONS,
        ),
        PermissionRow(
            PermissionRow.Kind.BATTERY,
            if (p.batteryUnrestricted) PermissionStatus.ALLOWED else PermissionStatus.RESTRICTED, SystemScreen.APP_DETAILS,
        ),
    )
}

/** Intents for [SystemScreen] (all public Settings actions; none needs a permission). */
object SystemScreens {
    fun intent(screen: SystemScreen, packageName: String): Intent = when (screen) {
        SystemScreen.APP_DETAILS, SystemScreen.BATTERY ->
            Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        SystemScreen.ACCESSIBILITY -> Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)
        SystemScreen.NOTIFICATIONS ->
            Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE, packageName)
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Opens [screen]; falls back to App info when a vendor build lacks the specific screen. */
    fun open(context: Context, screen: SystemScreen) {
        try {
            context.startActivity(intent(screen, context.packageName))
        } catch (e: ActivityNotFoundException) {
            context.startActivity(intent(SystemScreen.APP_DETAILS, context.packageName))
        }
    }

    /** Debug rows (Settings.debug*) exist only in debuggable builds. */
    fun isDebuggable(context: Context): Boolean = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** versionName of the app, for the inner category pane footer ("Murmur 0.1.0"). */
    fun versionName(context: Context): String? = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    } catch (e: Exception) {
        null
    }
}
