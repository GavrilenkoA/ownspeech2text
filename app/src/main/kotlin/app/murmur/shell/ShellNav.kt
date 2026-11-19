package app.murmur.shell

import android.content.Context
import android.content.Intent
import app.murmur.DashboardRoute
import app.murmur.HistoryEntryRoute
import app.murmur.HistoryRoute
import app.murmur.MainActivity
import app.murmur.ModelDetailRoute
import app.murmur.ModelsRoute
import app.murmur.PracticeRoute
import app.murmur.SettingsRoute
import app.murmur.TABS
import app.murmur.TopRoute
import app.murmur.core.dictation.AppNavigator
import app.murmur.core.dictation.OpenTarget
import app.murmur.core.model.EntryId
import app.murmur.core.model.ModelKind
import androidx.navigation3.runtime.NavKey

/**
 * Back-stack rules of the shell (pure, ShellTest). One back stack: a tab root, optionally followed by its detail
 * (History entry, model). On the cover display the detail is a pushed screen; on the inner display the same stack is
 * shown as list + detail panes, so a selection survives fold/unfold (the stack is saved with rememberNavBackStack).
 */
object ShellNav {
    /** The tab a stack belongs to (its root); History when empty. */
    fun tabOf(stack: List<NavKey>): TopRoute = stack.firstOrNull() as? TopRoute ?: HistoryRoute

    /** Tapping a tab: its root alone (tapping the current tab also closes its detail). */
    fun selectTab(tab: TopRoute): List<NavKey> = listOf(tab)

    fun openEntry(id: EntryId): List<NavKey> = listOf(HistoryRoute, HistoryEntryRoute(id.value))

    fun openModel(id: String): List<NavKey> = listOf(ModelsRoute, ModelDetailRoute(id))

    /** "Try a dictation": the practice screen over the current stack (once); Back returns to where it was opened. */
    fun openPractice(stack: List<NavKey>): List<NavKey> = stack.ifEmpty { listOf(HistoryRoute) }.filter { it != PracticeRoute } + PracticeRoute

    /** A pushed screen is on top (detail or practice): on the cover display it covers the bottom bar (hist-detail). */
    fun isPushed(stack: List<NavKey>): Boolean = stack.size > 1

    /**
     * System Back: close the detail; from another tab's root go to History (the start destination, DESIGN decision 4);
     * on History's root null = leave the app.
     */
    fun back(stack: List<NavKey>): List<NavKey>? = when {
        stack.size > 1 -> stack.dropLast(1)
        tabOf(stack) != HistoryRoute -> listOf(HistoryRoute)
        else -> null
    }

    /** Where a deep link lands. */
    fun forLink(link: DeepLink): List<NavKey> = when (link) {
        is DeepLink.Entry -> openEntry(link.id)
        is DeepLink.Models -> listOf(ModelsRoute)
    }

    /**
     * The hub segment a deep link asks for (a one-shot request the Models screen consumes): Cleanup for an LLM
     * problem, Speech for every other "Open Models" (OpenTarget.Models: null = Speech); null for other links.
     */
    fun segmentFor(link: DeepLink): ModelKind? = when (link) {
        is DeepLink.Models -> if (link.kind == ModelKind.LLM) ModelKind.LLM else ModelKind.ASR
        is DeepLink.Entry -> null
    }

    /** Index of [tab] in the bar / rail. */
    fun indexOf(tab: TopRoute): Int = TABS.indexOfFirst { it.route == tab }.coerceAtLeast(0)

    /** The routes the shell knows, for exhaustive checks in tests. */
    val tabRoots: List<TopRoute> = listOf(DashboardRoute, HistoryRoute, ModelsRoute, SettingsRoute)
}

/** What the bubble (and the status notification) can open in the app (ARCHITECTURE 5.2 Effect.Open). */
sealed interface DeepLink {
    data class Entry(val id: EntryId) : DeepLink
    /** [kind] null = the Speech segment (OpenTarget.Models). */
    data class Models(val kind: ModelKind?) : DeepLink

    companion object {
        /** Intent extras understood by MainActivity (singleTop: onCreate and onNewIntent). */
        const val EXTRA_OPEN = "app.murmur.extra.OPEN"
        const val EXTRA_ENTRY_ID = "app.murmur.extra.ENTRY_ID"
        const val EXTRA_MODEL_KIND = "app.murmur.extra.MODEL_KIND"
        const val OPEN_ENTRY = "history_entry"
        const val OPEN_MODELS = "models"

        fun of(target: OpenTarget): DeepLink = when (target) {
            is OpenTarget.Entry -> Entry(target.id)
            is OpenTarget.Models -> Models(target.kind)
        }

        /** Parses the extras (null = an ordinary launch). Unknown values and a missing entry id are ignored. */
        fun parse(open: String?, entryId: Long?, modelKind: String?): DeepLink? = when (open) {
            OPEN_ENTRY -> entryId?.takeIf { it > 0 }?.let { Entry(EntryId(it)) }
            OPEN_MODELS -> Models(modelKind?.let { k -> ModelKind.entries.firstOrNull { it.name == k } })
            else -> null
        }

        fun parse(intent: Intent?): DeepLink? {
            if (intent == null) return null
            val open = intent.getStringExtra(EXTRA_OPEN) ?: return null
            val id = if (intent.hasExtra(EXTRA_ENTRY_ID)) intent.getLongExtra(EXTRA_ENTRY_ID, -1) else null
            return parse(open, id, intent.getStringExtra(EXTRA_MODEL_KIND))
        }

        /** The Intent for [link]: MainActivity, brought to front without a second instance. */
        fun intent(context: Context, link: DeepLink): Intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .apply {
                when (link) {
                    is Entry -> { putExtra(EXTRA_OPEN, OPEN_ENTRY); putExtra(EXTRA_ENTRY_ID, link.id.value) }
                    is Models -> { putExtra(EXTRA_OPEN, OPEN_MODELS); link.kind?.let { putExtra(EXTRA_MODEL_KIND, it.name) } }
                }
            }
    }
}

/** The AppNavigator port (core/dictation): the bubble's "open entry" / "Open Models" start MainActivity with extras. */
class IntentAppNavigator(private val context: Context) : AppNavigator {
    override fun open(target: OpenTarget) {
        context.startActivity(DeepLink.intent(context, DeepLink.of(target)))
    }
}
