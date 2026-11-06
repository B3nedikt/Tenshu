package org.tenshu.storage

import org.tenshu.core.TError
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.number.TInteger
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.int

/**
 * Tier-2 enforcement error (`docs/proposals/storage-capacity-gating.md` §2, §3):
 * a storage-consuming op would exceed the owner's remaining quota. Carries the
 * bytes the action needed and the bytes available so the client can render a
 * precise message. Bytes are `TInteger` (Long-backed).
 */
interface InsufficientStorageError : TError {

    override val schema: CompositeSchema get() = Schema

    val requiredBytes: TInteger
        get() = getField(Fields.REQUIRED_BYTES)

    val availableBytes: TInteger
        get() = getField(Fields.AVAILABLE_BYTES)

    companion object {
        const val ID = "tenshu/storage/error/insufficient"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = TError.Schema,
            fields = {
                int(Fields.REQUIRED_BYTES)
                int(Fields.AVAILABLE_BYTES)
            },
        )

        fun create(
            context: TContext,
            requiredBytes: Long,
            availableBytes: Long,
        ): InsufficientStorageError = context.create(
            type = ID,
            fields = listOf(requiredBytes, availableBytes),
        )
    }

    object Fields {
        const val REQUIRED_BYTES = "$ID/requiredBytes"
        const val AVAILABLE_BYTES = "$ID/availableBytes"
    }
}
