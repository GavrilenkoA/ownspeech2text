package app.murmur.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.murmur.core.catalog.Catalog
import app.murmur.core.context.AppCategories
import app.murmur.core.dictation.PermissionState
import app.murmur.core.prompt.PromptEditRules
import app.murmur.core.prompt.PromptEditState
import app.murmur.core.prompt.PromptProfile
import app.murmur.core.store.BubbleSize
import app.murmur.core.store.BubbleVisibility
import app.murmur.core.store.CleanupChoice
import app.murmur.core.store.Feedback
import app.murmur.core.store.RetentionPolicy
import app.murmur.core.store.Settings
import app.murmur.core.store.Tone
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** A shortening of "Keep history" waiting for the confirm dialog ("Delete 12 dictations older than 3 days?"). */
data class RetentionConfirm(val days: Int, val count: Int)

/** The cleanup prompt as the Settings row and the inner preview show it. */
data class PromptSummary(val profile: PromptProfile, val state: PromptEditState, val editable: Boolean, val previewText: String)

/** Everything the Settings list renders. */
data class SettingsUi(
    val settings: Settings,
    /** null only for a catalog that lists no cleanup model at all (the prompt rows are then disabled). */
    val prompt: PromptSummary?,
    val tones: List<ToneRow>,
    /** "All other apps": the stored choice, else Neutral (apps without a category use the default style line). */
    val defaultTone: Tone,
    val defaultTonePreview: Boolean,
    val permissions: PermissionState,
    val serviceConnected: Boolean,
)

/**
 * Settings (DESIGN 6.5) over [SettingsDeps]. Every setting is written only through `SettingsRepository.update`
 * (WP-19 brief: the orchestrator observes prompt, preferences, unit and model changes and rebuilds the prefix
 * itself). [today] is the local calendar day for the retention count; [io] runs PackageManager and repository reads.
 */
