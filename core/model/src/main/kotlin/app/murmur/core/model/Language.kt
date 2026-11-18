package app.murmur.core.model

import kotlinx.serialization.Serializable

/** ISO 639-1 code, lowercase ("ru", "en"). */
@Serializable
@JvmInline
value class Language(val code: String) {
    init {
        require(code.length in 2..8 && code == code.lowercase()) { "bad language code: $code" }
    }

    override fun toString() = code

    companion object {
        val RU = Language("ru")
        val EN = Language("en")
    }
}
