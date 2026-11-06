package org.tenshu.sync

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.core.toTString

/**
 * One per-entity delta inside a [DeltaBatchPayload] — the task-scoped sync
 * lane's unit of merge (`core-background-processing.md` §10.2).
 *
 * Carries exactly the information a standalone [DataBlockPayload]-bearing
 * [SyncBlock] carries for one entity, folded into the container:
 *
 * - [hlc] — the delta's **own** clock. Batching is not semantic fusion:
 *   the receiver runs each delta through the ordinary per-entity
 *   HLC/merge cycle (`SyncProcessor.resolveConflictAndSave`) with *this*
 *   HLC, so a concurrent organic edit to one contained entity on another
 *   device resolves exactly as it would against a standalone block.
 * - [targetEntityIdHash] — the standalone block's plaintext routing hash,
 *   moved *inside* the ciphertext (the container block's own
 *   `SyncBlock.targetEntityIdHash` is null — §10.2 D2). Required for
 *   property deltas (the [attachPropertyAndSave] path resolves the parent
 *   by hash); optional for entity deltas, whose DataId is derived from
 *   [value] itself.
 * - [propertyId] / [value] — same semantics as [DataBlockPayload]:
 *   `propertyId == null` ⇒ [value] is the entity; non-null names the
 *   property slot on the parent entity.
 */
interface SyncDelta : Composite {

    override val schema: CompositeSchema
        get() = Schema

    /** This delta's own HLC — drives the per-entity merge on the receiver. */
    val hlc: HybridLogicalClock
        get() = getField(Fields.HLC)

    /**
     * The parent entity's DataId hash. Inside the ciphertext, unlike the
     * standalone block's wire-metadata hash. Required when [propertyId]
     * is set; optional (derivable from [value]) for entity deltas.
     */
    val targetEntityIdHash: TString?
        get() = getOptionalField(Fields.TARGET_ENTITY_ID_HASH)

    /** `null` ⇒ entity delta; non-null names the property slot. */
    val propertyId: TString?
        get() = getOptionalField(Fields.PROPERTY_ID)

    /** The entity or property value, exactly as [DataBlockPayload.value]. */
    val value: Data
        get() = getField(Fields.VALUE)

    /**
     * Unique id of this delta — the [org.tenshu.core.entity.DataIdProvider]
     * key, mirroring [SyncBlock.blockId]. Exists so a staged delta is
     * addressable in the durable lane journal
     * (`SyncService.Collections.PENDING_LANE_DELTAS`, §10.3 D6) — array
     * removal is by element DataId. Receivers ignore it (merge keys on
     * [targetEntityIdHash]/[hlc], never on delta identity).
     */
    val deltaId: TString
        get() = getField(Fields.DELTA_ID)

    companion object {
        const val ID = "tenshu/sync/syncDelta"

        val Schema: CompositeSchema = composite(
            id = ID,
            traits = listOf(org.tenshu.core.entity.DataIdProvider.Schema),
            fields = {
                composite(Fields.HLC, type = HybridLogicalClock.Schema)
                string(Fields.TARGET_ENTITY_ID_HASH, isOptional = true)
                string(Fields.PROPERTY_ID, isOptional = true)
                composite(Fields.VALUE, type = Data.Schema)
                string(Fields.DELTA_ID)
            }
        )

        fun create(
            context: TContext,
            hlc: HybridLogicalClock,
            value: Data,
            targetEntityIdHash: String? = null,
            propertyId: String? = null,
            deltaId: String,
        ): SyncDelta = context.create(
            type = ID,
            fields = listOf(
                hlc,
                targetEntityIdHash?.toTString(context),
                propertyId?.toTString(context),
                value,
                deltaId.toTString(context)
            )
        )
    }

    object Fields {
        const val HLC = "$ID/hlc"
        const val TARGET_ENTITY_ID_HASH = "$ID/targetEntityIdHash"
        const val PROPERTY_ID = "$ID/propertyId"
        const val VALUE = "$ID/value"
        const val DELTA_ID = "$ID/deltaId"
    }
}
