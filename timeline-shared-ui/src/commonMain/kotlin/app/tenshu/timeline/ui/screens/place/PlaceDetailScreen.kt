package app.tenshu.timeline.ui.screens.place

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tenshu.timeline.model.Place
import app.tenshu.timeline.model.PlaceVisit
import app.tenshu.timeline.shared.resources.Res
import app.tenshu.timeline.shared.resources.timeline_place_merge_action
import app.tenshu.timeline.shared.resources.timeline_place_merge_body
import app.tenshu.timeline.shared.resources.timeline_place_merge_cancel
import app.tenshu.timeline.shared.resources.timeline_place_merge_title
import app.tenshu.timeline.shared.resources.timeline_place_unmerge
import app.tenshu.timeline.shared.resources.timeline_place_rename_a11y
import app.tenshu.timeline.shared.resources.timeline_place_rename_cancel
import app.tenshu.timeline.shared.resources.timeline_place_rename_confirm
import app.tenshu.timeline.shared.resources.timeline_place_rename_hint
import app.tenshu.timeline.shared.resources.timeline_place_rename_title
import app.tenshu.timeline.shared.resources.timeline_place_not_found
import app.tenshu.timeline.shared.resources.timeline_place_unnamed
import app.tenshu.ui.feedback.AppLoadingIndicator
import app.tenshu.ui.feedback.AppLoadingSize
import app.tenshu.timeline.ui.components.ChoosePlaceDialog
import app.tenshu.timeline.ui.components.DetailHero
import app.tenshu.timeline.ui.components.DetailLoading
import app.tenshu.timeline.ui.components.DetailNotFound
import app.tenshu.timeline.ui.components.DetailUnavailable
import app.tenshu.timeline.ui.components.FooterAffordance
import app.tenshu.timeline.ui.components.PhotoCluster
import app.tenshu.timeline.ui.components.PlaceMap
import app.tenshu.timeline.model.PlaceRoleNames
import app.tenshu.timeline.ui.components.PlaceRoleChip
import app.tenshu.timeline.ui.components.PlaceRolePrompt
import app.tenshu.timeline.ui.components.TagChips
import app.tenshu.timeline.ui.components.TimelineDetailScaffold
import app.tenshu.timeline.ui.theme.TimelineType
import app.tenshu.timeline.ui.theme.rememberPersonalisation
import org.jetbrains.compose.resources.stringResource

/**
 * Place detail (§1.4) — hero + identity + a visit-history timeline (left rail: serif date +
 * time + connector dot, accent when the visit had photos; right: label, counts, cluster).
 * Ph5 of docs/proposals/timeline-app.md.
 */
@Composable
fun PlaceDetailScreen(
    viewModel: PlaceDetailViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    // Resolved outside the `when`: `stringResource` is @Composable and cannot be called from the
    // non-composable `semantics { }` lambda below.
    when (val s = state) {
        PlaceDetailUiState.Loading -> DetailLoading(onBack, modifier)
        PlaceDetailUiState.Unavailable -> DetailUnavailable(viewModel::retry, onBack, modifier)
        PlaceDetailUiState.NotFound ->
            DetailNotFound(stringResource(Res.string.timeline_place_not_found), onBack, modifier)
        is PlaceDetailUiState.Loaded -> PlaceContent(
            s, onBack, viewModel::rename, viewModel::suggestions,
            onSetRole = viewModel::setRole,
            onMerge = { other -> viewModel.merge(other) },
            onUnmerge = viewModel::unmerge,
            modifier = modifier,
        )
    }
}

