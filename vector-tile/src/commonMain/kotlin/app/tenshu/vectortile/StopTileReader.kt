package app.tenshu.vectortile

/**
 * Reads the **nearest named transit stop** out of a PMTiles archive: point + radius in, one name out
 * (`docs/proposals/timeline-movement-capture.md` §28.2).
 *
 * The offline twin of the Photon `transitStop` lookup. Same question, same shape of answer, no
 * third-party egress: the tiles are ours, already streamed for rail matching, and served from disk
 * outright once a region is installed.
 *
 * ⚠⚠ **The premise this rests on was recorded as false and is not.** §13.2a surveyed the *Firenze
 * demo* archive, found no station POIs, and that conclusion propagated into `RailMatcher` and the doc
 * as "the basemap emits no station POIs at all". Surveyed against the **deployed** `de-bayern` cut,
 * the `pois` layer carries named `station`/`halt`/`stop`/`tram_stop`/`platform` — `Odeonsplatz` sits
 * 93 m from the plaza centre. The two archives are different Protomaps generations
 * (`ui-map/ui-map-agent.md`), which is also why production rail rides `roads` and not `transit`.
 *
 * ### Two findings from that survey that shape the code
 *
 * ⚠⚠ **Exact kind values, never substring matching.** `station` is a substring of `stationery`,
 * `fire_station`, `charging_station` and `bicycle_repair_station`; `stop` is a substring of
 * `buffer_stop` (a lump of metal at the end of a siding). A regex survey of one plaza returned a
 * stationery shop named "Bethge". So [STOP_KINDS] is matched by equality.
 *
 * ⚠⚠ **Tiers before distance.** At Odeonsplatz a `bus_stop` sits 52 m out and the `station` 93 m, so
 * pure nearest-first would answer from the bus stop — the same failure that made a notice board beat
 * Ostbahnhof (`PhotonGeocoder`). Ranking is therefore [tierOf] first, distance second.
 */
object StopTileReader {

    /**
     * Read at the archive's **deepest** zoom, whatever it is.
     *
     * ⚠ A feature appears in every tile at or above its own `min_zoom`, so the deepest zoom holds the
     * most POIs — and it is *not* a constant: the demo archive stops at z14 and the deployed
     * `de-bayern` cut goes to z15. Hard-coding 14 would silently read a coarser tile than shipped.
     * [PmtilesTileWalk] clamps this to the header's range.
     */
    private const val DEEPEST_AVAILABLE_ZOOM = Int.MAX_VALUE

    /** Tiles one lookup may read. A z15 tile is ~1.2 km across, so a 3×3 grid covers any sane radius. */
    const val MAX_TILES = 9

    /**
     * Largest radius answerable.
     *
     * ⚠ Not defensive padding: with no zoom descent (a stop lookup must read the deepest zoom), a box
     * wider than the [MAX_TILES] grid would be **clipped to its first tiles** — biasing the answer
     * toward one corner while still returning confidently. Callers ask for ≤ 300 m
     * (`PlaceLabelEnricher.MAX_STOP_SEARCH_M`), so this only ever fires on a caller bug, and it fires
     * as a clamp rather than as a wrong name.
     */
    const val MAX_RADIUS_M = 1_000.0

    /** A named stop found in the tiles. */
    data class StopHit(val name: String, val kind: String, val distanceM: Double)

    /**
     * The nearest named transit stop within [withinM] of the point, or `null` — which means "these
     * tiles do not have one", not "look somewhere else". A caller wanting a network fallback decides
     * that itself.
     *
     * @param readRange absolute byte-range read against the archive.
     * @param decompress gunzip when the header says so; identity otherwise (see [PmtilesTileWalk]).
     */
    suspend fun nearestStop(
        lat: Double,
        lng: Double,
        withinM: Double,
        readRange: suspend (offset: Long, length: Int) -> ByteArray?,
        decompress: (bytes: ByteArray, compression: Int) -> ByteArray,
    ): StopHit? {
        val radius = withinM.coerceIn(0.0, MAX_RADIUS_M)
        if (radius <= 0.0) return null
        val bounds = GeoBounds(minLon = lng, minLat = lat, maxLon = lng, maxLat = lat).expandedBy(radius)

        val hits = ArrayList<StopHit>()
        PmtilesTileWalk.walk(
            bounds = bounds,
            readRange = readRange,
            decompress = decompress,
            preferredZoom = DEEPEST_AVAILABLE_ZOOM,
            // ⚠ Equal to the preferred zoom on purpose: descending would read a tile with fewer POIs.
            floorZoom = DEEPEST_AVAILABLE_ZOOM,
            maxTiles = MAX_TILES,
        ) { layers, z, tileX, tileY ->
            for (layer in layers) {
                if (layer.name != POIS_LAYER) continue
                hits += stopsFrom(layer, z, tileX, tileY, lat, lng, radius)
            }
        }
        return best(hits)
    }

