package org.tenshu.sync.proxy

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.entity.DataId
import org.tenshu.core.id
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.Field
import org.tenshu.sync.DeleteBlock
import org.tenshu.sync.SyncBlock

/**
 * Delete a block row that has reached the end of its life, together with the rows it
 * **exclusively owns** (`core-local-store-orphan-gc.md` G1).
 *
 * ## Why this exists
 *
 * `Database.addToArray(queue, block)` calls `saveData(block)` on the member, so queueing a block
 * for push **persists it as a row**. The drain's `removeFromArray(queue, dataId)` then rewrites
 * the list's refs and *never deletes that row*. Nothing else ever does either — the object store
 * has no refcount and no reachability sweep — so every block this device has ever pushed is
 * immortal.
 *
 * Measured before this landed: **77,800 `tenshu/sync/block` rows, 101 of them referenced** — 99.87%
 * garbage, 129 MB of a 145 MB store, growing ~1.8 rows/sec and never shrinking. That is the
 * dominant cost behind O(all-history) recovery and the iOS store-wipe watchdog kill.
 *
 * ## What counts as "owned"
 *
 * The rule is the schema's, not this function's. `DbEntryMapper.writeValuesToDb` states it:
 *
 * > *a child with its own `DataIdProvider` has its own identity (and its own sync blocks); one
 * > that falls back to `EntityFieldDataId` is owned by this parent and has no other writer.*
 *
 * So a composite field whose value resolves a `DataId` is somebody else's row and is left alone;
 * one that does not is addressed by a key **derived from this parent**, cannot be reached by any
 * other referent, and dies with it. For a `SyncBlock` that is exactly the `hlc` child — which is
 * why the store held one HLC row per block, tombstone and metadata row, to the unit
 * (`51,297 + 10,521 + 14,042 = 75,860` against `75,861`, the spare being the local tip).
 *
 * Deriving this from the schema rather than naming `hlc` is deliberate: a block that later gains
 * another parent-owned composite field is collected without anyone remembering to come back here.
 * Scalars need no handling — they are inlined into the parent's `DbEntry`, not stored as rows.
 *
 * ## ⚠⚠ Call this ONLY where the block is genuinely finished
 *
 * This is **not** safe to fold into `removeFromArray`. Two live counter-examples:
 *
 *  - [PendingBlockRestamper]'s `replace` removes and re-adds **the same row** —
 *    `SyncBlockDataIdProviderImpl` keys on `blockId`, which the reseal preserves — so a delete
 *    hung off the removal would destroy the row the very next `addToArray` re-adds.
 *  - A `DeleteBlock` sits in **two** arrays at once: the pending-tombstone queue *and*
 *    [TombstoneRegistry]'s durable store, which rebuilds the in-memory map across restarts from
 *    exactly those rows. Deleting one on push would silently disarm resurrection protection.
 *    Tombstones are therefore **out of scope here** — they are referenced state, not garbage
 *    (measured: 10,522 rows, 10,522 referenced, zero orphaned). Their unbounded growth is a
 *    retention question for `docs/adr/0006-sync-tombstones.md` Phase 4.
 *
 * Terminal for a `SyncBlock` means: pushed successfully, quarantined as unpushable, or dropped
 * below the schema floor. Nothing reads a block after that — the content-plane readers
 * (`fetchEntityBlocks`, `openContentStream`) fetch from the *relay*, not the local store.
 *
 * ## ⚠ The contract this establishes: a block reference does not outlive its drain
 *
 * Queued blocks are **lazily backed** — a field resolves from the store on access — so once the
 * row is gone a later read throws `IllegalStateException: Entry not found`. A consumer that wants
 * a block's data past the drain must **copy it during the call**, which is what the real one does
 * (`RestSyncRelay.pushBlock` serialises immediately).
 *
 * ⚠ This bites *flakily*, not cleanly: an already-resolved field still answers, so whether a
 * stale read throws depends on cache state. `SyncMetadataOperationTest` captured the block in its
 * push stub and asserted on it afterwards — green in isolation, failing only under parallel load.
 * If a stale-read failure ever appears somewhere new, this is the reason, and the fix is to read
 * at push time rather than to keep the row alive.
 */
internal fun ExecutionContext.deleteRetiredBlock(block: Data) {
    val dataId = getDataId(block) as? DataId.EntityDataId ?: return
    val composite = block as? Composite ?: return
    val typeId = block.id
    val schema = schemaProvider.getSchema(typeId) as? CompositeSchema ?: return
    // ⚠ Read before the row goes: a queued block is lazily backed (see the KDoc above).
    val blockId = (block as? SyncBlock)?.blockId?.get() ?: (block as? DeleteBlock)?.blockId?.get()

    database.transaction {
        schema.values.toList().forEach { field: Field ->
            val fieldId = field.id.get()
            val value: Data? =
                if (field.isOptional.get()) composite.getOptionalField(fieldId)
                else composite.getField(fieldId)

            // Only composites get a row of their own. One that resolves its own DataId belongs
            // to another writer; one that does not is keyed off this parent and dies with it.
            if (value is Composite && getDataId(value) == null) {
                database.deleteData(
                    DataId.EntityFieldDataId.create(
                        context = context,
                        type = value.id,
                        parentEntity = dataId,
                        fieldRef = fieldId,
                    )
                )
            }
        }
        database.deleteData(dataId)
        // ⭐ CR-2 B3d: a retired block's sidecars die with it — the edges its push carried, or the
        // cascade its tombstone named. Left behind they would be rows nothing ever reads again.
        if (blockId != null) {
            when (block) {
                is SyncBlock -> {
                    PendingBlockEdgeStore(this@deleteRetiredBlock).delete(blockId)
                    // CR-2 B4: and the revive it carried (or was going to).
                    PendingBlockReviveStore(this@deleteRetiredBlock).delete(blockId)
                }
                is DeleteBlock -> DatabaseTombstoneCascadeStore(this@deleteRetiredBlock).delete(listOf(blockId))
            }
        }
    }
}
