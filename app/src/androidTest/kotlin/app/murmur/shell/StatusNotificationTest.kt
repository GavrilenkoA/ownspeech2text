package app.murmur.shell

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.murmur.MurmurApp
import app.murmur.core.model.ProductName
import app.murmur.shell.Device.sh
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The idle status notification (ADR-055, WP-15 wiring): with notifications allowed it appears ("<product> is ready",
 * the models line from the resolver) once the accessibility service connects, and goes away when the service stops.
 * Checked in `dumpsys notification`.
 */
@RunWith(AndroidJUnit4::class)
class StatusNotificationTest {
    private val container get() = (Device.context.applicationContext as MurmurApp).container!!

    private fun posted(): String = sh("dumpsys notification --noredact")
        .lineSequence().filter { it.contains("pkg=${Device.pkg}") || it.contains("android.title=") }.joinToString("\n")

    private fun readyShown(): Boolean = sh("dumpsys notification --noredact").let { d ->
        d.contains("android.title=String (${ProductName.NAME} is ready)") || d.contains("android.title=${ProductName.NAME} is ready")
    }

    @After
    fun off() {
        sh("settings put secure enabled_accessibility_services \"\"")
    }

    @Test
    fun readyNotificationFollowsTheServiceConnection() {
        sh("pm grant ${Device.pkg} android.permission.POST_NOTIFICATIONS")
        container.permissions.refresh()
        sh("settings put secure enabled_accessibility_services ${Device.SERVICE}")
        sh("settings put secure accessibility_enabled 1")
        Device.waitFor(what = "service connected") { container.serviceConnected.value }
        Device.waitFor(what = "\"${ProductName.NAME} is ready\" in dumpsys notification") { readyShown() }
        sh("settings put secure enabled_accessibility_services \"\"")
        Device.waitFor(what = "service disconnected") { !container.serviceConnected.value }
        Device.waitFor(what = "notification removed") { !readyShown() }
        assertTrue(posted(), !readyShown())
    }
}
