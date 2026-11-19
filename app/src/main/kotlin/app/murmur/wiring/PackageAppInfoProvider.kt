package app.murmur.wiring

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.LruCache
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.core.graphics.drawable.toBitmap
import app.murmur.ui.design.AppInfoProvider
import kotlin.math.roundToInt

/**
 * AppInfoProvider (ui:design contract) over PackageManager: launcher labels and icons of installed apps, visible through
 * the `<queries>` MAIN/LAUNCHER block of :platform:a11y (ADR-021, no QUERY_ALL_PACKAGES). Icons are rasterised once at
 * the largest tile size (36 dp, DESIGN 4) and cached as Painters; a package that is not visible or gone yields null
 * (screens show the letter tile / the package name).
 */
class PackageAppInfoProvider(private val context: Context) : AppInfoProvider {
    private val pm = context.packageManager
    private val labels = LruCache<String, Optional>(256)
    private val icons = LruCache<String, Optional>(96)
    private val iconPx = (TILE_DP * context.resources.displayMetrics.density).roundToInt()

    /** LruCache cannot hold nulls: a remembered miss. */
    private class Optional(val value: Any?)

    override fun label(pkg: String): String? = cached(labels, pkg) {
        pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
    } as String?

    override fun icon(pkg: String): Painter? = cached(icons, pkg) {
        BitmapPainter(pm.getApplicationIcon(pkg).toBitmap(iconPx, iconPx).asImageBitmap())
    } as Painter?

    override fun launcherApps(): List<String> {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != context.packageName }
            .sortedBy { (label(it) ?: it).lowercase() }
    }

    private fun cached(cache: LruCache<String, Optional>, pkg: String, load: () -> Any): Any? {
        cache.get(pkg)?.let { return it.value }
        val v = try {
            load()
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
        cache.put(pkg, Optional(v))
        return v
    }

    private companion object {
        const val TILE_DP = 36
    }
}
