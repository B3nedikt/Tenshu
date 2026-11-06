package org.tenshu.core.mapregion

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Evicts a downloaded region from the proxy's canonical store (D5): removes it from the install
 * index and deletes the bundle bytes. Frees the Free-tier active-region slot (D9). An
 * already-open map surface keeps its own `filesDir` copy until it releases (D6) — deletion here
 * only affects *new* opens.
 */
suspend fun MapRegionService.deleteRegion(regionId: String): TResult<Data?> =
    findOperation<DeleteRegion>(DeleteRegion.ID)
        .deleteRegion(regionId)
        .asTaskInteractor<Data?>()
        .execute()

interface DeleteRegion : Operation {

    override val schema: OperationSchema get() = Schema

    fun deleteRegion(regionId: String): RequestObject

    companion object {
        const val ID = "${MapRegionService.ID}/deleteRegion"

        val Schema = operation(
            id = ID,
            receiver = MapRegionService.Schema.toType(),
            params = {
                string(Params.REGION_ID)
            },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }

    object Params {
        const val REGION_ID = "$ID/regionId"
    }
}
