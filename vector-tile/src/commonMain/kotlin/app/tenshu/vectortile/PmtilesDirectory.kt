package app.tenshu.vectortile

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.tan

/**
 * A minimal **PMTiles v3** directory reader (`docs/proposals/maps-region-delivery.md` — partial
 * streaming prototype). Just enough to compute **which byte ranges MapLibre will `pread` for a
 * fixed-camera snippet** — the header, the root/leaf directories, the JSON metadata, and the tile
 * data for the tiles covering the viewport — so a *sparse* file with only those ranges populated is
 * one MapLibre can open and render (it culls the holes it never reads).
 *
 * Directory bytes are `internal_compression`-compressed (usually gzip); the byte-level parsing is
 * pure/commonMain and takes an injected [decompress] so the gzip stays platform-side (androidMain).
 * Spec: <https://github.com/protomaps/PMTiles/blob/main/spec/v3/spec.md>.
 */
object PmtilesDirectory {

    const val HEADER_BYTES = 127

    data class Header(
        val rootDirOffset: Long,
        val rootDirLength: Long,
        val metadataOffset: Long,
        val metadataLength: Long,
        val leafDirsOffset: Long,
        val leafDirsLength: Long,
        val tileDataOffset: Long,
        val tileDataLength: Long,
        val internalCompression: Int,
        /**
         * How each **tile payload** is compressed (1 = none, 2 = gzip, …) — distinct from
         * [internalCompression], which covers only the header's directories/metadata. Our archives
         * ship `2`, so raw tile bytes are gzip, not protobuf: a decoder that skips this reads noise.
         */
        val tileCompression: Int = 0,
        /** Payload format (1 = MVT). */
        val tileType: Int = 0,
        val minZoom: Int,
        val maxZoom: Int,
    ) {
        val archiveSize: Long get() = tileDataOffset + tileDataLength
    }

    /** One directory entry: a tile run ([runLength] > 0) or a leaf-directory pointer ([runLength] == 0). */
    data class Entry(val tileId: Long, val runLength: Long, val offset: Long, val length: Long)

    /** A byte range `[start, start + length)` to fetch/populate. */
    data class ByteRange(val start: Long, val length: Long)

    /** Parses the fixed 127-byte v3 header (little-endian). Throws on a bad magic/version. */
    fun parseHeader(bytes: ByteArray): Header {
        require(bytes.size >= HEADER_BYTES) { "short header" }
        require(bytes[0].toInt() == 'P'.code && bytes[7].toInt() == 3) { "not a PMTiles v3 archive" }
        fun u64(o: Int): Long {
            var v = 0L
            for (i in 0 until 8) v = v or ((bytes[o + i].toLong() and 0xff) shl (8 * i))
            return v
        }
        return Header(
            rootDirOffset = u64(8), rootDirLength = u64(16),
            metadataOffset = u64(24), metadataLength = u64(32),
            leafDirsOffset = u64(40), leafDirsLength = u64(48),
            tileDataOffset = u64(56), tileDataLength = u64(64),
            internalCompression = bytes[97].toInt() and 0xff,
            tileCompression = bytes[98].toInt() and 0xff,
            tileType = bytes[99].toInt() and 0xff,
            minZoom = bytes[100].toInt() and 0xff,
            maxZoom = bytes[101].toInt() and 0xff,
        )
    }

    /** Decodes a (decompressed) directory into its entries (the v3 columnar varint layout). */
    fun decodeDirectory(decompressed: ByteArray): List<Entry> {
        val r = Varint(decompressed)
        val n = r.next().toInt()
        val ids = LongArray(n)
        val runs = LongArray(n)
        val lens = LongArray(n)
        val offs = LongArray(n)
        var last = 0L
        for (i in 0 until n) { last += r.next(); ids[i] = last }
        for (i in 0 until n) runs[i] = r.next()
        for (i in 0 until n) lens[i] = r.next()
        for (i in 0 until n) {
            val v = r.next()
            offs[i] = if (v == 0L && i > 0) offs[i - 1] + lens[i - 1] else v - 1
        }
        return List(n) { Entry(ids[it], runs[it], offs[it], lens[it]) }
    }

