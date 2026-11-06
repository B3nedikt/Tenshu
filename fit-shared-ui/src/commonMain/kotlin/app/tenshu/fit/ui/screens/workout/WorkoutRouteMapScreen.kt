package app.tenshu.fit.ui.screens.workout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import app.tenshu.fit.ui.components.WorkoutRouteMap
import app.tenshu.fit.ui.components.workoutTitle

/**
 * A workout's GPS route, full-screen (`maps-region-delivery.md` R6 taken full-bleed) — Fit's twin
 * of Timeline's route-map screen, so the two apps behave identically.
 *
 * The workout detail renders the same route as a gesture-free inline map — a pannable map inside a
 * vertically scrolling page would fight the page scroll — and hands the gestures to this pushed
 * destination instead: [WorkoutRouteMap] with `height = null` (fill the screen) and
 * `interactive = true` (pan + pinch-zoom; rotate/tilt stay off, see `RouteBasemap`). The ODbL
 * attribution chip rides along inside [WorkoutRouteMap] (`BasemapChrome`).
 *
 * The track is **not** carried by the navigation destination: destinations are `@Serializable` and
 * a GPS track can be hundreds of points. The entry re-resolves the same [WorkoutDetailViewModel] by
 * id (the same Koin key the detail entry uses, so the already-loaded instance is reused) and reads
 * the route off its state, exactly as [WorkoutDetailScreen] does.
 *
 * Reached only from the real-GPS branch of the detail's route section — a workout with no
 * `geoRoute` shows the schematic `RoutePath`, which is not tappable. Should this ever be entered
 * for a route-less workout anyway, [WorkoutRouteMap] renders nothing rather than an empty basemap.
 */
@Composable
fun WorkoutRouteMapScreen(
    viewModel: WorkoutDetailViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val loaded = state as? WorkoutDetailUiState.Loaded
    Column(Modifier.fillMaxSize()) {
        DetailTopBar(
            title = loaded?.let { workoutTitle(it.workout.title, it.workout.activityType) }.orEmpty(),
            onBack = onBack,
            // No overflow: the map has no action of its own, and deleting the workout from
            // under the map you are looking at is not one it should offer.
        )
        Box(Modifier.fillMaxSize()) {
            WorkoutRouteMap(
                route = loaded?.workout?.geoRoute.orEmpty(),
                modifier = Modifier.fillMaxSize(),
                regionBundlePath = loaded?.regionBundlePath,
                regionStream = loaded?.regionStream,
                height = null,
                interactive = true,
            )
        }
    }
}
