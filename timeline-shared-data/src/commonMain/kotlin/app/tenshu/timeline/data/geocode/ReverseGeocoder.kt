package app.tenshu.timeline.data.geocode

import kotlin.coroutines.cancellation.CancellationException

/**
 * The best available answer for a reverse-geocode lookup
 * (`docs/proposals/place-geocoding.md` §3). [label] is the most specific name the provider
 * offers — a POI name or a street name; [city]/[countryCode]
 * ride along when the provider gives them (persisted only from G4, when the `Place` schema
 * grows the structured fields).
 */
data class GeocodeResult(
    val label: String,
    val city: String = "",
    val countryCode: String = "",
)

/**
 * The **reverse-geocoding seam** (`docs/proposals/place-geocoding.md` §3, executing
 * `timeline-services.md` §3.3): `(lat, lng) → GeocodeResult?`. Coordinates are plain degrees —
 * the E7-integer storage form is converted at the edge by the caller ([PlaceLabelEnricher]).
 *
 * Implementations are platform lookups (Android `Geocoder` (G2), the common Photon client (G3),
 * iOS `CLGeocoder` (G5)) or the future offline extract, **DI-bound in the Timeline app** — not
 * `expect`/`actual` — mirroring `HealthImporter` / `LocationCaptureSource` (proposal D1/D2).
 * A single best answer, not a candidate list (D4): auto-labelling wants the top result or
 * nothing; candidate lists return when a picker UI exists.
 */
interface ReverseGeocoder {
    /** Best answer for the coordinate, or null (unavailable / nothing there / throttled). */
    suspend fun resolve(lat: Double, lng: Double): GeocodeResult?

    /**
     * The nearest **named transit stop** within [withinM] metres, or `null` when this tier cannot say.
     *
     * ⚠⚠ A separate question from [resolve], because an ordinary reverse geocode structurally cannot
     * answer it: at a city-centre platform the nearest features are shops, and the station is not
     * returned *at all* however many candidates are requested (measured — see `NearestTransitStop`).
     * Only a tag-filtered lookup finds it, which is a second request; so it is asked only by callers
     * that have already decided the fix is too coarse to name a venue.
     *
     * ⚠ Defaults to `null` rather than to [resolve] — "this tier does not do stops". A tier that fell
     * back to its ordinary answer would hand back the very shop this exists to avoid.
     */
    suspend fun transitStop(lat: Double, lng: Double, withinM: Double): String? = null

    /**
     * The **street this coordinate is on**, or `null` when this tier cannot say (§32.9).
     *
     * ⚠⚠ The tier between a venue and a city, and the one that was missing. `PlaceLabelEnricher`
     * refuses a venue name above `COARSE_LABEL_ACCURACY_M` — correctly, a venue is a *point* and a
     * ±100 m fix cannot claim one — and then fell all the way to `result.city`. Measured on the real
     * store, that is why three separate places are called **"München"**, one of them the user's
     * office: the answer is not merely coarse, it is the same string for everywhere in the city and
     * it collides with every other poor-fix dwell.
     *
     * A street is a *line*, so it degrades gracefully in exactly the way a venue does not — a ±100 m
     * fix on a street is on that street. That is the whole argument for
     * putting it between the two.
     *
     * ⚠ No radius parameter, deliberately, unlike [transitStop]: this asks *"what am I standing on"*
     * and the provider answers for the coordinate given, so there is no distance to bound and no
     * constant to justify. [transitStop] needs one because it searches outward for a different kind
     * of thing.
     *
     * ⚠ Defaults to `null` — "this tier does not do streets" — for the same reason [transitStop]
     * does: a tier falling back to its ordinary answer would hand back the venue this exists to avoid.
     */
    suspend fun street(lat: Double, lng: Double): String? = null
}

/**
 * Tiered chain per `timeline-services.md` §3.4 — first non-null answer wins, later tiers are
 * never asked. A tier that throws is treated as answerless (untrusted-provider posture, same
 * as the import parsers), so one broken tier never takes the chain down.
 */
class ChainedReverseGeocoder(private val tiers: List<ReverseGeocoder>) : ReverseGeocoder {

    /** Same posture as [resolve]: first tier that can answer wins, a throwing tier is answerless. */
    override suspend fun transitStop(lat: Double, lng: Double, withinM: Double): String? =
        tiers.firstNotNullOfOrNull { tier ->
            try {
                tier.transitStop(lat, lng, withinM)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }

    /** Same posture as [resolve]. */
    override suspend fun street(lat: Double, lng: Double): String? =
        tiers.firstNotNullOfOrNull { tier ->
            try {
                tier.street(lat, lng)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }

    override suspend fun resolve(lat: Double, lng: Double): GeocodeResult? =
        tiers.firstNotNullOfOrNull { tier ->
            try {
                tier.resolve(lat, lng)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
}
