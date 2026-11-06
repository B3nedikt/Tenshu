package app.tenshu.maps.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tenshu.maps.data.RegionStreamHandle
import app.tenshu.maps.model.GeoPoint
import app.tenshu.ui.map.BasemapChrome
import app.tenshu.ui.map.BasemapCoord
import app.tenshu.ui.map.OfflineBasemap
import app.tenshu.ui.map.RegionStreamSource

/**
 * The place-detail map snippet (docs/proposals/maps-app.md §6 Ph7). A single-pin offline basemap
 * — a place is a *point*, not a journey (D1).
 *
 * A thin wrapper over `:ui-map`'s [OfflineBasemap] with the standard [BasemapChrome] (centre pin +
 * "Offline · MapLibre" attribution + scale). The live-vs-schematic render, the bundled offline
 * tiles, and the House-Rule-#1 posture all live in `:ui-map` now.
 *
 * Both real-data seams are threaded through to [OfflineBasemap]:
 *  - [location] centres the live map on the place's SI coordinate ([GeoPoint] → [BasemapCoord]);
 *    when null the map falls back to a per-[seed] spot inside the bundled region (mock/demo).
 *  - [regionStream] is the D10 streamed default — a lazy handle the render fetches only the covering
 *    tiles through (no full download). Preferred over [regionBundlePath].
 *  - [regionBundlePath] points the live render at a downloaded region bundle
 *    (`docs/proposals/maps-region-delivery.md` D5, resolved by `RegionResolver`); when both are null
 *    (or the file is absent) it falls back to the bundled demo then the schematic — the surface never
 *    blanks.
 */
@Composable
fun MapSnippet(
    seed: Int,
    modifier: Modifier = Modifier,
    height: Dp = 200.dp,
    location: GeoPoint? = null,
    regionBundlePath: String? = null,
    regionStream: RegionStreamHandle? = null,
) {
    OfflineBasemap(
        modifier = modifier,
        height = height,
        seed = seed,
        center = location?.toBasemapCoord(),
        regionBundlePath = regionBundlePath,
        streamSource = regionStream?.let {
            RegionStreamSource(
                id = it.regionId,
                totalSize = it.totalSize,
                readRange = it.readRange,
            )
        },
    ) {
        BasemapChrome()
    }
}

/** Bridges the logic-layer [GeoPoint] to `:ui-map`'s [BasemapCoord] (its default snippet zoom). */
private fun GeoPoint.toBasemapCoord(): BasemapCoord =
    BasemapCoord(latitude = latitude, longitude = longitude)
