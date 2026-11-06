package app.tenshu.timeline.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tenshu.timeline.data.RouteRegionStream
import app.tenshu.ui.map.BasemapChrome
import app.tenshu.ui.map.BasemapCoord
import app.tenshu.ui.map.OfflineBasemap
import app.tenshu.ui.map.RegionStreamSource

/**
 * A **map hero for a place** — `:ui-map`'s [OfflineBasemap] centred on the place's coordinate with a
 * single pin, replacing the flat colour placeholder on the place-detail screen. On Android it renders
 * live offline MapLibre tiles (keyless, House Rule #1) for the place's own downloaded/streamed region
 * ([regionStream] preferred, else [regionBundlePath]); when neither is available it falls back to the
 * bundled demo and then the schematic canvas (so the surface never blanks — the pin still marks the
 * spot). Desktop is schematic-only. Mirrors [TripRouteMap], but a single point instead of a route.
 *
 * Only used on the place *detail* screen (one live surface) — never per feed row, where a MapLibre
 * view per item would be a memory/perf problem.
 */
@Composable
fun PlaceMap(
    latE7: Long,
    lngE7: Long,
    modifier: Modifier = Modifier,
    height: Dp = 220.dp,
    regionBundlePath: String? = null,
    regionStream: RouteRegionStream? = null,
    overlayPill: String? = null,
) {
    OfflineBasemap(
        modifier = modifier,
        height = height,
        // Deterministic schematic layout per place, for the no-tiles fallback.
        seed = (latE7 * 31 + lngE7).toInt(),
        center = BasemapCoord(latitude = latE7 / 1e7, longitude = lngE7 / 1e7),
        regionBundlePath = regionBundlePath,
        streamSource = regionStream?.let {
            RegionStreamSource(id = it.regionId, totalSize = it.totalSize, readRange = it.readRange)
        },
    ) {
        BasemapChrome(showPin = true)
        if (overlayPill != null) {
            OverlayPill(overlayPill, Modifier.align(Alignment.TopStart).padding(16.dp))
        }
    }
}
