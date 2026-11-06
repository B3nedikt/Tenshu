package app.tenshu.ui.map

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import app.tenshu.basemap.BasemapBounds
import app.tenshu.basemap.BasemapSource
import app.tenshu.basemap.basemapBoundsFromPmtilesHeader
import app.tenshu.basemap.labelLanguageFor
import app.tenshu.basemap.mapPalette
import app.tenshu.basemap.offlineBasemapStyleJson
import app.tenshu.basemap.resolveBasemapSource
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
import java.io.File

/**
 * Android `actual`: a **live MapLibre Native basemap** over a bundled offline PMTiles region
 * (Organic-Maps model). Fully offline and keyless — the style's only URLs are `pmtiles://file://…`
 * (tiles, staged to `filesDir`) and `asset://glyphs/…` (SDF label glyphs, shipped in the APK), both
 * device-local, so no byte ever leaves the device (House Rule #1). Gestures and ornaments are
 * disabled: a basemap snippet is a static frame, not an interactive map.
 *
 * The bundled asset is a small Firenze extract (Protomaps v4 schema, ODbL). When [center] is
 * provided the camera frames it; otherwise a per-[seed] location inside the bundled region is
 * used (mock/demo — the mock datasets carry no real lat/lng yet).
 *
 * The archive is copied from APK assets to app-private `filesDir` once, then loaded via
 * `pmtiles://file://…`. (The native reader aborts on `pmtiles://asset://` in android-sdk 13.0.2
 * — a hard SIGABRT `onMapLoadFailed` can't catch — so `file://` is the only safe scheme.) Until
 * the copy finishes, and if it ever fails, we render [SchematicBasemap] so the surface never blanks.
 *
 * [regionBundlePath] is the app-side terminus of region-bundle delivery: when it names an existing
 * `.pmtiles` file that region is opened instead of the demo (a file already in app-private storage
 * is opened directly; one outside is staged into `filesDir` via the same temp-then-rename dance as
 * [ensureBasemapFile]). The [BasemapSource] resolution — region → bundled demo → schematic — is the
 * pure [resolveBasemapSource]; only the `File.exists()` probe and byte-staging are Android here.
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
    val context = LocalContext.current

    // The camera centre (real place, or the mock Firenze jitter) — also drives which tiles to stream.
    val cameraPos = remember(seed, center) {
        if (center != null) {
            Triple(center.longitude, center.latitude, center.zoom)
        } else {
            val r = Lcg(seed)
            Triple(FIRENZE_LNG + (r.next() - 0.5) * 0.028, FIRENZE_LAT + (r.next() - 0.5) * 0.018, 14.5)
        }
    }

    // null = still resolving (show schematic); non-null = tile file ready; failed = no live tiles.
    var tilePath by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    // Re-resolve when the region / stream source / camera changes (additive: null keeps demo behaviour).
    LaunchedEffect(regionBundlePath, streamSource?.id, cameraPos) {
        tilePath = null
        failed = false
        val path = withContext(Dispatchers.IO) {
            runCatching {
                // D10 streamed default: fetch only the covering tiles into a sparse file. On any
                // stream failure fall through to the whole-region path (or demo), never blanking.
                // ⚠ AM-4b: the camera centre is what the demo must cover. For a route hero that is
                // the route's midpoint (`RouteBasemap` centres there), so a route straddling the
                // extract's edge is judged by its middle — imprecise in the same direction as the
                // camera itself, and never worse than the pre-AM-4b answer of "always the demo".
                val target = BasemapBounds(
                    minLatitude = cameraPos.second,
                    minLongitude = cameraPos.first,
                    maxLatitude = cameraPos.second,
                    maxLongitude = cameraPos.first,
                )
                if (streamSource != null) {
                    buildStreamedPartial(context, streamSource, cameraPos.first, cameraPos.second)
                        ?: resolveTilePath(context, regionBundlePath, target)
                } else {
                    resolveTilePath(context, regionBundlePath, target)
                }
            }.getOrNull()
        }
        if (path == null) failed = true else tilePath = path
    }

    val path = tilePath
    if (failed || path == null) {
        SchematicBasemap(modifier = modifier, height = height, seed = seed, content = content)
        return
    }

    val dark = isSystemInDarkTheme()
    val palette = mapPalette(dark = dark)
    val shape = RoundedCornerShape(MaterialTheme.spacing.m)


    // Label in the reader's language when we ship glyphs for it; else the endonym (LB-3 / D4).
    val labelLanguage = labelLanguageFor(Locale.current.language)

    val style = remember(dark, path, labelLanguage) {
        BaseStyle.Json(offlineBasemapStyleJson(palette, "pmtiles://file://$path", labelLanguage))
    }

    Box(
        modifier = modifier
            .basemapSurfaceSize(height)
            .clip(shape),
        contentAlignment = Alignment.Center,
    ) {
        // 0.16: style and first camera position belong to the state; gestures and ornaments
        // are two separate options, and ornaments are Compose — an empty overlay is the old
        // `OrnamentOptions.AllDisabled`. A style that fails to load is a state, not a callback.
        val mapState = rememberMapState(
            baseStyle = style,
            initialCameraPosition = remember(cameraPos) {
                CameraPosition(
                    center = Position(longitude = cameraPos.first, latitude = cameraPos.second),
                    zoom = cameraPos.third,
                )
            },
        )
        val loadState = mapState.style.loadState
        LaunchedEffect(loadState) { if (loadState is StyleLoadState.Failed) failed = true }

        MaplibreMap(
            modifier = Modifier.matchParentSize(),
            state = mapState,
            interactions = MapInteractions.None,
            uiOptions = MapUiOptions.None,
            overlay = {},
        )
        BasemapLoadingOverlay(phase = loadState.toBasemapLoadPhase(), palette = palette)
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

private const val FIRENZE_LNG = 11.2558
private const val FIRENZE_LAT = 43.7696
private const val BASEMAP_ASSET = "tenshu_basemap.pmtiles"

/**
 * Resolves the on-disk `.pmtiles` file to open for this lookup (or null → [SchematicBasemap]),
 * off the main thread. The decision itself is the pure, unit-tested [resolveBasemapSource]; only
 * the `File.exists()` probe and byte-staging are Android-specific (framework — not unit-testable,
 * so the pure resolver carries the branch coverage). The bundled demo is always shipped in assets,
 * so `bundledDemoAvailable = true`; a staging failure surfaces as a thrown exception the caller
 * catches into the schematic fallback.
 */
