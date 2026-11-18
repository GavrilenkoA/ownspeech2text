package app.murmur.ui.design.components

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.murmur.ui.design.MurmurIcons
import app.murmur.ui.design.MurmurShapes
import app.murmur.ui.design.MurmurTheme
import app.murmur.ui.design.R
import app.murmur.ui.design.Sizes

/**
 * Tab screen header (prototype .hdr): >= 64 dp, 12/12/8/20 padding, the `headline` title and trailing icon
 * [actions] (4 dp apart).
 */
@Composable
fun ScreenHeader(title: String, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = Sizes.screenHeader)
            .padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            title, style = MurmurTheme.type.headline, color = MurmurTheme.colors.ink,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), content = actions)
    }
}

/**
 * Top bar for pushed screens (prototype .topbar): >= 56 dp, back button, optional [leading] (a 36 dp app tile), the
 * `topBar` title (one line, ellipsis) with an optional caption [subtitle], then icon [actions].
 */
@Composable
fun TopBar(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val c = MurmurTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = Sizes.topBar)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (onBack != null) MurmurIconButton(MurmurIcons.back, stringResource(R.string.design_back), onBack)
        Row(
            Modifier
                .weight(1f)
                .padding(start = if (onBack == null) 12.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            leading?.invoke()
            Column(Modifier.weight(1f)) {
                Text(
                    title, style = MurmurTheme.type.topBar, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
                if (subtitle != null) {
                    Text(subtitle, style = MurmurTheme.type.caption, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        actions()
    }
}

/** One destination of the bottom bar / rail. */
data class NavItem(val label: String, val icon: ImageVector)

/** DESIGN 8: above 150 % font scale the nav shows the label only for the active item. */
@Composable
private fun showNavLabel(selected: Boolean): Boolean = selected || LocalDensity.current.fontScale <= 1.5f

@Composable
private fun NavDestination(item: NavItem, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val c = MurmurTheme.colors
    val interaction = remember { MutableInteractionSource() }
    Column(
        Modifier
            .selectable(selected, interactionSource = interaction, indication = null, role = Role.Tab, onClick = onClick)
            .then(modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier
                .size(Sizes.navPillW, Sizes.navPillH)
                .clip(MurmurShapes.full)
                .background(if (selected) c.ink else c.surface)
                .indication(interaction, LocalIndication.current),
            contentAlignment = Alignment.Center,
        ) {
            Icon(item.icon, null, tint = if (selected) c.bg else c.ink3, modifier = Modifier.size(Sizes.icon))
        }
        if (showNavLabel(selected)) {
            Text(item.label, style = MurmurTheme.type.labelSm, color = if (selected) c.ink else c.ink3, maxLines = 1)
        }
    }
}

/**
 * Bottom navigation for compact width (DESIGN 4): 72 dp plus the gesture inset, surface, 1 dp top line; items are a
 * 56 × 32 pill with a 24 dp icon and a 12 sp label 4 dp below. Active: ink pill, bg icon, ink label (never accent).
 */
@Composable
fun MurmurNavigationBar(items: List<NavItem>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = MurmurTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .background(c.surface)
            .drawBehind { drawLine(c.line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .height(Sizes.bottomNav)
            .padding(start = 8.dp, end = 8.dp, top = 6.dp)
            .selectableGroup(),
    ) {
        items.forEachIndexed { i, item ->
            NavDestination(item, i == selectedIndex, { onSelect(i) }, Modifier.weight(1f).heightIn(min = 56.dp).fillMaxHeight())
        }
    }
}

/**
 * Navigation rail for medium+ width (DESIGN 4): 88 dp, surface, 1 dp right line, the mark in a 48 dp box at the top,
 * 80 dp wide items with the same pill.
 */
@Composable
fun MurmurNavigationRail(items: List<NavItem>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = MurmurTheme.colors
    Column(
        modifier
            .width(Sizes.rail)
            .fillMaxHeight()
            .background(c.surface)
            .drawBehind { drawLine(c.line, Offset(size.width - 0.5.dp.toPx(), 0f), Offset(size.width - 0.5.dp.toPx(), size.height), 1.dp.toPx()) }
            .padding(vertical = 12.dp)
            .selectableGroup(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Icon(MurmurIcons.mark, null, tint = c.ink, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(12.dp))
        items.forEachIndexed { i, item ->
            NavDestination(item, i == selectedIndex, { onSelect(i) }, Modifier.width(80.dp).padding(top = 4.dp, bottom = 6.dp))
        }
    }
}
