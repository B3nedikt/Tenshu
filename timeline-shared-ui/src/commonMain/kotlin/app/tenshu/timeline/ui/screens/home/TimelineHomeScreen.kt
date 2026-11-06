package app.tenshu.timeline.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tenshu.timeline.model.ActivityRollup
import app.tenshu.timeline.model.Place
import app.tenshu.timeline.shared.resources.Res
import app.tenshu.timeline.shared.resources.timeline_empty_activities
import app.tenshu.timeline.shared.resources.timeline_empty_places
import app.tenshu.timeline.shared.resources.timeline_place_visits_one
import app.tenshu.timeline.shared.resources.timeline_place_visits_other
import app.tenshu.timeline.shared.resources.timeline_place_unnamed
import app.tenshu.timeline.shared.resources.timeline_settings
import app.tenshu.timeline.ui.components.ChapterHeader
import app.tenshu.timeline.ui.components.InterludeCard
import app.tenshu.timeline.ui.components.NamedMoment
import app.tenshu.timeline.ui.components.placeRoleName
import app.tenshu.timeline.ui.components.TimelineIcons
import app.tenshu.timeline.ui.components.TimelineEmptyState
import app.tenshu.timeline.ui.components.TimelineTabEmpty
import app.tenshu.timeline.ui.components.TripCoverCard
import app.tenshu.timeline.ui.components.PhotoFrame
import app.tenshu.timeline.ui.theme.TimelineType
import app.tenshu.timeline.ui.theme.rememberPersonalisation
import app.tenshu.ui.layout.appContentWidth
import org.jetbrains.compose.resources.stringResource

/**
 * Home (§1.1) — the top-level surface: a small-caps wordmark, then whichever of the three
 * surfaces [tab] names. Detail navigation is via the callbacks. Ph5 of
 * docs/proposals/timeline-app.md.
 *
 * ⚠ The surface switcher is **not** here any more. It used to be an in-screen M3 secondary tab
 * row, which stretched across a desktop window and left the app the only Tenshu shell without
 * the shared `AdaptiveNavScaffold` chrome; the three surfaces are now that chrome's items
 * (bottom bar → rail → permanent drawer as the window widens), so [tab] arrives from the shell.
 * The view model keeps its own `tab`/`selectTab` for `timeline-ios`, whose SwiftUI shell drives
 * a segmented picker from it — see `App` for why the two cannot be the same state.
 *
 * ⚠ Insets are **status-bar only**: the shell owns the bottom edge (that is where the nav bar
 * is on a phone), so consuming `systemBars` here would pad above it twice.
 */
@Composable
fun TimelineHomeScreen(
    viewModel: TimelineHomeViewModel,
    tab: HomeTab,
    onOpenMoment: (String) -> Unit,
    onOpenTrip: (String) -> Unit,
    onOpenPlace: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(modifier = modifier, contentWindowInsets = WindowInsets.statusBars) { padding ->
        Box(
            Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            // ⚠ `appContentWidth()` FIRST — see `ContentWidthOrderTest`: after a `fillMaxSize()`
            // the cap is silently a no-op, because the fill has already fixed the width.
            Column(Modifier.appContentWidth().fillMaxSize()) {
                HomeTopBar()
                when {
                    // ⚠⚠ B3: nothing at all, and the store has *answered* — the import is the
                    // screen. Rendering this from `days.isEmpty()` alone would show "your timeline
                    // starts here" to a user with ten years of it while the first read ran.
                    state.hasNothing -> TimelineEmptyState()

                    tab == HomeTab.Home -> HomeFeed(state, viewModel, onOpenMoment, onOpenTrip)

                    // ⚠ A *tab* with nothing yet is a quieter, different message: this user has a
                    // timeline, just no named places (or no walks) in it.
                    tab == HomeTab.Places && state.placesLoaded && state.places.isEmpty() ->
                        TimelineTabEmpty(stringResource(Res.string.timeline_empty_places))

                    tab == HomeTab.Places -> PlacesTab(state.places, onOpenPlace)

                    tab == HomeTab.Activities && state.activitiesLoaded && state.activities.isEmpty() ->
                        TimelineTabEmpty(stringResource(Res.string.timeline_empty_activities))

                    else -> ActivitiesTab(state.activities)
                }
            }
        }
    }
}

