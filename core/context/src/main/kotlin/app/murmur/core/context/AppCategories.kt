package app.murmur.core.context

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Package -> style category from prompts/tones/app_categories.v1.json (D8: prompts/ is the single source; the build
 * packages the file into this module's resources at [RESOURCE], see build.gradle.kts). Unknown packages have no
 * category (ctx omits it; the renderer uses "default").
 */
object AppCategories {
    /** Own path, so the APK never holds two copies under one name next to :core:prompt's /prompts/tones/ resources. */
    const val RESOURCE = "/app/murmur/core/context/app_categories.v1.json"

    /** Parses the file format: `{"version": 1, "packages": {"<package>": "<category>"}}`. */
    fun parse(json: String): Map<String, String> {
        val root = Json.parseToJsonElement(json).jsonObject
        val version = root["version"]?.jsonPrimitive?.content
        require(version == "1") { "app_categories version $version" }
        val packages = root["packages"]?.jsonObject ?: throw IllegalArgumentException("app_categories: no packages")
        return packages.mapValues { (_, v) -> v.jsonPrimitive.content }
    }

    private val bundled: Map<String, String> by lazy {
        val stream = AppCategories::class.java.getResourceAsStream(RESOURCE)
            ?: throw IllegalStateException("$RESOURCE missing from the classpath")
        parse(stream.use { it.readBytes().decodeToString() })
    }

    fun bundled(): Map<String, String> = bundled
}
