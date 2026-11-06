package org.tenshu.core.mapregion

import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.boolean
import org.tenshu.core.schema.string
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Downloads a region bundle into the proxy's canonical store (`maps-region-delivery.md` §4):
 * the proxy resolves the region in the (verified) catalog, fetches the bundle over plain HTTPS
 * from the tile host, verifies its sha256 against the manifest (R4), lands it in the store, and
 * returns the resulting [InstalledRegion].
 *
 * v1 is **one-shot** — it returns when the download completes. The incremental *progress stream*
 * (`maps-region-delivery.md` §4 step 2) is a follow-up; a picker shows an indeterminate state
 * until this resolves. Resumable ranged GET is likewise a follow-up optimisation (correctness is
 * a whole-bundle fetch + sha256 gate).
 *
 * A transport / sha256-mismatch / unknown-region failure surfaces as a typed [TResult.Failure].
 */
suspend fun MapRegionService.downloadRegion(
    regionId: String,
    allowMetered: Boolean = false,
): TResult<InstalledRegion> =
    findOperation<DownloadRegion>(DownloadRegion.ID)
        .downloadRegion(regionId, allowMetered)
        .asTaskInteractor<InstalledRegion>()
        .execute()

interface DownloadRegion : Operation {

    override val schema: OperationSchema get() = Schema

    fun downloadRegion(regionId: String, allowMetered: Boolean = false): RequestObject

    companion object {
        const val ID = "${MapRegionService.ID}/downloadRegion"

        val Schema = operation(
            id = ID,
            receiver = MapRegionService.Schema.toType(),
            params = {
                string(Params.REGION_ID)
                // ⚠ Optional, and the client only sends it when TRUE (see `DownloadRegionRo`), so the
                // ordinary call is byte-identical on the wire to before this parameter existed. Only the
                // deliberate "yes, use mobile data" path carries anything new.
                boolean(Params.ALLOW_METERED, isOptional = true)
            },
            returnType = InstalledRegion.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }

    object Params {
        const val REGION_ID = "$ID/regionId"

        /**
         * The user's explicit consent to spend mobile data on this download (D5 / §7.1 track 3g).
         *
         * ⚠ Per-call rather than a preference: the alternative would make the only way past the gate
         * "go and turn a setting off", which is a poor answer to a deliberate tap — and reusing the
         * background-sync `WifiOnly` preference would let a background setting silently block a
         * foreground action.
         */
        const val ALLOW_METERED = "$ID/allowMetered"
    }
}
