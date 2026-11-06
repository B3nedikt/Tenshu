package org.tenshu.core.mapregion

import org.tenshu.core.Operation
import org.tenshu.core.TByteArray
import org.tenshu.core.TResult
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
 * Reads up to [length] bytes at [offset] from the installed region bundle [regionId] in the
 * proxy's canonical store (D5/D6, `maps-region-delivery.md` §7 render-hop slice). This is the
 * **app-side terminus's byte transport**: the region bundle bytes live in the proxy's sandbox
 * (a `FileDatabase`-relative path the app process can't open directly), so the `:ui-map`
 * terminus drains them a bounded window at a time over this op into its own `filesDir`, then
 * opens the staged file with `pmtiles://file://…` (the `InstalledRegion.localPath` KDoc's
 * "copy bytes over the IPC pull range-source" — now built).
 *
 * Region bundles are **public plaintext** (ODbL map data, not user content), so — unlike the
 * sync-service `ReadContentRange` streaming ops — there is no decrypt stage, no realm routing,
 * and no per-session handle: a bundle is an immutable device-local file addressed by its
 * [regionId], so a stateless positional read is enough (the app knows the total size from
 * [InstalledRegion.byteSize]).
 *
 * Callers keep [length] small (≤ ~256 KiB) so the inline `TByteArray` stays under the Binder
 * transaction cap. Bytes at/after EOF come back **empty**; an unknown/absent region surfaces as
 * a typed [TResult.Failure].
 */
suspend fun MapRegionService.readRegionRange(
    regionId: String,
    offset: Long,
    length: Int,
): TResult<TByteArray> =
    findOperation<ReadRegionRange>(ReadRegionRange.ID)
        .readRegionRange(regionId, offset, length)
        .asTaskInteractor<TByteArray>()
        .execute()

interface ReadRegionRange : Operation {

    override val schema: OperationSchema get() = Schema

    fun readRegionRange(regionId: String, offset: Long, length: Int): RequestObject

    companion object {
        const val ID = "${MapRegionService.ID}/readRegionRange"

        val Schema = operation(
            id = ID,
            receiver = MapRegionService.Schema.toType(),
            params = {
                string(Params.REGION_ID)
                int(Params.OFFSET)
                int(Params.LENGTH)
            },
            returnType = TByteArray.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }

    object Params {
        const val REGION_ID = "$ID/regionId"
        const val OFFSET = "$ID/offset"
        const val LENGTH = "$ID/length"
    }
}
