package app.tenshu.ui.map

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.Dp
import app.tenshu.basemap.labelLanguageFor
import app.tenshu.basemap.mapPalette
import app.tenshu.basemap.routeBasemapStyleJson
import app.tenshu.basemap.routeCameraFrame
import app.tenshu.ui.theme.spacing
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.map.MapUiOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position

/**
 * iOS `actual`: a **geographically-precise** route rendering — the same live MapLibre Native line
 * layer over the same bundled offline PMTiles basemap that Android draws, from the same
 * `commonMain` style. The route is a GeoJSON `LineString` painted in
 * `MaterialTheme.colorScheme.primary`, endpoints get a circle layer, and the camera frames the
 * route's geographic bounding box via the pure, shared [routeCameraFrame].
 *
 * `timeline-ios`'s `RouteSketch` exists because MapKit would disclose the route's region to Apple
 * on every detail open, not because an abstract sketch was ever the intent; local tiles remove the
 * trade-off entirely — House Rule #1 holds, and the only bytes added to the style are the route's
 * own coordinates.
 *
 * ⚠ It was **not this composable** that demoted that sketch to a fallback. No Compose Multiplatform
 * UI ships on iOS (`ui-map-agent.md`), so this `actual` has no consumer today; Timeline's real iOS
 * hero is the SwiftUI `TenshuRouteMap` over the same `:basemap-core` style and camera. This exists
 * so the `expect` is satisfied — and so the two renderers stay near-identical if one ever is.
 *
 * Falls back to [SchematicBasemap] + the [RoutePolyline] sketch while tiles resolve, when there are
 * no tiles at all, and for a route with fewer than two points (no geography to frame) — so the
 * surface never blanks, matching the Android contract exactly.
 *
 * ⚠ [streamSource] is ignored on iOS for now; see [OfflineBasemap].
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

    val tiles = rememberBasemapTiles(regionBundlePath)

    if (tiles == null) {
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

    val style = remember(dark, tiles, route, routeColorHex, labelLanguage) {
        BaseStyle.Json(
            routeBasemapStyleJson(
                pal = palette,
                tileUrl = "pmtiles://file://${tiles.archivePath}",
                route = route,
                routeColorHex = routeColorHex,
                labelLanguage = labelLanguage,
                glyphsUrl = tiles.glyphsUrl,
            ),
        )
    }

    // BoxWithConstraints, not Box: the fit needs the surface's real width ÷ height, which for a
    // `fillMaxWidth()` hero at a fixed height is only known once measured. Without it a wide, short
    // strip zooms in too far on a north-south route and clips both ends — the same reason the
    // Android actual measures.
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

        // Pan and pinch-zoom, but no rotate or tilt — 0.14's `GestureOptions.RotationLocked`,
        // which is now a builder over `Standard`. A route map is read north-up in every
        // comparable app.
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
        BasemapLoadingOverlay(phase = mapState.style.loadState.toBasemapLoadPhase(), palette = palette)
        // Live OSM tiles: the chrome must carry the ODbL credit (`LocalBasemapData`).
        OsmDataContent(content)
    }
}
