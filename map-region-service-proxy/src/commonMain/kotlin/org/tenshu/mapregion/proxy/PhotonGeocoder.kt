package org.tenshu.mapregion.proxy

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The **proxy-side reverse-geocode egress** (`timeline-movement-capture.md` §11.3 P3): given a
 * coordinate, return the best **venue/POI label**, or `null`. Kept behind this interface so the
 * `ReverseGeocodePlace` op stays independent of the network stack (and tests can fake it).
 *
 * This runs on **Control** (which has network access), not the app — the whole point of routing
 * geocoding through the proxy is that the Timeline app deliberately has no `INTERNET`. The coordinate
 * egresses to the geocoding provider here; the app-side `PlaceGeocodingControl` gates whether the op
 * is ever called.
 */
fun interface ProxyReverseGeocoderEgress {
    suspend fun label(lat: Double, lng: Double): String?

    /**
     * The top-N candidate labels near the coordinate, best first — for a "choose the right place"
     * picker (`timeline-recognition-and-place-naming.md` §4). Defaults to just [label] so a lambda
     * fake stays a SAM; the real Photon impl returns several.
     */
    suspend fun candidates(lat: Double, lng: Double): List<String> = listOfNotNull(label(lat, lng))

    /**
     * The label **with its locality tiers** — what `ReverseGeocodePlace` actually answers.
     *
     * ⚠ Defaulted to a label-only result so a lambda fake stays a SAM, and so a tier that genuinely
     * has no locality (a future offline tier reading POIs out of map tiles) is not forced to invent
     * one. ⚠⚠ A blank `city` therefore still means "this tier did not say", never "no city here" —
     * `PlaceLabelEnricher` reads it as a reason to *skip* coarsening, so a fabricated value would be
     * strictly worse than an absent one.
     */
    suspend fun place(lat: Double, lng: Double): GeocodedLocality? =
        label(lat, lng)?.let { GeocodedLocality(label = it) }

    /**
     * The nearest **named transit stop** within [withinM] metres, or `null`.
     *
     * ⚠ Defaulted to `null` — "this tier cannot answer" — rather than to [label]. A tier that does not
     * do tag-filtered lookups must not fall back to whatever happens to be nearest, because the whole
     * point of the question is that the nearest thing is usually a shop.
     */
    suspend fun transitStop(lat: Double, lng: Double, withinM: Double): String? = null
}

/**
 * A geocoding tier's answer, proxy-side. The wire shape is `GeocodedPlace`; this is the plain holder
 * the egress implementations speak, so `:map-region-service-proxy` internals need no schema types.
 */
data class GeocodedLocality(
    val label: String,
    val city: String = "",
    val countryCode: String = "",
)

/**
 * [ProxyReverseGeocoderEgress] over a [Photon](https://photon.komoot.io) instance's `GET /reverse`
 * (OSM-backed GeoJSON) — POI-aware, so it returns the office's name, where the app's `android.location.Geocoder`
 * returns the street. [endpoint] defaults to komoot's public instance; a self-hosted instance swaps in
 * by config. Any failure (no backend, throttled, garbage) is a `null` — the op then returns `""`.
 *
 * **Venue-preferring ranking (P3b, `timeline-recognition-and-place-naming.md` §4.2).** Rather than
 * blindly taking the single nearest feature (which, from a coarse indoor fix, is usually the *road*),
 * this requests [MAX_CANDIDATES] and prefers the nearest **named POI** — an `amenity`/`shop`/`office`/
 * … within [VENUE_PREFER_RADIUS_M] — over a bare street, even when the street is geometrically nearer.
 * With no qualifying venue it falls back to the nearest feature's label, preserving the old behaviour.
 * ⚠ This only helps when the venue is *among* the returned features; the deeper cause (a ±100 m
 * network fix landing on the wrong spot) is a capture-precision problem (§4.5), not a geocoding one.
 *
 * **Stations outrank venues, and street furniture never wins.** Measured against komoot's instance
 * from a real Ostbahnhof dwell, the label came back as **"Information"** — an `information=board`
 * 50 m away, beating `Ostbahnhof` at 61 m and `München Ost` (`railway=stop`) at 70 m on pure
 * distance. Two independent causes, both fixed here: `railway` was not in [VENUE_OSM_KEYS], so a
 * station could never win the venue tier at all; and with no venue qualifying, ranking fell through
 * to *nearest labelled feature of any kind*, which a notice board wins. So the comparator now reads
 * station → venue → ordinary feature → street furniture, each by distance within its tier.
 *
 * ⚠⚠ **What this deliberately does not fix.** From a coarse fix the *right* answer is often not in
 * Photon's reply at all: at an Odeonsplatz U-Bahn dwell recorded from a 300 m-accuracy network fix,
 * a `limit=50` reverse query returns **no** railway or `public_transport` feature whatsoever — the
 * response is capped at ~70 m, and the station is further than that from a cell centroid. No ranking
 * rule can recover a candidate that was never offered. That case needs the *fix's accuracy* to reach
 * this ranking (a 300 m fix has no business naming a venue 14 m away), which the reverse-geocode
 * operation does not currently carry.
 *
 * Label precedence for a single feature mirrors the app-side `geocodeResultOf`: the POI `name` (unless
 * it is merely the house number), then the street, then the city.
 */
