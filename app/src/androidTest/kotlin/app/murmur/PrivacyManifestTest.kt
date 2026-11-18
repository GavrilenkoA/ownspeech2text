package app.murmur

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/** D9 guards on the installed package: no telemetry provider, no overlay permission, processes as designed. */
@RunWith(AndroidJUnit4::class)
class PrivacyManifestTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val pm = ctx.packageManager

    @Test
    fun noTelemetryProvidersAndNoOverlayPermission() {
        val info = pm.getPackageInfo(ctx.packageName, PackageManager.PackageInfoFlags.of(
            (PackageManager.GET_PROVIDERS or PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES).toLong()))
        val providers = info.providers.orEmpty().map { it.name }
        assertFalse(providers.toString(), providers.any { it.contains("Telemetry", ignoreCase = true) })
        assertFalse(info.requestedPermissions.orEmpty().contains("android.permission.SYSTEM_ALERT_WINDOW"))
        val proc = info.services.orEmpty().associate { it.name.substringAfterLast('.') to it.processName }
        assertEquals("${ctx.packageName}:asr", proc["AsrService"])
        assertEquals("${ctx.packageName}:llm", proc["LlmService"])
        assertEquals(ctx.packageName, proc["MurmurAccessibilityService"])
    }
}
