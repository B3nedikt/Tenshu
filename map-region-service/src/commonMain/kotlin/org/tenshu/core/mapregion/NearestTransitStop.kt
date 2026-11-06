package org.tenshu.core.mapregion

import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.TString
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
 * **The named transit stop near a coordinate**, or `""` — the answer an ordinary reverse geocode
 * cannot give (`timeline-movement-capture.md` §26.4/§28).
 *
 * ### ⚠⚠ Why this is a separate question, not a field on [GeocodedPlace]
 *
 * A plain reverse query returns the *nearest* features, and in a city centre those are shops. At a
 * real Odeonsplatz U-Bahn dwell a `limit=50` reverse query returns **no** railway feature at all —
 * Photon caps the response around 70 m and the station node is 82 m from the cell centroid. The
 * station is not ranked badly; it is **never offered**. Only a tag-filtered query asks the right
 * question, and that is a second request — so it is its own operation, called only when the caller
 * has decided it needs one, rather than doubling the cost of every lookup.
 *
 * [withinM] is the caller's own uncertainty: a stop only answers for a fix if it lies inside the
 * radius that fix could plausibly be anywhere in. ⚠ Deliberately the caller's number, because only
 * the caller knows it — the geocoder is told how far it may look, it does not guess.
 *
 * Egress is **Control's**, like every other tier here; the Timeline app has no `INTERNET`.
 */
suspend fun MapRegionService.nearestTransitStop(
    latE7: Long,
    lngE7: Long,
    withinM: Long,
): TResult<TString> =
    findOperation<NearestTransitStop>(NearestTransitStop.ID)
        .nearestTransitStop(latE7, lngE7, withinM)
        .asTaskInteractor<TString>()
        .execute()

interface NearestTransitStop : Operation {

    override val schema: OperationSchema get() = Schema

    fun nearestTransitStop(latE7: Long, lngE7: Long, withinM: Long): RequestObject

    companion object {
        const val ID = "${MapRegionService.ID}/nearestTransitStop"

        val Schema = operation(
            id = ID,
            receiver = MapRegionService.Schema.toType(),
            params = {
                int(Params.LAT_E7)
                int(Params.LNG_E7)
                int(Params.WITHIN_M)
            },
            returnType = TString.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }

    object Params {
        const val LAT_E7 = "$ID/latE7"
        const val LNG_E7 = "$ID/lngE7"
        const val WITHIN_M = "$ID/withinM"
    }
}
