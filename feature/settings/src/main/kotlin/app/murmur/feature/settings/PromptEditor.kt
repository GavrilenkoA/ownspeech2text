package app.murmur.feature.settings

import app.murmur.core.model.MurmurError
import app.murmur.core.model.Outcome
import app.murmur.core.prompt.PromptAssets
import app.murmur.core.prompt.PromptEditRules
import app.murmur.core.prompt.PromptEditState
import app.murmur.core.prompt.PromptProfile
import app.murmur.core.store.PromptOverride
import app.murmur.core.store.Settings
import app.murmur.core.store.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The "Try it" card (DESIGN 6.5): nothing run yet, running (the card shows Polishing), a result, or why not. */
sealed interface TryState {
    data object Idle : TryState
    data object Polishing : TryState
    data class Done(val text: String) : TryState
    data class Failed(val reason: TryFailure) : TryState
}

/** Why "Try it" produced no cleaned text; each has its own line in the card. */
enum class TryFailure {
    /** No cleanup model is downloaded (WP-19 brief: "No cleanup model downloaded"). */
    NO_MODEL,
    /** Cleanup is set to None in Models. */
    CLEANUP_OFF,
    /** The resolver needs a speech model before it resolves the cleanup model. */
    NO_SPEECH_MODEL,
    /** The memory gate refused to load the model now (ADR-067). */
    LOW_MEMORY,
    /** The output guard rejected the model's answer (the dictation would insert the transcript as spoken). */
    REJECTED,
    /** Anything else (load error, timeout, process death). */
    FAILED,
    ;

    companion object {
        /** Errors of `DictationOrchestrator.tryCleanup` (doTryCleanup / cleanupOffline) mapped onto the card's lines. */
        fun of(error: MurmurError): TryFailure = when {
            error is MurmurError.LlmFailed && error.detail == "no_model" -> NO_MODEL
            error is MurmurError.LlmFailed && error.detail == "none" -> CLEANUP_OFF
            error is MurmurError.NoAsrModel -> NO_SPEECH_MODEL
            error is MurmurError.OutOfMemory -> LOW_MEMORY
            error is MurmurError.GuardRejected || error is MurmurError.NoSpeech -> REJECTED
            else -> FAILED
        }
    }
}

/** What the editor shows above the text (ADR-046, ADR-078; no frame: banner components). */
enum class PromptNotice {
    NONE,
    /** Fine-tune profile: read-only, "This model was trained on its own prompt". */
    FINE_TUNE,
    /** Fine-tune profile with an edit stored for a stock profile: "Your edit is kept for the standard prompt" (no Reset). */
    FINE_TUNE_EDIT_KEPT,
    /** Stock profile, edit made for another profile: "Your edit was made for an older prompt" + Reset / Keep editing. */
    OLDER_PROMPT,
}

/** Validation problems of the editor; keys of [PromptEditRules.validateSystem] plus the preferences limit. */
data class PromptProblems(val system: List<String>, val preferencesTooLong: Boolean) {
    val any: Boolean get() = system.isNotEmpty() || preferencesTooLong
}

/** Snapshot of the editor for the UI. */
data class PromptEditorUi(
    val profile: PromptProfile,
    val text: String,
    val preferences: String,
    val editable: Boolean,
    val notice: PromptNotice,
    val problems: PromptProblems,
    /** Reset (top bar) is offered: an editable profile whose text or stored edit differs from the shipped default. */
    val canReset: Boolean,
    val canSave: Boolean,
    /** Unsaved changes (back asks before discarding them). */
    val dirty: Boolean,
    val tryState: TryState,
)

/**
 * Settings -> Cleanup prompt (ADR-019, ADR-046, ADR-078). Holds the unsaved system-prompt text and "My preferences"
 * for the active [profile], validates them with [PromptEditRules], runs "Try it" through [tryCleanup] with the unsaved
 * texts, and writes only through [SettingsRepository.update] (the orchestrator rebuilds the prefix itself).
 *
 * The editor starts from the user's stored edit when there is one for an editable profile (also an edit made for
 * an older profile, so that "Keep editing" continues it), else from the shipped `system/<stem>.md` of the profile.
 * Saving the shipped text stores no edit: "Edited" means the stored text differs from the version the user started
 * from (DESIGN 6.5).
 */
