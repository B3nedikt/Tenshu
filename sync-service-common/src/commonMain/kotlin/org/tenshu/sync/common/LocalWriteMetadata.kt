package org.tenshu.sync.common

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.DataIdProvider
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.core.toTString

/**
 * Durable, local-only record of the HLC at which this device last wrote an entity **itself** —
 * `entityIdHash → HLC of the last non-ingest write`. One row per entity, keyed by
 * [targetEntityIdHash] through [DataIdProvider], the same shape as [ResolvedDataId],
 * [PersistedTombstone] and [SyncMetadata].
 *
 * ## ⚠⚠ Why this exists: [SyncMetadata] is not "the entity's last write"
 *
 * [SyncMetadata] is written by the **ingest** paths only, so it records the last write that
 * arrived *over the wire*. `SyncProcessor.processDeleteBlock`'s order-independence guard needs the
 * entity's last write of any kind: compared against [SyncMetadata] alone, every local write is
 * invisible to it, and a tombstone newer than the last *ingest* — but older than a local write
 * that followed it — passes the comparison and deletes a live row.
 *
 * That is the "ghost file": `DataId` is path-derived, so a recreated
 * `/photos` carries the identical id hash as the deleted one, and the device pulls its own
 * pushed tombstone back from the relay and applies it to the folder the user just recreated.
 * Depending on where the ~100–250 ms round trip lands, `deleteRecursively` either finds the
 * folder already gone and orphans its children (their bytes then unreachable, and the next
 * upload at a child path fails `AlreadyExists`), or a subsequent upload fails
 * `No folder at /photos to hold …`. ⚠ The same defect needs no self-echo: peer B's delete
 * racing device A's local recreate is the identical comparison.
 *
 * ## ⚠ Kept separate from [SyncMetadata], deliberately
 *
 * Stamping local writes into [SyncMetadata] itself would be wrong: the *absence* of that row is
 * load-bearing elsewhere.
 * `SyncProcessor.resolveConflictAndSave` reads a missing row as "anything incoming is newer",
 * which is the documented contract `SliceSnapshotProducer.applySlice` relies on to let a real
 * block replace a snapshot's provisional projection. Widening `SyncMetadata` to cover local
 * writes would therefore also change ingest's `isNewer`, letting a skewed local clock fence out
 * peer writes permanently. This record changes exactly one comparison — the tombstone guard's —
 * and nothing else reads it.
 *
 * Local-only: never serialised into a block or pushed to the relay.
 */
interface LocalWriteMetadata : Composite {

    override val schema: CompositeSchema
        get() = Schema

    /** Stringified hash of the entity's DataId — the key this record is stored under. */
    val targetEntityIdHash: TString
        get() = getField(Fields.TARGET_ENTITY_ID_HASH)

    /**
     * The device's HLC as of the last local write to the entity.
     *
     * ⚠⚠ Stamped with `hlcManager.tick()`, **not** `current()`. A local write is a local event
     * and needs its own position in the total order, exactly like the block the same save emits.
     * `current()` returns whatever the clock last absorbed, which is `(0, 0)` until something
     * ticks or a peer block arrives — so a device's first local writes would stamp at HLC 0 and
     * every tombstone would beat them. `TombstoneRegistry`'s `localEventHlc` parameter carries
     * the rest of the reasoning.
     *
     * ⚠ `TombstoneRegistry` is registered as an observer before `SyncObserver` (`ObserverRegistry`
     * dispatches in registration order), so this sits one tick *below* the HLC of the block that
     * same save emits. Harmless: the guard only asks whether a *tombstone* is newer than the
     * local write, and a tombstone landing inside that one-tick window is indeterminate anyway.
     */
    val hlc: HybridLogicalClock
        get() = getField(Fields.HLC)

    companion object {
        const val ID = "tenshu/sync/localWriteMetadata"

        val Schema: CompositeSchema = composite(
            id = ID,
            traits = listOf(DataIdProvider.Schema),
            fields = {
                string(Fields.TARGET_ENTITY_ID_HASH)
                composite(Fields.HLC, type = HybridLogicalClock.Schema)
            }
        )

        fun create(
            context: TContext,
            targetEntityIdHash: String,
            hlc: HybridLogicalClock,
        ): LocalWriteMetadata = context.create(
            type = ID,
            fields = listOf(
                targetEntityIdHash.toTString(context),
                hlc,
            )
        )
    }

    object Fields {
        const val TARGET_ENTITY_ID_HASH = "$ID/targetEntityIdHash"
        const val HLC = "$ID/hlc"
    }
}
