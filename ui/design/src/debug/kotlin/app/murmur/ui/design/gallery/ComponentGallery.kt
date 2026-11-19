package app.murmur.ui.design.gallery

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.murmur.ui.design.MurmurIcons
import app.murmur.ui.design.MurmurShapes
import app.murmur.ui.design.MurmurTheme
import app.murmur.ui.design.Spacing
import app.murmur.ui.design.components.AppTile
import app.murmur.ui.design.components.AppTileSize
import app.murmur.ui.design.components.AudioPlayer
import app.murmur.ui.design.components.BadgeKind
import app.murmur.ui.design.components.Banner
import app.murmur.ui.design.components.BannerKind
import app.murmur.ui.design.components.ButtonSize
import app.murmur.ui.design.components.ButtonStyle
import app.murmur.ui.design.components.DialogSurface
import app.murmur.ui.design.components.DividerInsetIcon
import app.murmur.ui.design.components.EmptyState
import app.murmur.ui.design.components.GroupedList
import app.murmur.ui.design.components.InTrainingBadge
import app.murmur.ui.design.components.InUsePill
import app.murmur.ui.design.components.LinearProgress
import app.murmur.ui.design.components.ListRow
import app.murmur.ui.design.components.MurmurBadge
import app.murmur.ui.design.components.MurmurButton
import app.murmur.ui.design.components.MurmurChip
import app.murmur.ui.design.components.MurmurIconButton
import app.murmur.ui.design.components.MurmurNavigationBar
import app.murmur.ui.design.components.MurmurNavigationRail
import app.murmur.ui.design.components.MurmurRadio
import app.murmur.ui.design.components.MurmurSnackbar
import app.murmur.ui.design.components.MurmurSwitch
import app.murmur.ui.design.components.NavItem
import app.murmur.ui.design.components.NoRussianBadge
import app.murmur.ui.design.components.Overline
import app.murmur.ui.design.components.RingProgress
import app.murmur.ui.design.components.RowTrailing
import app.murmur.ui.design.components.ScreenHeader
import app.murmur.ui.design.components.SearchField
import app.murmur.ui.design.components.SectionLabel
import app.murmur.ui.design.components.SegmentedControl
import app.murmur.ui.design.components.SheetSurface
import app.murmur.ui.design.components.Skeleton
import app.murmur.ui.design.components.StatusKind
import app.murmur.ui.design.components.StatusLabel
import app.murmur.ui.design.components.TopBar
import app.murmur.ui.design.rememberIsWide
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Test tag of the gallery's scrolling column (screenshot test). */
const val GALLERY_TAG = "component-gallery"

/**
 * Every :ui:design component in every state (DESIGN 4), for the screenshot test and as the reference for the screen
 * packages. Wrap it in MurmurTheme(darkTheme = …). On medium+ width the sections flow in two columns. Debug only.
 * Sample copy is design placeholder text; screens use string resources.
 */
@Composable
fun ComponentGallery(modifier: Modifier = Modifier, scrollState: ScrollState = rememberScrollState()) {
    val c = MurmurTheme.colors
    val wide = rememberIsWide()
    val sections: List<Pair<String, @Composable () -> Unit>> = listOf(
        "Type" to { TypeSection() },
        "Colour roles" to { ColorSection() },
        "Icons" to { IconSection() },
        "Buttons" to { ButtonSection() },
        "Icon buttons" to { IconButtonSection() },
        "Chips" to { ChipSection() },
        "Badges, pills, status" to { BadgeSection() },
        "Segmented" to { SegmentedSection() },
        "Search" to { SearchSection() },
        "Grouped list" to { ListSection() },
        "Switch and radio" to { ControlSection() },
        "Progress" to { ProgressSection() },
        "Banners" to { BannerSection() },
        "Snackbar" to { SnackbarSection() },
        "Dialog" to { DialogSection() },
        "Audio player" to { PlayerSection() },
        "App tiles" to { TileSection() },
        "Skeleton" to { SkeletonSection() },
        "Empty state" to { EmptySection() },
        "Navigation" to { NavSection(wide) },
    )
    Column(
        modifier
            .fillMaxSize()
            .background(c.bg)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .testTag(GALLERY_TAG)
            .verticalScroll(scrollState)
            .padding(bottom = 32.dp),
    ) {
        ScreenHeader("Components")
        if (wide) {
            val half = (sections.size + 1) / 2
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) { sections.take(half).forEach { (t, s) -> Section(t, s) } }
                Column(Modifier.weight(1f)) { sections.drop(half).forEach { (t, s) -> Section(t, s) } }
            }
        } else {
            sections.forEach { (t, s) -> Section(t, s) }
        }
        // Full width on medium+: the top bar needs a detail pane's width, the side sheet is 420 dp wide.
        if (wide) Row(Modifier.padding(horizontal = 8.dp)) { Column { Section("Bars") { BarSection() } } } else Section("Bars") { BarSection() }
        Section(if (wide) "Sheet (side, medium+)" else "Sheet (bottom, compact)") { SheetSection(wide) }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    SectionLabel(title)
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { content() }
}

