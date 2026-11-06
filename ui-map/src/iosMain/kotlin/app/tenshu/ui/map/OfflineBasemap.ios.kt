package app.tenshu.ui.map

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.Dp
import app.tenshu.basemap.BasemapSource
import app.tenshu.basemap.fileGlyphsUrl
import app.tenshu.basemap.labelLanguageFor
import app.tenshu.basemap.mapPalette
import app.tenshu.basemap.offlineBasemapStyleJson
import app.tenshu.basemap.resolveBasemapSource
import app.tenshu.ui.theme.spacing
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.map.MapUiOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.StyleLoadState
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position

/**
 * iOS `actual`: the **same live MapLibre Native basemap as Android**, over the same bundled offline
 * PMTiles region and built from the same `commonMain` style. Fully offline and keyless — the style's
 * only URLs are `pmtiles://file://…` (the archive in the app bundle) and `file://…` (the SDF glyph
 * atlas beside it), both device-local, so no byte leaves the device (House Rule #1).
 *
 * ⚠ **MapKit is deliberately not used here, and it is never the default anywhere.** Apple-served
 * tiles would disclose the viewed region to a third party on every detail open (threat model A35),
 * which is why `timeline-ios`'s `RouteSketch.swift` drew an abstract sketch before there was a map
 * to draw — it is that screen's no-tiles fallback now. MapLibre over local tiles is what lets iOS
 * show a *real* map without the leak.
 *
 * ⚠⚠ The absolute rule was **narrowed, not repealed** (2026-08-13): `ios-apple-maps-option.md`
 * shipped MapKit as an explicit, revocable, off-by-default election, for the three heroes only and
 * only on the SwiftUI side (`ios-basemap/Sources/MapKitHeroes.swift`). This renderer has no such
 * path and must not grow one without the election — see `ui-map-agent.md` for the full boundary.
 *
 * Differences from the Android actual, both simplifications rather than gaps:
 * - **nothing is staged.** Android must copy the archive out of `assets/` into `filesDir` (a
 *   `pmtiles://asset://` URL SIGABRTs the native reader). A Compose resource on iOS is already a
 *   real file, and the equivalent hazard does not exist — there is no `asset://` scheme to trip
 *   over, and `pmtiles://file://` names the bundle copy in place. See [IosBasemapAssets].
 * - **no region staging.** Every path this process can read is inside its own sandbox, so a
 *   [regionBundlePath] is opened where it lies.
 *
 * ⚠ [streamSource] (the D10 streamed default) is **not implemented on iOS yet** and is ignored:
 * resolution falls through to [regionBundlePath] → bundled demo → schematic, so a caller that passes
 * one still renders, just without the sparse-file optimisation. The pieces it needs are all portable
 * (`PmtilesDirectory` is in `:vector-tile`, which already targets iOS); only `buildStreamedPartial`'s
 * file writing is Android-specific today.
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
    // The camera centre — a real place, or the mock Firenze jitter. Identical to the Android actual
    // so a snippet frames the same point on both platforms.
    val cameraPos = remember(seed, center) {
        if (center != null) {
            Triple(center.longitude, center.latitude, center.zoom)
        } else {
            val r = Lcg(seed)
            Triple(FIRENZE_LNG + (r.next() - 0.5) * 0.028, FIRENZE_LAT + (r.next() - 0.5) * 0.018, 14.5)
        }
    }

    val tiles = rememberBasemapTiles(regionBundlePath)

    if (tiles == null) {
        SchematicBasemap(modifier = modifier, height = height, seed = seed, content = content)
        return
    }

    val dark = isSystemInDarkTheme()
    val palette = mapPalette(dark = dark)
    val shape = RoundedCornerShape(MaterialTheme.spacing.m)

    // Label in the reader's language when we ship glyphs for it; else the endonym (LB-3 / D4).
    val labelLanguage = labelLanguageFor(Locale.current.language)

    val style = remember(dark, tiles, labelLanguage) {
        BaseStyle.Json(
            offlineBasemapStyleJson(
                pal = palette,
                tileUrl = "pmtiles://file://${tiles.archivePath}",
                labelLanguage = labelLanguage,
                glyphsUrl = tiles.glyphsUrl,
            ),
        )
    }

    Box(
        modifier = modifier
            .basemapSurfaceSize(height)
            .clip(shape),
        contentAlignment = Alignment.Center,
    ) {
        // 0.16: style + first camera position live on the state; gestures and ornaments are
        // separate, and ornaments are Compose — an empty overlay is the old `AllDisabled`.
        val mapState = rememberMapState(
            baseStyle = style,
            initialCameraPosition = remember(cameraPos) {
                CameraPosition(
                    center = Position(longitude = cameraPos.first, latitude = cameraPos.second),
                    zoom = cameraPos.third,
                )
            },
        )
        MaplibreMap(
            modifier = Modifier.matchParentSize(),
            state = mapState,
            interactions = MapInteractions.None,
            uiOptions = MapUiOptions.None,
            overlay = {},
        )
        BasemapLoadingOverlay(phase = mapState.style.loadState.toBasemapLoadPhase(), palette = palette)
        // Live OSM tiles: the chrome must carry the ODbL credit (`LocalBasemapData`).
        OsmDataContent(content)
    }
}

/**
 * The library's [StyleLoadState] as the [BasemapLoadingOverlay]'s phase. `Pending` (no surface yet)
 * counts as loading; the `else` covers the library keeping the interface open.
 */
