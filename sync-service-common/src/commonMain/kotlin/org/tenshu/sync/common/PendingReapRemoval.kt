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
 * One reap this device owes the relay (CR-2 B3d, `content-plane-cr2-b3-wire-spec.md` §3.3/§5): the
 * entity named [targetEntityIdHash] was reaped with a deleted folder at [hlc], the folder tombstone's
 * HLC T, and `POST /sync/content/reap` has not yet accepted it.
 *
 * ## ⚠⚠ Durable, because a reap happens once
 *
 * A reap runs when this device applies a folder tombstone, and never again. A removal held only in
 * memory and lost to a process death would leave the reaped child's edges standing at the relay for
 * ever — a leak G2 could never close. So it is enqueued in the same transaction as the child's
 * [ReapWatermark] and leaves only when the relay answers 2xx.
 *
 * ⚠ One row per wire name: a later reap of the same entity (a higher T) replaces it, because a
 * removal at the higher T removes everything the lower one would. The drain deletes a row only if it
 * still holds the T it sent.
 *
 * ⚠ Device-local, never synced. A **new** composite, so no `SyncService` version moves (HR #9 bumps
 * on a reshape); its fields are positional and persisted, so any later change owes the bump.
 */
interface PendingReapRemoval : Composite {

    override val schema: CompositeSchema
        get() = Schema

    /** The reaped entity's wire name — this row's key. */
    val targetEntityIdHash: TString
        get() = getField(Fields.TARGET_ENTITY_ID_HASH)

    /** T: the HLC of the folder tombstone whose cascade reaped it. */
    val hlc: HybridLogicalClock
        get() = getField(Fields.HLC)

    companion object {
        const val ID = "tenshu/sync/pendingReapRemoval"

        val Schema: CompositeSchema = composite(
            id = ID,
            traits = listOf(DataIdProvider.Schema),
            fields = {
                string(Fields.TARGET_ENTITY_ID_HASH)
                composite(Fields.HLC, type = HybridLogicalClock.Schema)
            }
        )

        fun create(context: TContext, targetEntityIdHash: String, hlc: HybridLogicalClock): PendingReapRemoval =
            context.create(
                type = ID,
                fields = listOf(targetEntityIdHash.toTString(context), hlc),
            )
    }

    object Fields {
        const val TARGET_ENTITY_ID_HASH = "$ID/targetEntityIdHash"
        const val HLC = "$ID/hlc"
    }
}
