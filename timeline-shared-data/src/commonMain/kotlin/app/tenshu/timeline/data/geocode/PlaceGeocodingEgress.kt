package app.tenshu.timeline.data.geocode

import org.tenshu.core.common.TenshuClient
import org.tenshu.core.fold
import org.tenshu.core.mapregion.GeocodedPlace
import org.tenshu.core.mapregion.ReverseGeocodeCandidates
import org.tenshu.core.mapregion.nearestTransitStop
import org.tenshu.core.mapregion.reverseGeocodeCandidates
import org.tenshu.core.mapregion.reverseGeocodePlace
import org.tenshu.mapregion.client.mapRegionService
import kotlin.coroutines.cancellation.CancellationException

/**
 * **The only way Timeline asks Control's proxy to geocode a coordinate.** Every `:map-region-service`
 * op that can send a coordinate to a third-party place-name provider (komoot's public Photon, by
 * default) is called here and nowhere else, and each call checks the user's [PlaceGeocodingControl]
 * opt-in first.
 *
 * ### ⚠⚠ Why this exists: the transit-stop fallback skipped the opt-in
 *
 * `PhotonGeocoder`'s KDoc says *"the app-side `PlaceGeocodingControl` gates whether the op is ever
 * called"*, and [PlaceLabelEnricher] honoured that. But `TimelineProjectionRepository` called
 * `nearestTransitStop` (and the picker's `reverseGeocodeCandidates`) **directly**, with no check: every
 * transit leg's two endpoints were sent to Photon whenever the tile tier could not name a stop — with
 * the opt-in off, and before the user had ever chosen. On iOS, which has no tile tier, that was every
 * leg. Two callers had to remember the gate; one did not.
 *
 * So the gate is no longer something a caller remembers. The ops live behind this class, and
 * `GeocodingEgressGuardTest` fails if any other Timeline source calls one of them.
 *
 * ### ⚠⚠ Absence is NO
 *
 * A `null` [consent] — a platform or test that bound no control — refuses, exactly like a control
 * reading `false`. That is the project's consent rule (absence of a choice is never a yes), and it is
 * why the repository's constructor defaults to `null` rather than to a permissive stand-in.
 *
 * ### What a refusal costs
 *
 * Nothing breaks: a refused stop is `null` (the leg reads as a bare "Transit", as it does for a leg
 * between two stations), a refused picker is an empty list (free text only), a refused place is
 * `null`. ⚠ A refused stop also skips the proxy's **tile** tier, which needs no third party — the op
 * cannot be told "tiles only" without a `MapRegionService` reshape (`place-geocoding.md` §5.1).
 */
class PlaceGeocodingEgress(
    private val client: TenshuClient,
    private val consent: PlaceGeocodingControl?,
) {

    /** Whether the user has opted in **right now**. Read per call, so turning it off stops the next one. */
    val isAllowed: Boolean
        get() = consent?.enabled?.value == true

    /**
     * The nearest named transit stop within [withinM] metres, or `null` when refused, when no tier
     * answers, or on any failure — the op's `""` collapses to `null` here.
     */
    suspend fun transitStopNear(latE7: Long, lngE7: Long, withinM: Long): String? {
        if (!isAllowed) return null
        if (latE7 == 0L && lngE7 == 0L) return null
        return orNull {
            client.mapRegionService
                .nearestTransitStop(latE7, lngE7, withinM)
                .fold(onSuccess = { it.get() }, onFailure = { "" })
        }?.takeIf { it.isNotBlank() }
    }

    /** Nearby place-name candidates for the picker, best first; empty when refused or on any failure. */
    suspend fun placeCandidates(latE7: Long, lngE7: Long): List<String> {
        if (!isAllowed) return emptyList()
        return orNull {
            client.mapRegionService.reverseGeocodeCandidates(latE7, lngE7).fold(
                onSuccess = { it.get().split(ReverseGeocodeCandidates.DELIMITER).filter { s -> s.isNotBlank() } },
                onFailure = { emptyList() },
            )
        }.orEmpty()
    }

    /** The venue label plus its locality tiers, or `null` when refused or on any failure. */
    suspend fun placeAt(latE7: Long, lngE7: Long): GeocodedPlace? {
        if (!isAllowed) return null
        return orNull {
            client.mapRegionService
                .reverseGeocodePlace(latE7, lngE7)
                .fold(onSuccess = { it }, onFailure = { null })
        }
    }

    /** A dead dispatch may throw rather than fold; either way the answer is "nothing". */
    private inline fun <T> orNull(block: () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}
