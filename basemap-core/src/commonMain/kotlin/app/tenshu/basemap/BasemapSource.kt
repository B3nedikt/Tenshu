package app.tenshu.basemap

/** A geographic point for centring the basemap. */
data class BasemapCoord(
    val latitude: Double,
    val longitude: Double,
    val zoom: Double = 14.5,
)

/**
 * A **lazy byte source** for a region's PMTiles archive — the streamed-default delivery
 * (`docs/proposals/maps-region-delivery.md` D10). Instead of downloading the whole region up front,
 * a renderer uses [readRange] to fetch **only the ranges MapLibre reads for the static camera**
 * (header + directory + the covering tiles, via `:vector-tile`'s `PmtilesDirectory`) into a *sparse*
 * local file it then opens — so a snippet costs a few tiles, not the whole region.
 *
 * This module stays client-free: the app binds [readRange] to the proxy's `streamRegionRange` op (the
 * egress is the proxy's; the app never gains `INTERNET`, D6/R1). [id] names the sparse file so
 * distinct regions don't collide; [totalSize] is the archive's byte length (the sparse file's size).
 */
class RegionStreamSource(
    val id: String,
    val totalSize: Long,
    val readRange: suspend (offset: Long, length: Int) -> ByteArray?,
)

/**
 * Which archive backs a single basemap lookup, chosen by [resolveBasemapSource]. Keeping this a pure,
 * platform-agnostic decision (rather than inlining `File.exists()` branches into each renderer) lets
 * the resolution order be unit-tested once, and gives the eventual IPC byte-transfer (region bytes
 * fetched over the proxy, staged to disk) one seam to plug into.
 */
sealed interface BasemapSource {
    /** A downloaded region bundle at [path] — a real `.pmtiles` file present on local disk. */
    data class Region(val path: String) : BasemapSource

    /** The bundled Firenze demo asset (`tenshu_basemap.pmtiles`), staged into app-private storage. */
    data object BundledDemo : BasemapSource

    /** No live tiles available — draw the schematic fallback. */
    data object Schematic : BasemapSource
}

/**
 * A geographic rectangle in degrees — what an archive covers, or what a hero wants to show.
 *
 * ⚠ No antimeridian handling. Every archive the catalog ships is a country/region extract well
 * inside `[-180, 180]`, and a rectangle that wraps would need the whole comparison rewritten rather
 * than patched. If a wrapping extract ever appears, this type is where it breaks — deliberately,
 * rather than silently answering "not covered" for half the world.
 */
data class BasemapBounds(
    val minLatitude: Double,
    val minLongitude: Double,
    val maxLatitude: Double,
    val maxLongitude: Double,
) {
    /** Whether this rectangle shares any area with [other] (touching edges count). */
    fun intersects(other: BasemapBounds): Boolean =
        minLatitude <= other.maxLatitude &&
            maxLatitude >= other.minLatitude &&
            minLongitude <= other.maxLongitude &&
            maxLongitude >= other.minLongitude
}

/** The bounding rectangle of [route], or null when there is nothing to bound. */
fun boundsOf(route: List<BasemapCoord>): BasemapBounds? {
    if (route.isEmpty()) return null
    return BasemapBounds(
        minLatitude = route.minOf { it.latitude },
        minLongitude = route.minOf { it.longitude },
        maxLatitude = route.maxOf { it.latitude },
        maxLongitude = route.maxOf { it.longitude },
    )
}

/** The 127-byte PMTiles v3 header, and the offsets of the four bbox integers inside it. */
private const val PMTILES_HEADER_BYTES = 127
private const val PMTILES_BBOX_OFFSET = 102

/**
 * The geographic coverage declared in a **PMTiles v3 header**, or null when [header] is not one or
 * declares no bbox.
 *
 * ⚠ Reads the archive's own answer rather than a constant this module would have to keep in step
 * with a build artifact. The bbox lives at bytes 102..117 as four little-endian **signed** int32s
 * scaled by 1e7 (min lon, min lat, max lon, max lat).
 *
 * ⚠ `:vector-tile`'s `PmtilesDirectory.parseHeader` parses the same 127 bytes for the directory
 * offsets and deliberately does **not** expose the bbox. This reads only the bbox and nothing else,
 * which is why it can live here — `:basemap-core` has no project dependencies, and acquiring one on
 * `:vector-tile` would pull a tile decoder into the iOS umbrella to read sixteen bytes. If a third
 * reader of this header ever appears, that is the moment to merge them.
 */
