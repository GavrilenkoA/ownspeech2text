package app.murmur.core.prompt

import kotlinx.serialization.json.Json

/** Reads the prompt files packaged by the `packagePrompts` Gradle task. */
class ClasspathPromptAssets : PromptAssets {
    private val json = Json { ignoreUnknownKeys = true }

    override fun text(path: String): String =
        javaClass.getResourceAsStream("/prompts/$path")?.use { it.readBytes().decodeToString() }
            ?: error("prompt resource missing: $path")

    override fun systemPrompt(stem: String): String = text("system/$stem.md")

    override fun profile(name: String): PromptProfile {
        val res = javaClass.getResourceAsStream("/prompts/profiles/$name.json")
            ?: return PromptProfile(name = name, system = name, shots = null)  // bare system name (render.load_profile)
        val p = json.decodeFromString(PromptProfile.serializer(), res.use { it.readBytes().decodeToString() })
        return if (p.name.isEmpty()) p.copy(name = name) else p
    }

    override fun shots(path: String): List<Shot> =
        text(path).lineSequence().filter { it.isNotBlank() }.map { json.decodeFromString(Shot.serializer(), it) }.toList()
}
