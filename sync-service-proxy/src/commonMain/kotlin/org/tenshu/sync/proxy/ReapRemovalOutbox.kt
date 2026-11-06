package org.tenshu.sync.proxy

import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.util.HlcStamp
import org.tenshu.core.common.util.toStamp
import org.tenshu.core.entity.DataId
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.sync.SyncService
import org.tenshu.sync.common.PendingReapRemoval
import org.tenshu.sync.common.ReapRemoval

/**
 * The durable outbox of reaps this device owes `POST /sync/content/reap` ([PendingReapRemoval];
 * CR-2 B3d, `content-plane-cr2-b3-wire-spec.md` §3.3/§5).
 *
 * ⚠⚠ Fed from `TombstoneRegistry.recordReapPoint`, in the same transaction as the reaped entity's
 * `ReapWatermark`, so the device can never hold a watermark whose removal it forgot to send. Drained by
 * `SyncScheduler.drainReapRemovals`, which deletes an entry only once the relay answered 2xx.
 *
 * ⚠ Written through the `Database` API only, never `ExecutionContext.saveData`: no observer sees it
 * and nothing about it is ever synced. Stateless over the store, so every caller sees the same rows.
 */
class ReapRemovalOutbox(private val executionContext: ExecutionContext) {

    /**
     * One owed removal, with the T it was enqueued at so a drain can tell whether it moved since.
     * ⚠ T is copied out as a plain [HlcStamp] when read: a stored row is lazily backed, and a composite
     * read after a later reap replaced the row would answer the NEW T.
     */
    data class Entry(val holder: String, val stamp: HlcStamp) {
        fun toRemoval(): ReapRemoval = ReapRemoval(holder = holder, hlc = stamp.encode())
    }

    /**
     * Owes the relay "remove [holder]'s edges at or below [hlc]". ⚠ Replaces an earlier entry for the
     * same holder: the caller only records a reap point that moved forward, and a removal at the later
     * T removes everything the earlier one would.
     */
    fun enqueue(holder: String, hlc: HybridLogicalClock) {
        val database = executionContext.database
        database.transaction {
            database.removeFromArray(ref(), rowId(holder))
            database.addToArray(ref(), PendingReapRemoval.create(executionContext.context, holder, hlc))
        }
    }

    /** Every owed removal, in enqueue order. */
    fun pending(): List<Entry> =
        executionContext.database.getArrayData(ref())?.toList().orEmpty()
            .filterIsInstance<PendingReapRemoval>()
            .map { Entry(it.targetEntityIdHash.get(), it.hlc.toStamp()) }

    /**
     * Drops [sent] once the relay has accepted it. ⚠⚠ Only an entry that still holds the T that was
     * sent: a later reap of the same holder, enqueued while the request was in flight, replaced it with
     * a removal the relay has not seen yet, and that one must stay owed.
     */
    fun remove(sent: List<Entry>) {
        if (sent.isEmpty()) return
        val database = executionContext.database
        database.transaction {
            val current = pending().associateBy { it.holder }
            for (entry in sent) {
                val now = current[entry.holder] ?: continue
                if (now.stamp != entry.stamp) continue
                val id = rowId(entry.holder)
                val row = database.getData(id)
                database.removeFromArray(ref(), id)
                // ⚠⚠ The row too, and its owned HLC child, not just the membership: an orphaned row
                // reads as nothing owed and accumulates silently (`core-local-store-orphan-gc.md` G1).
                if (row != null) executionContext.deleteRetiredBlock(row)
            }
        }
    }

    private fun rowId(holder: String): DataId = DataId.StringDataId.create(
        context = executionContext.context,
        type = PendingReapRemoval.ID,
        keyValue = holder,
    )

    private fun ref() = ArrayDatabaseReference(
        arrayId = DataId.RelationDataId.create(
            context = executionContext.context,
            type = PendingReapRemoval.ID,
            relationId = SyncService.Collections.PENDING_REAP_REMOVALS,
        ),
        isEntity = false,
        elementSchemaId = PendingReapRemoval.ID,
    )
}
