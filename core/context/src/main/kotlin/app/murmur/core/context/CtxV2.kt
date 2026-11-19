package app.murmur.core.context

import app.murmur.core.ocr.OcrLine
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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

/**
 * `privacy` is the one object whose null is written out: `{"level": "full", "reason": null}` when a screen is present,
 * exactly as ml/screens/build_ctx.py and the prompt goldens write it. Every other null is absent (explicitNulls = false).
 */
@Serializable(with = PrivacySerializer::class)
data class Privacy(val level: String = PrivacyLevel.FULL, val reason: String? = null)

object PrivacyLevel {
    const val FULL = "full"
    const val APP_ONLY = "app_only"
    const val NONE = "none"
}

/** `privacy.reason` values (prompts/context.schema.v2.json enum; prompts/render.py NO_SCREEN_LEVEL). */
object PrivacyReason {
    const val SECURE_WINDOW = "secure_window"
    const val DENYLISTED_APP = "denylisted_app"
    const val PASSWORD_FIELD = "password_field"
    const val SCREENSHOT_ERROR = "screenshot_error"
    const val OCR_EMPTY = "ocr_empty"
    const val USER_SETTING = "user_setting"

    /**
     * prompts/render.py NO_SCREEN_LEVEL: the level sent with each reason when there is no screen. A password field
     * is level none and never reaches the LLM (ADR-070; ContextAssembler decides it with skipLlm). user_setting is
     * not in render.py's table but documented as app_only (docs/ml/CONTEXT_V2.md, ADR-085).
     */
    fun noScreenLevel(reason: String): String = when (reason) {
        PASSWORD_FIELD -> PrivacyLevel.NONE
        SECURE_WINDOW, DENYLISTED_APP, USER_SETTING -> PrivacyLevel.APP_ONLY
        else -> PrivacyLevel.FULL
    }
}

/** JSON-only serializer for [Privacy]: both keys always present, reason as an explicit null. */
object PrivacySerializer : KSerializer<Privacy> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("app.murmur.core.context.Privacy") {
        element<String>("level")
        element<String?>("reason")
    }

    override fun serialize(encoder: Encoder, value: Privacy) {
        val json = encoder as? JsonEncoder ?: throw SerializationException("Privacy is JSON-only")
        json.encodeJsonElement(
            JsonObject(mapOf("level" to JsonPrimitive(value.level), "reason" to (value.reason?.let(::JsonPrimitive) ?: JsonNull))),
        )
    }

    override fun deserialize(decoder: Decoder): Privacy {
        val json = decoder as? JsonDecoder ?: throw SerializationException("Privacy is JSON-only")
        val obj = json.decodeJsonElement().jsonObject
        val unknown = obj.keys - setOf("level", "reason")
        if (unknown.isNotEmpty()) throw SerializationException("unknown privacy keys $unknown")
        return Privacy(
            level = obj["level"]?.jsonPrimitive?.contentOrNull ?: PrivacyLevel.FULL,
            reason = obj["reason"]?.jsonPrimitive?.contentOrNull,
        )
    }
}
