package app.murmur.ui.design.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.murmur.ui.design.AppInfoProvider
import app.murmur.ui.design.MurmurIcons
import app.murmur.ui.design.MurmurShapes
import app.murmur.ui.design.MurmurTheme
import app.murmur.ui.design.R
import app.murmur.ui.design.TokenColors
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToLong

/** "0:04" style clock for recordings (mono, tabular). */
fun formatClock(ms: Long): String {
    val s = (ms.coerceAtLeast(0) + 500) / 1000
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

private val BAR_W = 2.5.dp
private val BAR_GAP = 2.dp
private val WAVE_H = 32.dp
private val BAR_MIN = 4.dp
private val BAR_MAX = 28.dp

/** Downsamples [peaks] (0..1) to [n] bars by taking each bucket's maximum. */
internal fun resamplePeaks(peaks: FloatArray, n: Int): FloatArray {
    if (n <= 0) return FloatArray(0)
    if (peaks.isEmpty()) return FloatArray(n)
    return FloatArray(n) { i ->
        val from = (i.toLong() * peaks.size / n).toInt()
        val to = max(from + 1, ((i + 1).toLong() * peaks.size / n).toInt()).coerceAtMost(peaks.size)
        var m = 0f
        for (k in from until to) m = max(m, peaks[k])
        m.coerceIn(0f, 1f)
    }
}

/**
 * Audio player (DESIGN 4, hist-detail): surface2, md radius. 44 dp ink play button with a bg glyph (48 dp hit), a
 * waveform of 2.5 dp bars from the recording's real [peaks] (0..1; played part ink, rest ink4), and a mono
 * "0:04 / 0:12". Drag or tap the waveform (48 dp tall hit band) to scrub; TalkBack gets a seekable progress.
 */
@Composable
fun AudioPlayer(
    peaks: FloatArray,
    positionMs: Long,
    durationMs: Long,
    playing: Boolean,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = MurmurTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    var scrub by remember { mutableFloatStateOf(-1f) }
    val shown = if (scrub >= 0f) scrub else fraction
    val seek by rememberUpdatedState(onSeek)
    val duration by rememberUpdatedState(durationMs)
    val playLabel = stringResource(if (playing) R.string.design_pause_recording else R.string.design_play_recording)
    val positionLabel = stringResource(R.string.design_recording_position)
    Row(
        modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clip(MurmurShapes.md)
            .background(c.surface2)
            .padding(start = 8.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .clickable(interaction, null, enabled = enabled, role = Role.Button, onClick = onPlayPause)
                .semantics { contentDescription = playLabel }
                .padding(2.dp)
                .size(44.dp)
                .clip(MurmurShapes.full)
                .background(c.ink)
                .indication(interaction, LocalIndication.current),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (playing) MurmurIcons.pause else MurmurIcons.play, null, tint = c.bg, modifier = Modifier.size(20.dp))
        }
        Box(
            Modifier
                .weight(1f)
                .height(MinTouch)
                .semantics {
                    contentDescription = positionLabel
                    stateDescription = "${formatClock((shown * durationMs).roundToLong())} / ${formatClock(durationMs)}"
                    progressBarRangeInfo = ProgressBarRangeInfo(shown, 0f..1f)
                    if (enabled) setProgress { v -> seek((v.coerceIn(0f, 1f) * duration).roundToLong()); true }
                }
                .then(
                    if (!enabled) Modifier else Modifier
                        .pointerInput(Unit) {
                            detectTapGestures { o -> seek(((o.x / size.width).coerceIn(0f, 1f) * duration).roundToLong()) }
                        }
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures(
                                onDragStart = { o -> scrub = (o.x / size.width).coerceIn(0f, 1f) },
                                onDragEnd = { seek((scrub * duration).roundToLong()); scrub = -1f },
                                onDragCancel = { scrub = -1f },
                            ) { change, _ -> scrub = (change.position.x / size.width).coerceIn(0f, 1f) }
                        },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxWidth().height(WAVE_H)) {
                val bw = BAR_W.toPx(); val gap = BAR_GAP.toPx()
                val n = floor((size.width + gap) / (bw + gap)).toInt().coerceAtLeast(1)
                val bars = resamplePeaks(peaks, n)
                // space-between, as in the prototype
                val step = if (n > 1) (size.width - bw) / (n - 1) else 0f
                val minH = BAR_MIN.toPx(); val maxH = BAR_MAX.toPx()
                val played = shown * n
                for (i in 0 until n) {
                    val h = minH + (maxH - minH) * bars[i]
                    drawRoundRect(
                        color = if (i < played) c.ink else c.ink4,
                        topLeft = Offset(i * step, (size.height - h) / 2),
                        size = Size(bw, h),
                        cornerRadius = CornerRadius(bw / 2, bw / 2),
                    )
                }
            }
        }
        Text(
            "${formatClock((shown * durationMs).roundToLong())} / ${formatClock(durationMs)}",
            style = MurmurTheme.type.mono, color = c.ink2, maxLines = 1,
        )
    }
}

/** App tile sizes (DESIGN 4): 20 dp in lists, 36 dp in headers and the tone list. */
enum class AppTileSize(val size: Dp, val radius: Dp, val letterDp: Dp) { Small(20.dp, 6.dp, 10.dp), Large(36.dp, 10.dp, 15.dp) }

/**
 * Host app identity: the real launcher [icon] when there is one, else a letter tile in a stable colour from the
 * tokens' app tile palette (picked by [packageName]). Decorative: the app name is always shown next to it.
 */
@Composable
fun AppTile(
    label: String,
    icon: Painter?,
    modifier: Modifier = Modifier,
    size: AppTileSize = AppTileSize.Small,
    packageName: String = label,
) {
    val shape = RoundedCornerShape(size.radius)
    if (icon != null) {
        Image(icon, contentDescription = null, contentScale = ContentScale.Fit, modifier = modifier.size(size.size).clip(shape))
        return
    }
    val palette = TokenColors.appTilePalette
    val bg = palette[Math.floorMod(packageName.hashCode(), palette.size)]
    // The letter tile stands in for a launcher icon, so like the icon it does not scale with the font: the letter is
    // sized in dp. It is decorative (the app name is always shown next to it), hence no semantics, and white on the
    // palette only needs the 3:1 of a graphic (ContrastTest).
    val letter = with(LocalDensity.current) { size.letterDp.toSp() }
    Box(modifier.size(size.size).clip(shape).background(bg).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        Text(
            label.trim().take(1).uppercase(),
            style = MurmurTheme.type.label.copy(fontSize = letter, lineHeight = letter, fontWeight = FontWeight.SemiBold),
            color = Color.White, maxLines = 1,
        )
    }
}

/** App tile from an [AppInfoProvider] (label falls back to the package name). */
@Composable
fun AppTile(packageName: String, provider: AppInfoProvider, modifier: Modifier = Modifier, size: AppTileSize = AppTileSize.Small) {
    AppTile(provider.label(packageName) ?: packageName, provider.icon(packageName), modifier, size, packageName)
}
