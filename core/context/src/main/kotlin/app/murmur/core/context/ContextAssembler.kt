package app.murmur.core.context

import app.murmur.core.ocr.OcrLine
import app.murmur.core.ocr.Rect
import app.murmur.core.ocr.ScreenText

/** User/settings inputs to the context decision (Settings -> Cleanup / Bubble). */
data class ContextSettings(
    /** Settings "Use what's on screen" (DESIGN 6.5). Off -> no screenshot, privacy app_only. */
    val useScreen: Boolean = true,
    /**
     * Settings "Hidden in" (Settings.hiddenInPackages). [BuiltInDenylist] is always added by the assembler, so callers
     * pass only the user's list (passing the union is harmless).
     */
    val excludedPackages: Set<String> = emptySet(),
    /** The press comes from the in-app practice field (StartMode.IN_APP): the only dictation allowed into Murmur. */
    val inAppPractice: Boolean = false,
)

/** Decision taken at bubble press, before any screenshot (privacy filters run first, D9; ARCHITECTURE 9.2). */
data class PrivacyDecision(
    val level: String,
    val takeScreenshot: Boolean,
    /** [PrivacyReason] code (schema v2 `privacy.reason`) when the screen is withheld by a rule; never user text. */
    val reason: String?,
    /** Password fields: no LLM call at all (schema v2 "password"), the raw transcript is inserted. */
    val skipLlm: Boolean,
    /**
     * false where the bubble is hidden and no dictation starts (DESIGN 5.2, ARCHITECTURE 9.2): password, number and
     * phone fields, built-in denylist, the user's "Hidden in" list, Murmur's own UI outside the in-app practice.
     * The level above is still the one to use if a dictation is started anyway.
     */
    val dictationAllowed: Boolean = true,
)

/**
 * What became of the screenshot by the time the context is assembled (ARCHITECTURE 9.1/9.3). It decides
 * `privacy.reason` (and through prompts/render.py NO_SCREEN_LEVEL the level) when there is no screen.
 */
sealed interface ScreenOutcome {
    /** OCR result; no lines = `ocr_empty`. */
    data class Text(val text: ScreenText) : ScreenOutcome
    /** The decision took no screenshot (privacy rule): the decision's reason stands. */
    data object NotTaken : ScreenOutcome
    /** Error 6 or a black frame (FLAG_SECURE). */
    data object SecureWindow : ScreenOutcome
    /** Any other capture failure, a timeout, or OCR that did not finish in time (CONTEXT_WAIT). */
    data object Failed : ScreenOutcome
}

/** Pure assembly of ctx v2 (ARCHITECTURE.md section 9, docs/ml/CONTEXT_V2.md). */
interface ContextAssembler {
    fun decide(snapshot: A11ySnapshot, settings: ContextSettings): PrivacyDecision

    /**
     * [screen] null when no screenshot was taken or it failed (secure window -> error 6, black frame, timeout,
     * nothing recognised). [asrLanguage] from the transcript (per-dictation language id). Without the cause of a
     * missing screen this reports `screenshot_error`; callers that know it use the [ScreenOutcome] overload.
     */
    fun assemble(snapshot: A11ySnapshot, decision: PrivacyDecision, screen: ScreenText?, asrLanguage: String?): CtxV2

    /** As above with the cause of a missing screen (secure window vs error vs nothing recognised). */
    fun assemble(snapshot: A11ySnapshot, decision: PrivacyDecision, screen: ScreenOutcome, asrLanguage: String?): CtxV2 =
        assemble(snapshot, decision, (screen as? ScreenOutcome.Text)?.text, asrLanguage)
}

