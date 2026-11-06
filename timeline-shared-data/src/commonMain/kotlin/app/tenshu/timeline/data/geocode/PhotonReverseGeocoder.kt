package app.tenshu.timeline.data.geocode

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException

/**
 * The **common Photon tier** (`docs/proposals/place-geocoding.md` G3): reverse geocoding over a
 * [Photon](https://photon.komoot.io) instance's `GET /reverse` — OSM-backed GeoJSON, one feature
 * requested (`limit=1`, D4: best answer or nothing). Desktop binds this as its only tier; Android
 * chains device-first → Photon (§4.3). Like every network tier it runs only behind the user's
 * explicit [PlaceGeocodingControl] consent, enforced per-lookup by [PlaceLabelEnricher].
 *
 * **The endpoint is config, not code.** [DEFAULT_PUBLIC_ENDPOINT] points at komoot's public
 * instance until a self-hosted Photon container exists (the ops sub-slice on the DO/Terraform
 * relay box) — swapping it in is a constructor-argument change at the binding site, zero code
 * change, and upgrades the privacy tier (queries stay on our infra). The komoot public instance
 * is fair-use: that policy is exactly why the enricher's ~1 request/sec throttle is
 * **load-bearing** — this geocoder deliberately adds no second throttle of its own, so never call
 * it outside the enricher's throttled sweep.
 *
 * Untrusted-input posture (proposal §3, same as the import parsers): HTTP errors, timeouts,
 * malformed or empty JSON all map to an answerless `null` — this class never throws. The request
 * is bounded by a short [timeoutMs] so a hung instance can't stall a backfill sweep. The
 * [httpClient] is injected (tests ride Ktor's `MockEngine`; platforms hand in their engine of
 * choice at the binding site).
 */
class PhotonReverseGeocoder(
    private val httpClient: HttpClient,
    private val endpoint: String = DEFAULT_PUBLIC_ENDPOINT,
    private val timeoutMs: Long = 10_000L,
) : ReverseGeocoder {

    override suspend fun resolve(lat: Double, lng: Double): GeocodeResult? =
        try {
            withTimeoutOrNull(timeoutMs) { lookup(lat, lng) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    /** §32.9 — the street tier, off the same `/reverse` shape. */
    override suspend fun street(lat: Double, lng: Double): String? =
        try {
            withTimeoutOrNull(timeoutMs) {
                val response = httpClient.get("$endpoint/reverse") {
                    parameter("lat", lat)
                    parameter("lon", lng)
                    parameter("limit", 1)
                }
                if (!response.status.isSuccess()) null
                else parseFirstFeatureProperties(response.bodyAsText())?.street?.takeIf { it.isNotBlank() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    private suspend fun lookup(lat: Double, lng: Double): GeocodeResult? {
        val response = httpClient.get("$endpoint/reverse") {
            parameter("lat", lat)
            parameter("lon", lng)
            parameter("limit", 1)
        }
        if (!response.status.isSuccess()) return null
        val properties = parseFirstFeatureProperties(response.bodyAsText()) ?: return null
        // Photon also returns `properties.osm_id` — parked, NOT persisted: `Place.externalRefs`
        // storage is out of scope (proposal §1), blocked on the ref-identity decision.
        return geocodeResultOf(
            featureName = properties.name,
            subThoroughfare = properties.housenumber,
            thoroughfare = properties.street,
            locality = properties.city,
            countryCode = properties.countrycode,
        )
    }

    /** GeoJSON → first feature's properties; provider garbage decodes to `null`, never a throw. */
    private fun parseFirstFeatureProperties(body: String): PhotonProperties? =
        try {
            json.decodeFromString<PhotonResponse>(body).features.firstOrNull()?.properties
        } catch (_: Exception) {
            null
        }

    companion object {
        /**
         * komoot's public Photon instance — the default until the self-hosted container lands;
         * a self-hosted endpoint swaps in via config only (the [endpoint] constructor argument).
         * Public-instance fair use is carried by [PlaceLabelEnricher]'s 1 rps throttle.
         */
        const val DEFAULT_PUBLIC_ENDPOINT = "https://photon.komoot.io"

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}

// ── Photon GeoJSON wire shapes (fields of interest only; everything else ignored) ────────────

@Serializable
internal data class PhotonResponse(val features: List<PhotonFeature> = emptyList())

@Serializable
internal data class PhotonFeature(val properties: PhotonProperties = PhotonProperties())

@Serializable
internal data class PhotonProperties(
    val name: String? = null,
    val housenumber: String? = null,
    val street: String? = null,
    val city: String? = null,
    val countrycode: String? = null,
)
