package app.tenshu.maps.ui.screens.places

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tenshu.maps.model.Place
import app.tenshu.maps.model.Reason
import app.tenshu.maps.shared.resources.Res
import app.tenshu.maps.shared.resources.maps_action_dismiss
import app.tenshu.maps.shared.resources.maps_action_save
import app.tenshu.maps.shared.resources.maps_action_saved
import app.tenshu.maps.shared.resources.maps_add_a_place
import app.tenshu.maps.shared.resources.maps_contribute_add_body
import app.tenshu.maps.shared.resources.maps_contribute_add_title
import app.tenshu.maps.shared.resources.maps_contribute_blurb
import app.tenshu.maps.shared.resources.maps_contribute_pending
import app.tenshu.maps.shared.resources.maps_contribute_queue_note
import app.tenshu.maps.shared.resources.maps_contribute_report_body
import app.tenshu.maps.shared.resources.maps_contribute_report_title
import app.tenshu.maps.shared.resources.maps_contribute_your_contributions
import app.tenshu.maps.shared.resources.maps_for_you_blurb
import app.tenshu.maps.shared.resources.maps_new_list
import app.tenshu.maps.shared.resources.maps_places_count_one
import app.tenshu.maps.shared.resources.maps_places_count_other
import app.tenshu.maps.shared.resources.maps_search_cd
import app.tenshu.maps.shared.resources.maps_settings_cd
import app.tenshu.maps.shared.resources.maps_tab_contribute
import app.tenshu.maps.shared.resources.maps_tab_for_you
import app.tenshu.maps.shared.resources.maps_tab_saved
import app.tenshu.maps.shared.resources.maps_tab_yours
import app.tenshu.maps.shared.resources.maps_title
import app.tenshu.maps.shared.resources.maps_yours_blurb
import app.tenshu.maps.ui.components.CategoryAvatar
import app.tenshu.maps.ui.components.Eyebrow
import app.tenshu.maps.ui.components.LabelChip
import app.tenshu.maps.ui.components.MapsCard
import app.tenshu.maps.ui.components.MapsIcons
import app.tenshu.maps.ui.components.PhotoStrip
import app.tenshu.maps.ui.components.PlaceRow
import app.tenshu.maps.ui.components.PlaceRowTrailing
import app.tenshu.maps.ui.components.SectionLabel
import app.tenshu.maps.ui.components.TrustCue
import app.tenshu.maps.ui.text.visitCountText
import app.tenshu.ui.theme.spacing
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.stringResource