@Composable
private fun PlaceContent(
    state: PlaceDetailUiState.Loaded,
    onBack: () -> Unit,
    onRename: (String) -> Unit,
    loadSuggestions: suspend () -> List<String>,
    onSetRole: (String) -> Unit,
    onMerge: (String) -> Unit,
    onUnmerge: () -> Unit,
    modifier: Modifier,
) {
    val place = state.place
    val strength = state.personalisationStrength
    // Label-less until geocoded (place-geocoding.md G1) — honest fallback, not blank text.
    val name = place.name.ifBlank { stringResource(Res.string.timeline_place_unnamed) }
    var renaming by remember { mutableStateOf(false) }
    var merging by remember { mutableStateOf(false) }
    TimelineDetailScaffold(title = name, onBack = onBack, modifier = modifier) {
        // A real map of the place (its own region tiles) instead of a flat colour; the flat hero
        // remains the fallback for a place with no coordinate yet (e.g. imported without one).
        if (place.latE7 != 0L || place.lngE7 != 0L) {
            PlaceMap(
                latE7 = place.latE7,
                lngE7 = place.lngE7,
                regionBundlePath = state.regionBundlePath,
                regionStream = state.regionStream,
                overlayPill = "Place",
            )
        } else {
            DetailHero(place.cover, overlayPill = "Place")
        }
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name,
                    style = TimelineType.hero,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f, fill = false),
                )
                IconButton(onClick = { renaming = true }) {
                    Icon(
                        Icons.Outlined.Edit,
                        contentDescription = stringResource(Res.string.timeline_place_rename_a11y),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // City-level is all geocoding stores (G4); hide the line entirely when unknown.
            val locality = listOf(place.neighborhood, place.city).filter { it.isNotBlank() }.joinToString(", ")
            if (locality.isNotEmpty()) {
                Text(
                    locality,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                "${place.visitsCount} visits · ${place.photosCount} photos · since ${place.firstVisit}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // place-roles.md PR-3: the prompt sits directly under the facts that justify the guess
            // ("N visits · since …") and above the point the layout goes full-bleed. A confirmed
            // role renders as a chip in the same slot — same position, question becomes statement.
            place.role?.let { role ->
                Spacer(Modifier.height(12.dp))
                if (role.isSuggestion) {
                    PlaceRolePrompt(
                        state = role,
                        accent = MaterialTheme.colorScheme.primary,
                        onConfirm = { onSetRole(role.role) },
                        onDismiss = { onSetRole(PlaceRoleNames.NONE) },
                    )
                } else {
                    PlaceRoleChip(role, MaterialTheme.colorScheme.primary)
                }
            }
            if (place.tags.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                TagChips(place.tags)
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "HISTORY · ${place.history.size} OF ${place.visitsCount}",
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.5.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        place.history.forEachIndexed { i, visit ->
            VisitRow(visit, strength, showConnector = i != place.history.lastIndex)
        }
        FooterAffordance("See all ${place.visitsCount} visits →")
        // ⚠ Structural actions live BELOW the content, together and in words. They were a bare icon
        // beside the title and a text button interleaved with the history rows — which made a rare,
        // corrective action look like part of the list, and gave "merge" a glyph no one can read.
        // Rename keeps its pencil: it is frequent and the pencil is conventional.
        if (state.mergeCandidates.isNotEmpty() || state.isMerged) {
            Spacer(Modifier.height(12.dp))
            Column(Modifier.padding(horizontal = 4.dp)) {
                if (state.mergeCandidates.isNotEmpty()) {
                    TextButton(onClick = { merging = true }) {
                        Text(stringResource(Res.string.timeline_place_merge_action))
                    }
                }
                // Only when something is actually folded into this row — the folded rows are
                // invisible by design, so the question cannot be answered from this place alone.
                if (state.isMerged) {
                    TextButton(onClick = onUnmerge) {
                        Text(stringResource(Res.string.timeline_place_unmerge))
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (merging) {
        MergePlaceDialog(
            candidates = state.mergeCandidates,
            onPick = { merging = false; onMerge(it) },
            onDismiss = { merging = false },
        )
    }
    if (renaming) {
        ChoosePlaceDialog(
            current = place.name,
            loadSuggestions = loadSuggestions,
            onConfirm = { renaming = false; onRename(it) },
            onDismiss = { renaming = false },
        )
    }
}

@Composable
private fun VisitRow(visit: PlaceVisit, strength: Float, showConnector: Boolean) {
    val hasPhotos = visit.photos.isNotEmpty()
    val accent = rememberPersonalisation(visit.photos.firstOrNull()?.dominantColor, strength).accent
    val connectorColor = MaterialTheme.colorScheme.outlineVariant
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .drawBehind {
                if (!showConnector) return@drawBehind
                val x = 64.dp.toPx() / 2f
                drawLine(connectorColor, Offset(x, 30.dp.toPx()), Offset(x, size.height), strokeWidth = 1.dp.toPx())
            },
    ) {
        Column(Modifier.width(64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(visit.date, style = TimelineType.body.copy(fontSize = 13.sp), color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
            Text(visit.time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (hasPhotos) accent else MaterialTheme.colorScheme.outlineVariant))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(bottom = 20.dp)) {
            Text(visit.label, style = TimelineType.title, color = MaterialTheme.colorScheme.onSurface)
            Text(
                "${visit.duration} · ${visit.photosCount} photos",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (hasPhotos) {
                Spacer(Modifier.height(8.dp))
                PhotoCluster(photos = visit.photos, accent = accent)
            }
        }
    }
}


/**
 * "Which place is this really part of?" — the manual merge picker (§9.4). Deliberately a plain list
 * of the user's own places rather than a search: manual merge exists for the *few* pairs the
 * automatic pass will not touch, and a user who reaches for it already knows which two they mean.
 */
@Composable
private fun MergePlaceDialog(
    candidates: List<app.tenshu.timeline.model.Place>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.timeline_place_merge_title)) },
        text = {
            Column {
                Text(
                    stringResource(Res.string.timeline_place_merge_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                candidates.forEach { candidate ->
                    TextButton(onClick = { onPick(candidate.id) }) {
                        Text(candidate.name.ifBlank { stringResource(Res.string.timeline_place_unnamed) })
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.timeline_place_merge_cancel)) }
        },
    )
}
