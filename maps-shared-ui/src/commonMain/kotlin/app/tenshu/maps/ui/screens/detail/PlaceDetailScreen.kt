package app.tenshu.maps.ui.screens.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tenshu.maps.data.OfflineRegionOffer
import app.tenshu.maps.data.RegionStreamHandle
import app.tenshu.maps.model.Place
import app.tenshu.maps.model.PlaceSection
import app.tenshu.maps.shared.resources.Res
import app.tenshu.maps.shared.resources.maps_action_directions
import app.tenshu.maps.shared.resources.maps_action_edit
import app.tenshu.maps.shared.resources.maps_action_save
import app.tenshu.maps.shared.resources.maps_action_saved
import app.tenshu.maps.shared.resources.maps_action_share
import app.tenshu.maps.shared.resources.maps_detail_directions_note
import app.tenshu.maps.shared.resources.maps_detail_embed_note
import app.tenshu.maps.shared.resources.maps_detail_first_seen
import app.tenshu.maps.shared.resources.maps_detail_history
import app.tenshu.maps.shared.resources.maps_detail_offline_line
import app.tenshu.maps.shared.resources.maps_offline_failed
import app.tenshu.maps.shared.resources.maps_offline_failed_hint
import app.tenshu.maps.shared.resources.maps_offline_keep
import app.tenshu.maps.shared.resources.maps_offline_keep_hint
import app.tenshu.maps.shared.resources.maps_offline_downloading
import app.tenshu.maps.shared.resources.maps_offline_kept
import app.tenshu.maps.shared.resources.maps_detail_photos
import app.tenshu.maps.shared.resources.maps_detail_trips_sub
import app.tenshu.maps.shared.resources.maps_detail_trips_sub_arrivals
import app.tenshu.maps.shared.resources.maps_detail_trips_title
import app.tenshu.maps.ui.components.CategoryAvatar
import app.tenshu.maps.ui.components.Eyebrow
import app.tenshu.maps.ui.components.LabelChip
import app.tenshu.maps.ui.components.MapSnippet
import app.tenshu.maps.ui.components.MapsCard
import app.tenshu.maps.ui.components.MapsDetailScaffold
import app.tenshu.maps.ui.components.MapsIcons
import app.tenshu.maps.ui.components.PhotoStrip
import app.tenshu.maps.ui.components.SavedToggle
import app.tenshu.maps.ui.components.VisitHistory
import app.tenshu.maps.ui.text.mediumDateText
import app.tenshu.ui.theme.spacing
import kotlin.math.roundToInt
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.stringResource

/**
 * The place-detail screen (Ph5, docs/proposals/maps-app.md §6 / handoff §1.2). Wires
 * [PlaceDetailViewModel] to the **embeddable** [PlaceDetailContent] — the canonical detail
 * (D2) that Timeline (visit moments) and Photos (place linking) reuse by calling
 * [PlaceDetailContent] directly over their own `Place`.
 */
@Composable
fun PlaceDetailScreen(
    viewModel: PlaceDetailViewModel,
    today: LocalDate,
    onBack: () -> Unit,
    onOpenTimeline: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    when (val s = state) {
        PlaceDetailUiState.Loading -> Box(modifier.fillMaxSize())
        PlaceDetailUiState.NotFound -> Box(modifier.fillMaxSize())
        is PlaceDetailUiState.Loaded -> PlaceDetailContent(
            place = s.place,
            today = today,
            onBack = onBack,
            onToggleSaved = viewModel::setSaved,
            onOpenTimeline = onOpenTimeline,
            regionBundlePath = s.regionBundlePath,
            regionStream = s.regionStream,
            offlineOffer = s.offlineOffer,
            offlineDownloading = s.offlineDownloading,
            offlineProgress = s.offlineProgress,
            offlineFailed = s.offlineFailed,
            onKeepOffline = viewModel::keepOffline,
            modifier = modifier,
        )
    }
}

