package app.murmur.e2e

import app.murmur.platform.a11y.A11yBridge
import app.murmur.platform.a11y.MurmurAccessibilityService

/**
 * The accessibility service, switched the way the brief says (`settings put secure enabled_accessibility_services
 * app.murmur/app.murmur.platform.a11y.MurmurAccessibilityService`). Connected = the service published itself on
 * [A11yBridge] (its onServiceConnected ran in this process).
 */
object Accessibility {
    private const val SETTING = "enabled_accessibility_services"
    val component = "${E2e.PKG}/${MurmurAccessibilityService::class.java.name}"

    val connected: Boolean get() = A11yBridge.service.value != null

    fun enable(timeoutMs: Long = 15_000) {
        if (connected && enabledInSettings()) return
        // A new instrumentation run killed the previous :main, and the system keeps the service marked as crashed until
        // the setting changes: turn it off first, then on (WP-02 found the same on API 36).
        E2e.shell("settings delete secure $SETTING")
        E2e.shell("settings put secure $SETTING $component")
        E2e.shell("settings put secure accessibility_enabled 1")
        E2e.waitUntil("accessibility service connected", timeoutMs, pollMs = 100) { connected }
    }

    fun disable(timeoutMs: Long = 10_000) {
        E2e.shell("settings delete secure $SETTING")
        E2e.waitUntil("accessibility service disconnected", timeoutMs, pollMs = 100) { !connected }
    }

    fun enabledInSettings(): Boolean = E2e.shell("settings get secure $SETTING").contains(component)
}
