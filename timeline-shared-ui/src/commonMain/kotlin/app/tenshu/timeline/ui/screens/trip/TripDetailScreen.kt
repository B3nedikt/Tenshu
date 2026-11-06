package app.tenshu.timeline.ui.screens.trip

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tenshu.timeline.data.RouteRegionStream
import app.tenshu.timeline.data.TripOfflineOffer
import app.tenshu.timeline.model.Trip
import app.tenshu.timeline.model.TripEntry
import app.tenshu.timeline.shared.resources.Res
import app.tenshu.timeline.shared.resources.timeline_trip_not_found
import org.jetbrains.compose.resources.stringResource
import app.tenshu.timeline.ui.components.DetailHero
import app.tenshu.timeline.ui.components.DetailLoading
import app.tenshu.timeline.ui.components.DetailNotFound
import app.tenshu.timeline.ui.components.DetailUnavailable
import app.tenshu.timeline.ui.components.FooterAffordance
import app.tenshu.timeline.ui.components.NamedMoment
import app.tenshu.timeline.ui.components.TagChips
import app.tenshu.timeline.ui.components.TimelineDetailScaffold
import app.tenshu.timeline.ui.components.TripCoverCard
import app.tenshu.timeline.ui.components.TripOfflineRow
import app.tenshu.timeline.ui.components.TripRouteMap
import app.tenshu.timeline.ui.theme.TimelineType
import app.tenshu.timeline.ui.theme.rememberPersonalisation

/**
 * Trip detail (§1.2). Recursive: a nested-trip entry opens this same screen with the nested
 * id via [onOpenTrip]. Multi-day trips render compact day-chapters; single-day trips list
 * their moments flat. Ph5 of docs/proposals/timeline-app.md.
 */
@Composable
fun TripDetailScreen(
    viewModel: TripDetailViewModel,
    onBack: () -> Unit,
    onOpenMoment: (String) -> Unit,
    onOpenTrip: (String) -> Unit,
    onOpenRouteMap: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    when (val s = state) {
        TripDetailUiState.Loading -> DetailLoading(onBack, modifier)
        TripDetailUiState.Unavailable -> DetailUnavailable(viewModel::retry, onBack, modifier)
        TripDetailUiState.NotFound ->
            DetailNotFound(stringResource(Res.string.timeline_trip_not_found), onBack, modifier)
        is TripDetailUiState.Loaded -> TripContent(s.trip, s.personalisationStrength, s.regionBundlePath, s.regionStream, s.offlineOffer, s.offlineDownloading, s.offlineProgress, s.offlineFailed, viewModel::keepOffline, onBack, onOpenMoment, onOpenTrip, onOpenRouteMap, modifier)
    }
}

@Composable
private fun TripContent(
    trip: Trip,
    strength: Float,
    regionBundlePath: String?,
    regionStream: RouteRegionStream?,
    offlineOffer: TripOfflineOffer?,
    offlineDownloading: Boolean,
    offlineProgress: Float?,
    offlineFailed: Boolean,
    onKeepOffline: () -> Unit,
    onBack: () -> Unit,
    onOpenMoment: (String) -> Unit,
    onOpenTrip: (String) -> Unit,
    onOpenRouteMap: () -> Unit,
    modifier: Modifier,
) {
    TimelineDetailScaffold(title = trip.title, onBack = onBack, modifier = modifier) {
        DetailHero(trip.cover, overlayPill = "Trip · ${trip.year ?: ""}".trim().trimEnd('·', ' '))
        // The route over the offline basemap (maps-app.md §2 step 3) — shown when the trip has a
        // route; renders on the trip's downloaded region when one is installed, else the demo.
        if (trip.route.size >= 2) {
            TripRouteMap(
                route = trip.route,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 16.dp),
                regionBundlePath = regionBundlePath,
                regionStream = regionStream,
                // Gesture-free here (this page scrolls); tapping opens the full-screen map.
                onClick = onOpenRouteMap,
            )
            // "Keep this trip's map areas offline" (maps-region-delivery.md track 3d), under the map it
            // is about. A trip can cross several regions, so this offers the whole set.
            if (offlineOffer != null) {
                TripOfflineRow(
                    offer = offlineOffer,
                    downloading = offlineDownloading,
                    progress = offlineProgress,
                    failed = offlineFailed,
                    onKeepOffline = onKeepOffline,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 8.dp),
                )
            }
        }
        Column(Modifier.padding(16.dp)) {
            Text(trip.title, style = TimelineType.hero, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(4.dp))
            Text(
                "${trip.dateRange} · ${trip.daysSpan} days",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${trip.placesCount} places · ${trip.photosCount} photos",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (trip.tags.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                TagChips(trip.tags)
            }
        }
        trip.contents.forEach { entry ->
            when (entry) {
                is TripEntry.DayEntry -> DayChapter(entry, strength, onOpenMoment)
                is TripEntry.NestedTrip -> Box(Modifier.padding(16.dp)) {
                    NestedDayHeader(entry.dayLabel, entry.dateLong)
                    TripCoverCard(
                        cover = entry.trip.cover,
                        eyebrow = "Day trip · ${entry.trip.dateRange}",
                        title = entry.trip.title,
                        meta = "${entry.trip.placesCount} places · ${entry.trip.photosCount} photos · ${entry.trip.momentsCount ?: 0} moments",
                        cta = "→",
                        onClick = { onOpenTrip(entry.trip.id) },
                        modifier = Modifier.padding(top = 28.dp),
                    )
                }
                is TripEntry.MomentItem -> Box(Modifier.padding(horizontal = 16.dp)) {
                    NamedMoment(
                        moment = entry.moment,
                        personalisation = rememberPersonalisation(entry.moment.photos.firstOrNull()?.dominantColor, strength),
                        onClick = { onOpenMoment(entry.moment.id) },
                    )
                }
                is TripEntry.Collapsed -> Text(
                    text = "${entry.label}  ⌄",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }
        }
        FooterAffordance("All photos · ${trip.photosCount} →")
    }
}

@Composable
private fun DayChapter(day: TripEntry.DayEntry, strength: Float, onOpenMoment: (String) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        HorizontalDivider(Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(12.dp))
        Text(
            day.dayLabel.uppercase(),
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.5.sp),
            color = MaterialTheme.colorScheme.primary,
        )
        Text(day.dateLong, style = TimelineType.title, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(12.dp))
        day.moments.forEachIndexed { i, moment ->
            NamedMoment(
                moment = moment,
                personalisation = rememberPersonalisation(moment.photos.firstOrNull()?.dominantColor, strength),
                showConnector = i != day.moments.lastIndex,
                onClick = { onOpenMoment(moment.id) },
            )
        }
    }
}

@Composable
private fun NestedDayHeader(dayLabel: String, dateLong: String) {
    Column {
        Text(
            dayLabel.uppercase(),
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.5.sp),
            color = MaterialTheme.colorScheme.primary,
        )
        Text(dateLong, style = TimelineType.title, color = MaterialTheme.colorScheme.onSurface)
    }
}


