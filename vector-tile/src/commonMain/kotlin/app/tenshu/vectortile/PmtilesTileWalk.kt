package app.tenshu.vectortile

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Walks the tiles of a PMTiles archive covering a bounding box and hands each decoded tile to a
 * visitor: **bbox in, decoded layers out**.
 *
 * Extracted from [RailTileReader] when a second consumer appeared ([StopTileReader], §28.2). The
 * duplication would have been ~70 lines, and the part worth having exactly once is the two-pass leaf
 * prefetch below — it is the only non-obvious thing in the file and getting it wrong degrades
 * silently (tiles behind a leaf pointer are simply skipped, which on a large archive is most of
 * them).
 *
 * ⚠ **`decompress` is injected because `commonMain` has no gzip.** PMTiles compresses directories
 * and tile payloads under two *separate* header fields, and `java.util.zip` is reachable only from
 * JVM/Android source sets. See [RailTileReader]'s KDoc for why this is a lambda and not an
 * `expect/actual`.
 */
internal object PmtilesTileWalk {

    /**
     * @param readRange absolute byte-range read against the archive.
     * @param decompress gunzip when the header says so; identity otherwise. Supplied per platform.
     * @param preferredZoom the zoom to read at, clamped to what the archive holds.
     * @param floorZoom the lowest zoom to drop to when the grid over [bounds] exceeds [maxTiles].
     *   Pass the same value as [preferredZoom] to refuse to descend.
     * @param visit called per tile with its decoded layers and its own address. Coordinates in
     *   [MvtFeature.geometry] are tile-local, so the address is needed to project.
     */
    suspend fun walk(
        bounds: GeoBounds,
        readRange: suspend (offset: Long, length: Int) -> ByteArray?,
        decompress: (bytes: ByteArray, compression: Int) -> ByteArray,
        preferredZoom: Int,
        floorZoom: Int,
        maxTiles: Int,
        visit: (layers: List<MvtLayer>, z: Int, tileX: Long, tileY: Long) -> Unit,
    ) {
        val headerBytes = readRange(0, PmtilesDirectory.HEADER_BYTES) ?: return
        val header = runCatching { PmtilesDirectory.parseHeader(headerBytes) }.getOrNull() ?: return

        // Clamp to what the archive holds, then drop to the highest zoom whose tile grid over the
        // *whole* bounds fits the budget — so a long journey is surveyed end-to-end in one pass
        // instead of clipped to the first [maxTiles] of its bounding rectangle (which for a long
        // diagonal route is a corner nowhere near the path → coverage collapses → false demote).
        val preferred = preferredZoom.coerceIn(header.minZoom, header.maxZoom)
        val floor = max(header.minZoom, min(floorZoom, preferred))
        val z = chooseZoom(bounds, preferred, floor, maxTiles)

        val rootBytes = readRange(header.rootDirOffset, header.rootDirLength.toInt()) ?: return
        val rootDir = runCatching {
            PmtilesDirectory.decodeDirectory(decompress(rootBytes, header.internalCompression))
        }.getOrNull() ?: return

        val tiles = tilesCovering(bounds, z, maxTiles)

        // ── leaf directories, prefetched ────────────────────────────────────────
        // `PmtilesDirectory.tileRange` takes a *synchronous* leaf reader, but our range source
        // suspends. Rather than degrade (dropping every tile behind a leaf pointer), resolve in two
        // passes: run once with a recording reader to learn which leaves are needed, fetch those,
        // then run again backed by the fetched map.
        val leafRequests = LinkedHashSet<PmtilesDirectory.ByteRange>()
        for ((tileX, tileY) in tiles) {
            val tileId = PmtilesDirectory.zxyToTileId(z, tileX, tileY)
            runCatching {
                PmtilesDirectory.tileRange(header, rootDir, tileId) { r ->
                    leafRequests += r
                    emptyList()
                }
            }
        }
        val leaves = HashMap<PmtilesDirectory.ByteRange, List<PmtilesDirectory.Entry>>()
        for (r in leafRequests) {
            val bytes = readRange(r.start, r.length.toInt()) ?: continue
            val entries = runCatching {
                PmtilesDirectory.decodeDirectory(decompress(bytes, header.internalCompression))
            }.getOrNull() ?: continue
            leaves[r] = entries
        }

        for ((tileX, tileY) in tiles) {
            val tileId = PmtilesDirectory.zxyToTileId(z, tileX, tileY)
            val range = runCatching {
                PmtilesDirectory.tileRange(header, rootDir, tileId) { r -> leaves[r].orEmpty() }
            }.getOrNull() ?: continue

            val raw = readRange(range.start, range.length.toInt()) ?: continue
            val decoded = runCatching { decompress(raw, header.tileCompression) }.getOrNull() ?: continue
            val layers = runCatching { MvtDecoder.decode(decoded) }.getOrNull() ?: continue
            visit(layers, z, tileX, tileY)
        }
    }

    /** Highest zoom in `[floorZoom, preferred]` whose full tile grid over [bounds] fits [maxTiles]. */
    internal fun chooseZoom(bounds: GeoBounds, preferred: Int, floorZoom: Int, maxTiles: Int): Int {
        var z = preferred
        while (z > floorZoom && tileCount(bounds, z) > maxTiles) z--
        return z
    }

    /** How many tiles the full grid over [bounds] would take at [z] (no allocation, unlike [tilesCovering]). */
    private fun tileCount(bounds: GeoBounds, z: Int): Long {
        val (x0, y0) = PmtilesDirectory.lonLatToTile(z, bounds.minLon, bounds.maxLat)
        val (x1, y1) = PmtilesDirectory.lonLatToTile(z, bounds.maxLon, bounds.minLat)
        return (abs(x1 - x0) + 1).toLong() * (abs(y1 - y0) + 1).toLong()
    }

    /** The tile grid covering [bounds] at [z], capped at [maxTiles] (nearest-first is not needed). */
    internal fun tilesCovering(bounds: GeoBounds, z: Int, maxTiles: Int): List<Pair<Long, Long>> {
        val (x0, y0) = PmtilesDirectory.lonLatToTile(z, bounds.minLon, bounds.maxLat) // NW
        val (x1, y1) = PmtilesDirectory.lonLatToTile(z, bounds.maxLon, bounds.minLat) // SE
        val minX = min(x0, x1)
        val maxX = max(x0, x1)
        val minY = min(y0, y1)
        val maxY = max(y0, y1)

        val out = ArrayList<Pair<Long, Long>>()
        for (x in minX..maxX) {
            for (y in minY..maxY) {
                if (out.size >= maxTiles) return out
                out += x to y
            }
        }
        return out
    }
}
