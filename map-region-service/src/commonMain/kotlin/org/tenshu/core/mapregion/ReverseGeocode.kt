package org.tenshu.core.mapregion

import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.int
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * **Proxy-mediated reverse geocoding** (`timeline-movement-capture.md` §11.3 P3): resolve a coordinate
 * ([latE7]/[lngE7], degrees × 1e7) to a **venue/POI label plus its locality tiers** via the proxy's
 * Photon egress. The address geocoders on the app side (`android.location.Geocoder`) return *streets*;
 * Photon (OSM) returns the *venue name* (the office's name). The egress is **Control's** — the Timeline app
 * deliberately has no `INTERNET` (manifest `tools:node="remove"`), so the coordinate is sent to the
 * geocoding provider from the proxy, exactly as region tiles are fetched proxy-side. Consent-gated on
 * the app side (`PlaceGeocodingControl`).
 *
 * Returns a [GeocodedPlace] whose fields are blank when nothing is found / the tier is unconfigured —
 * the app-side `ProxyReverseGeocoder` maps a blank label → `null`, so it folds cleanly into the
 * geocoder chain.
 *
 * ### ⚠⚠ Why the operation id moved
 *
 * This used to return a bare `TString`, which meant `city`/`countryCode` could not cross the boundary
 * at all and a shipped feature was silently dead — see [GeocodedPlace] for the measurement. Widening
 * the return type is a **contract change**, and the rule for those (`core-schema-evolution.md` §"Ops",
 * the `RenewTaskLease` precedent) is a **new operation id**, not a redefinition of the old one: op
 * params and returns are positional composites, so a client built against the old shape would parse a
 * composite as a string and produce garbage rather than an error. With a new id the same client gets a
 * typed `unsupportedOperation` instead, which is the whole point.
 */
suspend fun MapRegionService.reverseGeocodePlace(latE7: Long, lngE7: Long): TResult<GeocodedPlace> =
    findOperation<ReverseGeocodePlace>(ReverseGeocodePlace.ID)
        .reverseGeocodePlace(latE7, lngE7)
        .asTaskInteractor<GeocodedPlace>()
        .execute()

interface ReverseGeocodePlace : Operation {

    override val schema: OperationSchema get() = Schema

    fun reverseGeocodePlace(latE7: Long, lngE7: Long): RequestObject

    companion object {
        const val ID = "${MapRegionService.ID}/reverseGeocodePlace"

        val Schema = operation(
            id = ID,
            receiver = MapRegionService.Schema.toType(),
            params = {
                int(Params.LAT_E7)
                int(Params.LNG_E7)
            },
            returnType = GeocodedPlace.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }

    object Params {
        const val LAT_E7 = "$ID/latE7"
        const val LNG_E7 = "$ID/lngE7"
    }
}
