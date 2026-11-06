package app.tenshu.vectortile

/**
 * Reads rail geometry out of a PMTiles archive: **bbox in, rail lines out**
 * (`docs/proposals/timeline-movement-capture.md` §13.3, M9-4).
 *
 * Covers the bbox with tiles via [PmtilesTileWalk], filters each to running track, projects. It is
 * written against two injected lambdas rather than any I/O type, so the same code serves the proxy
 * (where the bytes come from `StreamingRegionSource`) and a test (where they come from a file).
 *
 * ⚠ **`decompress` is injected because `commonMain` has no gzip.** PMTiles stores directories and
 * tile payloads compressed — under two *separate* header fields — and `java.util.zip` is reachable
 * only from JVM/Android source sets. Rather than an `expect/actual` (awkward: JS
 * `DecompressionStream` is asynchronous, iOS needs a zlib cinterop), the decision was to keep this
 * pure and let the platform supply the one function it needs. That mirrors how `PmtilesDirectory`
 * already documents its own boundary.
 */
object RailTileReader {

    /**
     * Zoom to read rail from — deep enough that minor track is present, since lower zooms drop it and
     * for matching that means false negatives.
     *
     * ⚠ "z14 is the archives' max zoom" was the stated reason and is **false**: the bundled demo cut
     * stops at z14 but the deployed `de-bayern` archive goes to z15 (§28.2b). Left at 14 deliberately —
     * mainline and minor track are both present there, matching is tuned against it, and z15 would
     * quadruple the tiles per bbox and hit [MAX_TILES] (hence the coarsening descent) far sooner. A
     * stop lookup reads the deepest zoom instead, because POI density genuinely varies with it.
     */
    const val RAIL_ZOOM = 14

    /**
     * Refuse to walk more than this many tiles for one request. A z14 tile is ~2.4 km across at
     * mid-latitudes, so this covers a very long journey; beyond it the caller should be chunking,
     * and an unbounded loop over a corrupt bbox would otherwise fetch forever.
     */
    const val MAX_TILES = 64

    /**
     * The lowest zoom [railLinesIn] will drop to when a journey is too long to survey at [RAIL_ZOOM]
     * within [MAX_TILES]. Mainline track is still present this coarse (its per-feature `min_zoom` is
     * low); minor spurs drop out, which for matching a long-distance run is acceptable — the
     * alternative (clipping a 150 km route to the western corner of its bbox) collapses coverage and
     * *falsely demotes a real train*, which is exactly the bug this floor fixes.
     */
    const val MIN_RAIL_ZOOM = 12

    /**
     * @param decompress gunzip when the header says so; identity otherwise. Supplied per platform.
     * @param readRange absolute byte-range read against the archive.
     * @return running-track lines covering [bounds], deduplicated across tiles by geometry identity.
     */
    suspend fun railLinesIn(
        bounds: GeoBounds,
        readRange: suspend (offset: Long, length: Int) -> ByteArray?,
        decompress: (bytes: ByteArray, compression: Int) -> ByteArray,
        zoom: Int = RAIL_ZOOM,
        maxTiles: Int = MAX_TILES,
    ): List<RailMatcher.RailLine> {
        val out = ArrayList<RailMatcher.RailLine>()
        val seen = HashSet<String>()

        PmtilesTileWalk.walk(
            bounds = bounds,
            readRange = readRange,
            decompress = decompress,
            preferredZoom = zoom,
            floorZoom = MIN_RAIL_ZOOM,
            maxTiles = maxTiles,
        ) { layers, z, tileX, tileY ->
            // Rail lives in `transit` in the demo archives and in `roads` in the production basemap
            // (see RailMatcher.railLinesFrom) — read whichever this tile carries. The endpoint-keyed
            // dedup below makes reading both harmless if an archive ever has rail in both.
            for (layer in layers) {
                if (layer.name != TRANSIT_LAYER && layer.name != ROADS_LAYER) continue
                for (line in RailMatcher.railLinesFrom(layer, z, tileX, tileY)) {
                    // The same physical line appears in every tile it crosses; key on its endpoints so a
                    // long railway is not scored once per tile it happens to touch.
                    val key = "${line.kind}:${line.points.first()}:${line.points.last()}:${line.points.size}"
                    if (seen.add(key)) out += line
                }
            }
        }
        // S4 (§20): rejoin what tiling split. The endpoint dedup above suppresses the SAME line
        // re-read from a neighbouring tile; this joins the DIFFERENT clipped fragments of one
        // physical railway, which is what lets RailSnapper's same-line walk cross a tile edge.
        return RailLineStitcher.stitch(out)
    }

    private const val TRANSIT_LAYER = "transit"
    private const val ROADS_LAYER = "roads"
}
