package app.tenshu.ui.map

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tenshu.basemap.routeCameraFrame

/**
 * A **geographically-precise** route rendering over the offline basemap — the follow-up flagged in
 * [RoutePolyline] and `TripRouteMap` (`maps-region-delivery.md` R6). Where [RoutePolyline] paints a
 * *schematic* sketch of the route in a normalized unit square (not aligned to real geography), this
 * draws the route as a **real map line layer** anchored to actual lat/lng, with the camera framed to
 * the route's geographic bounding box.
 *
 * The render is platform-specific behind this one API:
 *
 * - **Android** ([`RouteBasemap.android.kt`]) — a live **MapLibre Native** line layer over the
 *   bundled offline PMTiles basemap: a GeoJSON `LineString` source painted in
 *   `MaterialTheme.colorScheme.primary`, camera framed to [routeCameraFrame]. Fully offline and
 *   keyless (House Rule #1) — reuses `OfflineBasemap`'s label-free style; adds no online source.
 * - **Desktop** ([`RouteBasemap.desktop.kt`]) — delegates to `OfflineBasemap { RoutePolyline(route) }`:
 *   desktop has no MapLibre, so the schematic overlay is the honest fallback (mirrors how
 *   `OfflineBasemap.desktop.kt` itself falls back to the schematic).
 *
 * [content] is overlaid on the basemap (a `BoxScope`, centre-anchored) — e.g. pass [BasemapChrome]
 * `(showPin = false)` for the attribution chip without a single-point pin. A route with fewer than
 * two points has no geography to frame, so the surface renders an empty (schematic) basemap.
 *
 * [streamSource] is the D10 streamed default (`maps-region-delivery.md`), matching [OfflineBasemap]:
 * a lazy byte source the render fetches only the route's covering tiles through (a sparse file) rather
 * than downloading the whole region. Preferred over [regionBundlePath] when present.
 *
 * [regionBundlePath] is the whole-region render-hop terminus (`maps-region-delivery.md` §7), matching
 * [OfflineBasemap]: an absolute path to a downloaded `.pmtiles` region on local disk. When present
 * (and readable) Android draws the route over THAT region's tiles instead of the bundled demo; both
 * null / missing falls back to the demo then the schematic. Desktop ignores them (schematic-only).
 *
 * [height] fixes the surface's height; pass `null` to fill whatever height the parent gives instead
 * — that is the full-screen case, where the map is the screen rather than a hero inside one.
 *
 * [interactive] enables panning and pinch-zoom (rotate and tilt stay off — a north-up route map is
 * what every comparable app shows, and a rotated one is disorienting on a static trace). It defaults
 * to `false`, which is deliberate for the inline heroes: they sit inside a vertically scrolling detail
 * screen, and a pannable map there competes with the page scroll for the same drag. The established
 * pattern elsewhere is a static preview that opens a full-screen, gesture-enabled map on tap.
 */
@Composable
expect fun RouteBasemap(
    route: List<BasemapCoord>,
    modifier: Modifier = Modifier,
    height: Dp? = 200.dp,
    regionBundlePath: String? = null,
    streamSource: RegionStreamSource? = null,
    interactive: Boolean = false,
    content: @Composable BoxScope.() -> Unit = {},
)
