package app.tenshu.ui.map

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import app.tenshu.macos.MacMapSnapshotter

/**
 * Desktop `actual`: MapLibre-Compose has no desktop target here, so there is no live map to draw a
 * geographic line layer over. The honest fallback is the same one `OfflineBasemap.desktop.kt` uses —
 * the schematic basemap — with the route drawn as the normalized [RoutePolyline] sketch on top. The
 * live geographic line layer drops in behind this same `actual` once MapLibre's desktop offline path
 * lands. [seed] is derived from the route so the schematic underlay is stable per route.
 *
 * [regionBundlePath] and [streamSource] are ignored — desktop is schematic-only (as
 * `OfflineBasemap.desktop.kt` also ignores them); the params exist for API parity so the shared
 * consumer can pass them unconditionally. [interactive] is ignored for the same reason: there is no
 * live camera to pan or zoom, only a fixed sketch, so honouring it would mean pretending. A
 * full-screen desktop route map therefore shows a larger schematic, not a navigable map — the live
 * geographic render arrives here with MapLibre's desktop offline path.
 *
 * ⚠⚠ **Unless Apple Maps is elected** (AM-5): then the route is drawn on a real basemap, by the
 * snapshotter itself rather than as the normalized sketch. Off by default, macOS-only, gated on the
 * election AND the helper. ⚠ The line is drawn *inside* the snapshot — `MKMapSnapshotter` owns the
 * projection, so re-drawing it here over the image would need the region reprojected and would
 * disagree with it at the edges.
 */
@Composable
actual fun RouteBasemap(
    route: List<BasemapCoord>,
    modifier: Modifier,
    height: Dp?,
    regionBundlePath: String?,
    streamSource: RegionStreamSource?,
    interactive: Boolean,
    content: @Composable BoxScope.() -> Unit,
) {
    if (route.size >= 2 && DesktopMapRendererControl.usesAppleMaps && MacMapSnapshotter.isAvailable()) {
        AppleMapsSnapshot(
            modifier = modifier,
            height = height,
            seed = route.hashCode(),
            center = route.first(),
            route = route,
            colorHex = MaterialTheme.colorScheme.primary.toBasemapHex(),
            content = content,
        )
        return
    }
    OfflineBasemap(modifier = modifier, height = height, seed = route.hashCode()) {
        content()
        RoutePolyline(route)
    }
}

/** This colour as the opaque `#RRGGBB` the helper strokes the route with (D8). */
private fun Color.toBasemapHex(): String {
    fun channel(value: Float) = ((value.coerceIn(0f, 1f) * 255).toInt()).toString(16).padStart(2, '0')
    return "#${channel(red)}${channel(green)}${channel(blue)}"
}
