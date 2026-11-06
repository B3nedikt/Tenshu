package app.tenshu.timeline.ui.screens.moment

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tenshu.timeline.data.RouteRegionStream
import app.tenshu.timeline.model.GeoPoint
import app.tenshu.timeline.model.Moment
import app.tenshu.timeline.model.MomentKind
import app.tenshu.timeline.model.Photo
import app.tenshu.timeline.ui.components.ChoosePlaceDialog
import app.tenshu.timeline.shared.resources.timeline_moment_not_found
import app.tenshu.timeline.ui.components.DetailHero
import app.tenshu.timeline.ui.components.DetailLoading
import app.tenshu.timeline.ui.components.DetailNotFound
import app.tenshu.timeline.ui.components.DetailUnavailable
import app.tenshu.timeline.ui.components.PhotoCluster
import app.tenshu.timeline.ui.components.rememberPhotoImage
import app.tenshu.timeline.ui.components.TimelineDetailScaffold
import app.tenshu.timeline.ui.components.momentTitleText
import app.tenshu.timeline.ui.components.TripRouteMap
import app.tenshu.timeline.ui.components.distanceText
import app.tenshu.timeline.ui.components.durationText
import app.tenshu.timeline.ui.components.movementModeText
import app.tenshu.timeline.shared.resources.Res
import app.tenshu.timeline.shared.resources.timeline_movement_distance_label
import app.tenshu.timeline.shared.resources.timeline_movement_duration_label
import app.tenshu.timeline.shared.resources.timeline_movement_mode_label
import app.tenshu.timeline.shared.resources.timeline_moment_open_place_a11y
import org.jetbrains.compose.resources.stringResource
import app.tenshu.timeline.ui.theme.TimelineType
import app.tenshu.timeline.ui.theme.rememberPersonalisation

/**
 * Moment detail (§1.3) — one screen, four anatomies switched on [MomentKind]: activity
 * (stat grid), visit (inline stats + place context), event (stats + note placeholder),
 * quiet (near-empty). Ph5 of docs/proposals/timeline-app.md.
 */
@Composable
fun MomentDetailScreen(
    viewModel: MomentDetailViewModel,
    onBack: () -> Unit,
    onOpenPlace: (String) -> Unit = {},
    onOpenRouteMap: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    when (val s = state) {
        MomentDetailUiState.Loading -> DetailLoading(onBack, modifier)
        MomentDetailUiState.Unavailable -> DetailUnavailable(viewModel::retry, onBack, modifier)
        MomentDetailUiState.NotFound ->
            DetailNotFound(stringResource(Res.string.timeline_moment_not_found), onBack, modifier)
        is MomentDetailUiState.Loaded -> MomentContent(
            moment = s.moment,
            strength = s.personalisationStrength,
            route = s.route,
            regionStream = s.regionStream,
            regionBundlePath = s.regionBundlePath,
            coverPhoto = s.coverPhoto,
            coverPhotoBytes = s.coverPhotoBytes,
            onBack = onBack,
            onOpenPlace = onOpenPlace,
            onOpenRouteMap = onOpenRouteMap,
            onRename = viewModel::rename,
            loadSuggestions = viewModel::suggestions,
            modifier = modifier,
        )
    }
}

