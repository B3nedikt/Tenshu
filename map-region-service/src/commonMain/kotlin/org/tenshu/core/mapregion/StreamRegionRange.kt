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
 * Reads up to [length] bytes at [offset] of region [regionId] in **streamed** mode
 * (`maps-region-delivery.md` D10) — the remote-on-demand counterpart to [readRegionRange]. Where
 * `readRegionRange` requires the whole bundle already downloaded into the proxy's store,
 * `streamRegionRange` serves the range **without** a full download: an installed region still serves
 * from disk, otherwise the proxy range-fetches the covering chunks from the tile host on a cache
 * miss (the streamed default). The app-side caller is `buildStreamedPartial`, which fetches the
 * covering ranges into a **sparse** `.pmtiles` and hands MapLibre a real `file://`; the app never
 * gains `INTERNET` — the egress is the proxy's.
 *
 * ⚠ It is **not** a lazy file descriptor, and the sparse file is why. `pmtiles://file:///proc/self/fd/N`
 * was spike-proven NEGATIVE on 2026-07-13: maplibre-native 13.0.2 does its own `open()` on the path
 * string and never reads through the descriptor you pass. There is no "local path backed by lazy
 * bytes" form it accepts — the file must be real and fully materialised. See `ui-map-agent.md`.
 *
 * Same wire shape as [readRegionRange] (stateless positional read, ≤ ~256 KiB windows, empty at
 * EOF). A region in neither the store nor the (verified) catalog surfaces as a typed
 * [TResult.Failure]. **Streamed integrity note:** an individual streamed range is not sha-verified
 * (PMTiles has no per-tile hashes); trust rests on TLS + the signed catalog (§5).
 */
suspend fun MapRegionService.streamRegionRange(
    regionId: String,
    offset: Long,
    length: Int,
): TResult<TByteArray> =
    findOperation<StreamRegionRange>(StreamRegionRange.ID)
        .streamRegionRange(regionId, offset, length)
        .asTaskInteractor<TByteArray>()
        .execute()

interface StreamRegionRange : Operation {

    override val schema: OperationSchema get() = Schema

    fun streamRegionRange(regionId: String, offset: Long, length: Int): RequestObject

    companion object {
        const val ID = "${MapRegionService.ID}/streamRegionRange"

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