class SettingsViewModel(
    private val deps: SettingsDeps,
    scope: CoroutineScope? = null,
    private val today: () -> LocalDate = { LocalDate.now() },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Package -> style category (`prompts/tones/app_categories.v1.json`, bundled with :core:context). */
    private val categories: Map<String, String> = AppCategories.bundled(),
) : ViewModel() {
    private val scope: CoroutineScope = scope ?: viewModelScope

    private val launcherApps = MutableStateFlow<List<String>>(emptyList())
    private val tonesWithText: Set<Tone> = runCatching { ToneRules.tonesWithText(deps.prompts.text("tones/tones.v1.json")) }.getOrDefault(emptySet())

    private val _retentionConfirm = MutableStateFlow<RetentionConfirm?>(null)
    /** Set while the retention confirm dialog is open. */
    val retentionConfirm: StateFlow<RetentionConfirm?> = _retentionConfirm.asStateFlow()

    private val _deleteAllCount = MutableStateFlow<Int?>(null)
    /** Set while the "Delete all history" dialog is open: the number of dictations it deletes. */
    val deleteAllCount: StateFlow<Int?> = _deleteAllCount.asStateFlow()

    private val _events = MutableStateFlow<SettingsEvent?>(null)
    /** One-shot messages for the snackbar; the UI calls [eventShown]. */
    val events: StateFlow<SettingsEvent?> = _events.asStateFlow()

    private val profile: StateFlow<PromptProfile?> = combine(deps.settings.settings, deps.catalog.catalog) { s, c -> activeProfileName(s, c) }
        .map { name -> name?.let { runCatching { deps.prompts.profile(it) }.getOrNull() } }
        .stateIn(this.scope, SharingStarted.Eagerly, activeProfileName(deps.settings.settings.value, deps.catalog.catalog.value)?.let { runCatching { deps.prompts.profile(it) }.getOrNull() })

    val ui: StateFlow<SettingsUi> = combine(
        deps.settings.settings, profile, launcherApps, deps.permissions.state, deps.serviceConnected,
    ) { s, p, apps, perms, connected -> build(s, p, apps, perms, connected) }
        .stateIn(
            this.scope, SharingStarted.Eagerly,
            build(deps.settings.settings.value, profile.value, emptyList(), deps.permissions.state.value, deps.serviceConnected.value),
        )

    init {
        this.scope.launch { launcherApps.value = withContext(io) { deps.apps.launcherApps() } }
    }

    private fun build(s: Settings, p: PromptProfile?, apps: List<String>, perms: PermissionState, connected: Boolean): SettingsUi {
        val prompt = p?.let { prof ->
            val editable = PromptEditRules.editable(prof)
            val state = PromptEditRules.editState(prof, s.promptOverride?.baseProfile)
            val text = if (state == PromptEditState.EDITED) s.promptOverride!!.systemText else runCatching { PromptEditor.shippedText(deps.prompts, prof) }.getOrDefault("")
            PromptSummary(prof, state, editable, text)
        }
        val rows = ToneRules.rows(apps, categories, s.tonePerApp, tonesWithText) { deps.apps.label(it) }
        val defaultTone = s.defaultTone ?: Tone.NEUTRAL
        return SettingsUi(
            settings = s,
            prompt = prompt,
            tones = rows,
            defaultTone = defaultTone,
            defaultTonePreview = s.defaultTone != null && ToneRules.isPreview(defaultTone, Tone.NEUTRAL, tonesWithText),
            permissions = perms,
            serviceConnected = connected,
        )
    }

    private fun write(transform: (Settings) -> Settings) {
        scope.launch { deps.settings.update(transform) }
    }

    // ------------------------------------------------------------ Dictation

    /** Languages sheet: at least one language stays selected (the resolver keys the speech model on them). */
    fun setLanguage(code: String, on: Boolean) = write { s ->
        val next = if (on) (s.languages + code).distinct() else s.languages - code
        if (next.isEmpty()) s else s.copy(languages = LANGUAGES.filter { it in next } + next.filter { it !in LANGUAGES })
    }

    fun setAutoDetect(on: Boolean) = write { it.copy(autoDetectLanguage = on) }
    fun setHoldToTalk(on: Boolean) = write { it.copy(holdToTalk = on) }
    fun setFeedback(f: Feedback) = write { it.copy(feedback = f) }

    fun setTypingWpm(wpm: Int) {
        if (wpm in MIN_WPM..MAX_WPM) write { it.copy(typingWpm = wpm) }
    }

    // ------------------------------------------------------------ Bubble

    fun setBubbleVisibility(v: BubbleVisibility) = write { it.copy(bubbleVisibility = v) }
    fun setTuckIn(on: Boolean) = write { it.copy(tuckIn = on) }
    fun setBubbleSize(size: BubbleSize) = write { it.copy(bubbleSize = size) }

    fun setHiddenIn(pkg: String, hidden: Boolean) = write { s ->
        s.copy(hiddenInPackages = if (hidden) s.hiddenInPackages + pkg else s.hiddenInPackages - pkg)
    }

    /** "Reset position": the stored per-display positions are dropped (the bubble goes back to the right edge). */
    fun resetPosition() {
        scope.launch {
            deps.settings.update { it.copy(bubblePositions = emptyMap()) }
            _events.value = SettingsEvent.POSITION_RESET
        }
    }

    /** Launcher label, else the package name (DESIGN: screens show the package when the app is not visible). */
    fun appLabel(pkg: String): String = deps.apps.label(pkg) ?: pkg

    fun appIcon(pkg: String) = deps.apps.icon(pkg)

    /** Launcher apps for the "Hidden in" picker, without [ownPackage]. */
    fun hiddenInCandidates(ownPackage: String?): List<String> = launcherApps.value.filter { it != ownPackage }

    // ------------------------------------------------------------ Cleanup

    fun setUseScreen(on: Boolean) = write { it.copy(useScreen = on) }

    /** Tone dropdown of a listed or added app. */
    fun setTone(pkg: String, tone: Tone) = write { s ->
        val row = ui.value.tones.firstOrNull { it.pkg == pkg }
        val listed = row != null && !row.removable
        val cat = row?.categoryTone ?: ToneRules.categoryTone(categories[pkg])
        s.copy(tonePerApp = ToneRules.pick(s.tonePerApp, pkg, tone, listed, cat))
    }

    /** "Add an app": stored with its category's tone until the user picks another. */
    fun addToneApp(pkg: String) = write { s ->
        if (pkg in s.tonePerApp) s else s.copy(tonePerApp = s.tonePerApp + (pkg to ToneRules.categoryTone(categories[pkg])))
    }

    fun removeToneApp(pkg: String) = write { it.copy(tonePerApp = it.tonePerApp - pkg) }

    /** "All other apps": Neutral is the category default, so picking it stores no choice. */
    fun setDefaultTone(tone: Tone) = write { it.copy(defaultTone = tone.takeIf { t -> t != Tone.NEUTRAL }) }

    /** Apps that "Add an app" offers: launcher apps not in the list yet, without [ownPackage]. */
    fun toneCandidates(ownPackage: String?): List<String> {
        val listed = ui.value.tones.map { it.pkg }.toSet()
        return launcherApps.value.filter { it !in listed && it != ownPackage }
    }

    // ------------------------------------------------------------ Navigation inside Settings

    private val _category = MutableStateFlow(SettingsCategory.DICTATION)
    /** Inner display: the category in the detail pane. */
    val category: StateFlow<SettingsCategory> = _category.asStateFlow()

    private val _sub = MutableStateFlow<SubScreen?>(null)
    /** The open sub-screen (pushed on the cover display, the detail pane on the inner display). */
    val sub: StateFlow<SubScreen?> = _sub.asStateFlow()

    private val _promptEditor = MutableStateFlow<PromptEditor?>(null)
    /** The prompt editor while [sub] is [SubScreen.PROMPT]; kept here so that folding keeps unsaved text. */
    val promptEditor: StateFlow<PromptEditor?> = _promptEditor.asStateFlow()

    fun selectCategory(c: SettingsCategory) {
        closeSub()
        _category.value = c
    }

    /** Opens the editor for the active profile; false when the catalog lists no cleanup model. */
    fun openPrompt(): Boolean {
        val p = profile.value ?: return false
        _promptEditor.value?.close()
        _promptEditor.value = PromptEditor(p, deps.prompts, deps.settings, deps.tryCleanup, scope, deps.settings.settings.value)
        _category.value = SettingsCategory.CLEANUP
        _sub.value = SubScreen.PROMPT
        return true
    }

    fun openTones() {
        closeSub()
        _category.value = SettingsCategory.CLEANUP
        _sub.value = SubScreen.TONE
    }

    fun closeSub() {
        _promptEditor.value?.close()
        _promptEditor.value = null
        _sub.value = null
    }

    override fun onCleared() {
        _promptEditor.value?.close()
    }

    // ------------------------------------------------------------ History and privacy

    fun setKeepRecordings(on: Boolean) = write { it.copy(keepRecordings = on) }

    /**
     * Retention sheet choice. Lengthening is written at once. Shortening (to fewer days, or to "Don't keep history" =
     * 0) first counts the dictations it deletes (DESIGN 6.5; with 0 every entry, today's included,
     * `RetentionPolicy.firstKeptDay`): none -> applied at once; some -> [retentionConfirm] opens the dialog.
     */
    fun chooseRetention(days: Int) {
        scope.launch {
            val current = deps.settings.current().retentionDays
            if (days == current) return@launch
            if (!shortens(current, days)) {
                deps.settings.update { it.copy(retentionDays = days) }
                return@launch
            }
            val firstKept = RetentionPolicy.firstKeptDay(today(), days)
            val count = withContext(io) { deps.history.entries().first().count { it.localDay.isBefore(firstKept) } }
            if (count == 0) applyRetention(days) else _retentionConfirm.value = RetentionConfirm(days, count)
        }
    }

    /** The confirm dialog's Delete: the period is stored, then WP-12's immediate retention run deletes the entries. */
    fun confirmRetention() {
        val c = _retentionConfirm.value ?: return
        _retentionConfirm.value = null
        scope.launch { applyRetention(c.days) }
    }

    fun cancelRetention() {
        _retentionConfirm.value = null
    }

    private suspend fun applyRetention(days: Int) {
        deps.settings.update { it.copy(retentionDays = days) }
        withContext(io) { deps.retention(days) }
    }

    /** "Delete all history": counts first; nothing to delete -> a snackbar, else the confirm dialog. */
    fun askDeleteAll() {
        scope.launch {
            val n = withContext(io) { deps.history.entries().first().size }
            if (n == 0) _events.value = SettingsEvent.NOTHING_TO_DELETE else _deleteAllCount.value = n
        }
    }

    fun confirmDeleteAll() {
        if (_deleteAllCount.value == null) return
        _deleteAllCount.value = null
        scope.launch {
            withContext(io) { deps.history.deleteAll() }
            _events.value = SettingsEvent.HISTORY_DELETED
        }
    }

    fun cancelDeleteAll() {
        _deleteAllCount.value = null
    }

    // ------------------------------------------------------------ Permissions and debug

    fun refreshPermissions() = deps.permissions.refresh()

    fun setDebugFakeEngines(on: Boolean) = write { it.copy(debugFakeEngines = on) }
    fun setDebugTimeoutScale(scale: Float) = write { it.copy(debugTimeoutScale = scale) }
    fun setDebugIgnoreMemoryGate(on: Boolean) = write { it.copy(debugIgnoreMemoryGate = on) }

    fun eventShown() {
        _events.value = null
    }

    companion object {
        /** The dictation languages Settings offers (ru + en are first-class, BRIEF). */
        val LANGUAGES: List<String> = listOf("ru", "en")
        val WPM_PRESETS: List<Int> = listOf(30, 40, 50, 60)
        const val MIN_WPM = 10
        const val MAX_WPM = 200
        val TIMEOUT_SCALES: List<Float> = listOf(1f, 2f, 4f, 8f, 16f)

        /** 0 ("Don't keep history") is the shortest period. */
        fun shortens(from: Int, to: Int): Boolean = rank(to) < rank(from)
        private fun rank(days: Int): Int = if (days <= 0) 0 else days

        /**
         * The prompt profile the cleanup uses: the chosen model's, or the catalog default's (also while cleanup is
         * None, so that the editor shows the prompt that applies once a model is used again).
         */
        fun activeProfileName(s: Settings, c: Catalog): String? {
            val chosen = when (val choice = s.cleanup) {
                is CleanupChoice.Model -> c.model(choice.id)?.llm?.promptProfile
                CleanupChoice.Default, CleanupChoice.None -> null
            }
            return chosen ?: c.defaults.llm?.let { c.model(it)?.llm?.promptProfile }
        }
    }
}

/** Snackbar messages of the Settings screen. */
enum class SettingsEvent { POSITION_RESET, HISTORY_DELETED, NOTHING_TO_DELETE }

/** DESIGN 6.5 inner display: the 260 dp category list. [DEBUG] exists in debug builds only. */
enum class SettingsCategory { DICTATION, BUBBLE, CLEANUP, PRIVACY, PERMISSIONS, DEBUG }

/** Screens below Settings: the prompt editor and Tone per app. */
enum class SubScreen { PROMPT, TONE }
