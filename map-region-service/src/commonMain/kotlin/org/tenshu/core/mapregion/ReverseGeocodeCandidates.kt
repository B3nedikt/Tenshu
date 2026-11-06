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
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * **Proxy-mediated reverse-geocode *candidates*** (`timeline-recognition-and-place-naming.md` §4) —
 * the several nearest labelled features around a coordinate, best first, for a "choose the right
 * place" picker. Same Photon egress and consent gate as [reverseGeocode]; this one just returns the
 * ranked list instead of only the top pick, so the user can correct a mis-named stay (a ±100 m indoor
 * fix can't tell the office from the clinic next door — the picker lets them say which).
 *
 * Returned as a single **newline-delimited** [TString] (POI labels never contain newlines), which the
 * app splits — no list-of-primitives wire type needed. Empty string when nothing is found.
 */
suspend fun MapRegionService.reverseGeocodeCandidates(latE7: Long, lngE7: Long): TResult<TString> =
    findOperation<ReverseGeocodeCandidates>(ReverseGeocodeCandidates.ID)
        .reverseGeocodeCandidates(latE7, lngE7)
        .asTaskInteractor<TString>()
        .execute()

interface ReverseGeocodeCandidates : Operation {

    override val schema: OperationSchema get() = Schema

    fun reverseGeocodeCandidates(latE7: Long, lngE7: Long): RequestObject

    companion object {
        const val ID = "${MapRegionService.ID}/reverseGeocodeCandidates"

        /** The candidate labels are joined with this on the wire and split app-side. */
        const val DELIMITER = "\n"

        val Schema = operation(
            id = ID,
            receiver = MapRegionService.Schema.toType(),
            params = {
                int(Params.LAT_E7)
                int(Params.LNG_E7)
            },
            returnType = TString.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val LAT_E7 = "$ID/latE7"
        const val LNG_E7 = "$ID/lngE7"
    }
}