/** Platform-independent field kind mapping (lifted from spikes/platform ContextV2.kindOf, ints only). */
object FieldKinds {
    // android.text.InputType constants (stable since API 3); duplicated so this module stays JVM-only.
    const val TYPE_MASK_CLASS = 0x0000000f
    const val TYPE_MASK_VARIATION = 0x00000ff0
    const val TYPE_CLASS_TEXT = 1
    const val TYPE_CLASS_NUMBER = 2
    const val TYPE_CLASS_PHONE = 3
    const val TYPE_CLASS_DATETIME = 4
    const val TYPE_TEXT_FLAG_CAP_SENTENCES = 0x00004000
    const val TYPE_TEXT_FLAG_MULTI_LINE = 0x00020000
    const val TYPE_TEXT_VARIATION_NORMAL = 0x00
    const val TYPE_TEXT_VARIATION_URI = 0x10
    const val TYPE_TEXT_VARIATION_EMAIL_ADDRESS = 0x20
    const val TYPE_TEXT_VARIATION_EMAIL_SUBJECT = 0x30
    const val TYPE_TEXT_VARIATION_SHORT_MESSAGE = 0x40
    const val TYPE_TEXT_VARIATION_LONG_MESSAGE = 0x50
    const val TYPE_TEXT_VARIATION_PERSON_NAME = 0x60
    const val TYPE_TEXT_VARIATION_POSTAL_ADDRESS = 0x70
    const val TYPE_TEXT_VARIATION_PASSWORD = 0x80
    const val TYPE_TEXT_VARIATION_VISIBLE_PASSWORD = 0x90
    const val TYPE_TEXT_VARIATION_WEB_EDIT_TEXT = 0xa0
    const val TYPE_TEXT_VARIATION_FILTER = 0xb0
    const val TYPE_TEXT_VARIATION_PHONETIC = 0xc0
    const val TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS = 0xd0
    const val TYPE_TEXT_VARIATION_WEB_PASSWORD = 0xe0
    const val TYPE_NUMBER_VARIATION_PASSWORD = 0x10
    const val IME_MASK_ACTION = 0x000000ff
    const val IME_ACTION_SEARCH = 3
    const val IME_ACTION_SEND = 4

    /** Text-class variation -> android.text.InputType constant name (prompts/tones/tones.v1.json field_overrides keys). */
    private val TEXT_VARIATION_NAMES = mapOf(
        TYPE_TEXT_VARIATION_URI to "TYPE_TEXT_VARIATION_URI",
        TYPE_TEXT_VARIATION_EMAIL_ADDRESS to "TYPE_TEXT_VARIATION_EMAIL_ADDRESS",
        TYPE_TEXT_VARIATION_EMAIL_SUBJECT to "TYPE_TEXT_VARIATION_EMAIL_SUBJECT",
        TYPE_TEXT_VARIATION_SHORT_MESSAGE to "TYPE_TEXT_VARIATION_SHORT_MESSAGE",
        TYPE_TEXT_VARIATION_LONG_MESSAGE to "TYPE_TEXT_VARIATION_LONG_MESSAGE",
        TYPE_TEXT_VARIATION_PERSON_NAME to "TYPE_TEXT_VARIATION_PERSON_NAME",
        TYPE_TEXT_VARIATION_POSTAL_ADDRESS to "TYPE_TEXT_VARIATION_POSTAL_ADDRESS",
        TYPE_TEXT_VARIATION_PASSWORD to "TYPE_TEXT_VARIATION_PASSWORD",
        TYPE_TEXT_VARIATION_VISIBLE_PASSWORD to "TYPE_TEXT_VARIATION_VISIBLE_PASSWORD",
        TYPE_TEXT_VARIATION_WEB_EDIT_TEXT to "TYPE_TEXT_VARIATION_WEB_EDIT_TEXT",
        TYPE_TEXT_VARIATION_FILTER to "TYPE_TEXT_VARIATION_FILTER",
        TYPE_TEXT_VARIATION_PHONETIC to "TYPE_TEXT_VARIATION_PHONETIC",
        TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS to "TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS",
        TYPE_TEXT_VARIATION_WEB_PASSWORD to "TYPE_TEXT_VARIATION_WEB_PASSWORD",
    )

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

    /** Snapshot-level password check: the node flag or the inputType variation (ARCHITECTURE 9.2 row 1). */
    fun isPassword(field: FieldSnapshot): Boolean = field.isPassword || isPassword(field.inputType)

