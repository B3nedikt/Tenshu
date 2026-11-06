package org.tenshu.sync.common

import org.tenshu.core.Composite
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.DataIdProvider
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.boolean
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.core.toTBoolean
import org.tenshu.core.toTString

/**
 * Metadata stored locally to track the sync state of an entity.
 */
interface SyncMetadata : Composite {

    override val schema: CompositeSchema
        get() = Schema

    /**
     * The HLC timestamp of the last update to this entity.
     */
    val hlc: HybridLogicalClock
        get() = getField(Fields.HLC)

    /**
     * The stringified hash of the DataId of the entity this metadata belongs to.
     */
    val targetDataIdHash: TString
        get() = getField(Fields.TARGET_DATA_ID_HASH)

    /**
     * Whether this entity is currently in a conflicted state.
     */
    val isConflicted: org.tenshu.core.TBoolean
        get() = getField(Fields.IS_CONFLICTED)

    companion object {
        const val ID = "tenshu/sync/metadata"

        val Schema: CompositeSchema = composite(
            id = ID,
            traits = listOf(DataIdProvider.Schema),
            fields = {
                composite(Fields.HLC, type = HybridLogicalClock.Schema)
                string(Fields.TARGET_DATA_ID_HASH)
                boolean(Fields.IS_CONFLICTED)
            }
        )

        fun create(
            context: TContext,
            hlc: HybridLogicalClock,
            targetDataIdHash: String,
            isConflicted: Boolean = false
        ): SyncMetadata = context.create(
            type = ID,
            fields = listOf(
                hlc,
                targetDataIdHash.toTString(context),
                isConflicted.toTBoolean(context)
            )
        )
    }

    object Fields {
        const val HLC = "$ID/hlc"
        const val TARGET_DATA_ID_HASH = "$ID/targetDataIdHash"
        const val IS_CONFLICTED = "$ID/isConflicted"
    }
}