@Composable
private fun MomentContent(
    moment: Moment,
    strength: Float,
    route: List<GeoPoint>,
    regionStream: RouteRegionStream?,
    regionBundlePath: String?,
    coverPhoto: Photo?,
    coverPhotoBytes: ByteArray?,
    onBack: () -> Unit,
    onOpenPlace: (String) -> Unit,
    onOpenRouteMap: () -> Unit,
    onRename: (String) -> Unit,
    loadSuggestions: suspend () -> List<String>,
    modifier: Modifier,
) {
    // T-P2: the tint comes from the photo actually on screen, averaged from the bitmap the hero is
    // decoding anyway — so `personalise()` finally shifts the chrome toward a real colour instead of
    // the amethyst placeholder. Falls back through the projection's tints when there is no photo.
    val photoImage by rememberPhotoImage(coverPhotoBytes)
    val tint = photoImage?.dominantColor
        ?: coverPhoto?.dominantColor
        ?: moment.photos.firstOrNull()?.dominantColor
    val personalisation = rememberPersonalisation(tint, strength)
    var picking by remember { mutableStateOf(false) }
    // The same derived headline the feed row shows — reading "Visit" here after tapping a row
    // labelled "Blue Bottle" would look like the wrong moment opened.
    val headline = momentTitleText(moment)
    TimelineDetailScaffold(title = headline, onBack = onBack, modifier = modifier) {
        if (moment.kind == MomentKind.Quiet) {
            QuietBand()
        } else if (moment.kind == MomentKind.Movement && route.size >= 2) {
            // M5: the movement's hero is its route over the offline basemap — the same
            // route-over-basemap surface the trip detail renders, drawn from the trace's
            // decoded polyline instead of visit-place coordinates. It stays gesture-free (this
            // page scrolls) and opens the full-screen, pannable map on tap.
            TripRouteMap(
                route = route,
                regionBundlePath = regionBundlePath,
                regionStream = regionStream,
                onClick = onOpenRouteMap,
            )
        } else {
            // T-P1: a photo the user actually took wins the hero, because it is the only one of
            // these three that is a photograph — `cover`/`photos` still carry only a tint today.
            // Absent, the band renders exactly as before (including not at all).
            val hero = coverPhoto ?: moment.cover ?: moment.photos.firstOrNull()
            hero?.let { DetailHero(it, image = photoImage?.bitmap) }
        }
        Column(Modifier.padding(16.dp)) {
            Text(
                "${moment.kind.name.uppercase()} · ${moment.time}",
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.sp),
                color = personalisation.accent,
            )
            Text(headline, style = TimelineType.hero, color = MaterialTheme.colorScheme.onSurface)
            // A Visit's headline already IS its place name, so this subject line would only echo it.
            if (moment.place.isNotBlank() && moment.place != headline) {
                Text(
                    moment.place,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            Spacer(Modifier.height(16.dp))
            when (moment.kind) {
                MomentKind.Activity -> ActivityBody(moment)
                MomentKind.Visit -> VisitBody(
                    moment = moment,
                    accent = personalisation.accent,
                    onOpenPlace = moment.placeId?.let { id -> { onOpenPlace(id) } },
                    onFixName = { picking = true },
                )
                MomentKind.Event -> EventBody(moment, personalisation.accent)
                MomentKind.Quiet -> QuietBody()
                MomentKind.Movement -> MovementBody(moment)
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "CAPTURED AUTOMATICALLY · tenshu://timeline/${moment.id}",
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (picking) {
        ChoosePlaceDialog(
            current = moment.place,
            loadSuggestions = loadSuggestions,
            onConfirm = { picking = false; onRename(it) },
            onDismiss = { picking = false },
        )
    }
}

/**
 * A movement's anatomy: mode, distance, duration — the three facts a trip *has*. Deliberately not
 * the Event body it fell through to before M5-lite, which offered "Add a note about this event…"
 * and a place-context card a movement has no place for.
 *
 * The route itself renders as the hero above (M5, [TripRouteMap]) via a dedicated single-trace
 * read; the feed query still never projects the polyline blob. A movement with no decodable path
 * (dropped polyline / unknown trace) keeps the stats as the whole body.
 */
@Composable
private fun MovementBody(moment: Moment) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatCell(
            stringResource(Res.string.timeline_movement_mode_label),
            movementModeText(moment.movementMode),
            Modifier.weight(1f),
        )
        moment.distanceM?.let {
            StatCell(
                stringResource(Res.string.timeline_movement_distance_label),
                distanceText(it),
                Modifier.weight(1f),
            )
        }
        moment.durationMin?.let {
            StatCell(
                stringResource(Res.string.timeline_movement_duration_label),
                durationTextValue(it),
                Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ActivityBody(moment: Moment) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        moment.durationMin?.let { StatCell("Duration", durationTextValue(it), Modifier.weight(1f)) }
        StatCell("Photos", "${moment.photos.size}", Modifier.weight(1f))
    }
    if (moment.photos.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        Photos(moment)
    }
}

@Composable
private fun VisitBody(
    moment: Moment,
    accent: Color,
    onOpenPlace: (() -> Unit)?,
    onFixName: () -> Unit,
) {
    InlineStats(moment)
    if (moment.photos.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        Photos(moment)
    }
    if (moment.mergedStayCount > 1 || moment.containedMoves.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        MovementWithinCard(moment, accent)
    }
    Spacer(Modifier.height(16.dp))
    PlaceContextCard(moment.place, accent, onOpen = onOpenPlace)
    // "Wrong place?" — a coarse indoor fix can name the neighbour; let the user correct it here (§4).
    TextButton(onClick = onFixName, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)) {
        Text(
            "Wrong place? Choose the right one",
            style = MaterialTheme.typography.labelMedium,
            color = accent,
        )
    }
}

/**
 * "Activity here" (§2b): when several same-place stays were merged into this visit, the wandering
 * between them is surfaced here — the only place the merge is legible, since the feed shows just the
 * combined visit. Short intra-visit movements (when the capture layer recorded them as a trace) list
 * their mode + distance; otherwise the below-threshold wander is summarised by the merged-stay count.
 */
@Composable
private fun MovementWithinCard(moment: Moment, accent: Color) {
    val totalM = moment.containedMoves.sumOf { it.distanceM }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .padding(16.dp),
    ) {
        Text(
            "ACTIVITY HERE",
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
            color = accent,
        )
        Spacer(Modifier.height(8.dp))
        if (totalM > 0) {
            Text(
                "Moved ${distanceText(totalM)} around this place",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (moment.mergedStayCount > 1) {
            Text(
                "Combined from ${moment.mergedStayCount} nearby stays",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        moment.containedMoves.forEach { move ->
            val duration = move.durationMin?.let { " · ${durationTextValue(it)}" }.orEmpty()
            Text(
                "${movementModeText(move.mode)} · ${distanceText(move.distanceM)}$duration",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EventBody(moment: Moment, accent: Color) {
    InlineStats(moment)
    if (moment.photos.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        Photos(moment)
    }
    Spacer(Modifier.height(16.dp))
    // "Add a note…" placeholder (affordance-only, §7).
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .padding(16.dp),
    ) {
        Text("Add a note about this event…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.height(16.dp))
    PlaceContextCard(moment.place, accent)
}

@Composable
private fun QuietBody() {
    Text(
        "A quiet evening. Nothing to capture.",
        style = TimelineType.body,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatCell("Count", "24", Modifier.weight(1f))
        StatCell("Most common", "Mon", Modifier.weight(1f))
    }
}

@Composable
private fun InlineStats(moment: Moment) {
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        moment.durationMin?.let { StatInline("Duration", durationTextValue(it)) }
        StatInline("Photos", "${moment.photos.size}")
    }
}

@Composable
private fun Photos(moment: Moment) {
    Text(
        "PHOTOS · ${moment.photos.size}",
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    PhotoCluster(photos = moment.photos, accent = MaterialTheme.colorScheme.primary)
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(16.dp),
    ) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun StatInline(label: String, value: String) {
    Column {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun PlaceContextCard(place: String, accent: Color, onOpen: (() -> Unit)? = null) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
            .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier)
            .padding(16.dp),
    ) {
        Text("AT THIS PLACE", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                place,
                style = TimelineType.title,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (onOpen != null) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowForward,
                    contentDescription = stringResource(Res.string.timeline_moment_open_place_a11y),
                    tint = accent,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun QuietBand() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(180.dp)
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(64.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Home, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
        }
    }
}

@Composable
private fun durationTextValue(minutes: Int): String = durationText(minutes)