    /**
     * The byte ranges MapLibre needs to render a snippet centred on ([lon], [lat]) — header + root
     * dir + metadata + every covering tile across the archive's zoom band (plus any leaf dirs those
     * tiles descend through, fetched via [readRange]). A tile-not-present is skipped (a hole MapLibre
     * culls). [marginTiles] widens the covering set by ±N tiles per axis to cover the frame edges.
     */
    fun coveringRanges(
        header: Header,
        rootDir: List<Entry>,
        lon: Double,
        lat: Double,
        marginTiles: Int = 1,
        // Leaf-dir descent (rare for small extracts): (offset,length) → decompressed dir entries.
        readLeaf: (ByteRange) -> List<Entry> = { emptyList() },
    ): List<ByteRange> {
        val ranges = mutableListOf(
            ByteRange(0, HEADER_BYTES.toLong()),
            ByteRange(header.rootDirOffset, header.rootDirLength),
            ByteRange(header.metadataOffset, header.metadataLength),
        )
        val leafCache = mutableMapOf<Long, List<Entry>>()
        for (z in header.minZoom..header.maxZoom) {
            val (cx, cy) = lonLatToTile(z, lon, lat)
            val n = 1L shl z
            for (dx in -marginTiles..marginTiles) for (dy in -marginTiles..marginTiles) {
                val x = cx + dx
                val y = cy + dy
                if (x < 0 || y < 0 || x >= n || y >= n) continue
                val tileId = zxyToTileId(z, x, y)
                resolveTile(header, rootDir, tileId, leafCache, readLeaf)?.let { ranges.add(it) }
            }
        }
        return ranges
    }

    /**
     * The per-zoom covering-tile cap for a route corridor ([coveringRangesForRoute]).
     *
     * A route's corridor grows with its geographic span: a city walk covers a handful of tiles per zoom,
     * but a cross-country trip at the archive's deepest zoom could select thousands — a large, pointless
     * fetch, because the camera is framed to the whole route ([routeCameraFrame]) and is therefore zoomed
     * *out*, so those deep tiles are never drawn. 256 tiles/zoom comfortably covers a city-scale route
     * while bounding the pathological one.
     */
    const val DEFAULT_MAX_TILES_PER_ZOOM = 256

    /**
     * The byte ranges MapLibre needs to render a snippet framing a **route** — the same header + root
     * dir + metadata prelude as [coveringRanges], plus the union of the covering tiles around every
     * point in [points] (`(lon, lat)` pairs) across the archive's zoom band. A route spans an area, so
     * covering a single centre (as [coveringRanges] does) would leave the ends of the line on holes;
     * unioning per-point coverage tracks the corridor without fetching the whole bounding box. Tiles
     * are deduped by their (globally-unique) PMTiles id, so a dense polyline along one street costs a
     * handful of tiles per zoom, not one per vertex. An empty [points] yields just the prelude.
     *
     * **Bounded (§7.1 track 1b).** A zoom whose corridor exceeds [maxTilesPerZoom] is **dropped whole**,
     * and reported through [onZoomDropped] (the caller logs it — a silently-truncated zoom would leave the
     * route sitting on scattered holes, which reads as a rendering bug). Dropping the *whole* zoom is the
     * safe truncation: MapLibre overzooms the parent tiles from a shallower zoom, so the route still draws,
     * just less crisply — whereas keeping an arbitrary subset would blank parts of the corridor.
     */
    fun coveringRangesForRoute(
        header: Header,
        rootDir: List<Entry>,
        points: List<Pair<Double, Double>>,
        marginTiles: Int = 1,
        maxTilesPerZoom: Int = DEFAULT_MAX_TILES_PER_ZOOM,
        readLeaf: (ByteRange) -> List<Entry> = { emptyList() },
        onZoomDropped: (zoom: Int, tiles: Int) -> Unit = { _, _ -> },
    ): List<ByteRange> {
        val ranges = mutableListOf(
            ByteRange(0, HEADER_BYTES.toLong()),
            ByteRange(header.rootDirOffset, header.rootDirLength),
            ByteRange(header.metadataOffset, header.metadataLength),
        )
        if (points.isEmpty()) return ranges
        val leafCache = mutableMapOf<Long, List<Entry>>()
        for (z in header.minZoom..header.maxZoom) {
            val n = 1L shl z
            // Select the zoom's distinct covering tiles first, so the cap is applied to the whole corridor
            // (dropping a zoom entirely) rather than truncating it mid-way through the route.
            val tileIds = LinkedHashSet<Long>()
            for ((lon, lat) in points) {
                val (cx, cy) = lonLatToTile(z, lon, lat)
                for (dx in -marginTiles..marginTiles) for (dy in -marginTiles..marginTiles) {
                    val x = cx + dx
                    val y = cy + dy
                    if (x < 0 || y < 0 || x >= n || y >= n) continue
                    tileIds.add(zxyToTileId(z, x, y))
                }
            }
            if (tileIds.size > maxTilesPerZoom) {
                onZoomDropped(z, tileIds.size)
                continue
            }
            for (tileId in tileIds) {
                resolveTile(header, rootDir, tileId, leafCache, readLeaf)?.let { ranges.add(it) }
            }
        }
        return ranges
    }