fun basemapBoundsFromPmtilesHeader(header: ByteArray): BasemapBounds? {
    if (header.size < PMTILES_HEADER_BYTES) return null
    // "PMTiles" + version 3 — the same gate `PmtilesDirectory.parseHeader` applies before trusting
    // any offset in this header.
    if (header[0].toInt() != 'P'.code || header[7].toInt() != 3) return null
    fun i32(offset: Int): Int {
        var value = 0
        for (i in 0 until 4) value = value or ((header[offset + i].toInt() and 0xff) shl (8 * i))
        return value
    }
    val minLongitude = i32(PMTILES_BBOX_OFFSET) / 1e7
    val minLatitude = i32(PMTILES_BBOX_OFFSET + 4) / 1e7
    val maxLongitude = i32(PMTILES_BBOX_OFFSET + 8) / 1e7
    val maxLatitude = i32(PMTILES_BBOX_OFFSET + 12) / 1e7
    // An all-zero bbox is how a writer says "unset", not a claim to cover the Gulf of Guinea. Same
    // reasoning as `Place.hasCoordinate` on the app side: (0, 0) is a real place, so it must never
    // stand in for "unknown".
    if (minLongitude == 0.0 && minLatitude == 0.0 && maxLongitude == 0.0 && maxLatitude == 0.0) {
        return null
    }
    return BasemapBounds(
        minLatitude = minLatitude,
        minLongitude = minLongitude,
        maxLatitude = maxLatitude,
        maxLongitude = maxLongitude,
    )
}

/**
 * Pure resolution order for a basemap lookup: a provided region bundle (only when its file is
 * present) wins, else the bundled demo (when available **and covering**), else the schematic.
 * [regionBundleExists] is passed in rather than probed here so this stays free of platform file
 * APIs — each renderer supplies its own existence check. A non-null [regionBundlePath] whose file is
 * missing therefore falls THROUGH to the demo/schematic, never blanking the surface.
 *
 * ## Coverage (AM-4b, `ios-apple-maps-option.md` §8.3)
 *
 * ⚠⚠ **"An archive exists" and "an archive covers what you are about to draw" are different
 * questions, and until AM-4b only the first was asked.** Every map-bearing app bundles the Firenze
 * demo extract, so `bundledDemoAvailable` is *always* true and the demo was returned for a route in
 * Lisbon exactly as for one in Florence — MapLibre then rendered a real archive that happens to hold
 * no tiles there, and the user got their route floating on an empty background. The schematic
 * fallback was unreachable, and with it every affordance hung off it.
 *
 * So [target] (what the hero will show) is compared against [demoBounds] (what the demo holds), and
 * a demo that cannot cover the target resolves to [BasemapSource.Schematic] — the honest answer,
 * and the one the fallback UI was written for. Both are nullable and a null means *unknown*, which
 * resolves as before: a caller that cannot read the header must not lose its map over it.
 *
 * ⚠ The check is **not** applied to [BasemapSource.Region]. A downloaded region's bbox is known to
 * the catalog but is not threaded to a renderer, which receives only a path; and a user who
 * downloaded a region did so *for* somewhere. A region held for one area and viewed in another still
 * renders empty tiles — a narrower version of the same bug, and its own piece of work.
 */
fun resolveBasemapSource(
    regionBundlePath: String?,
    regionBundleExists: Boolean,
    bundledDemoAvailable: Boolean,
    demoBounds: BasemapBounds?,
    target: BasemapBounds?,
): BasemapSource = when {
    regionBundlePath != null && regionBundleExists -> BasemapSource.Region(regionBundlePath)
    !bundledDemoAvailable -> BasemapSource.Schematic
    demoBounds != null && target != null && !demoBounds.intersects(target) -> BasemapSource.Schematic
    else -> BasemapSource.BundledDemo
}