    /** ctx v2 field.kind vocabulary (prompts/render.py FIELD_PHRASES keys). */
    fun ctxKind(inputType: Int, imeOptions: Int, appCategory: String?): String {
        val cls = inputType and TYPE_MASK_CLASS
        val v = inputType and TYPE_MASK_VARIATION
        val action = imeOptions and IME_MASK_ACTION
        if (cls != TYPE_CLASS_TEXT) return "generic"
        return when {
            v == TYPE_TEXT_VARIATION_EMAIL_SUBJECT -> "email_subject"
            v == TYPE_TEXT_VARIATION_URI || v == TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                v == TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS || v == TYPE_TEXT_VARIATION_POSTAL_ADDRESS -> "address"
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

    /**
     * ctx v2 field.input_type_variation: the InputType constant name of a text field's variation (read by
     * prompts/render.py style_line through tones.v1.json field_overrides). null for NORMAL and non-text classes.
     */
    fun variationName(inputType: Int): String? =
        if ((inputType and TYPE_MASK_CLASS) != TYPE_CLASS_TEXT) null else TEXT_VARIATION_NAMES[inputType and TYPE_MASK_VARIATION]
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

/**
 * The rules of ARCHITECTURE.md 9.2 / 9.4 and docs/ml/CONTEXT_V2.md (pure, host-tested).
 *
 * [appCategories]: package -> style category (prompts/tones/app_categories.v1.json, bundled). [ownPackage]: Murmur's
 * application id; its UI is never a dictation target except the in-app practice.
 */
class DefaultContextAssembler(
    private val appCategories: Map<String, String> = AppCategories.bundled(),
    private val ownPackage: String = OWN_PACKAGE,
) : ContextAssembler {

    override fun decide(snapshot: A11ySnapshot, settings: ContextSettings): PrivacyDecision {
        val field = snapshot.field
        val pkg = snapshot.pkg
        return when {
            field != null && FieldKinds.isPassword(field) ->
                PrivacyDecision(PrivacyLevel.NONE, false, PrivacyReason.PASSWORD_FIELD, skipLlm = true, dictationAllowed = false)
            pkg in BuiltInDenylist.packages || pkg in settings.excludedPackages ->
                PrivacyDecision(PrivacyLevel.APP_ONLY, false, PrivacyReason.DENYLISTED_APP, skipLlm = false, dictationAllowed = false)
            pkg == ownPackage ->
                PrivacyDecision(PrivacyLevel.APP_ONLY, false, PrivacyReason.DENYLISTED_APP, skipLlm = false,
                    dictationAllowed = settings.inAppPractice)
            else -> {
                val allowed = field == null || FieldKinds.bubbleAllowed(field.inputType)
                if (!settings.useScreen) {
                    PrivacyDecision(PrivacyLevel.APP_ONLY, false, PrivacyReason.USER_SETTING, skipLlm = false, dictationAllowed = allowed)
                } else {
                    PrivacyDecision(PrivacyLevel.FULL, true, null, skipLlm = false, dictationAllowed = allowed)
                }
            }
        }
    }

    override fun assemble(snapshot: A11ySnapshot, decision: PrivacyDecision, screen: ScreenText?, asrLanguage: String?): CtxV2 {
        val outcome = when {
            screen != null -> ScreenOutcome.Text(screen)
            decision.takeScreenshot -> ScreenOutcome.Failed
            else -> ScreenOutcome.NotTaken
        }
        return assemble(snapshot, decision, outcome, asrLanguage)
    }

    override fun assemble(snapshot: A11ySnapshot, decision: PrivacyDecision, screen: ScreenOutcome, asrLanguage: String?): CtxV2 {
        val fieldSnap = snapshot.field
        val password = fieldSnap != null && FieldKinds.isPassword(fieldSnap)
        val text = (screen as? ScreenOutcome.Text)?.text?.takeIf { it.size.size == 2 && it.size.all { v -> v > 0 } }
        val lines = text?.lines.orEmpty().filter { it.conf.isFinite() }.take(MAX_LINES)

        // Privacy: a password focus always wins (focus may have moved since decide()); a rule that withheld the
        // screen keeps its level and reason; otherwise the cause of a missing screen picks reason and level.
        val privacy = when {
            password -> Privacy(PrivacyLevel.NONE, PrivacyReason.PASSWORD_FIELD)
            decision.level != PrivacyLevel.FULL -> Privacy(decision.level, decision.reason)
            lines.isNotEmpty() -> Privacy(PrivacyLevel.FULL, null)
            else -> {
                val reason = when {
                    screen is ScreenOutcome.SecureWindow -> PrivacyReason.SECURE_WINDOW
                    screen is ScreenOutcome.Text && text != null -> PrivacyReason.OCR_EMPTY
                    else -> PrivacyReason.SCREENSHOT_ERROR
                }
                Privacy(PrivacyReason.noScreenLevel(reason), reason)
            }
        }
        val level = privacy.level
        val withScreen = !password && decision.level == PrivacyLevel.FULL && lines.isNotEmpty()

        val category = appCategories[snapshot.pkg]?.takeIf { it.isNotBlank() && it.codePointLength() <= MAX_CATEGORY }
        val app = AppInfo(
            pkg = snapshot.pkg.clipStart(MAX_PACKAGE),
            label = snapshot.label?.takeIf { it.isNotBlank() && level != PrivacyLevel.NONE }?.clipStart(MAX_LABEL),
            category = category,
        )

        // field.box in screenshot pixels: the capture's frame when there is a screen, else the frame the window capture
        // would have had; with no window bounds either, screen pixels as they are.
        val frame = if (withScreen) CaptureFrame.of(snapshot.windowBoundsInScreen, text!!.size[0], text.size[1])
        else CaptureFrame.ofWindow(snapshot.windowBoundsInScreen)
        fun boxOf(b: Rect): List<Int>? = if (frame != null) frame.toCapture(b) else listOf(b.x0, b.y0, b.x1, b.y1)
        val field = fieldSnap?.let { f ->
            FieldInfo(
                kind = FieldKinds.ctxKind(f.inputType, f.imeOptions, category),
                hint = f.hint?.takeIf { level != PrivacyLevel.NONE }?.clipStart(MAX_HINT),
                beforeCursor = f.beforeCursor?.takeIf { level == PrivacyLevel.FULL && !password }?.clipEnd(MAX_BEFORE_CURSOR),
                box = f.boundsInScreen?.let(::boxOf),
                inputTypeVariation = FieldKinds.variationName(f.inputType),
                password = if (password) true else null,
            )
        }

        return CtxV2(
            app = app,
            field = field,
            screen = if (withScreen) Screen(text!!.size, lines.map { it.forSchema() }) else null,
            language = asrLanguage?.takeIf { it.isNotBlank() && it.codePointLength() <= MAX_LANGUAGE }?.let { LanguageInfo(it) },
            privacy = privacy,
        )
    }

    companion object {
        /** applicationId of :app (app/build.gradle.kts); the :asr and :llm processes share it. */
        const val OWN_PACKAGE = "app.murmur"

        // prompts/context.schema.v2.json limits (code points, as JSON Schema counts them).
        const val MAX_PACKAGE = 200
        const val MAX_LABEL = 100
        const val MAX_CATEGORY = 40
        const val MAX_HINT = 200
        const val MAX_BEFORE_CURSOR = 4000
        const val MAX_LANGUAGE = 8
        const val MAX_LINES = 400
        const val MAX_LINE_TEXT = 1000

        private fun OcrLine.forSchema(): OcrLine {
            val t = text.clipStart(MAX_LINE_TEXT)
            val c = conf.coerceIn(0.0, 1.0)
            return if (t == text && c == conf) this else OcrLine(t, c, box)
        }
    }
}

/** Number of Unicode code points (JSON Schema maxLength unit). */
internal fun String.codePointLength(): Int = codePointCount(0, length)

/** The first [max] code points (never splits a surrogate pair). */
internal fun String.clipStart(max: Int): String =
    if (codePointLength() <= max) this else substring(0, offsetByCodePoints(0, max))

/** The last [max] code points (the text right before the cursor matters most). */
internal fun String.clipEnd(max: Int): String {
    val n = codePointLength()
    return if (n <= max) this else substring(offsetByCodePoints(0, n - max))
}
