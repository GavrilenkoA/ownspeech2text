package app.murmur.feature.onboarding

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.murmur.core.dictation.BubbleAction
import app.murmur.core.dictation.BubbleError
import app.murmur.core.dictation.DictationBubble
import app.murmur.core.model.MurmurClock
import app.murmur.ui.design.Elevation
import app.murmur.ui.design.MurmurIcons
import app.murmur.ui.design.MurmurShapes
import app.murmur.ui.design.MurmurTheme
import app.murmur.ui.design.Sizes
import app.murmur.ui.design.elevation
import kotlinx.coroutines.delay

/** Level bar weights of DESIGN 3.5 (the overlay bubble uses the same). */
private val BAR_WEIGHTS = floatArrayOf(.35f, .7f, 1f, .6f, .85f, .45f, .25f)

/**
 * The in-app bubble next to the practice field (DESIGN 5.2 "Murmur's own UI ... the practice field in onboarding uses an
 * in-app bubble"; visuals of DESIGN 5.3). It renders the orchestrator's [DictationBubble]: Ready when idle, the
 * Listening pill (live level bars, timer, stop), Transcribing / Polishing, Inserted, Copied and Error pills. Pills grow
 * toward the screen centre from the docked right edge, so the caller aligns this to the end.
 */
@Composable
internal fun PracticeBubble(
    bubble: DictationBubble,
    levels: FloatArray,
    clock: MurmurClock,
    onStart: () -> Unit,
    onTap: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = MurmurTheme.colors
    val t = MurmurTheme.type
    when (bubble) {
        DictationBubble.Idle -> {
            val cd = stringResource(R.string.ob_bubble_ready_cd)
            Box(
                modifier
                    .size(Sizes.bubbleHitArea)
                    .clickable(role = Role.Button, onClick = onStart)
                    .semantics { contentDescription = cd }
                    .testTag("practice-bubble-ready"),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(Sizes.bubbleDiameter)
                        .elevation(Elevation.e2, CircleShape)
                        .clip(CircleShape)
                        .background(c.surface)
                        .border(1.dp, c.line, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(MurmurIcons.bold("mark"), null, tint = c.ink, modifier = Modifier.size(26.dp))
                }
            }
        }
        is DictationBubble.Listening -> {
            val listening = stringResource(R.string.ob_bubble_listening)
            val finish = stringResource(R.string.ob_bubble_finish_cd)
            Pill(
                bg = c.accent, fg = c.onAccent, start = 16.dp, end = if (bubble.hold) 18.dp else 6.dp,
                modifier = modifier
                    .then(if (bubble.hold) Modifier else Modifier.width(Sizes.bubbleListeningWidth))
                    .clickable(role = Role.Button, onClick = onTap)
                    .semantics { contentDescription = finish; liveRegion = LiveRegionMode.Polite }
                    .testTag("practice-bubble-listening"),
            ) {
                LevelBars(levels, c.onAccent)
                if (bubble.hold) {
                    Text(stringResource(R.string.ob_bubble_hold), style = t.bubble, color = c.onAccent)
                } else {
                    var now by remember { mutableLongStateOf(clock.elapsedMs()) }
                    LaunchedEffect(bubble.startedAtMs, bubble.frozenAtMs) {
                        while (bubble.frozenAtMs == null) { now = clock.elapsedMs(); delay(250) }
                    }
                    val ms = ((bubble.frozenAtMs ?: now) - bubble.startedAtMs).coerceAtLeast(0)
                    Text(
                        formatTimer(ms), style = t.monoTimer.copy(fontWeight = FontWeight.Medium),
                        color = if (bubble.warn) c.warnSoft else c.onAccent, modifier = Modifier.weight(1f),
                    )
                    Box(Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                        Icon(MurmurIcons.stop, listening, tint = c.onAccent, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        DictationBubble.Transcribing -> Working(modifier, stringResource(R.string.ob_bubble_transcribing), dots = false)
        DictationBubble.Polishing -> Working(modifier, stringResource(R.string.ob_bubble_polishing), dots = true)
        is DictationBubble.Inserted -> Done(
            modifier, MurmurIcons.bold("check"), c.ok,
            stringResource(if (bubble.noCleanup) R.string.ob_bubble_inserted_nocleanup else R.string.ob_bubble_inserted),
        )
        is DictationBubble.Copied -> Done(modifier, MurmurIcons.clip, c.accentInk, stringResource(R.string.ob_bubble_copied))
        is DictationBubble.Error -> {
            val reason = stringResource(
                when (bubble.reason) {
                    BubbleError.NO_SPEECH -> R.string.ob_bubble_err_nospeech
                    BubbleError.MIC_BUSY -> R.string.ob_bubble_err_micbusy
                    BubbleError.NO_MODEL -> R.string.ob_bubble_err_nomodel
                    BubbleError.LOAD_FAILED -> R.string.ob_bubble_err_load
                    BubbleError.TRANSCRIPTION_FAILED -> R.string.ob_bubble_err_transcribe
                    BubbleError.GENERIC -> R.string.ob_bubble_err_generic
                },
            )
            Pill(
                bg = c.surface, fg = c.ink, start = 14.dp, end = if (bubble.action == BubbleAction.RETRY) 6.dp else 18.dp, ring = true,
                modifier = modifier.widthIn(max = Sizes.bubbleMaxWidth)
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Assertive }
                    .testTag("practice-bubble-error"),
            ) {
                Icon(MurmurIcons.alert, null, tint = c.err, modifier = Modifier.size(20.dp))
                Text(reason, style = t.bubble, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                // Only Retry makes sense inside setup; "Open Models" and "Open entry" lead out of it.
                if (bubble.action == BubbleAction.RETRY) {
                    Box(
                        Modifier.height(40.dp).clip(MurmurShapes.full).background(c.surface2).clickable(role = Role.Button, onClick = onRetry)
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(stringResource(R.string.ob_bubble_retry), style = t.label.copy(fontWeight = FontWeight.SemiBold, fontSize = t.rowSub.fontSize), color = c.ink)
                    }
                }
            }
        }
    }
}

/** 52 dp pill (prototype .bub): full radius, e2, optional 1 dp line ring (surface pills). */
@Composable
private fun Pill(
    bg: Color, fg: Color, start: Dp, end: Dp, modifier: Modifier, ring: Boolean = false,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val c = MurmurTheme.colors
    Row(
        modifier
            .heightIn(min = Sizes.bubbleDiameter)
            .elevation(Elevation.e2, MurmurShapes.full)
            .clip(MurmurShapes.full)
            .background(bg)
            .then(if (ring) Modifier.border(1.dp, c.line, MurmurShapes.full) else Modifier)
            .padding(start = start, end = end),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides fg) { content() }
    }
}

@Composable
private fun Working(modifier: Modifier, label: String, dots: Boolean) {
    val c = MurmurTheme.colors
    Pill(
        bg = c.inverse, fg = c.onInverse, start = 16.dp, end = 18.dp,
        modifier = modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }.testTag("practice-bubble-working"),
    ) {
        if (dots) PulsingDots(c.onInverse) else StaticBars(c.onInverse)
        Text(label, style = MurmurTheme.type.bubble, color = c.onInverse)
    }
}

@Composable
private fun Done(modifier: Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, label: String) {
    val c = MurmurTheme.colors
    Pill(
        bg = c.surface, fg = c.ink, start = 14.dp, end = 18.dp, ring = true,
        modifier = modifier.widthIn(max = Sizes.bubbleMaxWidth)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }.testTag("practice-bubble-done"),
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Text(label, style = MurmurTheme.type.bubble, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** 7 bars, 3 dp wide, 3 dp apart, 4..24 dp from the live RMS levels (no fake animation: silence is flat). */
@Composable
private fun LevelBars(levels: FloatArray, color: Color) {
    Row(Modifier.height(24.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until 7) {
            val v = (levels.getOrNull(i) ?: 0f).coerceIn(0f, 1f) * BAR_WEIGHTS[i]
            val h = (4f + 20f * v).dp
            Box(Modifier.size(width = 3.dp, height = h).clip(RoundedCornerShape(2.dp)).background(color))
        }
    }
}

/** Transcribing: dimmed static bars (prototype heights 6/10/14/10/6). */
@Composable
private fun StaticBars(color: Color) {
    Row(Modifier.height(24.dp).alpha(0.6f), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(6, 10, 14, 10, 6).forEach { h -> Box(Modifier.size(width = 3.dp, height = h.dp).clip(RoundedCornerShape(2.dp)).background(color)) }
    }
}

/** Polishing: three 5 dp dots pulsing 1.2 s, 0.15 s apart (static under reduced motion). */
@Composable
private fun PulsingDots(color: Color) {
    val reduced = MurmurTheme.reducedMotion
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until 3) {
            val a = if (reduced) 0.7f else {
                val tr = rememberInfiniteTransition(label = "dots")
                val v by tr.animateFloat(
                    0.35f, 1f,
                    infiniteRepeatable(tween(600, delayMillis = i * 150), RepeatMode.Reverse), label = "dot$i",
                )
                v
            }
            Box(Modifier.size(5.dp).alpha(a).clip(CircleShape).background(color))
        }
    }
}

internal fun formatTimer(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
