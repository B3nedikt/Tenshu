package org.tenshu.sync

import org.tenshu.core.Composite
import org.tenshu.core.collection.list.SimpleTList
import org.tenshu.core.collection.list.TList
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.number.TInteger
import org.tenshu.core.number.toTInteger
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.int
import org.tenshu.core.schema.list

/**
 * Response shape for `POST /sync/tombstones` after the arrival-time cursor design
 * landed (see [ChangesResponse] for the rationale and `memory/project_cursor_advancement_bug`).
 * Mirrors [ChangesResponse]: the receiver advances `lastSyncTimestamp` from
 * [nextCursor], not from `tombstone.hlc.physicalTime`, closing the same-millisecond
 * HLC collision that previously stranded a late-pushed tombstone whose HLC tick
 * matched a prior block already past the receiver's cursor.
 *
 * `SyncScheduler.tickOnce` snapshots a single `since` for both `pullChanges` and
 * `pullTombstones`, then advances `lastSyncTimestamp` to the max of the two responses'
 * `nextCursor`s — the relay's arrival counter is shared across blocks and tombstones,
 * so the two cursor-spaces are comparable. See `SyncScheduler.pullTombstones`.
 */
interface TombstonesResponse : Composite {

    override val schema: CompositeSchema get() = Schema

    val tombstones: TList<DeleteBlock> get() = getField(Fields.TOMBSTONES)
    val nextCursor: TInteger get() = getField(Fields.NEXT_CURSOR)

    companion object {
        const val ID = "tenshu/sync/tombstonesResponse"

        val Schema: CompositeSchema = composite(
            id = ID,
            fields = {
                list(Fields.TOMBSTONES, of = DeleteBlock.Schema)
                int(Fields.NEXT_CURSOR)
            }
        )

        fun create(
            context: TContext,
            tombstones: List<DeleteBlock>,
            nextCursor: Long
        ): TombstonesResponse = context.create(
            type = ID,
            fields = listOf(
                SimpleTList(tombstones),
                nextCursor.toTInteger(context)
            )
        )
    }

    object Fields {
        const val TOMBSTONES = "$ID/tombstones"
        const val NEXT_CURSOR = "$ID/nextCursor"
    }
}
