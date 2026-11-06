package app.tenshu.ui.map

import app.tenshu.vectortile.PmtilesDirectory
import android.content.Context
import android.util.Log
import java.util.zip.GZIPInputStream

/**
 * Streamed default (`maps-region-delivery.md` D10): renders a region MapLibre never fully downloads.
 *
 * MapLibre insists on a real `file://` it can `pread` at will (the `ProxyFileDescriptor` route is dead —
 * see `ui-map-agent.md`), but a **static** camera reads only a small, computable set of ranges: the header,
 * the directories, the metadata, and the covering tiles. So we keep a **sparse** full-size `.pmtiles` in
 * which only those ranges are populated — the holes are never read (MapLibre culls them) — and hand
 * MapLibre its path.
 *
 * The sparse file is a per-region **[RegionRangeCache]** that *accumulates* across cameras and launches
 * (§7.1 track 1a): a re-open fetches nothing, a nearby camera fetches only the tiles it is missing, and the
 * ~21 KB header/dir/metadata prelude is fetched once, ever. Only the covering-tile *selection* differs
 * between the single-point ([buildStreamedPartial]) and route ([buildStreamedPartialForRoute]) consumers.
 *
 * Returns the archive path, or null on any fetch failure (the caller falls back to the whole-region path,
 * then the bundled demo — the surface never blanks).
 */
internal suspend fun buildStreamedPartial(
    context: Context,
    source: RegionStreamSource,
    label: String,
    selectRanges: (
        header: PmtilesDirectory.Header,
        rootDir: List<PmtilesDirectory.Entry>,
        readLeaf: (PmtilesDirectory.ByteRange) -> List<PmtilesDirectory.Entry>,
    ) -> List<PmtilesDirectory.ByteRange>,
): String? {
    fun gunzip(b: ByteArray, compression: Int): ByteArray =
        if (compression == 2) GZIPInputStream(b.inputStream()).use { it.readBytes() } else b

    // cacheDir, not filesDir: these bytes are re-fetchable, so the OS may reclaim them and the user's
    // "clear cache" button should reach them (see RegionRangeCache.open).
    val cache = RegionRangeCache.open(context.cacheDir, source.id, source.totalSize)
    val residentBefore = cache.residentBytes()

    // Header → root directory → leaf-directory section, all *through* the cache: after the first launch
    // these are on disk, so a re-open decodes the directories with no network at all.
    val headerBytes =
        cache.read(PmtilesDirectory.ByteRange(0, PmtilesDirectory.HEADER_BYTES.toLong()), source.readRange)
            ?: return null
    val header = PmtilesDirectory.parseHeader(headerBytes)

    val rootComp =
        cache.read(PmtilesDirectory.ByteRange(header.rootDirOffset, header.rootDirLength), source.readRange)
            ?: return null
    val rootDir = PmtilesDirectory.decodeDirectory(gunzip(rootComp, header.internalCompression))

    val leafBuf = if (header.leafDirsLength > 0) {
        cache.read(PmtilesDirectory.ByteRange(header.leafDirsOffset, header.leafDirsLength), source.readRange)
            ?: return null
    } else null

    val readLeaf: (PmtilesDirectory.ByteRange) -> List<PmtilesDirectory.Entry> = { leaf ->
        if (leafBuf == null) emptyList()
        else {
            val rel = (leaf.start - header.leafDirsOffset).toInt()
            PmtilesDirectory.decodeDirectory(
                gunzip(leafBuf.copyOfRange(rel, rel + leaf.length.toInt()), header.internalCompression),
            )
        }
    }

    val ranges = selectRanges(header, rootDir, readLeaf)
    val fetched = cache.ensure(ranges, source.readRange) ?: return null
    val resident = cache.residentBytes()

    Log.d(
        "MapStream",
        "region=${source.id} $label archive=${source.totalSize} ranges=${ranges.size} " +
            "fetched=$fetched cached=$residentBefore resident=$resident " +
            "(${pct(resident, source.totalSize)}% of archive)",
    )
    return cache.dataFile.absolutePath
}

private fun pct(part: Long, whole: Long): Long = if (whole > 0) 100 * part / whole else 0

/** Streamed partial for a single-point snippet (`OfflineBasemap`) centred on ([lon], [lat]). */
internal suspend fun buildStreamedPartial(
    context: Context,
    source: RegionStreamSource,
    lon: Double,
    lat: Double,
): String? = buildStreamedPartial(
    context = context,
    source = source,
    label = "point",
) { header, rootDir, readLeaf ->
    PmtilesDirectory.coveringRanges(header, rootDir, lon, lat, marginTiles = 1, readLeaf = readLeaf)
}

/**
 * Streamed partial for a **route** snippet (`RouteBasemap`): covers the corridor along [points]
 * (`(lon, lat)` pairs) rather than a single centre, so the whole polyline sits on real tiles. A zoom whose
 * corridor would exceed the tile cap is dropped (§7.1 track 1b) — and **logged**, never dropped silently.
 */
internal suspend fun buildStreamedPartialForRoute(
    context: Context,
    source: RegionStreamSource,
    points: List<Pair<Double, Double>>,
): String? = buildStreamedPartial(
    context = context,
    source = source,
    label = "route(${points.size}pts)",
) { header, rootDir, readLeaf ->
    PmtilesDirectory.coveringRangesForRoute(
        header = header,
        rootDir = rootDir,
        points = points,
        marginTiles = 1,
        readLeaf = readLeaf,
        onZoomDropped = { zoom, tiles ->
            Log.w(
                "MapStream",
                "route too spread for z$zoom ($tiles tiles > ${PmtilesDirectory.DEFAULT_MAX_TILES_PER_ZOOM} " +
                    "cap) — dropping that zoom; MapLibre overzooms the parent tiles instead",
            )
        },
    )
}
