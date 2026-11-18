package app.murmur.core.store

import app.murmur.core.model.ArtifactId
import app.murmur.core.model.ModelId
import app.murmur.core.model.UnitPreference
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/**
 * The whole settings model (DESIGN 6.5 + hub selections + bubble positions). One typed document in
 * DataStore (ADR-012). Defaults here are the product defaults.
 */
@Serializable
data class Settings(
    // Dictation
    val languages: List<String> = listOf("ru", "en"),
    val autoDetectLanguage: Boolean = true,
    val holdToTalk: Boolean = true,
    val feedback: Feedback = Feedback.VIBRATION,
    val typingWpm: Int = 40,
    // Bubble
    val bubbleVisibility: BubbleVisibility = BubbleVisibility.WHEN_TYPING,
    val tuckIn: Boolean = true,
    val bubbleSize: BubbleSize = BubbleSize.STANDARD,
    val hiddenInPackages: Set<String> = emptySet(),
    /** Per display ("cover"/"inner" by displayId + size class): side + y fraction (DESIGN 5.4). */
    val bubblePositions: Map<String, BubblePosition> = emptyMap(),
    val hiddenUntilEpochMs: Long? = null,
    // Cleanup
    /** null = shipped default system prompt of the active profile; else the user's edited text. */
    val promptOverride: PromptOverride? = null,
    val preferences: String? = null,
    /** package -> tone (DESIGN "Tone per app"); missing = category default. */
    val tonePerApp: Map<String, Tone> = emptyMap(),
    val useScreen: Boolean = true,
    // History and privacy
    val retentionDays: Int = RetentionPolicy.DEFAULT_DAYS,
    val keepRecordings: Boolean = true,
    // Models
    val asrModel: ModelId? = null,
    val cleanup: CleanupChoice = CleanupChoice.Default,
    val livePreviewModel: ModelId? = null,
    val unitPreference: Map<String, UnitPreference> = emptyMap(),   // ArtifactId.value -> preference
    val wifiOnlyDownloads: Boolean = true,
    /** D5: keep inference processes warm this long after a dictation, then unload + exit. */
    val idleUnloadSec: Int = 60,
    // Onboarding / debug
    val onboardingDone: Boolean = false,
    /** Debug builds only: run the pipeline on :testing fakes (D6). Ignored in release. */
    val debugFakeEngines: Boolean = false,
)

@Serializable enum class Feedback { VIBRATION, SOUND, BOTH, OFF }
@Serializable enum class BubbleVisibility { WHEN_TYPING, ALWAYS }
@Serializable enum class BubbleSize(val dp: Int) { SMALL(44), STANDARD(52), LARGE(60) }
@Serializable enum class Tone { CASUAL, NEUTRAL, FORMAL, VERBATIM }
@Serializable data class BubblePosition(val rightEdge: Boolean, val yFraction: Float)

/** [baseProfile] = the shipped profile the user started editing from ("Edited" detection, DESIGN 6.5). */
@Serializable data class PromptOverride(val systemText: String, val baseProfile: String)

@Serializable
sealed interface CleanupChoice {
    /** The catalog default (defaults.llm). */
    @Serializable data object Default : CleanupChoice
    /** "None": insert the transcript as recognised (bubble skips Polishing). */
    @Serializable data object None : CleanupChoice
    @Serializable data class Model(val id: ModelId) : CleanupChoice
}

interface SettingsRepository {
    val settings: StateFlow<Settings>
    suspend fun update(transform: (Settings) -> Settings)
}