@Composable
private fun HomeTopBar() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // No search icon: there is no search to open (timeline-app.md §10 lists it as deferred, and
        // the retrieval it wants is designed in mia-query-layer.md MQ-2, not here). A dead top-bar
        // affordance reads as a broken feature — re-add the button with the destination behind it.
        // The gear moved to the nav chrome with the rest of the destinations.
        Text(
            text = "TIMELINE",
            style = MaterialTheme.typography.titleMedium.copy(letterSpacing = 2.sp),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun HomeFeed(
    state: TimelineHomeUiState,
    viewModel: TimelineHomeViewModel,
    onOpenMoment: (String) -> Unit,
    onOpenTrip: (String) -> Unit,
) {
    val strength = state.personalisationStrength
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        state.days.forEachIndexed { dayIndex, day ->
            item(key = day.date.toString()) {
                Column {
                    val headerAccent = rememberPersonalisation(
                        day.moments.firstNotNullOfOrNull { it.photos.firstOrNull()?.dominantColor },
                        strength,
                    ).accent
                    ChapterHeader(
                        eyebrow = day.dayLabel,
                        dateLong = day.dateLong,
                        momentKinds = day.moments.map { it.kind },
                        momentCount = day.moments.size,
                        accent = headerAccent,
                    )
                    Spacer(Modifier.height(12.dp))
                    day.moments.forEachIndexed { i, moment ->
                        NamedMoment(
                            moment = moment,
                            personalisation = rememberPersonalisation(
                                moment.cover?.dominantColor ?: moment.photos.firstOrNull()?.dominantColor,
                                strength,
                            ),
                            showConnector = i != day.moments.lastIndex,
                            onClick = { onOpenMoment(moment.id) },
                        )
                    }
                    val suggestion = state.suggestion
                    if (dayIndex == 0 && suggestion != null) {
                        Spacer(Modifier.height(8.dp))
                        InterludeCard(
                            suggestion = suggestion,
                            accent = headerAccent,
                            onDismiss = viewModel::dismissSuggestion,
                            onAccept = viewModel::acceptSuggestion,
                        )
                    }
                }
            }
        }
        if (state.boundTrips.isNotEmpty()) {
            item(key = "bound-header") {
                Text(
                    text = "BOUND CHAPTER",
                    style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.5.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            items(state.boundTrips, key = { it.id }) { trip ->
                TripCoverCard(
                    cover = trip.cover,
                    eyebrow = trip.year?.let { "${trip.dateRange.substringBefore(' ')} $it" } ?: trip.dateRange,
                    title = trip.title,
                    meta = "${trip.dateRange} · ${trip.placesCount} places · ${trip.photosCount} photos",
                    cta = "Open chapter →",
                    onClick = { onOpenTrip(trip.id) },
                )
            }
        }
    }
}

@Composable
private fun PlacesTab(places: List<Place>, onOpenPlace: (String) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(places, key = { it.id }) { place -> PlaceRow(place) { onOpenPlace(place.id) } }
    }
}

@Composable
private fun PlaceRow(place: Place, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhotoFrame(place.cover, Modifier.size(56.dp), cornerRadius = 12.dp)
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            // Label-less until geocoded (place-geocoding.md G1) — honest fallback, not blank text.
            Text(
                place.name.ifBlank { stringResource(Res.string.timeline_place_unnamed) },
                style = TimelineType.title,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                place.neighborhood.ifBlank { place.city },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // place-roles.md PR-3: the role rides the accent metadata line rather than the name
            // line — it is a fact *about* the place, not part of what it is called, and the name
            // line is not a Row so a chip there would cost a layout change for no gain.
            val role = placeRoleName(place.role?.takeIf { it.isConfirmed }?.role)
            Text(
                listOfNotNull(
                    role,
                    stringResource(
                        if (place.visitsCount == 1) Res.string.timeline_place_visits_one else Res.string.timeline_place_visits_other,
                        place.visitsCount,
                        place.lastVisit.lowercase(),
                    ),
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ActivitiesTab(activities: List<ActivityRollup>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Column {
                Text(
                    "THIS YEAR · 2026",
                    style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.5.sp),
                    color = MaterialTheme.colorScheme.primary,
                )
                Text("Everything you've done", style = TimelineType.date, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(8.dp))
            }
        }
        items(activities, key = { it.kind }) { ActivityRow(it) }
    }
}

@Composable
private fun ActivityRow(activity: ActivityRollup) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                TimelineIcons.forActivity(activity.icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(activity.label, style = TimelineType.title, color = MaterialTheme.colorScheme.onSurface)
            Text(activity.secondary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Last: ${activity.lastDate}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("${activity.count}", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(activity.suffix.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
