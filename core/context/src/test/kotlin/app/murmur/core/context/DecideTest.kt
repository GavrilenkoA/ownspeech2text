package app.murmur.core.context

import app.murmur.core.context.Fixtures.field
import app.murmur.core.context.Fixtures.snapshot
import org.junit.Assert.assertEquals
import org.junit.Test

/** ARCHITECTURE 9.2 privacy filters, one row per case (WP-08 acceptance table). */
class DecideTest {
    private val assembler = DefaultContextAssembler()
    private val on = ContextSettings()

    private data class Row(
        val name: String,
        val snapshot: A11ySnapshot,
        val settings: ContextSettings,
        val expected: PrivacyDecision,
    )

    private fun full(allowed: Boolean = true) = PrivacyDecision(PrivacyLevel.FULL, true, null, skipLlm = false, dictationAllowed = allowed)
    private fun appOnly(reason: String, allowed: Boolean) = PrivacyDecision(PrivacyLevel.APP_ONLY, false, reason, skipLlm = false, dictationAllowed = allowed)
    private val password = PrivacyDecision(PrivacyLevel.NONE, false, PrivacyReason.PASSWORD_FIELD, skipLlm = true, dictationAllowed = false)

    private val rows = listOf(
        Row("plain chat field", snapshot(), on, full()),
        Row("no focused field", snapshot(field = null), on, full()),
        Row("password via node flag", snapshot(field = field(isPassword = true, beforeCursor = null)), on, password),
        Row("password via text variation", snapshot(field = field(inputType = FieldKinds.TYPE_CLASS_TEXT or FieldKinds.TYPE_TEXT_VARIATION_PASSWORD)), on, password),
        Row("password via visible-password variation", snapshot(field = field(inputType = FieldKinds.TYPE_CLASS_TEXT or FieldKinds.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)), on, password),
        Row("password via web-password variation", snapshot(field = field(inputType = FieldKinds.TYPE_CLASS_TEXT or FieldKinds.TYPE_TEXT_VARIATION_WEB_PASSWORD)), on, password),
        Row("numeric PIN", snapshot(field = field(inputType = FieldKinds.TYPE_CLASS_NUMBER or FieldKinds.TYPE_NUMBER_VARIATION_PASSWORD)), on, password),
        Row("password wins over setting off", snapshot(field = field(isPassword = true)), ContextSettings(useScreen = false), password),
        Row("password wins over denylist", snapshot(pkg = "com.android.settings", field = field(isPassword = true)), on, password),
        Row("number field: bubble not allowed", snapshot(field = field(inputType = FieldKinds.TYPE_CLASS_NUMBER)), on, full(allowed = false)),
        Row("phone field: bubble not allowed", snapshot(field = field(inputType = FieldKinds.TYPE_CLASS_PHONE)), on, full(allowed = false)),
        Row("phone field, setting off", snapshot(field = field(inputType = FieldKinds.TYPE_CLASS_PHONE)), ContextSettings(useScreen = false),
            appOnly(PrivacyReason.USER_SETTING, allowed = false)),
        Row("SystemUI is never a target", snapshot(pkg = "com.android.systemui"), on, appOnly(PrivacyReason.DENYLISTED_APP, allowed = false)),
        Row("built-in denylist: Settings", snapshot(pkg = "com.android.settings"), on, appOnly(PrivacyReason.DENYLISTED_APP, allowed = false)),
        Row("built-in denylist: permission controller", snapshot(pkg = "com.google.android.permissioncontroller"), on,
            appOnly(PrivacyReason.DENYLISTED_APP, allowed = false)),
        Row("built-in denylist: biometrics", snapshot(pkg = "com.samsung.android.biometrics.app.setting"), on,
            appOnly(PrivacyReason.DENYLISTED_APP, allowed = false)),
        Row("user's Hidden in list", snapshot(pkg = "com.whatsapp"), ContextSettings(excludedPackages = setOf("com.whatsapp")),
            appOnly(PrivacyReason.DENYLISTED_APP, allowed = false)),
        Row("own package outside the practice", snapshot(pkg = DefaultContextAssembler.OWN_PACKAGE), on,
            appOnly(PrivacyReason.DENYLISTED_APP, allowed = false)),
        Row("own package, in-app practice", snapshot(pkg = DefaultContextAssembler.OWN_PACKAGE), ContextSettings(inAppPractice = true),
            appOnly(PrivacyReason.DENYLISTED_APP, allowed = true)),
        Row("in-app practice flag does not open other denylisted apps", snapshot(pkg = "com.android.settings"),
            ContextSettings(inAppPractice = true), appOnly(PrivacyReason.DENYLISTED_APP, allowed = false)),
        Row("Use what's on screen off", snapshot(), ContextSettings(useScreen = false), appOnly(PrivacyReason.USER_SETTING, allowed = true)),
    )

    @Test
    fun decisionTable() {
        for (r in rows) assertEquals(r.name, r.expected, assembler.decide(r.snapshot, r.settings))
    }

    @Test
    fun screenshotOnlyWhenFull() {
        for (r in rows) {
            val d = assembler.decide(r.snapshot, r.settings)
            assertEquals(r.name, d.level == PrivacyLevel.FULL, d.takeScreenshot)
            assertEquals(r.name, d.level == PrivacyLevel.FULL, d.reason == null)
        }
    }

    @Test
    fun customOwnPackage() {
        val debug = DefaultContextAssembler(ownPackage = "app.murmur.debug")
        assertEquals(appOnly(PrivacyReason.DENYLISTED_APP, allowed = false), debug.decide(snapshot(pkg = "app.murmur.debug"), on))
        assertEquals(full(), debug.decide(snapshot(pkg = "org.telegram.messenger"), on))
    }
}
