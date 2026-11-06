package app.tenshu.timeline.ui.screens.route

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import app.tenshu.timeline.data.RouteRegionStream
import app.tenshu.timeline.model.GeoPoint
import app.tenshu.timeline.ui.components.TripRouteMap
import app.tenshu.timeline.ui.components.momentTitleText
import app.tenshu.timeline.ui.screens.moment.MomentDetailUiState
import app.tenshu.timeline.ui.screens.moment.MomentDetailViewModel
import app.tenshu.timeline.ui.screens.trip.TripDetailUiState
import app.tenshu.timeline.ui.screens.trip.TripDetailViewModel
import app.tenshu.ui.chrome.AppTopAppBar

/**
 * The full-screen route map (`timeline-movement-capture.md` M5's route hero, taken full-bleed).
 *
 * The detail screens render the same route as a gesture-free inline hero — a pannable map inside a
 * vertically scrolling page would fight the page scroll — and hand the gestures to this pushed
 * destination instead: [TripRouteMap] with `height = null` (fill the screen) and
 * `interactive = true` (pan + pinch-zoom; rotate/tilt stay off, see `RouteBasemap`).
 *
 * The route geometry is **not** carried by the navigation destination: destinations are
 * `@Serializable` and a decoded polyline can be hundreds of points. The entry re-resolves the same
 * view model by id (the same Koin key the detail entry uses, so the already-loaded instance is
 * reused) and reads the route off its state, exactly as the detail screen does.
 */
@Composable
fun MomentRouteMapScreen(
    viewModel: MomentDetailViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    val loaded = state as? MomentDetailUiState.Loaded
    RouteMapScaffold(
        title = loaded?.let { momentTitleText(it.moment) }.orEmpty(),
        route = loaded?.route.orEmpty(),
        regionBundlePath = loaded?.regionBundlePath,
        regionStream = loaded?.regionStream,
        onBack = onBack,
        modifier = modifier,
    )
}

/** The trip's route, full-screen — see [MomentRouteMapScreen]. */
@Composable
fun TripRouteMapScreen(
    viewModel: TripDetailViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    val loaded = state as? TripDetailUiState.Loaded
    RouteMapScaffold(
        title = loaded?.trip?.title.orEmpty(),
        route = loaded?.trip?.route.orEmpty(),
        regionBundlePath = loaded?.regionBundlePath,
        regionStream = loaded?.regionStream,
        onBack = onBack,
        modifier = modifier,
    )
}

/**
 * Shared chrome: the app's ordinary pushed-screen top bar with a back chevron (as
 * `TimelineDetailScaffold` uses), then the map filling everything below it. Deliberately *not*
 * `TimelineDetailScaffold` — that one scrolls its body vertically, which is the very thing a
 * full-screen map must not do.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RouteMapScaffold(
    title: String,
    route: List<GeoPoint>,
    regionBundlePath: String?,
    regionStream: RouteRegionStream?,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = { AppTopAppBar(title = title, onBack = onBack) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            // The attribution chip rides along inside TripRouteMap (BasemapChrome) — ODbL
            // attribution is a licence obligation, so it stays on the full-screen map too.
            TripRouteMap(
                route = route,
                modifier = Modifier.fillMaxSize(),
                regionBundlePath = regionBundlePath,
                regionStream = regionStream,
                height = null,
                interactive = true,
            )
        }
    }
}
