package app.murmur.ui.design

import androidx.compose.ui.graphics.painter.Painter

/**
 * Installed-app labels and launcher icons for History rows, Dashboard "Where you dictate", Settings "Hidden in" and
 * "Tone per app" (review 2026-11-19: features may not reach PackageManager themselves). Contract here, implemented by
 * :app (WP-15, PackageManager with the <queries> LAUNCHER visibility of ADR-021); previews and tests use a fake.
 */
interface AppInfoProvider {
    /** Launcher label; null when the package is not visible or uninstalled (screens then show the package name). */
    fun label(pkg: String): String?
    /** Launcher icon as a Painter (cached); null = show the generic app tile. */
    fun icon(pkg: String): Painter?
    /** Packages with a launcher activity, sorted by label (app pickers). */
    fun launcherApps(): List<String>
}

/** Previews, screenshot tests and fake containers: labels from a map, no icons. */
class StaticAppInfoProvider(private val labels: Map<String, String> = emptyMap()) : AppInfoProvider {
    override fun label(pkg: String): String? = labels[pkg]
    override fun icon(pkg: String): Painter? = null
    override fun launcherApps(): List<String> = labels.entries.sortedBy { it.value }.map { it.key }
}