class PromptEditor(
    val profile: PromptProfile,
    private val prompts: PromptAssets,
    private val settings: SettingsRepository,
    private val tryCleanup: suspend (sample: String, systemOverride: String?, preferences: String?) -> Outcome<String>,
    private val scope: CoroutineScope,
    initial: Settings,
) {
    /** The shipped system prompt of [profile] (trailing newlines removed, as the renderer reads it). */
    val shipped: String = shippedText(prompts, profile)
    val editable: Boolean = PromptEditRules.editable(profile)
    private val stateAtOpen: PromptEditState = PromptEditRules.editState(profile, initial.promptOverride?.baseProfile)

    private var savedText: String = initialText(initial)
    private var savedPreferences: String = initial.preferences.orEmpty()
    private var storedOverride: PromptOverride? = initial.promptOverride
    private var noticeDismissed = false
    private var tryJob: Job? = null

    private val _ui = MutableStateFlow(snapshot(savedText, savedPreferences, TryState.Idle))
    val ui: StateFlow<PromptEditorUi> = _ui.asStateFlow()

    fun setText(text: String) {
        if (!editable) return
        _ui.update { snapshot(text, it.preferences, it.tryState) }
    }

    fun setPreferences(text: String) = _ui.update { snapshot(it.text, text, it.tryState) }

    /** "Keep editing" on the older-prompt notice: hide it; the editor already holds the user's text. */
    fun keepEditing() {
        noticeDismissed = true
        _ui.update { snapshot(it.text, it.preferences, it.tryState) }
    }

    /**
     * Saves both fields. Returns false (nothing written) while there are problems. The system text becomes an edit
     * for the active profile unless it equals the shipped text (then the stored edit is removed); on a fine-tune the
     * stored edit is left alone (it is kept for the stock profile it was written for).
     */
    suspend fun save(): Boolean {
        val u = _ui.value
        if (!u.canSave) return false
        val prefs = normalizePreferences(u.preferences)
        val text = u.text
        settings.update { s ->
            val override = when {
                !editable -> s.promptOverride
                same(text, shipped) -> null
                else -> PromptOverride(text, profile.name)
            }
            s.copy(promptOverride = override, preferences = prefs)
        }
        val now = settings.current()
        storedOverride = now.promptOverride
        savedText = if (editable) text else savedText
        savedPreferences = now.preferences.orEmpty()
        noticeDismissed = true
        _ui.update { snapshot(it.text, it.preferences, it.tryState) }
        return true
    }

    /**
     * The Reset dialog's confirm (DESIGN 6.5): the stored edit is deleted at once and the editor shows the shipped
     * text. Tone per app and "My preferences" are kept.
     */
    suspend fun reset() {
        if (!editable) return
        settings.update { it.copy(promptOverride = null) }
        storedOverride = null
        savedText = shipped
        noticeDismissed = true
        _ui.update { snapshot(shipped, it.preferences, it.tryState) }
    }

    /**
     * "Test again": the sample through the active cleanup model with the unsaved texts. The system text is passed only
     * when it differs from the shipped one (and never for a fine-tune); empty preferences are passed as null.
     */
    fun runTry(sample: String) {
        tryJob?.cancel()
        val u = _ui.value
        val override = if (editable && !same(u.text, shipped)) u.text else null
        val prefs = normalizePreferences(u.preferences)
        _ui.update { it.copy(tryState = TryState.Polishing) }
        tryJob = scope.launch {
            val result = try {
                when (val o = tryCleanup(sample, override, prefs)) {
                    is Outcome.Ok -> TryState.Done(o.value)
                    is Outcome.Err -> TryState.Failed(TryFailure.of(o.error))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TryState.Failed(TryFailure.FAILED)
            }
            _ui.update { it.copy(tryState = result) }
        }
    }

    /** Leaving the editor stops a running "Try it". */
    fun close() {
        tryJob?.cancel()
        tryJob = null
    }

    private fun initialText(s: Settings): String {
        val o = s.promptOverride
        return if (editable && o != null) o.systemText else shipped
    }

    private fun notice(): PromptNotice = when {
        !editable && stateAtOpen == PromptEditState.MADE_FOR_OLDER_PROMPT -> PromptNotice.FINE_TUNE_EDIT_KEPT
        !editable -> PromptNotice.FINE_TUNE
        stateAtOpen == PromptEditState.MADE_FOR_OLDER_PROMPT && !noticeDismissed -> PromptNotice.OLDER_PROMPT
        else -> PromptNotice.NONE
    }

    private fun snapshot(text: String, preferences: String, tryState: TryState): PromptEditorUi {
        val problems = PromptProblems(
            system = if (editable) PromptEditRules.validateSystem(text) else emptyList(),
            preferencesTooLong = normalizePreferences(preferences).orEmpty().length > PromptEditRules.MAX_PREFERENCES_CHARS,
        )
        val dirty = (editable && text != savedText) || preferences != savedPreferences
        return PromptEditorUi(
            profile = profile,
            text = text,
            preferences = preferences,
            editable = editable,
            notice = notice(),
            problems = problems,
            canReset = editable && (!same(text, shipped) || storedOverride != null),
            canSave = !problems.any,
            dirty = dirty,
            tryState = tryState,
        )
    }

    companion object {
        /** Sample of the "Try it" card (DESIGN 6.5 prototype). */
        const val SAMPLE: String = "ну короче скинь мне э отчёт до пятницы и созвонимся в понедельник в десять"

        /** `system/<stem>.md` of [profile] as the renderer reads it (unversioned stems get `.v1`, render.py _versioned). */
        fun shippedText(prompts: PromptAssets, profile: PromptProfile): String {
            val stem = if (Regex("""\.v\d+$""").containsMatchIn(profile.system)) profile.system else "${profile.system}.v1"
            return normalize(prompts.systemPrompt(stem))
        }

        /** The renderer's normalisation of system text (`replace("\r\n", "\n").rstrip("\n")`, ADR-046). */
        fun normalize(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n').trimEnd('\n')

        fun same(a: String, b: String): Boolean = normalize(a) == normalize(b)

        /** Stored preferences: surrounding blank space removed; empty = none (null). */
        fun normalizePreferences(text: String): String? = text.trim().takeIf { it.isNotEmpty() }
    }
}
