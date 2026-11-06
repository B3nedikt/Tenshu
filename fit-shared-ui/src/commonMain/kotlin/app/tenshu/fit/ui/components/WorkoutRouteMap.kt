package app.tenshu.fit.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tenshu.fit.data.RouteRegionStream
import app.tenshu.fit.model.GeoCoord
import app.tenshu.fit.shared.resources.Res
import app.tenshu.fit.shared.resources.fit_workout_route_open_a11y
import app.tenshu.ui.map.BasemapChrome
import app.tenshu.ui.map.BasemapCoord
import app.tenshu.ui.map.RegionStreamSource
import app.tenshu.ui.map.RouteBasemap
import org.jetbrains.compose.resources.stringResource

/**
 * A workout's real GPS route over the offline `:ui-map` basemap (`maps-region-delivery.md` R6) —
 * the map counterpart to the schematic [RoutePath]. Rendered when the workout carries real GPS
 * ([app.tenshu.fit.model.Workout.geoRoute]); the route line is framed to its own bbox over the
 * downloaded [regionBundlePath] region when installed, else the bundled demo (desktop draws the
 * schematic route). Mirrors Timeline's `TripRouteMap`, including its two shapes:
 *
 * - the **inline hero** on the workout detail — the defaults: a fixed [height], gesture-free
 *   ([interactive] `false`), and — when the caller passes an [onClick] — tappable to open the
 *   full-screen map. A pannable map inside a vertically scrolling detail page would compete with
 *   the page scroll for the same drag, so the hero stays a preview.
 * - the **full-screen map** — `height = null` (fill the parent) + `interactive = true` + no
 *   [onClick] (it is already the destination).
 *
 * [onClick] `null` renders exactly as before: no click action, no button role, no a11y label.
 *
 * ⚠ The tap target is a transparent overlay **inside** the basemap's content slot rather than a
 * `clickable` on the surface's own modifier: on Android the live render is a MapLibre `AndroidView`
 * filling the surface, and a view that consumes the touch would defeat a `clickable` on its Compose
 * ancestor. Composed after [BasemapChrome], the overlay is the topmost hit target by construction —
 * and the attribution chip under it is non-interactive, so nothing is shadowed.
 */
@Composable
fun WorkoutRouteMap(
    route: List<GeoCoord>,
    modifier: Modifier = Modifier,
    regionBundlePath: String? = null,
    regionStream: RouteRegionStream? = null,
    height: Dp? = 200.dp,
    interactive: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    if (route.size < 2) return
    val openLabel = stringResource(Res.string.fit_workout_route_open_a11y)
    RouteBasemap(
        route = route.map { BasemapCoord(latitude = it.latitude, longitude = it.longitude) },
        modifier = modifier,
        height = height,
        regionBundlePath = regionBundlePath,
        streamSource = regionStream?.let {
            RegionStreamSource(id = it.regionId, totalSize = it.totalSize, readRange = it.readRange)
        },
        interactive = interactive,
    ) {
        BasemapChrome(showPin = false)
        if (onClick != null) {
            Box(
                Modifier
                    .matchParentSize()
                    .semantics { contentDescription = openLabel; role = Role.Button }
                    .clickable(onClick = onClick),
            )
        }
    }
}
