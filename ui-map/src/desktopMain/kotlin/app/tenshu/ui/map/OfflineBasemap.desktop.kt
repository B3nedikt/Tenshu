package app.tenshu.ui.map

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import app.tenshu.macos.MacMapSnapshotter

/**
 * Desktop `actual`: the schematic basemap. MapLibre-Compose's desktop target is early-stage —
 * offline tile sources aren't supported there yet — so a live map would be blank or would have
 * to fetch tiles over the network (breaching House Rule #1). The schematic needs no native lib or
 * bundled tiles and reads convincingly as an offline vector tile; live MapLibre on desktop drops
 * in behind this same `actual` once the lib's desktop offline path lands. `center` and
 * `regionBundlePath` are ignored (the schematic is layout-only — a downloaded region can't render
 * here until the desktop offline path exists). [streamSource] (the D10 streamed default) is likewise
 * ignored — streaming produces a `.pmtiles` file MapLibre reads, which the desktop schematic doesn't.
 *
 * ⚠⚠ **One exception, since AM-5:** a user who has *elected* Apple Maps in this app gets a real
 * geographic hero — an `MKMapSnapshotter` PNG drawn as an ordinary `Image`
 * (`ios-apple-maps-option.md` §6.5). That path is off by default, macOS-only, and gated on BOTH the
 * election and the helper being present; everyone else still gets the schematic below. It is the
 * only way a desktop map is geographic today, and it is deliberately not the default: MapLibre's
 * desktop offline path remains the answer we want (D1).
 */
@Composable
actual fun OfflineBasemap(
    modifier: Modifier,
    height: Dp?,
    seed: Int,
    center: BasemapCoord?,
    regionBundlePath: String?,
    streamSource: RegionStreamSource?,
    content: @Composable BoxScope.() -> Unit,
) {
    if (center != null && DesktopMapRendererControl.usesAppleMaps && MacMapSnapshotter.isAvailable()) {
        AppleMapsSnapshot(
            modifier = modifier,
            height = height,
            seed = seed,
            center = center,
            route = emptyList(),
            colorHex = ROUTE_COLOR_FALLBACK,
            content = content,
        )
        return
    }
    SchematicBasemap(modifier = modifier, height = height, seed = seed, content = content)
}

/**
 * ⚠ A point hero draws no route line, so this colour is never stroked — the helper takes it
 * unconditionally and ignores it when the route is empty. Named rather than inlined so the next
 * reader does not go looking for where a point map gets a line colour.
 */
internal const val ROUTE_COLOR_FALLBACK = "#2F6FEB"
