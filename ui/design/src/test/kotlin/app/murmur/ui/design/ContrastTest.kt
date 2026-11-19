package app.murmur.ui.design

import androidx.compose.ui.graphics.Color
import app.murmur.ui.design.components.rowBackground
import app.murmur.ui.design.components.rowSecondaryInk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * DESIGN 3.1 / 8: every text role is >= 4.5:1 on the surfaces it is drawn on, computed with the WCAG 2.x formula
 * (relative luminance of linearised sRGB). The pairs are the ones DESIGN 3.1 lists plus every text/background
 * combination the :ui:design components draw. ink4 is decorative only and never text.
 */
class ContrastTest {
    private fun channel(c: Float): Double = if (c <= 0.04045f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    private fun luminance(c: Color): Double = 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    private fun ratio(a: Color, b: Color): Double {
        val la = luminance(a); val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** (text, background, where) */
    private fun pairs(c: MurmurColors): List<Triple<Color, Color, String>> = listOf(
        Triple(c.ink, c.bg, "ink on bg: titles, history text"),
        Triple(c.ink, c.surface, "ink on surface: row titles"),
        Triple(c.ink, c.surface2, "ink on surface2: secondary button, search text"),
        Triple(c.ink, c.surface3, "ink on surface3: selected category"),
        Triple(c.ink, c.accentSoft, "ink on accentSoft: selected rows"),
        Triple(c.ink, c.errSoft, "ink on errSoft: error banner"),
        Triple(c.ink, c.warnSoft, "ink on warnSoft: warn banner"),
        Triple(c.ink2, c.bg, "ink2 on bg"),
        Triple(c.ink2, c.surface, "ink2 on surface: chips, badges"),
        Triple(c.ink2, c.surface2, "ink2 on surface2: badges, player time, idle search hint"),
        Triple(c.ink2, c.accentSoft, "ink2 on accentSoft: subtitles, values and chevrons of selected rows"),
        Triple(c.ink3, c.bg, "ink3 on bg: captions, section labels"),
        Triple(c.ink3, c.surface, "ink3 on surface: row subtitles, values, active search hint"),
        Triple(c.onAccent, c.accent, "onAccent on accent: primary button, in-use pill"),
        Triple(c.onAccent, c.accentPressed, "onAccent on accentPressed: pressed primary"),
        Triple(c.accentInk, c.accentSoft, "accentInk on accentSoft: NPU badge, highlights"),
        Triple(c.accentInk, c.surface, "accentInk on surface: text buttons"),
        Triple(c.accentInk, c.bg, "accentInk on bg: text buttons"),
        Triple(c.accentInk, c.surface2, "accentInk on surface2: info banner action"),
        Triple(c.ok, c.okSoft, "ok on okSoft"),
        Triple(c.ok, c.surface, "ok on surface: Allowed, On"),
        Triple(c.warn, c.warnSoft, "warn on warnSoft: No Russian badge"),
        Triple(c.warn, c.surface, "warn on surface: Restricted"),
        Triple(c.warn, c.bg, "warn on bg: Copied, Deletes tonight"),
        Triple(c.err, c.errSoft, "err on errSoft"),
        Triple(c.err, c.surface, "err on surface: destructive text button and row"),
        Triple(c.err, c.bg, "err on bg: Failed"),
        Triple(c.onInverse, c.inverse, "onInverse on inverse: snackbar"),
        Triple(c.inverseAccent, c.inverse, "inverseAccent on inverse: snackbar action"),
        Triple(c.bg, c.ink, "bg on ink: selected chip label"),
    )

    @Test
    fun allTextRolesMeetAaOnTheirSurfaces() {
        val failures = mutableListOf<String>()
        for ((theme, colors) in listOf("light" to TokenColors.light, "dark" to TokenColors.dark)) {
            for ((fg, bg, where) in pairs(colors)) {
                val r = ratio(fg, bg)
                if (r < 4.5) failures += "$theme $where = ${"%.2f".format(r)}:1"
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun documentedRatiosAreReproduced() {
        // DESIGN 3.1 states: ink 17:1 / 15.5:1 (on surface, rounded), ink3 4.5:1 on bg (light), onAccent 6.4:1 / 6.8:1.
        assertEquals(4.5, ratio(TokenColors.light.ink3, TokenColors.light.bg), 0.05)
        assertEquals(6.4, ratio(TokenColors.light.onAccent, TokenColors.light.accent), 0.05)
        assertEquals(6.8, ratio(TokenColors.dark.onAccent, TokenColors.dark.accent), 0.05)
        assertEquals(7.2, ratio(TokenColors.light.accentInk, TokenColors.light.accentSoft), 0.05)
        // WCAG formula sanity: black on white is 21:1.
        assertEquals(21.0, ratio(Color.Black, Color.White), 1e-6)
    }

    @Test
    fun pairsTheComponentsAvoid() {
        // These fall below 4.5:1, which is why the components switch colour: the idle search hint is ink2 (not the
        // prototype's ink3) on surface2, and selected (accentSoft) rows use ink2 for subtitles.
        assertTrue(ratio(TokenColors.light.ink3, TokenColors.light.surface2) < 4.5)
        assertTrue(ratio(TokenColors.light.ink3, TokenColors.light.accentSoft) < 4.5)
        // ...and therefore also the trailing value and chevron of a selected row (rowSecondaryInk).
    }

    /**
     * Row secondary content (subtitle, trailing value, chevron) is drawn with rowSecondaryInk on rowBackground; the
     * pair must pass on plain and on selected (accentSoft) rows in both themes.
     */
    @Test
    fun rowSecondaryContentPassesOnPlainAndSelectedRows() {
        for ((theme, c) in listOf("light" to TokenColors.light, "dark" to TokenColors.dark)) {
            for (selected in listOf(false, true)) {
                val r = ratio(rowSecondaryInk(c, selected), rowBackground(c, selected))
                assertTrue("$theme selected=$selected: ${"%.2f".format(r)}:1", r >= 4.5)
            }
        }
    }

    /**
     * The fallback app tile's white letter is decorative (the tile has no semantics and the app name is always shown
     * next to it), so it is held to the 3:1 of a graphic (WCAG 1.4.11), not to 4.5:1. Two palette colours are
     * between 3:1 and 4.5:1 (#B9861E 3.2:1, #3C7EBF 4.3:1), which is why the letter must stay decorative.
     */
    @Test
    fun appTileLettersMeetGraphicContrast() {
        val ratios = TokenColors.appTilePalette.map { ratio(Color.White, it) }
        assertTrue(ratios.joinToString { "%.2f".format(it) }, ratios.all { it >= 3.0 })
        assertTrue("some tile colours are below 4.5:1, so the letter is decorative only", ratios.any { it < 4.5 })
    }
}
