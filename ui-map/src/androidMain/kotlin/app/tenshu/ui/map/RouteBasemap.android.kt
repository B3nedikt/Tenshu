package app.tenshu.ui.map

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tenshu.basemap.boundsOf
import app.tenshu.basemap.labelLanguageFor
import app.tenshu.basemap.mapPalette
import app.tenshu.basemap.routeBasemapStyleJson
import app.tenshu.basemap.routeCameraFrame
import app.tenshu.ui.theme.spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.map.MapUiOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.StyleLoadState
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position

/**
 * Android `actual`: a **geographically-precise** route rendering — a live MapLibre Native line layer
 * over the bundled offline PMTiles basemap. The route is a GeoJSON `LineString` source painted in
 * `MaterialTheme.colorScheme.primary`; endpoints get a small circle layer. The camera frames the
 * route's geographic bounding box via [routeCameraFrame].
 *
 * Reuses `OfflineBasemap.android.kt` wholesale — the same [resolveTilePath] (bundled demo → schematic
 * fallback; `regionBundlePath = null` here), the same `offlineBasemapStyle` and `MapPalette`, with the
 * route source + layers **appended into the hand-built style JSON**. Fully offline and keyless
 * (House Rule #1): no online source, and the base style's labels resolve against APK-bundled glyphs —
 * the only added bytes are the route coordinates themselves.
 *
 * While tiles resolve, on any load failure, or for a route with fewer than two points (no geography to
 * frame), it falls back to [SchematicBasemap] with the [RoutePolyline] sketch — the surface never blanks.
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
    // A route needs two points to have a geographic span to frame; otherwise render an empty basemap.
    if (route.size < 2) {
        SchematicBasemap(modifier = modifier, height = height, seed = 0, content = content)
        return
    }

    val context = LocalContext.current
    var tilePath by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    // The route's covering points drive which tiles to stream — the whole corridor, not one centre.
    val routePoints = remember(route) { route.map { it.longitude to it.latitude } }
    LaunchedEffect(regionBundlePath, streamSource?.id, routePoints) {
        tilePath = null
        failed = false
        // Open the downloaded region when one is staged (render-hop terminus), else the bundled
        // demo. The route line is drawn in real geographic coordinates regardless of which tiles
        // back it — a region whose coverage doesn't contain the route just frames onto empty tiles.
        val path = withContext(Dispatchers.IO) {
            runCatching {
                // D10 streamed default: fetch only the route's covering tiles into a sparse file. On
                // any stream failure fall through to the whole-region path (or demo), never blanking.
                // ⚠ AM-4b: the whole route's bounding box, not one centre — this composable
                // already frames the corridor, so it can answer the coverage question precisely
                // where `OfflineBasemap` can only offer its camera point.
                val target = boundsOf(route)
                if (streamSource != null) {
                    buildStreamedPartialForRoute(context, streamSource, routePoints)
                        ?: resolveTilePath(context, regionBundlePath = regionBundlePath, target = target)
                } else {
                    resolveTilePath(context, regionBundlePath = regionBundlePath, target = target)
                }
            }.getOrNull()
        }
        if (path == null) failed = true else tilePath = path
    }

    val path = tilePath
    if (failed || path == null) {
        SchematicBasemap(modifier = modifier, height = height, seed = route.hashCode()) {
            content()
            RoutePolyline(route)
        }
        return
    }

    val dark = isSystemInDarkTheme()
    val palette = mapPalette(dark = dark)
    val shape = RoundedCornerShape(MaterialTheme.spacing.m)
    val routeColorHex = MaterialTheme.colorScheme.primary.hex()

    val labelLanguage = labelLanguageFor(Locale.current.language)

    val style = remember(dark, path, route, routeColorHex, labelLanguage) {
        BaseStyle.Json(routeBasemapStyleJson(palette, "pmtiles://file://$path", route, routeColorHex, labelLanguage))
    }

    // BoxWithConstraints, not Box: the fit needs the surface's real width ÷ height. These heroes are
    // `fillMaxWidth()` at a fixed [height], so the aspect ratio is only known once measured — and
    // without it a wide, short strip zooms in too far on a north-south route and clips both ends.
    // Constraints resolve during composition of the content, so the frame is ready before the camera
    // state is created (`firstPosition` is honoured once and never re-applied).
    BoxWithConstraints(
        modifier = modifier
            .basemapSurfaceSize(height)
            .clip(shape),
        contentAlignment = Alignment.Center,
    ) {
        val frame = remember(route, maxWidth, maxHeight) {
            routeCameraFrame(
                route = route,
                viewportWidthDp = maxWidth.value.toDouble(),
                viewportHeightDp = maxHeight.value.toDouble(),
            )
        }
        val mapState = rememberMapState(
            baseStyle = style,
            initialCameraPosition = remember(frame) {
                CameraPosition(
                    center = Position(longitude = frame.centerLongitude, latitude = frame.centerLatitude),
                    zoom = frame.zoom,
                )
            },
        )
        val loadState = mapState.style.loadState
        LaunchedEffect(loadState) { if (loadState is StyleLoadState.Failed) failed = true }

        // Pan and pinch-zoom, but no rotate or tilt — 0.14's `GestureOptions.RotationLocked`,
        // which is now a builder over `Standard`. A route map is read north-up in every
        // comparable app, and letting a static trace be spun or tilted only loses the reader their
        // bearings. `MapInteractions.None` is the old `AllDisabled`.
        val interactions = remember(interactive) {
            if (interactive) {
                MapInteractions(MapInteractions.Standard) {
                    camera {
                        rotate { enabled = false }
                        pitch { enabled = false }
                    }
                }
            } else {
                MapInteractions.None
            }
        }

        MaplibreMap(
            modifier = Modifier.matchParentSize(),
            state = mapState,
            interactions = interactions,
            // ⚠⚠ The INPUT BINDINGS live here, not on `interactions`: `MapUiOptions.None` is
            // "no pointer, scroll, key or rotary input reaches the map", so passing it to the
            // interactive map silently killed pan and pinch-zoom while the rotation lock still
            // held. `Standard` cannot re-enable what `interactions` disallows — the library
            // states that — so rotate and tilt stay locked above.
            uiOptions = if (interactive) MapUiOptions.Standard else MapUiOptions.None,
            overlay = {},
        )
        BasemapLoadingOverlay(phase = loadState.toBasemapLoadPhase(), palette = palette)
        // Live OSM tiles: the chrome must carry the ODbL credit (`LocalBasemapData`).
        OsmDataContent(content)
    }
}

// The route style JSON (`routeBasemapStyle` + its GeoJSON/layer builders) lives in `:basemap-core`
// with the base style — pure JSON, no Android API, and `BasemapStyleTest` guards the glyphs and
// no-egress invariants there for both styles.