/**
 * The Places surface (Ph5, docs/proposals/maps-app.md §6 / handoff §1.1): a small-caps top
 * bar, the four secondary tabs (Your places / Saved / For you / Contribute), and the
 * list-first content per tab (D1). Wired to [PlacesViewModel]; [today] resolves relative
 * dates (sourced at the composition root so this stays store-agnostic).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PlacesScreen(
    viewModel: PlacesViewModel,
    today: LocalDate,
    onOpenPlace: (String) -> Unit,
    onOpenRegions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        modifier = modifier,
        topBar = { MapsTopBar(onOpenRegions = onOpenRegions) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            val tabs = listOf(
                MapsTab.Yours to stringResource(Res.string.maps_tab_yours),
                MapsTab.Saved to stringResource(Res.string.maps_tab_saved),
                MapsTab.ForYou to stringResource(Res.string.maps_tab_for_you),
                MapsTab.Contribute to stringResource(Res.string.maps_tab_contribute),
            )
            SecondaryScrollableTabRow(selectedTabIndex = tabs.indexOfFirst { it.first == state.tab }) {
                tabs.forEach { (tab, label) ->
                    Tab(
                        selected = state.tab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        text = { Text(label) },
                    )
                }
            }

            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = MaterialTheme.spacing.l, vertical = MaterialTheme.spacing.m),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.m),
            ) {
                when (state.tab) {
                    MapsTab.Yours -> YourPlacesTab(state.yours, today, onOpenPlace)
                    MapsTab.Saved -> SavedTab(state, today, onOpenPlace) { id, saved -> viewModel.setSaved(id, saved) }
                    MapsTab.ForYou -> ForYouTab(state.recommended) { id, saved -> viewModel.setSaved(id, saved) }
                    MapsTab.Contribute -> ContributeTab()
                }
                TrustCue(Modifier.fillMaxWidth().padding(top = MaterialTheme.spacing.s))
            }
        }
    }
}

@Composable
private fun MapsTopBar(onOpenRegions: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = MaterialTheme.spacing.l, end = MaterialTheme.spacing.xs, top = MaterialTheme.spacing.s, bottom = MaterialTheme.spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(Res.string.maps_title).uppercase(),
            style = MaterialTheme.typography.titleMedium.copy(letterSpacing = 2.5.sp),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        // Search is gated on P4 geocoding (D4); settings opens the offline-region picker
        // (maps-region-delivery.md D5 — Maps owns the picker).
        IconButton(onClick = {}) {
            Icon(MapsIcons.search, contentDescription = stringResource(Res.string.maps_search_cd), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onOpenRegions) {
            Icon(MapsIcons.settings, contentDescription = stringResource(Res.string.maps_settings_cd), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun YourPlacesTab(places: List<Place>, today: LocalDate, onOpenPlace: (String) -> Unit) {
    Blurb(stringResource(Res.string.maps_yours_blurb))
    places.forEach { place ->
        MapsCard(Modifier.clickable { onOpenPlace(place.id) }) {
            Row(
                Modifier.padding(MaterialTheme.spacing.m),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.m),
            ) {
                CategoryAvatar(place.category, size = 48.dp, iconSize = 24.dp)
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.s)) {
                        Text(place.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        place.label?.let { LabelChip(it) }
                    }
                    Text(
                        "${place.address} · ${visitCountText(place.visitCount)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(MapsIcons.chevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    AddRow(stringResource(Res.string.maps_add_a_place))
}

@Composable
private fun SavedTab(
    state: PlacesUiState,
    today: LocalDate,
    onOpenPlace: (String) -> Unit,
    onToggleSaved: (String, Boolean) -> Unit,
) {
    state.savedLists.forEach { list ->
        val items = state.saved.filter { it.savedList == list.id }
        SectionLabel(
            text = list.name,
            trailing = {
                Text(
                    if (list.count == 1) stringResource(Res.string.maps_places_count_one, list.count)
                    else stringResource(Res.string.maps_places_count_other, list.count),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
        MapsCard {
            Column(Modifier.padding(horizontal = MaterialTheme.spacing.m)) {
                items.forEachIndexed { index, place ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    PlaceRow(
                        place = place,
                        today = today,
                        onClick = { onOpenPlace(place.id) },
                        trailing = PlaceRowTrailing.Saved,
                        onToggleSaved = { saved -> onToggleSaved(place.id, saved) },
                    )
                }
            }
        }
    }
    AddRow(stringResource(Res.string.maps_new_list))
}

@Composable
private fun ForYouTab(recommended: List<Place>, onToggleSaved: (String, Boolean) -> Unit) {
    Blurb(stringResource(Res.string.maps_for_you_blurb))
    recommended.forEach { place ->
        RecommendedCard(place, onSave = { onToggleSaved(place.id, true) })
    }
}

@Composable
private fun RecommendedCard(place: Place, onSave: () -> Unit) {
    MapsCard {
        Column {
            PhotoStrip(place.photos, height = 92.dp)
            Column(Modifier.padding(MaterialTheme.spacing.m)) {
                place.reason?.let { ReasonChip(it, place) }
                Text(place.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = MaterialTheme.spacing.s))
                Text(
                    "${place.category.name} · ${place.address}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = MaterialTheme.spacing.m),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.s),
                ) {
                    Button(onClick = onSave, modifier = Modifier.weight(1f)) {
                        Icon(MapsIcons.bookmark, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text(
                            if (place.saved) stringResource(Res.string.maps_action_saved) else stringResource(Res.string.maps_action_save),
                            modifier = Modifier.padding(start = MaterialTheme.spacing.xs),
                        )
                    }
                    OutlinedButton(onClick = {}) { Text(stringResource(Res.string.maps_action_dismiss)) }
                }
            }
        }
    }
}

@Composable
private fun ReasonChip(reason: Reason, place: Place) {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(
            Modifier.padding(horizontal = MaterialTheme.spacing.s, vertical = MaterialTheme.spacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
        ) {
            Icon(MapsIcons.forReason(reason.kind), contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(14.dp))
            Text(reason.text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

@Composable
private fun ContributeTab() {
    Blurb(stringResource(Res.string.maps_contribute_blurb))
    ContributeAction(MapsIcons.pin, stringResource(Res.string.maps_contribute_add_title), stringResource(Res.string.maps_contribute_add_body))
    ContributeAction(MapsIcons.flag, stringResource(Res.string.maps_contribute_report_title), stringResource(Res.string.maps_contribute_report_body))
    // D3 — honest posture on moderation.
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(MaterialTheme.spacing.m), horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.s)) {
            Icon(MapsIcons.lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Text(stringResource(Res.string.maps_contribute_queue_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    SectionLabel(stringResource(Res.string.maps_contribute_your_contributions))
    MapsCard {
        Row(Modifier.padding(MaterialTheme.spacing.m), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.m)) {
            Text(stringResource(Res.string.maps_contribute_pending), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ContributeAction(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String) {
    MapsCard {
        Row(Modifier.padding(MaterialTheme.spacing.m), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.m)) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(46.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(MapsIcons.chevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Blurb(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun AddRow(label: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = MaterialTheme.spacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.m),
    ) {
        Box(
            Modifier.size(44.dp).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(MapsIcons.plus, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
        Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    }
}
