package org.tenshu.core.mapregion

import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.collection.list.TList
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.int
import org.tenshu.core.schema.listType
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Railway track within a bounding box (M9-4, `timeline-movement-capture.md` §13.3).
 *
 * The proxy resolves a covering region, walks the tiles at the rail zoom, decompresses and decodes
 * them, keeps only *running* track (dropping `service=` yards/sidings and `aeroway`), and returns the
 * geometry. An empty list is a real answer — "no track here" — and is what lets the app's
 * `RailRefinement` distinguish evidence from ignorance. ⚠ A *failure* must not be read as emptiness.
 *
 * Coordinates are E7 integers, matching every other geo op in this service.
 */
suspend fun MapRegionService.railLinesIn(
    minLatE7: Long,
    minLngE7: Long,
    maxLatE7: Long,
    maxLngE7: Long,
): TResult<TList<RailLineGeometry>> =
    findOperation<RailLinesIn>(RailLinesIn.ID)
        .railLinesIn(minLatE7, minLngE7, maxLatE7, maxLngE7)
        .asTaskInteractor<TList<RailLineGeometry>>()
        .execute()

interface RailLinesIn : Operation {

    override val schema: OperationSchema get() = Schema

    fun railLinesIn(minLatE7: Long, minLngE7: Long, maxLatE7: Long, maxLngE7: Long): RequestObject

    companion object {
        const val ID = "${MapRegionService.ID}/railLinesIn"

        val Schema = operation(
            id = ID,
            receiver = MapRegionService.Schema.toType(),
            params = {
                int(Params.MIN_LAT_E7)
                int(Params.MIN_LNG_E7)
                int(Params.MAX_LAT_E7)
                int(Params.MAX_LNG_E7)
            },
            returnType = listType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }

    object Params {
        const val MIN_LAT_E7 = "$ID/minLatE7"
        const val MIN_LNG_E7 = "$ID/minLngE7"
        const val MAX_LAT_E7 = "$ID/maxLatE7"
        const val MAX_LNG_E7 = "$ID/maxLngE7"
    }
}