internal fun resolveTilePath(
    context: Context,
    regionBundlePath: String?,
    target: BasemapBounds?,
): String? {
    val regionExists = regionBundlePath != null && File(regionBundlePath).exists()
    val source = resolveBasemapSource(
        regionBundlePath = regionBundlePath,
        regionBundleExists = regionExists,
        bundledDemoAvailable = true,
        demoBounds = demoArchiveBounds(context),
        target = target,
    )
    return when (source) {
        is BasemapSource.Region -> stageRegionFile(context, source.path)
        BasemapSource.BundledDemo -> ensureBasemapFile(context)
        BasemapSource.Schematic -> null
    }
}

/**
 * What the bundled demo extract declares it covers (AM-4b), or null when it cannot be read — which
 * degrades to the pre-AM-4b behaviour rather than to a lost map.
 *
 * ⚠ Read from the **asset**, not from the staged copy: staging is what `ensureBasemapFile` does
 * *after* this decision, and the point of the decision is to sometimes not stage at all. 127 bytes
 * off the front of an asset stream is cheap enough not to cache, and caching it in a `var` would
 * add a race for no measurable gain.
 *
 * ⚠ Not `readNBytes`: that is API 33 and this module's `minSdk` is 24. A single `read` may also
 * return short, so the loop is the correct shape rather than a defensive one.
 */
private fun demoArchiveBounds(context: Context): BasemapBounds? = runCatching {
    context.assets.open(BASEMAP_ASSET).use { stream ->
        val header = ByteArray(PMTILES_HEADER_BYTES)
        var read = 0
        while (read < header.size) {
            val n = stream.read(header, read, header.size - read)
            if (n <= 0) break
            read += n
        }
        if (read < header.size) null else basemapBoundsFromPmtilesHeader(header)
    }
}.getOrNull()

private const val PMTILES_HEADER_BYTES = 127

/**
 * Returns an app-private absolute path for a downloaded region [regionPath], suitable for a
 * `pmtiles://file://` source. A file already inside app-private storage is opened in place; one
 * outside it is copied into `filesDir` via a temp-file-then-rename (so an interrupted copy never
 * leaves a truncated archive MapLibre would abort on — same guarantee as [ensureBasemapFile]).
 * The staged name is keyed by the source path so distinct regions don't collide and a re-open
 * reuses the prior stage.
 */
private fun stageRegionFile(context: Context, regionPath: String): String {
    val src = File(regionPath)
    val appPrivateRoots = listOfNotNull(context.filesDir, context.cacheDir, context.noBackupFilesDir)
    val srcCanonical = src.canonicalPath
    if (appPrivateRoots.any { srcCanonical.startsWith(it.canonicalPath + File.separator) }) {
        return src.absolutePath
    }
    val staged = File(context.filesDir, "region_${regionPath.hashCode().toUInt()}.pmtiles")
    if (!staged.exists()) {
        val tmp = File(context.filesDir, "${staged.name}.tmp")
        src.inputStream().use { input -> tmp.outputStream().use { output -> input.copyTo(output) } }
        if (!tmp.renameTo(staged)) {
            tmp.delete()
            error("Failed to stage region tiles")
        }
    }
    return staged.absolutePath
}

/**
 * Copies the bundled PMTiles from APK assets to app-private `filesDir` (once) and returns its
 * absolute path for a `pmtiles://file://` source. Runs off the main thread. Nothing leaves the
 * device (House Rule #1).
 */
private fun ensureBasemapFile(context: Context): String {
    val out = File(context.filesDir, BASEMAP_ASSET)
    if (!out.exists()) {
        // Copy to a temp file then rename, so an interrupted copy never leaves a truncated
        // archive that MapLibre would abort on. (`openFd` can't size the asset — it's stored
        // AAPT-compressed — so `open()` streams the decompressed bytes.)
        val tmp = File(context.filesDir, "$BASEMAP_ASSET.tmp")
        context.assets.open(BASEMAP_ASSET).use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        }
        if (!tmp.renameTo(out)) {
            tmp.delete()
            error("Failed to stage basemap tiles")
        }
    }
    return out.absolutePath
}

// The style JSON, the palette and the source resolution live in `:basemap-core` — a Compose-free
// module (ios-fit-timeline-maps-parity.md §3.1), so a SwiftUI renderer over MapLibre's native iOS
// SDK shares this exact style, and its House Rule #1 no-egress guard, without importing Compose.