class PhotonGeocoder(
    private val httpClient: HttpClient,
    private val endpoint: String = DEFAULT_ENDPOINT,
    private val timeoutMs: Long = 10_000L,
) : ProxyReverseGeocoderEgress {

    override suspend fun label(lat: Double, lng: Double): String? =
        rankedLabelsFor(lat, lng)?.firstOrNull()

    /**
     * ⚠ One fetch, not two: the locality is read off the **same** ranked response the label comes
     * from, so this costs no extra request and cannot disagree with itself. Photon repeats `city` /
     * `countrycode` on every feature, so the nearest feature that carries one answers for the point —
     * the *label* may legitimately come from a POI that omits them.
     */
    override suspend fun place(lat: Double, lng: Double): GeocodedLocality? =
        fetchFeatures(lat, lng)?.let { localityOf(it, lat, lng) }

    override suspend fun candidates(lat: Double, lng: Double): List<String> =
        rankedLabelsFor(lat, lng).orEmpty()

    /**
     * ⚠⚠ A **tag-filtered** query, which is the whole reason this exists: an unfiltered reverse at a
     * city-centre platform returns ten shops and no station, however large the `limit` — measured at a
     * real Odeonsplatz dwell, `limit=50` yields no railway feature at all while `osm_tag=railway`
     * yields `railway/station "Odeonsplatz"` at 82 m. The station was never ranked badly; it was never
     * offered.
     *
     * ⚠ Filtered again on the **value**, not just the key: `railway` also tags the rails, and a line
     * named "München–Rosenheim" is not a place anyone stood — the same trap [isTransitStation] guards.
     */
    override suspend fun transitStop(lat: Double, lng: Double, withinM: Double): String? {
        val features = fetchFeatures(lat, lng, osmTag = TRANSIT_OSM_TAG) ?: return null
        return features
            .filter { it.properties.osmValue in TRANSIT_OSM_VALUES && !it.properties.name.isNullOrBlank() }
            .filter { distanceM(it, lat, lng) <= withinM }
            .minByOrNull { distanceM(it, lat, lng) }
            ?.properties?.name
    }

    /** One Photon fetch → ranked, de-duplicated labels; `null` only on a failed fetch. */
    private suspend fun rankedLabelsFor(lat: Double, lng: Double): List<String>? =
        fetchFeatures(lat, lng)?.let { rankedLabels(it, lat, lng) }

    /** The raw candidate features for one point; `null` only on a failed fetch. */
    private suspend fun fetchFeatures(
        lat: Double,
        lng: Double,
        osmTag: String? = null,
    ): List<PhotonFeature>? =
        try {
            withTimeoutOrNull(timeoutMs) {
                val response = httpClient.get("$endpoint/reverse") {
                    parameter("lat", lat)
                    parameter("lon", lng)
                    parameter("limit", MAX_CANDIDATES)
                    osmTag?.let { parameter("osm_tag", it) }
                }
                if (!response.status.isSuccess()) return@withTimeoutOrNull null
                parseFeatures(response.bodyAsText())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    private fun parseFeatures(body: String): List<PhotonFeature> =
        try {
            json.decodeFromString<PhotonResponse>(body).features
        } catch (_: Exception) {
            emptyList()
        }

    companion object {
        /** komoot's public Photon instance — the default until a self-hosted container is configured. */
        const val DEFAULT_ENDPOINT = "https://photon.komoot.io"

        /** How many features to consider — enough to see a venue behind the nearest road, still cheap. */
        internal const val MAX_CANDIDATES = 8

        /** A named POI within this distance (m) of the query outranks a nearer street. */
        internal const val VENUE_PREFER_RADIUS_M = 70.0

        /**
         * A transit station within this distance (m) outranks *any* venue.
         *
         * ⚠ Deliberately wider than [VENUE_PREFER_RADIUS_M] because a station's node sits at its
         * centre while the traveller is on a platform or a concourse — but measured against komoot's
         * instance the binding constraint is Photon's own reverse radius, which returns nothing beyond
         * ~70 m however large a `limit` is requested. So this is defensive against a differently
         * configured instance rather than the thing doing the work today.
         */
        internal const val TRANSIT_PREFER_RADIUS_M = 150.0

        /**
         * OSM top-level keys that denote a *venue* the user would recognise, deliberately excluding
         * `building` — a named building must not outrank the office inside it, which was the reported failure.
         */
        internal val VENUE_OSM_KEYS = setOf(
            "amenity", "shop", "office", "tourism", "leisure", "healthcare", "craft",
        )

        /**
         * Keys whose named features are transit stations, and the values that mean "a station" rather
         * than "a piece of track".
         *
         * ⚠⚠ The value check is not optional. `railway` also tags the **rails**, and a line named
         * "München–Rosenheim" is emphatically not a place anyone visited — matching the bare key would
         * label a walk beside a mainline with the name of the line.
         */
        internal val TRANSIT_OSM_KEYS = setOf("railway", "public_transport", "aerialway")

        /**
         * The tag the stop lookup filters on.
         *
         * ⚠ `railway` alone, measured: at both real sites it returns the station (`Odeonsplatz` 82 m,
         * `München Ost` 70 m). Photon accepts repeated `osm_tag`, so `public_transport` can join it
         * if a site is ever found that needs it — but adding an unmeasured tag now would widen the
         * candidate set with nothing to show it helps.
         */
        internal const val TRANSIT_OSM_TAG = "railway"

        internal val TRANSIT_OSM_VALUES = setOf(
            "station", "halt", "stop", "tram_stop", "subway_entrance", "station_entrance",
            "platform", "stop_position",
        )

        /**
         * Street furniture — real OSM features, with real names, that are never where somebody *was*.
         *
         * ⚠⚠ This is what actually produced the reported "Information": an
         * `information=board` sits 50 m from the Ostbahnhof fix while the station itself is at 61 m, so
         * on pure distance the notice board won. Several of these also carry a [VENUE_OSM_KEYS] key —
         * a vending machine is `amenity` — so without this they compete in the *venue* tier and beat
         * the actual station.
         *
         * ⚠ Demoted, never dropped. If street furniture is genuinely the only labelled thing Photon
         * returns, its name is still better than no label at all; it must simply never win while a real
         * place is on the list.
         */
        internal val NON_PLACE_OSM_KEYS = setOf("information")

        internal val NON_PLACE_OSM_VALUES = setOf(
            "vending_machine", "bench", "waste_basket", "waste_disposal", "recycling", "post_box",
            "bicycle_parking", "motorcycle_parking", "bicycle_repair_station", "street_lamp",
            "drinking_water", "toilets", "clock", "atm", "telephone", "tower", "mast", "surveillance",
            "hydrant",
        )

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * Rank Photon's candidate features into labels, best first (pure; unit-tested): named venues
         * within [VENUE_PREFER_RADIUS_M] first (nearest first), then everything else by distance, with
         * duplicate labels dropped. Feeds both the single-label pick ([bestLabel]) and the picker.
         */
        internal fun rankedLabels(features: List<PhotonFeature>, lat: Double, lng: Double): List<String> =
            features
                .mapNotNull { f -> labelOf(f.properties)?.let { it to f } }
                .sortedWith(
                    // 1. A transit station beats everything. If somebody dwelt inside a station, they
                    //    were in the station — not in a shop that happens to be 40 m nearer.
                    compareByDescending<Pair<String, PhotonFeature>> { (_, f) ->
                        isTransitStation(f.properties) && distanceM(f, lat, lng) <= TRANSIT_PREFER_RADIUS_M
                    }
                        // 2. Then the P3b venue rule, unchanged.
                        .thenByDescending { (_, f) ->
                            isVenue(f.properties) && distanceM(f, lat, lng) <= VENUE_PREFER_RADIUS_M
                        }
                        // 3. Street furniture last, whatever its distance or category.
                        .thenBy { (_, f) -> isNonPlace(f.properties) }
                        .thenBy { (_, f) -> distanceM(f, lat, lng) },
                )
                .map { it.first }
                .distinct()

        /**
         * The best label plus the locality tiers around it (pure; unit-tested).
         *
         * ⚠ The locality is taken from the **nearest feature that carries one**, not from the chosen
         * label's feature: the label routinely wins on the venue rule while carrying no `city` of its
         * own, and reading it off that feature alone would leave the locality blank exactly as it was
         * before this existed. Photon repeats `city`/`countrycode` across features, so any of them
         * answers for the point.
         */
        internal fun localityOf(
            features: List<PhotonFeature>,
            lat: Double,
            lng: Double,
        ): GeocodedLocality? {
            val label = bestLabel(features, lat, lng) ?: return null
            return GeocodedLocality(
                label = label,
                city = features.firstNotNullOfOrNull { it.properties.city?.takeIf(String::isNotBlank) }.orEmpty(),
                countryCode = features
                    .firstNotNullOfOrNull { it.properties.countryCode?.takeIf(String::isNotBlank) }
                    .orEmpty(),
            )
        }

        /** The single best label — the nearest venue within radius, else the nearest labelled feature. */
        internal fun bestLabel(features: List<PhotonFeature>, lat: Double, lng: Double): String? =
            rankedLabels(features, lat, lng).firstOrNull()

        /**
         * ⚠ `!isNonPlace` is part of being a venue, not a separate later filter: a vending machine is
         * `amenity`, so without this conjunct street furniture competes in — and frequently wins — the
         * venue tier, which no amount of demotion further down the comparator can undo.
         */
        private fun isVenue(p: PhotonProperties): Boolean =
            p.osmKey in VENUE_OSM_KEYS && !p.name.isNullOrBlank() && p.name != p.housenumber &&
                !isNonPlace(p)

        /** A named station / stop — the place a traveller actually was. */
        internal fun isTransitStation(p: PhotonProperties): Boolean =
            p.osmKey in TRANSIT_OSM_KEYS && p.osmValue in TRANSIT_OSM_VALUES &&
                !p.name.isNullOrBlank() && p.name != p.housenumber

        /** Street furniture: a real named feature that is never where somebody *was*. */
        internal fun isNonPlace(p: PhotonProperties): Boolean =
            p.osmKey in NON_PLACE_OSM_KEYS || p.osmValue in NON_PLACE_OSM_VALUES

        private fun labelOf(p: PhotonProperties): String? =
            p.name?.takeIf { it.isNotBlank() && it != p.housenumber }
                ?: p.street?.takeIf { it.isNotBlank() }
                ?: p.city?.takeIf { it.isNotBlank() }

        /** Haversine distance (m) from the query to a feature; a feature with no point is treated as far. */
        private fun distanceM(f: PhotonFeature, lat: Double, lng: Double): Double {
            val c = f.geometry?.coordinates ?: return Double.MAX_VALUE
            if (c.size < 2) return Double.MAX_VALUE
            return haversineM(lat, lng, c[1], c[0]) // GeoJSON is [lon, lat]
        }

        private fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6_371_000.0
            val dLat = (lat2 - lat1) * PI / 180.0
            val dLon = (lon2 - lon1) * PI / 180.0
            val s = sin(dLat / 2) * sin(dLat / 2) +
                cos(lat1 * PI / 180.0) * cos(lat2 * PI / 180.0) * sin(dLon / 2) * sin(dLon / 2)
            return 2 * r * asin(min(1.0, sqrt(s)))
        }
    }
}

@Serializable
internal data class PhotonResponse(val features: List<PhotonFeature> = emptyList())

@Serializable
internal data class PhotonFeature(
    val geometry: PhotonGeometry? = null,
    val properties: PhotonProperties = PhotonProperties(),
)

/** GeoJSON point geometry — `coordinates` is `[lon, lat]`. */
@Serializable
internal data class PhotonGeometry(val coordinates: List<Double> = emptyList())

@Serializable
internal data class PhotonProperties(
    val name: String? = null,
    val housenumber: String? = null,
    val street: String? = null,
    val city: String? = null,
    @SerialName("osm_key") val osmKey: String? = null,
    /**
     * OSM's *value* half (`station`, `board`, `vending_machine`, …). Previously unparsed, which is
     * why the ranking could only reason about categories and not about what a feature actually is —
     * `amenity` alone cannot tell a restaurant from a vending machine.
     */
    @SerialName("osm_value") val osmValue: String? = null,
    /** ISO country code. Photon spells it lower-case and unhyphenated. */
    @SerialName("countrycode") val countryCode: String? = null,
)
