package org.tenshu.sync

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
 * Pulls new tombstones from the relay since the given cursor. Sibling of [GetChanges]
 * on the tombstone wire path per `docs/adr/0006-sync-tombstones.md`
 *
 * Returns a [TombstonesResponse] wrapping both the tombstones and the relay's
 * arrival-time `nextCursor` (see [ChangesResponse] / `memory/project_cursor_advancement_bug`).
 * `SyncScheduler.pullTombstones` advances `lastSyncTimestamp` from `response.nextCursor`,
 * NOT from `tombstone.hlc.physicalTime` — closing the same-millisecond HLC collision
 * that previously stranded late-pushed tombstones.
 *
 * Driven by [org.tenshu.sync.proxy.SyncScheduler.pullTombstones] — every poll, the
 * scheduler snapshots `lastSyncTimestamp` once and uses it for both this call and
 * [GetChanges] so a tombstone arriving between the two pulls isn't skipped.
 */
suspend fun SyncService.getTombstones(sinceTimestamp: Long): TResult<TombstonesResponse> =
    findOperation<GetTombstones>(GetTombstones.ID)
        .getTombstones(sinceTimestamp)
        .asTaskInteractor<TombstonesResponse>()
        .execute()

interface GetTombstones : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun getTombstones(sinceTimestamp: Long): RequestObject

    companion object {
        const val ID = "${SyncService.ID}/getTombstones"

        val Schema = operation(
            id = ID,
            receiver = SyncService.Schema.toType(),
            traits = listOf(TerminalOperation.Schema, IoOperation.Schema),
            params = {
                int(Params.SINCE_TIMESTAMP)
            },
            returnType = TombstonesResponse.Schema.toType()
        )
    }

    object Params {
        const val SINCE_TIMESTAMP = "$ID/sinceTimestamp"
    }
}