/**
 * The canonical, **embeddable** place-detail body (D2) — a single-pin snippet, identity,
 * action pills, visit history, a Directions hand-off note, photos, and the "Trips through
 * here → Timeline" cross-link (routes belong to Timeline, D1). No VM dependency, so Timeline
 * & Photos embed the same view.
 *
 * [regionBundlePath] is the resolved offline-region terminus (`RegionResolver` →
 * `docs/proposals/maps-region-delivery.md` D5): when the place sits in a downloaded region the
 * embedder passes its `.pmtiles` path here and the snippet renders real tiles; null falls back to
 * the bundled demo/schematic. The place's own [Place.location] centres the map.
 */
@Composable
fun PlaceDetailContent(
    place: Place,
    today: LocalDate,
    onBack: () -> Unit,
    onToggleSaved: (Boolean) -> Unit,
    onOpenTimeline: () -> Unit = {},
    regionBundlePath: String? = null,
    regionStream: RegionStreamHandle? = null,
    offlineOffer: OfflineRegionOffer? = null,
    offlineDownloading: Boolean = false,
    offlineProgress: Float? = null,
    offlineFailed: Boolean = false,
    onKeepOffline: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val isYours = place.section == PlaceSection.Yours
    MapsDetailScaffold(title = place.name, onBack = onBack, modifier = modifier) {
        Column(
            Modifier.padding(horizontal = MaterialTheme.spacing.l, vertical = MaterialTheme.spacing.s),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.l),
        ) {
            // Hero — the offline single-pin snippet + the on-device honesty line.
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.s)) {
                MapSnippet(
                    seed = place.seed,
                    height = 208.dp,
                    location = place.location,
                    regionBundlePath = regionBundlePath,
                    regionStream = regionStream,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(MapsIcons.lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(12.dp))
                    Text(
                        stringResource(Res.string.maps_detail_offline_line),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                // The contextual "keep this area offline" trigger (track 3d), directly under the map it
                // is about. Shown only when a region actually covers this place.
                if (offlineOffer != null) {
                    OfflineOfferRow(
                        offer = offlineOffer,
                        downloading = offlineDownloading,
                        progress = offlineProgress,
                        failed = offlineFailed,
                        onKeepOffline = onKeepOffline,
                    )
                }
            }

            // Identity.
            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.m)) {
                CategoryAvatar(place.category, size = 52.dp, iconSize = 26.dp)
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.s)) {
                        Text(place.name, style = MaterialTheme.typography.headlineSmall)
                        if (isYours) place.label?.let { LabelChip(it) }
                    }
                    Text(
                        "${place.category.name} · ${place.address}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!isYours) SavedToggle(saved = place.saved, onToggle = onToggleSaved)
            }

            // Actions.
            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.s)) {
                if (isYours) {
                    ActionPill(MapsIcons.edit, stringResource(Res.string.maps_action_edit), Modifier.weight(1f)) {}
                } else {
                    ActionPill(
                        icon = if (place.saved) MapsIcons.bookmarkFilled else MapsIcons.bookmark,
                        label = if (place.saved) stringResource(Res.string.maps_action_saved) else stringResource(Res.string.maps_action_save),
                        modifier = Modifier.weight(1f),
                        filled = true,
                        onClick = { onToggleSaved(!place.saved) },
                    )
                }
                ActionPill(MapsIcons.directions, stringResource(Res.string.maps_action_directions), Modifier.weight(1f)) {}
                ActionPill(MapsIcons.share, stringResource(Res.string.maps_action_share), Modifier.weight(1f)) {}
            }

            // History.
            MapsCard {
                Column(
                    Modifier.padding(MaterialTheme.spacing.l),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.m),
                ) {
                    Eyebrow(stringResource(Res.string.maps_detail_history))
                    VisitHistory(place = place, today = today)
                    place.firstVisit?.let {
                        Text(
                            stringResource(Res.string.maps_detail_first_seen, mediumDateText(it)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Directions hand-off note (Navigate is deferred).
            DetailNote(MapsIcons.directions, stringResource(Res.string.maps_detail_directions_note))

            // Photos.
            if (place.photos.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.s)) {
                    Eyebrow(stringResource(Res.string.maps_detail_photos))
                    PhotoStrip(place.photos, height = 100.dp)
                }
            }

            // Trips → Timeline (D1).
            TimelineTripsLink(place, onOpenTimeline)

            // The D2 embed note — only on Your places (the owner detail).
            if (isYours) DetailNote(MapsIcons.info, stringResource(Res.string.maps_detail_embed_note))
        }
    }
}

