package app.murmur.core.context

import app.murmur.core.ocr.ScreenText

/** User/settings inputs to the context decision (Settings -> Cleanup / Bubble). */
data class ContextSettings(
    /** Settings "Use what's on screen" (DESIGN 6.5). Off -> no screenshot, privacy app_only. */
    val useScreen: Boolean = true,
    /** Settings "Hidden in" + the built-in denylist. */
    val excludedPackages: Set<String> = emptySet(),
)

/** Decision taken at bubble press, before any screenshot (privacy filters run first, D9). */
data class PrivacyDecision(
    val level: String,
    val takeScreenshot: Boolean,
    /** Short machine code ("password", "secure", "denylist", "setting_off"); never user text. */
    val reason: String?,
    /** Password fields: no LLM call at all (schema v2 "password"), the raw transcript is inserted. */
    val skipLlm: Boolean,
)

/**
 * Pure assembly of ctx v2 (ARCHITECTURE.md section 9). Implemented by WP-08; the skeleton holds the
 * rules as documentation and throws.
 */
interface ContextAssembler {
    fun decide(snapshot: A11ySnapshot, settings: ContextSettings): PrivacyDecision

    /**
     * [screen] null when no screenshot was taken or it failed (secure window -> error 6, black frame, timeout,
     * nothing recognised). [asrLanguage] from the transcript (per-dictation language id).
     */
    fun assemble(snapshot: A11ySnapshot, decision: PrivacyDecision, screen: ScreenText?, asrLanguage: String?): CtxV2
}

/** Platform-independent field kind mapping (lifted from spikes/platform ContextV2.kindOf, ints only). */
object FieldKinds {
    // android.text.InputType constants (stable since API 3); duplicated so this module stays JVM-only.
    const val TYPE_MASK_CLASS = 0x0000000f
    const val TYPE_MASK_VARIATION = 0x00000ff0
    const val TYPE_CLASS_TEXT = 1
    const val TYPE_CLASS_NUMBER = 2
    const val TYPE_CLASS_PHONE = 3
    const val TYPE_TEXT_FLAG_MULTI_LINE = 0x00020000
    const val TYPE_TEXT_VARIATION_EMAIL_ADDRESS = 0x20
    const val TYPE_TEXT_VARIATION_EMAIL_SUBJECT = 0x30
    const val TYPE_TEXT_VARIATION_SHORT_MESSAGE = 0x40
    const val TYPE_TEXT_VARIATION_LONG_MESSAGE = 0x50
    const val TYPE_TEXT_VARIATION_URI = 0x10
    const val TYPE_TEXT_VARIATION_FILTER = 0xb0
    const val TYPE_TEXT_VARIATION_PASSWORD = 0x80
    const val TYPE_TEXT_VARIATION_VISIBLE_PASSWORD = 0x90
    const val TYPE_TEXT_VARIATION_WEB_PASSWORD = 0xe0
    const val TYPE_NUMBER_VARIATION_PASSWORD = 0x10
    const val IME_MASK_ACTION = 0x000000ff
    const val IME_ACTION_SEARCH = 3
    const val IME_ACTION_SEND = 4

    fun isPassword(inputType: Int): Boolean {
        val cls = inputType and TYPE_MASK_CLASS
        val v = inputType and TYPE_MASK_VARIATION
        return (cls == TYPE_CLASS_TEXT && (v == TYPE_TEXT_VARIATION_PASSWORD || v == TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            v == TYPE_TEXT_VARIATION_WEB_PASSWORD)) || (cls == TYPE_CLASS_NUMBER && v == TYPE_NUMBER_VARIATION_PASSWORD)
    }

    /** DESIGN 5.2: the bubble is hidden on password, number and phone fields. */
    fun bubbleAllowed(inputType: Int): Boolean {
        val cls = inputType and TYPE_MASK_CLASS
        return !isPassword(inputType) && cls != TYPE_CLASS_NUMBER && cls != TYPE_CLASS_PHONE
    }

    /** ctx v2 field.kind vocabulary (prompts/render.py FIELD_PHRASES keys). */
    fun ctxKind(inputType: Int, imeOptions: Int, appCategory: String?): String {
        val cls = inputType and TYPE_MASK_CLASS
        val v = inputType and TYPE_MASK_VARIATION
        val action = imeOptions and IME_MASK_ACTION
        if (cls != TYPE_CLASS_TEXT) return "generic"
        return when {
            v == TYPE_TEXT_VARIATION_EMAIL_SUBJECT -> "email_subject"
            v == TYPE_TEXT_VARIATION_URI -> "address"
            v == TYPE_TEXT_VARIATION_FILTER || action == IME_ACTION_SEARCH -> "search"
            v == TYPE_TEXT_VARIATION_SHORT_MESSAGE || action == IME_ACTION_SEND -> "message"
            v == TYPE_TEXT_VARIATION_LONG_MESSAGE -> if (appCategory == "email") "email_body" else "message"
            appCategory == "messaging" || appCategory == "work_chat" -> "message"
            appCategory == "email" -> "email_body"
            appCategory == "notes" -> "note"
            appCategory == "code" -> "code"
            else -> "generic"
        }
    }
}

/** Always-excluded packages (ARCHITECTURE.md 9.2). The user's "Hidden in" list is added on top. */
object BuiltInDenylist {
    val packages: Set<String> = setOf(
        "com.android.systemui",
        "com.android.settings",
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller",
        "com.samsung.android.biometrics.app.setting",
    )
}

/** WP-08 implements the rules of ARCHITECTURE.md 9 here (pure, host-tested). */
class DefaultContextAssembler(private val appCategories: Map<String, String> = emptyMap()) : ContextAssembler {
    override fun decide(snapshot: A11ySnapshot, settings: ContextSettings): PrivacyDecision = TODO("WP-08")
    override fun assemble(snapshot: A11ySnapshot, decision: PrivacyDecision, screen: ScreenText?, asrLanguage: String?): CtxV2 = TODO("WP-08")
}