    /**
     * Public single-tile lookup: the byte range holding tile [tileId], or `null` when the archive
     * does not contain it.
     *
     * Exposed for consumers that want a tile's **contents** rather than a range plan to hand
     * MapLibre — rail map-matching being the first (`timeline-movement-capture.md` §13.2). The
     * range-planning entry points above keep their own private [resolveTile] overload with a shared
     * leaf cache, which is load-bearing for batch planning; this one is the single-shot convenience.
     */
    fun tileRange(
        header: Header,
        rootDir: List<Entry>,
        tileId: Long,
        readLeaf: (ByteRange) -> List<Entry> = { emptyList() },
    ): ByteRange? = resolveTile(header, rootDir, tileId, mutableMapOf(), readLeaf)

    /**
     * Finds a tile's data range, descending one leaf level if needed; null when absent.
     *
     * [leafCache] **memoises decoded leaf directories by offset** — it must not be a "seen" set that
     * skips a leaf the second time. A big archive's root directory is mostly *leaf pointers* (the live
     * `test-toscana` region's root is 62 bytes for 22,018 tiles), so nearly every tile in a viewport
     * descends through the **same one or two leaves**. Short-circuiting a repeat visit therefore
     * resolved only the *first* tile per leaf and silently dropped the rest — the snippet then had
     * holes where MapLibre expected tiles the directory advertised, and MapLibre failed them with
     * `invalid tag exception` (it read zeros). Caching the decode keeps the fetch-once property that
     * was intended, without dropping tiles.
     */
    private fun resolveTile(
        header: Header,
        rootDir: List<Entry>,
        tileId: Long,
        leafCache: MutableMap<Long, List<Entry>>,
        readLeaf: (ByteRange) -> List<Entry>,
    ): ByteRange? {
        val entry = findEntry(rootDir, tileId) ?: return null
        if (entry.runLength > 0L) {
            return ByteRange(header.tileDataOffset + entry.offset, entry.length)
        }
        // Leaf pointer: decode that leaf (once) and re-search it for the tile.
        val leaf = leafCache.getOrPut(entry.offset) {
            readLeaf(ByteRange(header.leafDirsOffset + entry.offset, entry.length))
        }
        val leafEntry = findEntry(leaf, tileId) ?: return null
        if (leafEntry.runLength == 0L) return null // a nested (2nd-level) leaf — not produced by go-pmtiles
        return ByteRange(header.tileDataOffset + leafEntry.offset, leafEntry.length)
    }

    /** Binary search for the entry whose run covers [tileId] (entries are tileId-sorted). */
    private fun findEntry(entries: List<Entry>, tileId: Long): Entry? {
        var lo = 0
        var hi = entries.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val e = entries[mid]
            when {
                tileId < e.tileId -> hi = mid - 1
                // runLength 0 = leaf pointer keyed exactly at tileId; >0 = a run.
                e.runLength == 0L && tileId == e.tileId -> return e
                tileId < e.tileId + e.runLength -> return e
                else -> lo = mid + 1
            }
        }
        // Not an exact match: the covering leaf/run is the greatest entry with tileId <= target.
        val idx = lo - 1
        return entries.getOrNull(idx)?.takeIf { it.runLength == 0L }
    }

    /** Web-mercator tile (x,y) containing ([lon],[lat]) at zoom [z]. */
    fun lonLatToTile(z: Int, lon: Double, lat: Double): Pair<Long, Long> {
        val n = 1L shl z
        val x = ((lon + 180.0) / 360.0 * n).toLong().coerceIn(0, n - 1)
        val latRad = lat * PI / 180.0
        val y = ((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n).toLong().coerceIn(0, n - 1)
        return x to y
    }

    /** PMTiles Hilbert-curve tile id for (z,x,y) — the archive's addressing (spec §Tile IDs). */
    fun zxyToTileId(z: Int, x: Long, y: Long): Long {
        var acc = 0L
        for (t in 0 until z) acc += (1L shl t) * (1L shl t)
        val n = 1L shl z
        var d = 0L
        var xx = x
        var yy = y
        var s = n / 2
        while (s > 0) {
            val rx = if (xx and s > 0) 1L else 0L
            val ry = if (yy and s > 0) 1L else 0L
            d += s * s * ((3 * rx) xor ry)
            // rotate quadrant (Wikipedia xy2d rot with the full dimension n)
            if (ry == 0L) {
                if (rx == 1L) {
                    xx = n - 1 - xx
                    yy = n - 1 - yy
                }
                val tmp = xx; xx = yy; yy = tmp
            }
            s /= 2
        }
        return acc + d
    }

    private class Varint(private val b: ByteArray) {
        private var pos = 0
        fun next(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val byte = b[pos++].toLong() and 0xff
                result = result or ((byte and 0x7f) shl shift)
                if (byte and 0x80L == 0L) break
                shift += 7
            }
            return result
        }
    }
}