/** A MutableInteractionSource that is held pressed, to show pressed states statically. */
@Composable
private fun pressedSource(): MutableInteractionSource {
    val src = remember { MutableInteractionSource() }
    LaunchedEffect(src) { src.emit(PressInteraction.Press(Offset.Zero)) }
    return src
}

@Composable
private fun Caption(text: String) = Text(text, style = MurmurTheme.type.caption, color = MurmurTheme.colors.ink3)

@Composable
private fun TypeSection() {
    val c = MurmurTheme.colors
    val type = MurmurTheme.type
    Column(Modifier.fillMaxWidth().clip(MurmurShapes.lg).background(c.surface).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        type.all.forEach { (name, style) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(name, style = type.mono, color = c.ink3, modifier = Modifier.width(112.dp))
                val sample = when (name) {
                    "display" -> "1,284"
                    "headline", "onboardingTitle", "title" -> "History"
                    "mono", "monoTimer" -> "0:04 / 1:12"
                    "monoBadge" -> "Q4_K_M"
                    "editor" -> "{transcript}"
                    else -> "Слушаю · Listening"
                }
                Text(type.transform(name, sample), style = style, color = c.ink, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ColorSection() {
    val c = MurmurTheme.colors
    val roles = listOf(
        "bg" to c.bg, "surface" to c.surface, "surface2" to c.surface2, "surface3" to c.surface3, "ink" to c.ink,
        "ink2" to c.ink2, "ink3" to c.ink3, "ink4" to c.ink4, "line" to c.line, "line2" to c.line2, "accent" to c.accent,
        "accentPressed" to c.accentPressed, "onAccent" to c.onAccent, "accentSoft" to c.accentSoft,
        "accentInk" to c.accentInk, "ok" to c.ok, "okSoft" to c.okSoft, "warn" to c.warn, "warnSoft" to c.warnSoft,
        "err" to c.err, "errSoft" to c.errSoft, "inverse" to c.inverse, "onInverse" to c.onInverse,
        "inverseAccent" to c.inverseAccent, "scrim" to c.scrim,
    )
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        roles.forEach { (name, color) ->
            Column(Modifier.width(92.dp)) {
                Box(Modifier.fillMaxWidth().height(28.dp).clip(MurmurShapes.xs).background(color).border(1.dp, c.line, MurmurShapes.xs))
                Text(name, style = MurmurTheme.type.caption, color = c.ink2, maxLines = 1)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IconSection() {
    val c = MurmurTheme.colors
    Column(Modifier.fillMaxWidth().clip(MurmurShapes.lg).background(c.surface).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(MurmurIcons.mark, "mark", tint = c.ink, modifier = Modifier.size(48.dp))
            Caption("mark · 2.25 stroke · ${MurmurIcons.all.size} icons")
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MurmurIcons.all.forEach { (name, icon) -> Icon(icon, name, tint = c.ink, modifier = Modifier.size(24.dp)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ButtonSection() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MurmurButton("Download 1.7 GB", {}, Modifier.fillMaxWidth(), leadingIcon = MurmurIcons.dl)
        MurmurButton("Pressed", {}, Modifier.fillMaxWidth(), interactionSource = pressedSource())
        MurmurButton("Downloading…", {}, Modifier.fillMaxWidth(), loading = true)
        MurmurButton("Use this model", {}, Modifier.fillMaxWidth(), enabled = false)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MurmurButton("Re-polish", {}, style = ButtonStyle.Secondary, leadingIcon = MurmurIcons.refresh)
            MurmurButton("Delete", {}, style = ButtonStyle.Outline, leadingIcon = MurmurIcons.trash)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            MurmurButton("Cancel", {}, style = ButtonStyle.Text)
            MurmurButton("Delete", {}, style = ButtonStyle.Destructive)
            MurmurButton("Open", {}, style = ButtonStyle.Text, size = ButtonSize.Small)
            MurmurButton("Retry", {}, style = ButtonStyle.Secondary, size = ButtonSize.Small)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IconButtonSection() {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        MurmurIconButton(MurmurIcons.search, "Search", {})
        MurmurIconButton(MurmurIcons.share, "Share", {}, interactionSource = pressedSource())
        MurmurIconButton(MurmurIcons.pause, "Pause download", {}, tonal = true, small = true, iconSize = 18.dp)
        MurmurIconButton(MurmurIcons.refresh, "Resume download", {}, tonal = true, small = true, iconSize = 18.dp)
        MurmurIconButton(MurmurIcons.x, "Clear", {}, small = true)
        MurmurIconButton(MurmurIcons.trash, "Delete", {}, tonal = true)
        MurmurIconButton(MurmurIcons.copy, "Copy", {}, enabled = false)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipSection() {
    var sel by remember { mutableIntStateOf(0) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("All apps", "Telegram", "Gmail").forEachIndexed { i, s -> MurmurChip(s, { sel = i }, selected = sel == i) }
        MurmurChip("Last 7 days", {}, dropdown = true)
        MurmurChip("{transcript}", {}, leadingIcon = MurmurIcons.plus)
        MurmurChip("Casual", {}, dropdown = true, interactionSource = pressedSource())
        MurmurChip("Slack", {}, enabled = false)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BadgeSection() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MurmurBadge("NPU", BadgeKind.Npu)
            MurmurBadge("GPU", BadgeKind.Neutral)
            MurmurBadge("CPU", BadgeKind.Neutral)
            MurmurBadge("Q4_K_M", BadgeKind.Neutral)
            MurmurBadge("RU", BadgeKind.Language)
            MurmurBadge("EN", BadgeKind.Language)
            MurmurBadge("+28 · 22 dialects", BadgeKind.Language)
            NoRussianBadge()
            InTrainingBadge()
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            InUsePill()
            StatusLabel("Copied", StatusKind.Warn, icon = MurmurIcons.clip)
            StatusLabel("Failed", StatusKind.Err, icon = MurmurIcons.alert)
            StatusLabel("Allowed", StatusKind.Ok, icon = MurmurIcons.check)
            StatusLabel("Restricted", StatusKind.Warn)
        }
    }
}

@Composable
private fun SegmentedSection() {
    var a by remember { mutableIntStateOf(0) }
    var b by remember { mutableIntStateOf(1) }
    Column {
        SegmentedControl(listOf("Final", "Original", "Changes"), a, { a = it })
        SegmentedControl(listOf("Speech", "Cleanup"), b, { b = it })
    }
}

@Composable
private fun SearchSection() {
    var q by remember { mutableStateOf("созвон") }
    var empty by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SearchField(empty, { empty = it }, hint = "Search 7 days of dictations")
        SearchField(q, { q = it }, hint = "Search 7 days of dictations", active = true, onBack = {})
        SearchField("", {}, hint = "Search 7 days of dictations", active = true, onBack = {})
    }
}

@Composable
private fun ListSection() {
    var hold by remember { mutableStateOf(true) }
    var tuck by remember { mutableStateOf(false) }
    var keep by remember { mutableIntStateOf(2) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GroupedList(dividerInset = DividerInsetIcon) {
            item { ListRow("Languages", subtitle = "Russian and English, detected per dictation", leadingIcon = MurmurIcons.globe, trailing = RowTrailing.Chevron(), onClick = {}) }
            item { ListRow("Hold to talk", subtitle = "Press and hold the bubble, release to finish", leadingIcon = MurmurIcons.hand, trailing = RowTrailing.Switch(hold), onClick = { hold = !hold }) }
            item { ListRow("Tuck in when idle", subtitle = "Shrinks to an edge tab after 4 s", leadingIcon = MurmurIcons.eyeOff, trailing = RowTrailing.Switch(tuck), onClick = { tuck = !tuck }) }
            item { ListRow("Sound and vibration", leadingIcon = MurmurIcons.vib, trailing = RowTrailing.Chevron("Vibration"), onClick = {}, interactionSource = pressedSource()) }
            item { ListRow("Size", trailing = RowTrailing.Chevron("Standard"), onClick = {}) }
            item { ListRow("Works offline", subtitle = "Only model downloads use the internet", leadingIcon = MurmurIcons.shield, trailing = RowTrailing.Value("On", MurmurTheme.colors.ok)) }
            item { ListRow("Microphone", leadingIcon = MurmurIcons.mic, trailing = RowTrailing.Status("Allowed", StatusKind.Ok, MurmurIcons.check), onClick = {}) }
            item { ListRow("Background use", leadingIcon = MurmurIcons.battO, trailing = RowTrailing.Status("Restricted", StatusKind.Warn), onClick = {}) }
            item { ListRow("Delete all history", titleColor = MurmurTheme.colors.err, onClick = {}) }
        }
        GroupedList {
            listOf("1 day", "3 days", "7 days", "Don't keep history").forEachIndexed { i, s ->
                item(selected = i == 3) {
                    ListRow(
                        s, subtitle = if (i == 2) "Default" else if (i == 3) "Text is copied and inserted, nothing is saved" else null,
                        trailing = RowTrailing.Radio(keep == i), onClick = { keep = i }, selected = i == 3,
                    )
                }
            }
            item { ListRow("Unavailable", subtitle = "Disabled row", trailing = RowTrailing.Chevron(), onClick = {}, enabled = false) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ControlSection() {
    var s by remember { mutableStateOf(true) }
    var r by remember { mutableStateOf(true) }
    FlowRow(itemVerticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MurmurSwitch(s, { s = it })
        MurmurSwitch(!s, { s = !it })
        MurmurSwitch(true, {}, enabled = false)
        MurmurRadio(r, { r = true })
        MurmurRadio(!r, { r = false })
        MurmurRadio(true, {}, enabled = false)
    }
}

@Composable
private fun ProgressSection() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LinearProgress(0.62f)
        LinearProgress(0.48f, paused = true)
        LinearProgress(0.46f, height = 6.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            RingProgress(0.62f)
            RingProgress(0.3f, paused = true)
            RingProgress(1f)
            RingProgress(0.08f, showPercent = false)
        }
    }
}

@Composable
private fun BannerSection() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Banner(
            BannerKind.Err, "The bubble is off.",
            "Android turned off the accessibility service, usually after an update or a crash.",
            actionLabel = "Open Accessibility settings", onAction = {},
        )
        Banner(BannerKind.Warn, "Not enough space.", "It needs 1.1 GB and 0.4 GB is free.")
        Banner(BannerKind.Info, "Couldn't read your stats.", "History is not affected.", actionLabel = "Retry", onAction = {})
    }
}

@Composable
private fun SnackbarSection() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MurmurSnackbar("Dictation deleted, with its audio", "Undo", {})
        MurmurSnackbar("Bubble hidden until 15:32", null, {})
    }
}

@Composable
private fun DialogSection() {
    Box(
        Modifier.fillMaxWidth().clip(MurmurShapes.lg).background(MurmurTheme.colors.bg).background(MurmurTheme.colors.scrim).padding(vertical = 24.dp, horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        DialogSurface(
            "Delete this dictation?",
            "The text and the recording are removed from this phone. Text already pasted into Telegram stays there.",
            Modifier.fillMaxWidth(),
        ) {
            MurmurButton("Cancel", {}, style = ButtonStyle.Text)
            MurmurButton("Delete", {}, style = ButtonStyle.Destructive)
        }
    }
}

@Composable
private fun SheetSection(wide: Boolean) {
    var keep by remember { mutableIntStateOf(2) }
    Box(
        Modifier
            .fillMaxWidth()
            .then(if (wide) Modifier.height(420.dp) else Modifier)
            .clip(MurmurShapes.lg)
            .background(MurmurTheme.colors.bg)
            .background(MurmurTheme.colors.scrim)
            .padding(top = if (wide) 0.dp else 24.dp),
        contentAlignment = if (wide) Alignment.CenterEnd else Alignment.BottomCenter,
    ) {
        SheetSurface(side = wide) {
            Column(Modifier.padding(horizontal = 24.dp)) {
                Text("Keep history for", style = MurmurTheme.type.title, color = MurmurTheme.colors.ink)
                Text(
                    "Text and recordings are deleted at midnight once they are older than this.",
                    style = MurmurTheme.type.body, color = MurmurTheme.colors.ink2, modifier = Modifier.padding(top = 6.dp),
                )
            }
            Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                listOf("1 day", "3 days", "7 days").forEachIndexed { i, s ->
                    ListRow(s, subtitle = if (i == 2) "Default" else null, trailing = RowTrailing.Radio(keep == i), onClick = { keep = i }, minHeight = 60.dp)
                }
            }
        }
    }
}

/** Synthetic peaks for the gallery (the prototype's waveform formula). */
private val samplePeaks = FloatArray(120) { i -> (abs(sin(i * 0.6) * cos(i * 0.13)).toFloat()) }

@Composable
private fun PlayerSection() {
    var playing by remember { mutableStateOf(true) }
    var pos by remember { mutableLongStateOf(4_000L) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AudioPlayer(samplePeaks, pos, 12_000L, playing, { playing = !playing }, { pos = it })
        AudioPlayer(samplePeaks, 0L, 12_000L, false, {}, {})
        AudioPlayer(FloatArray(0), 0L, 0L, false, {}, {}, enabled = false)
    }
}

/** A stand-in for a launcher icon Painter: a bitmap drawn in code (accent square with a white disc). */
@Composable
private fun sampleLauncherIcon(): Painter = remember {
    val bmp = ImageBitmap(96, 96)
    val canvas = androidx.compose.ui.graphics.Canvas(bmp)
    canvas.drawRect(0f, 0f, 96f, 96f, Paint().apply { color = Color(0xFF2AABEE) })
    canvas.drawCircle(Offset(48f, 48f), 26f, Paint().apply { color = Color.White })
    BitmapPainter(bmp)
}

@Composable
private fun TileSection() {
    val icon = sampleLauncherIcon()
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        AppTile("Telegram", icon)
        AppTile("Telegram", icon, size = AppTileSize.Large)
        AppTile("Gmail", null, packageName = "com.google.android.gm")
        AppTile("Slack", null, packageName = "com.Slack")
        AppTile("Termux", null, size = AppTileSize.Large, packageName = "com.termux")
        AppTile("Notes", null, size = AppTileSize.Large, packageName = "com.samsung.android.app.notes")
    }
}

@Composable
private fun SkeletonSection() {
    Column(
        Modifier.fillMaxWidth().clip(MurmurShapes.lg).background(MurmurTheme.colors.surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Skeleton(Modifier.width(120.dp).height(16.dp))
        Skeleton(Modifier.width(180.dp).height(44.dp))
        Row(Modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
            listOf(60, 52, 20, 16, 64, 58, 40).forEach { h -> Skeleton(Modifier.weight(1f).height(h.dp)) }
        }
    }
}

@Composable
private fun EmptySection() {
    EmptyState(
        MurmurIcons.hist, "Nothing here yet",
        "Tap the bubble in any app and speak. Each dictation shows up here with its audio for 7 days.",
        actionLabel = "Try a dictation", actionIcon = MurmurIcons.mic, onAction = {},
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
    )
}

@Composable
private fun BarSection() {
    val c = MurmurTheme.colors
    Column(Modifier.fillMaxWidth().clip(MurmurShapes.lg).background(c.surface)) {
        ScreenHeader("History") { MurmurIconButton(MurmurIcons.search, "Search history", {}) }
        TopBar(
            "Дима Соколов", onBack = {}, subtitle = "Telegram · Today, 14:32 · 0:12",
            leading = { AppTile("Telegram", null, size = AppTileSize.Large, packageName = "org.telegram.messenger") },
        ) {
            MurmurIconButton(MurmurIcons.share, "Share", {})
            MurmurIconButton(MurmurIcons.trash, "Delete", {})
        }
        TopBar("Cleanup prompt", onBack = {}) { MurmurButton("Reset", {}, style = ButtonStyle.Text) }
        Overline("Context used", Modifier.padding(start = 20.dp, bottom = 12.dp))
    }
}

@Composable
private fun NavSection(wide: Boolean) {
    val items = listOf(
        NavItem("Dashboard", MurmurIcons.dash), NavItem("History", MurmurIcons.hist),
        NavItem("Models", MurmurIcons.models), NavItem("Settings", MurmurIcons.sliders),
    )
    var sel by remember { mutableIntStateOf(1) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.clip(MurmurShapes.lg)) { MurmurNavigationBar(items, sel, { sel = it }) }
        Row(Modifier.height(400.dp).clip(MurmurShapes.lg)) {
            MurmurNavigationRail(items, sel, { sel = it })
            Box(Modifier.fillMaxSize().background(MurmurTheme.colors.bg).padding(Spacing.s16)) {
                Caption(if (wide) "Rail: medium+ width" else "Rail (shown on medium+ width)")
            }
        }
    }
}