    /**
     * The best of [hits] — lowest tier first, then nearest. Pure, so the ranking rule is testable
     * without an archive.
     */
    internal fun best(hits: List<StopHit>): StopHit? =
        hits.minWithOrNull(compareBy({ tierOf(it.kind) }, { it.distanceM }))

    /**
     * Named stops in one decoded layer, within [radius] of the query point. Pure given a layer, so the
     * kind filter and the projection are testable from a synthetic tile.
     */
    internal fun stopsFrom(
        layer: MvtLayer,
        z: Int,
        tileX: Long,
        tileY: Long,
        lat: Double,
        lng: Double,
        radius: Double,
    ): List<StopHit> {
        val out = ArrayList<StopHit>()
        for (feature in layer.features) {
            // ⚠ Points only. A `platform` is sometimes a way, and its first vertex is an arbitrary
            // corner — near enough to mislead, and never where the sign with the name is.
            if (feature.type != MvtGeometryType.POINT) continue
            val kind = stopKindOf(feature) ?: continue
            val name = feature.tag(NAME_TAG)?.takeIf { it.isNotBlank() } ?: continue
            val point = feature.geometry.firstOrNull()?.firstOrNull() ?: continue
            val at = layer.toLonLat(point, z, tileX, tileY)
            val d = distanceMeters(LonLat(lon = lng, lat = lat), at)
            if (d > radius) continue
            out += StopHit(name = name, kind = kind, distanceM = d)
        }
        return out
    }

    /**
     * The stop kind for a feature, from whichever schema generation the tile uses, or `null`.
     *
     * - **production basemap:** `kind=station` (the subtype, when there is one, is in `kind_detail`).
     * - **older `pmap:`-prefixed cut:** `pmap:kind`, same values.
     * - **OSM-tag-style cuts:** a bare `railway=station`, mirroring how [RailMatcher] reads track from
     *   both schemas.
     */
    internal fun stopKindOf(feature: MvtFeature): String? {
        val kind = feature.tag(KIND_TAG)
            ?: feature.tag(PMAP_KIND_TAG)
            ?: feature.tag(RAILWAY_TAG)
            ?: return null
        return kind.takeIf { it in STOP_KINDS }
    }

    /**
     * How strongly a kind means "the station a traveller was at". Lower wins.
     *
     * ⚠ A `platform` ranks below a `station` rather than being dropped: at a small halt the platform is
     * often the only named node. But it must never beat the station itself, or a dwell in a concourse
     * gets named after whichever platform is geometrically nearest.
     */
    internal fun tierOf(kind: String): Int = when (kind) {
        in RAIL_STATION_KINDS -> 0
        in RAIL_STOP_KINDS -> 1
        else -> 2
    }

    /** The station proper — a named place with a building and a name on the front. */
    internal val RAIL_STATION_KINDS = setOf("station", "halt")

    /**
     * Stop positions, platforms and tram stops: real, named, and where somebody stood — but a station
     * outranks them when both are in range.
     */
    internal val RAIL_STOP_KINDS = setOf("stop", "stop_position", "tram_stop", "platform")

    /**
     * Every accepted kind, matched by **equality** (see the class KDoc on `stationery`).
     *
     * ⚠⚠ **Bus is deliberately absent**, and not because a bus stop is not a transit stop. The
     * network tier filters Photon on `osm_tag=railway`, so admitting bus here would make one operation
     * answer differently in kind depending on which tier served it — and bus stops are ~6× denser than
     * stations in the surveyed tiles (101 vs 16 in nine tiles), so a coarse dwell in a residential
     * street would start being named after the stop on the corner. Adding `bus_stop`/`bus_station` is
     * one line if a real site is ever found that needs it; measured, none has been.
     *
     * ⚠ `subway_entrance` is absent for a different reason: all 11 within 400 m of Odeonsplatz are
     * **unnamed**, so it would contribute nothing while ranking nearest (20 m) of anything there.
     */
    internal val STOP_KINDS = RAIL_STATION_KINDS + RAIL_STOP_KINDS

    private const val POIS_LAYER = "pois"
    private const val NAME_TAG = "name"
    private const val KIND_TAG = "kind"
    private const val PMAP_KIND_TAG = "pmap:kind"
    private const val RAILWAY_TAG = "railway"
}
