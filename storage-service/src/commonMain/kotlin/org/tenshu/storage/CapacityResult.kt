package org.tenshu.storage

import org.tenshu.core.Composite
import org.tenshu.core.TBoolean
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.number.TInteger
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.boolean
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.int

/**
 * Result of [CheckCapacity] (`docs/proposals/storage-capacity-gating.md` §2
 * Tier 3): whether the requested bytes fit, and how many bytes remain. The
 * folder-upload pre-flight reads [fits] to decide whether to start a long job.
 * Bytes are `TInteger` (Long-backed).
 */
interface CapacityResult : Composite {

    override val schema: CompositeSchema get() = Schema

    val fits: TBoolean
        get() = getField(Fields.FITS)

    val availableBytes: TInteger
        get() = getField(Fields.AVAILABLE_BYTES)

    companion object {
        const val ID = "tenshu/storage/capacityResult"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            fields = {
                boolean(Fields.FITS)
                int(Fields.AVAILABLE_BYTES)
            },
        )

        fun create(
            context: TContext,
            fits: Boolean,
            availableBytes: Long,
        ): CapacityResult = context.create(
            type = ID,
            fields = listOf(fits, availableBytes),
        )
    }

    object Fields {
        const val FITS = "$ID/fits"
        const val AVAILABLE_BYTES = "$ID/availableBytes"
    }
}
