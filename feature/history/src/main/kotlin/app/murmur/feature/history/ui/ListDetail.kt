package app.murmur.feature.history.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.murmur.core.model.EntryId
import app.murmur.feature.history.HistoryViewModel
import app.murmur.ui.design.Layout
import app.murmur.ui.design.MurmurTheme
import app.murmur.ui.design.rememberIsWide

/**
 * List + detail (DESIGN 6.3; ARCHITECTURE 3): on medium+ widths the 300 dp list pane and the detail pane side by
 * side (1 dp line between; the newest entry is shown when none is chosen, as in the `hist` inner frame; the list
 * alone spans the width when there is nothing to show beside it); on compact
 * widths the detail is pushed over the list and Back returns. The chosen entry id is saved, so folding, unfolding and
 * activity recreation keep it.
 */
@Composable
internal fun HistoryListDetailLayout(
    vm: HistoryViewModel,
    modifier: Modifier = Modifier,
    onTryDictation: (() -> Unit)? = null,
    bottomBar: @Composable () -> Unit = {},
) {
    var chosen by rememberSaveable { mutableStateOf<Long?>(null) }
    val wide = rememberIsWide()
    val c = MurmurTheme.colors
    if (wide) {
        val state by vm.state.collectAsStateWithLifecycle()
        val first = (state.search?.results ?: state.days).firstOrNull()?.rows?.firstOrNull()?.id
        val shown = chosen?.let(::EntryId) ?: first?.takeIf { state.search == null }
        if (shown == null) {
            // Nothing to show beside the list (empty, off, error, or a search before a result is chosen): the list
            // takes the whole width, as in the hist-empty and hist-search inner frames.
            HistoryListPane(vm, onOpenEntry = { chosen = it.value }, modifier = modifier.fillMaxSize(), onTryDictation = onTryDictation)
            return
        }
        Row(modifier.fillMaxSize()) {
            HistoryListPane(
                vm, onOpenEntry = { chosen = it.value },
                modifier = Modifier.width(Layout.historyListPane).fillMaxHeight(),
                selected = shown, onTryDictation = onTryDictation,
            )
            Box(Modifier.width(1.dp).fillMaxHeight().background(c.line))
            Box(Modifier.weight(1f).fillMaxHeight().background(c.surface)) {
                EntryDetail(vm, shown, onClose = { chosen = null }, pane = true)
            }
        }
    } else {
        val id = chosen
        if (id != null) {
            BackHandler { chosen = null }
            EntryDetail(vm, EntryId(id), onClose = { chosen = null }, modifier = modifier)
        } else {
            Column(modifier.fillMaxSize()) {
                HistoryListPane(vm, onOpenEntry = { chosen = it.value }, modifier = Modifier.weight(1f), onTryDictation = onTryDictation)
                bottomBar()
            }
        }
    }
}