internal fun StyleLoadState.toBasemapLoadPhase(): BasemapLoadPhase = when (this) {
    StyleLoadState.Ready -> BasemapLoadPhase.Loaded
    is StyleLoadState.Failed -> BasemapLoadPhase.Failed
    else -> BasemapLoadPhase.Loading
}

internal const val FIRENZE_LNG = 11.2558
internal const val FIRENZE_LAT = 43.7696

/**
 * The archive + glyph atlas a single render should open, or null while still resolving / when there
 * are no live tiles at all (→ [SchematicBasemap], so the surface never blanks — the same contract
 * the Android actual keeps).
 */
internal data class BasemapTiles(val archivePath: String, val glyphsUrl: String)

/**
 * Resolves [BasemapTiles] for this lookup off the composition.
 *
 * The decision itself is the shared, unit-tested [resolveBasemapSource] — this only supplies the
 * `fileExists` probe and reads the bundle, exactly as the Android actual only supplies its
 * `File.exists()` probe and its staging. Keeping the branch in commonMain is what lets one set of
 * `commonTest` cases cover the resolution order for every platform.
 *
 * A missing glyph atlas degrades to the label-free style (Android's `asset://` template is never
 * emitted here) rather than to no map: a template MapLibre cannot resolve would cost a per-label
 * load error on every frame, while dropping labels still leaves a readable basemap.
 */
@Composable
internal fun rememberBasemapTiles(regionBundlePath: String?): BasemapTiles? {
    var tiles by remember(regionBundlePath) { mutableStateOf<BasemapTiles?>(null) }
    LaunchedEffect(regionBundlePath) {
        val demoPath = bundledBasemapPath()
        val source = resolveBasemapSource(
            regionBundlePath = regionBundlePath,
            regionBundleExists = regionBundlePath != null && fileExists(regionBundlePath),
            bundledDemoAvailable = demoPath != null,
            // ⚠ Nulls — i.e. pre-AM-4b behaviour — on purpose. This actual is the **superseded**
            // Compose-on-iOS renderer (D7): iOS ships SwiftUI over native MapLibre and no longer
            // links `:ui-map` at all, so nothing reaches this code. The AM-4b coverage check lives
            // on the shipping path, in `ios-basemap/Sources/BasemapStyle.swift`. Wiring it here too
            // would mean maintaining a second reader for a file scheduled for deletion
            // (`ios-fit-timeline-maps-parity.md` §3.1.1).
            demoBounds = null,
            target = null,
        )
        val archive = when (source) {
            is BasemapSource.Region -> source.path
            // `bundledDemoAvailable` was derived from `demoPath`, so this branch implies non-null.
            BasemapSource.BundledDemo -> demoPath
            BasemapSource.Schematic -> null
        }
        tiles = archive?.let { path ->
            BasemapTiles(
                archivePath = path,
                glyphsUrl = bundledGlyphsRoot()?.let(::fileGlyphsUrl) ?: NO_GLYPHS_URL,
            )
        }
    }
    return tiles
}

/**
 * Emitted when the glyph atlas is absent from the bundle. It names a path that cannot resolve, which
 * is the point: MapLibre drops the labels and still draws the basemap. Anything device-local keeps
 * `BasemapStyleTest.everyUrlInTheStyleIsDeviceLocal` honest.
 */
private const val NO_GLYPHS_URL = "file:///dev/null/{fontstack}/{range}.pbf"
