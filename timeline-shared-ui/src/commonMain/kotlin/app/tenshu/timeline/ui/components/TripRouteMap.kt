package app.tenshu.timeline.ui.components

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
import app.tenshu.timeline.data.RouteRegionStream
import app.tenshu.timeline.model.GeoPoint
import app.tenshu.timeline.shared.resources.Res
import app.tenshu.timeline.shared.resources.timeline_route_map_open_a11y
import app.tenshu.ui.map.BasemapChrome
import app.tenshu.ui.map.BasemapCoord
import app.tenshu.ui.map.RegionStreamSource
import app.tenshu.ui.map.RouteBasemap
import org.jetbrains.compose.resources.stringResource

/**
 * The trip's route over the offline basemap (`maps-app.md` §2 step 3 — the route-over-basemap that
 * is "the strongest first justification to wire the basemap in anger"; `maps-region-delivery.md` R6).
 * Renders `:ui-map`'s [RouteBasemap], which draws the route as a **geographic** MapLibre line layer
 * over the offline tiles on Android (camera framed to the route's bounding box) and falls back to the
 * normalized route sketch on desktop (no MapLibre target there). No pin (a trip is a journey, not a
 * point); the attribution chip stays. Renders nothing for a route with fewer than two points.
 *
 * Two shapes, one composable:
 *
 * - the **inline hero** on a detail screen — the defaults: a fixed [height], gesture-free
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
fun TripRouteMap(
    route: List<GeoPoint>,
    modifier: Modifier = Modifier,
    regionBundlePath: String? = null,
    regionStream: RouteRegionStream? = null,
    height: Dp? = 200.dp,
    interactive: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    if (route.size < 2) return
    val openLabel = stringResource(Res.string.timeline_route_map_open_a11y)
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