@Composable
private fun ActionPill(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
        onClick = onClick,
    ) {
        Column(
            Modifier.padding(vertical = MaterialTheme.spacing.m, horizontal = MaterialTheme.spacing.xs),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
        ) {
            val fg = if (filled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = fg)
        }
    }
}

/**
 * "Keep this area offline" (`maps-region-delivery.md` §7.1 track 3d).
 *
 * ⚠ Deliberately **not** phrased as a fix. The map above is already rendering — it streams the handful of
 * tiles this camera needs (D10). What the download buys is that the map keeps working with **no network at
 * all**, and that no per-viewport request pattern reaches the tile host (T-MR-03). It also honestly names the
 * price up front, because a region is hundreds of megabytes. Once resident, the row states that and stops
 * being an action.
 */
@Composable
private fun OfflineOfferRow(
    offer: OfflineRegionOffer,
    downloading: Boolean,
    progress: Float?,
    failed: Boolean,
    onKeepOffline: () -> Unit,
) {
    val resident = offer.installed
    // A failed download stays tappable — that is the retry. Only "resident" and "in flight" are inert.
    val actionable = !resident && !downloading
    Row(
        Modifier
            .fillMaxWidth()
            .let { if (actionable) it.clickable(onClick = onKeepOffline) else it }
            .padding(vertical = MaterialTheme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            // Determinate: the ring is the actual bytes on disk (the proxy's staging file), so a stalled
            // download visibly stalls instead of spinning a lie.
            downloading && progress != null -> CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            downloading -> CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            else -> Icon(
                imageVector = if (resident) MapsIcons.lock else MapsIcons.download,
                contentDescription = null,
                tint = when {
                    failed -> MaterialTheme.colorScheme.error
                    resident -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.size(14.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = when {
                    downloading -> stringResource(
                        Res.string.maps_offline_downloading,
                        offer.name,
                        ((progress ?: 0f) * 100).roundToInt(),
                    )
                    resident -> stringResource(Res.string.maps_offline_kept, offer.name)
                    failed -> stringResource(Res.string.maps_offline_failed, offer.name)
                    else -> stringResource(Res.string.maps_offline_keep, offer.name)
                },
                style = MaterialTheme.typography.labelLarge,
                color = when {
                    failed -> MaterialTheme.colorScheme.error
                    resident || downloading -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.primary
                },
            )
            when {
                // A failure is a disappointment, not a breakage — say so, so nobody thinks the map died.
                failed -> Text(
                    text = stringResource(Res.string.maps_offline_failed_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                !resident && !downloading -> Text(
                    // The size is the point: the map already works, so the user is buying independence
                    // from the network at a real cost in disk + data.
                    text = stringResource(Res.string.maps_offline_keep_hint, formatRegionSize(offer.byteSize)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Coarse, honest size for a region bundle — GB above 1024 MB, else whole MB. */
private fun formatRegionSize(bytes: Long): String {
    val mib = bytes / (1024.0 * 1024.0)
    return if (mib >= 1024.0) "${kotlin.math.round(mib / 1024.0 * 10) / 10.0} GB" else "${mib.toLong()} MB"
}

@Composable
private fun DetailNote(icon: ImageVector, text: String) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(MaterialTheme.spacing.m), horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.s)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(17.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TimelineTripsLink(place: Place, onClick: () -> Unit) {
    MapsCard(Modifier.clickable(onClick = onClick)) {
        Row(
            Modifier.padding(MaterialTheme.spacing.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.m),
        ) {
            Surface(shape = androidx.compose.foundation.shape.CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(44.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(MapsIcons.route, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(Res.string.maps_detail_trips_title), style = MaterialTheme.typography.titleSmall)
                Text(
                    if (place.visitCount > 0) stringResource(Res.string.maps_detail_trips_sub_arrivals, place.visitCount)
                    else stringResource(Res.string.maps_detail_trips_sub),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(MapsIcons.arrowUpRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
