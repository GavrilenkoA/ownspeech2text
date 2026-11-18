package app.murmur.ui.design

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import java.util.Locale

/** Bundled fonts (DESIGN 3.2, FONTS.md): Onest variable (wght 100-900) and JetBrains Mono 400/500. */
object MurmurFonts {
    /** Onest at the weights tokens.json uses; each instance pins the `wght` axis of the one variable file. */
    val sans: FontFamily = FontFamily(
        listOf(400, 500, 600, 700).map { w ->
            Font(
                resId = R.font.onest_variable,
                weight = FontWeight(w),
                style = FontStyle.Normal,
                variationSettings = FontVariation.Settings(FontVariation.weight(w)),
            )
        },
    )

    val mono: FontFamily = FontFamily(
        Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
        Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
    )
}

/** TypeSpec -> TextStyle: sp sizes, letter spacing in sp, no font padding, CSS-like centred leading, `tnum`. */
fun TypeSpec.toTextStyle(): TextStyle = TextStyle(
    fontFamily = if (mono) MurmurFonts.mono else MurmurFonts.sans,
    fontWeight = FontWeight(weight),
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp,
    fontFeatureSettings = if (tabular) "tnum" else null,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(alignment = LineHeightStyle.Alignment.Center, trim = LineHeightStyle.Trim.None),
)

/**
 * The 22 text styles of DESIGN 3.2. `overline` is uppercase in the design; TextStyle cannot transform case, so
 * render it through [Overline] or [MurmurType.transform].
 */
@Immutable
class MurmurType internal constructor(
    val display: TextStyle,
    val headline: TextStyle,
    val onboardingTitle: TextStyle,
    val title: TextStyle,
    val titleSm: TextStyle,
    val topBar: TextStyle,
    val reading: TextStyle,
    val bodyLg: TextStyle,
    val entry: TextStyle,
    val rowTitle: TextStyle,
    val body: TextStyle,
    val label: TextStyle,
    val button: TextStyle,
    val bubble: TextStyle,
    val rowSub: TextStyle,
    val labelSm: TextStyle,
    val caption: TextStyle,
    val overline: TextStyle,
    val mono: TextStyle,
    val monoBadge: TextStyle,
    val monoTimer: TextStyle,
    val editor: TextStyle,
) {
    /** Every style by its tokens.json name (tests, gallery). */
    val all: Map<String, TextStyle>
        get() = linkedMapOf(
            "display" to display, "headline" to headline, "onboardingTitle" to onboardingTitle, "title" to title,
            "titleSm" to titleSm, "topBar" to topBar, "reading" to reading, "bodyLg" to bodyLg, "entry" to entry,
            "rowTitle" to rowTitle, "body" to body, "label" to label, "button" to button, "bubble" to bubble,
            "rowSub" to rowSub, "labelSm" to labelSm, "caption" to caption, "overline" to overline, "mono" to mono,
            "monoBadge" to monoBadge, "monoTimer" to monoTimer, "editor" to editor,
        )

    /** Applies the token's text transform (uppercase for `overline`). */
    fun transform(styleName: String, text: String, locale: Locale = Locale.getDefault()): String =
        if (TokenType.all[styleName]?.uppercase == true) text.uppercase(locale) else text

    /** Chip label: 13 sp / 500 (DESIGN 4 Chip); not a named token, derived from `label`. */
    val chip: TextStyle get() = label.copy(fontSize = 13.sp, lineHeight = 16.sp)

    /** Segmented and small-button label: 14 sp / 500, 18 line (prototype .seg). */
    val segment: TextStyle get() = label.copy(lineHeight = 18.sp)

    /** In-use pill: 11 sp / 600, tracking 0.2 (DESIGN 4). */
    val pill: TextStyle get() = overline.copy(letterSpacing = 0.2.sp, lineHeight = 14.sp)

    /** M3 roles mapped onto Murmur styles so that stock Material components use Onest. */
    fun toMaterial(): Typography = Typography(
        displayLarge = display, displayMedium = display, displaySmall = onboardingTitle,
        headlineLarge = onboardingTitle, headlineMedium = headline, headlineSmall = title,
        titleLarge = title, titleMedium = titleSm, titleSmall = rowTitle,
        bodyLarge = bodyLg, bodyMedium = body, bodySmall = caption,
        labelLarge = button, labelMedium = labelSm, labelSmall = overline,
    )

    companion object {
        val Default: MurmurType = TokenType.run {
            MurmurType(
                display = display.toTextStyle(), headline = headline.toTextStyle(),
                onboardingTitle = onboardingTitle.toTextStyle(), title = title.toTextStyle(),
                titleSm = titleSm.toTextStyle(), topBar = topBar.toTextStyle(), reading = reading.toTextStyle(),
                bodyLg = bodyLg.toTextStyle(), entry = entry.toTextStyle(), rowTitle = rowTitle.toTextStyle(),
                body = body.toTextStyle(), label = label.toTextStyle(), button = button.toTextStyle(),
                bubble = bubble.toTextStyle(), rowSub = rowSub.toTextStyle(), labelSm = labelSm.toTextStyle(),
                caption = caption.toTextStyle(), overline = overline.toTextStyle(), mono = mono.toTextStyle(),
                monoBadge = monoBadge.toTextStyle(), monoTimer = monoTimer.toTextStyle(), editor = editor.toTextStyle(),
            )
        }
    }
}

val LocalMurmurType = staticCompositionLocalOf { MurmurType.Default }
