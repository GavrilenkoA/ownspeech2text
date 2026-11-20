package app.murmur.e2e

import android.content.Intent
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import app.murmur.MainActivity
import app.murmur.R
import app.murmur.core.model.ModelId
import app.murmur.core.store.HistoryEntry
import java.text.NumberFormat
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * After real dictations: do the four tabs of the app show the data those dictations produced (WP-20 wave 3)? Each check
 * reads what the user sees (UiAutomator over the accessibility tree of MainActivity) and compares it with the stores
 * of this process, so a tab that still shows sample data, an empty state or another container's data fails:
 *  - History: a row per dictation, found by the start of its stored final text;
 *  - Dashboard: today's word count as StatsCalculator computes it from the stored metrics (not "Nothing dictated yet");
 *  - Models: the speech and the cleanup model in use have no Download button (installed), another model has one;
 *  - Settings: values this run stored (timeout scale) and the live accessibility service state ("On").
 * Privacy (D9): the report carries only which tab passed and why it failed, never the texts.
 */
object AppTabs {
    data class TabResult(val tab: String, val realData: Boolean, val why: String)

    private val device get() = E2e.device
    private val ctx get() = E2e.context
    private const val WAIT_MS = 10_000L

    fun open() {
        ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        E2e.dismissSystemDialogs()
        check(device.wait(Until.hasObject(By.pkg(E2e.PKG).depth(0)), WAIT_MS)) { "MainActivity did not open" }
    }

    fun close() {
        device.pressHome()
    }

    private fun tab(label: Int) {
        val text = ctx.getString(label)
        val item = device.wait(Until.findObject(By.text(text)), WAIT_MS) ?: throw AssertionError("no '$text' tab")
        item.click()
        device.waitForIdle(2_000)
    }

    private fun has(sel: BySelector, timeoutMs: Long = WAIT_MS) = device.wait(Until.hasObject(sel), timeoutMs)

    /** Scrolls the screen's list until a node with [text] is on screen (lazy lists compose only what is visible). */
    private fun scrollTo(text: String): Boolean {
        if (device.hasObject(By.textContains(text))) return true
        return runCatching {
            UiScrollable(UiSelector().scrollable(true)).apply { setMaxSearchSwipes(15) }.scrollIntoView(UiSelector().textContains(text))
        }.getOrDefault(false) && device.hasObject(By.textContains(text))
    }

    fun history(entries: List<HistoryEntry>): TabResult {
        tab(R.string.tab_history)
        val missing = entries.filter { e ->
            val start = e.finalText?.trim()?.take(24) ?: return@filter true
            !has(By.descContains(start), 3_000) && !scrollTo(start)
        }
        return TabResult("History", missing.isEmpty(), if (missing.isEmpty()) "${entries.size} rows" else "${missing.size} of ${entries.size} rows not shown")
    }

    fun dashboard(): TabResult {
        tab(R.string.tab_dashboard)
        val c = E2e.container
        val today = LocalDate.now(c.clock.zone())
        val words = runBlocking {
            val rows = c.metrics.since(today.minusDays(6)).first()
            c.dashboardDeps.stats.compute(rows, today, c.settings.settings.value.typingWpm).todayWords
        }
        val shown = NumberFormat.getIntegerInstance(ctx.resources.configuration.locales[0]).apply { isGroupingUsed = true }.format(words)
        val empty = device.hasObject(By.text(ctx.getString(app.murmur.feature.dashboard.R.string.dash_nothing_today)))
        val ok = words > 0 && !empty && has(By.text(shown))
        return TabResult("Dashboard", ok, "today $words words in the store, shown=${has(By.text(shown), 0)} empty-state=$empty")
    }

    fun models(asr: String, llm: String): TabResult {
        tab(R.string.tab_models)
        val c = E2e.container
        fun name(id: String) = c.catalog.catalog.value.model(ModelId(id))?.displayName ?: id
        // models_cd_download = "Download %1$s, %2$s" (name, size): its text up to the name, then with the name.
        val anyDownload = ctx.getString(app.murmur.feature.models.R.string.models_cd_download, "\u0000", "").substringBefore('\u0000')
        val problems = mutableListOf<String>()
        fun installedRow(id: String) {
            val n = name(id)
            if (!scrollTo(n)) { problems += "$id row not found"; return }
            if (device.hasObject(By.descStartsWith("$anyDownload$n,"))) problems += "$id offered for download"
        }
        installedRow(asr)
        if (!device.hasObject(By.descStartsWith(anyDownload))) problems += "no model offered for download (install states not real?)"
        val cleanup = ctx.getString(app.murmur.feature.models.R.string.models_tab_cleanup)
        device.findObject(By.text(cleanup))?.click() ?: problems.add("no Cleanup segment")
        device.waitForIdle(2_000)
        installedRow(llm)
        return TabResult("Models", problems.isEmpty(), problems.joinToString("; ").ifEmpty { "$asr and $llm installed, others downloadable" })
    }

    fun settings(): TabResult {
        tab(R.string.tab_settings)
        val s = E2e.container.settings.settings.value
        val scale = ctx.getString(
            app.murmur.feature.settings.R.string.settings_debug_timeout_value,
            if (s.debugTimeoutScale == s.debugTimeoutScale.toInt().toFloat()) s.debugTimeoutScale.toInt().toString() else s.debugTimeoutScale.toString(),
        )
        val problems = mutableListOf<String>()
        if (!scrollTo(ctx.getString(app.murmur.feature.settings.R.string.settings_perm_a11y))) problems += "no accessibility row"
        else if (!has(By.text(ctx.getString(app.murmur.feature.settings.R.string.settings_status_on)), 3_000)) problems += "accessibility service not shown On"
        if (!scrollTo(scale)) problems += "timeout scale $scale not shown"
        return TabResult("Settings", problems.isEmpty(), problems.joinToString("; ").ifEmpty { "service On, timeout scale $scale" })
    }
}
