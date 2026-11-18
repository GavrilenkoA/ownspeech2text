package app.murmur.core.context

import app.murmur.core.ocr.OcrLine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * ctx v2 exactly as prompts/context.schema.v2.json (binding: docs/ml/CONTEXT_V2.md, D11).
 * Field names are the JSON names; the Kotlin renderer consumes this object. In memory only: never
 * persisted, never logged, never sent anywhere (D9).
 */
@Serializable
data class CtxV2(
    val v: Int = 2,
    val app: AppInfo,
    val field: FieldInfo? = null,
    val screen: Screen? = null,
    val language: LanguageInfo? = null,
    val privacy: Privacy? = null,
) {
    companion object {
        val json = Json { explicitNulls = false; encodeDefaults = true; ignoreUnknownKeys = false }
    }
}

@Serializable
data class AppInfo(
    @SerialName("package") val pkg: String,
    val label: String? = null,
    /** prompts/tones/app_categories.v1.json value; unknown -> null (renderer uses "default"). */
    val category: String? = null,
)

@Serializable
data class FieldInfo(
    /** message | email_body | email_subject | search | address | note | code | generic. */
    val kind: String? = null,
    val hint: String? = null,
    @SerialName("before_cursor") val beforeCursor: String? = null,
    /** [x0,y0,x1,y1] of the focused node in screenshot pixels. */
    val box: List<Int>? = null,
    @SerialName("input_type_variation") val inputTypeVariation: String? = null,
    val password: Boolean? = null,
)

@Serializable
data class Screen(val size: List<Int>, val lines: List<OcrLine>)

@Serializable
data class LanguageInfo(val asr: String? = null)

@Serializable
data class Privacy(val level: String = PrivacyLevel.FULL, val reason: String? = null)

object PrivacyLevel {
    const val FULL = "full"
    const val APP_ONLY = "app_only"
    const val NONE = "none"
}
