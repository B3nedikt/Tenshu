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
 * load-bearing elsewhere. `SliceSnapshotProducer.applySlice` writes its provisional rows with
 * `SYNC_INGEST` and relies on a missing [SyncMetadata] to let a real block replace them. Those rows
 * never get this record either, because ingest-origin saves are not stamped.
 *
 * ## ⚠⚠ Read by ingest too (2026-11-14), not only by the tombstone guard
 *
 * `SyncProcessor.resolveConflictAndSave` measures "newer" against the later of [SyncMetadata] and
 * this record, for every conflict strategy except Merge. The relay echoes a device's own blocks
 * back, and against [SyncMetadata] alone the echo of an OLDER own write read as newer and replaced
 * a newer local one (`SyncProcessorOwnEchoTest`; the `ContactHandshakeSystemTest` flake). The echo
 * of the latest own write ties and is refused too, so a device no longer re-saves its own writes.
 * A wiped device has no record, so its replay of its own history still applies.
 *
 * ⚠ Why this does not let a skewed local clock fence peers out: ingest `observe()`s every block's
 * HLC, so a peer that has seen this device's block writes above it. Only a write made concurrently
 * loses, and it loses on the peer too, which ingests this device's block with no local-write guard.
 * The old rule made the two devices diverge, each showing the other's value. Merge keeps the
 * ingest-only basis, so an older peer change is still merged in.
 *
 * ⚠ Two stamps feed it, highest wins: `TombstoneRegistry`'s on-save tick, and the HLC `SyncObserver`
 * puts on the entity's emitted block or lane delta (`TombstoneRegistry.recordEmittedWrite`). The
 * second is needed because the block is stamped later, on the emit consumer, and under load after
 * the NEXT write's on-save tick. Without it that block's echo would beat the record.
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
     * dispatches in registration order), so the on-save stamp sits one tick *below* the HLC of the
     * block that same save emits. ⚠ The emitted-block stamp then raises it to the block's own HLC,
     * which is what ingest compares an echo against (see "Read by ingest too" above).
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
