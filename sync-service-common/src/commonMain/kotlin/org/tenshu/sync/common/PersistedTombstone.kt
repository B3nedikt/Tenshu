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
 * Durable, local-only tombstone record: `entityIdHash → (highest tombstone HLC, target type)`.
 *
 * One row per tombstoned entity, keyed by [targetEntityIdHash] through [DataIdProvider] —
 * deliberately the same shape as [ResolvedDataId] and [SyncMetadata], which are the two other
 * per-entity sidecars this proxy keeps.
 *
 * ## ⚠⚠ Why per-key rows and not one array
 *
 * `TombstoneRegistry` used to persist the raw `DeleteBlock`s in a `RECEIVED_TOMBSTONES` relation
 * array and rebuild its maps by folding that array at construction. That fold is O(tombstones)
 * **full composite materialisations** on the launch path, because `getArrayData` resolves every
 * member eagerly — and `TombstoneRegistry` is constructed inside `initSyncService`, which mints
 * the IPC session, so every client read waits behind it.
 *
 * Measured on a real iPhone 2026-08-04: **10,523 members ⇒ 6,316 ms**, ~96 % of a 6.5 s launch.
 * The fold that consumed them took 131 ms; the entire cost was the eager resolution. And the
 * array is unbounded, so it grew as the device caught up.
 *
 * The registry's actual read is a **point lookup** — "is there a tombstone for this idHash?" — so
 * holding every tombstone in memory was never required. Per-key rows make startup O(1) and each
 * lookup one read-through, exactly the shape [ResolvedDataId] already uses in the same class.
 *
 * ⚠ A single folded *digest* row was the other candidate and is worse: the digest would have to be
 * rewritten whole on every observed tombstone (~300 KB at this population), and tombstones arrive
 * in streams during catch-up.
 *
 * Local-only: never serialised into a block or pushed to the relay. It records what this device
 * has *observed*, and the observation is re-derivable from the tombstone stream.
 */
interface PersistedTombstone : Composite {

    override val schema: CompositeSchema
        get() = Schema

    /** Stringified hash of the tombstoned entity's DataId — the key this record is stored under. */
    val targetEntityIdHash: TString
        get() = getField(Fields.TARGET_ENTITY_ID_HASH)

    /** Highest tombstone HLC observed for the entity. */
    val hlc: HybridLogicalClock
        get() = getField(Fields.HLC)

    /**
     * The tombstoned entity's type id.
     *
     * Carried so a rebuild can recover the per-type counts without re-reading the blocks —
     * `SyncManager.currentRealmEpoch` derives the realm epoch from the `Device.ID` count.
     */
    val targetEntityType: TString
        get() = getField(Fields.TARGET_ENTITY_TYPE)

    companion object {
        const val ID = "tenshu/sync/persistedTombstone"

        val Schema: CompositeSchema = composite(
            id = ID,
            traits = listOf(DataIdProvider.Schema),
            fields = {
                string(Fields.TARGET_ENTITY_ID_HASH)
                composite(Fields.HLC, type = HybridLogicalClock.Schema)
                string(Fields.TARGET_ENTITY_TYPE)
            }
        )

        fun create(
            context: TContext,
            targetEntityIdHash: String,
            hlc: HybridLogicalClock,
            targetEntityType: String,
        ): PersistedTombstone = context.create(
            type = ID,
            fields = listOf(
                targetEntityIdHash.toTString(context),
                hlc,
                targetEntityType.toTString(context),
            )
        )
    }

    object Fields {
        const val TARGET_ENTITY_ID_HASH = "$ID/targetEntityIdHash"
        const val HLC = "$ID/hlc"
        const val TARGET_ENTITY_TYPE = "$ID/targetEntityType"
    }
}
