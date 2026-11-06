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
 * The ordering point at which this device **reaped** an entity with no tombstone of its own — a
 * descendant removed with a deleted folder (`content-plane-cr2-quorumless.md` §10.12 Q4, revised and
 * accepted 2026-10-02; B2 audit gap 11).
 *
 * ## ⚠⚠ The HLC is the deleted folder's tombstone HLC T — the same on every device
 *
 * Not "now" on the reaping device, and not the child's last version this device saw: both differ per
 * device, so devices would disagree about a late block, and the relay's register with them. T is the
 * one ordering point every party can name. A block for this entity at or below T is refused on
 * ingest; one above T is accepted — on every device alike.
 *
 * ## Why it is persisted
 *
 * The in-memory marker it replaces (`TombstoneRegistry.localTombstones`) died with the process, after
 * which an absent row accepted **any** block, whatever its HLC: a reaped child came back after a
 * restart, on one device only (audit gap 11).
 *
 * ⚠ Device-local, never synced, and not a tombstone: it feeds no type count and is never pushed.
 * ⚠ A **new** composite, so no `SyncService` version moves (HR #9 bumps on a reshape); its fields are
 * positional and persisted, so any later change to them owes the bump and a migration.
 */
interface ReapWatermark : Composite {

    override val schema: CompositeSchema
        get() = Schema

    /** The reaped entity's wire name — this row's key. */
    val targetEntityIdHash: TString
        get() = getField(Fields.TARGET_ENTITY_ID_HASH)

    /** T: the HLC of the folder tombstone whose cascade reaped it. */
    val hlc: HybridLogicalClock
        get() = getField(Fields.HLC)

    companion object {
        const val ID = "tenshu/sync/reapWatermark"

        val Schema: CompositeSchema = composite(
            id = ID,
            traits = listOf(DataIdProvider.Schema),
            fields = {
                string(Fields.TARGET_ENTITY_ID_HASH)
                composite(Fields.HLC, type = HybridLogicalClock.Schema)
            }
        )

        fun create(context: TContext, targetEntityIdHash: String, hlc: HybridLogicalClock): ReapWatermark =
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
