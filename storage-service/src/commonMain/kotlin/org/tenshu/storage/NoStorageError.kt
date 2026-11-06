package org.tenshu.storage

import org.tenshu.core.TError
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.composite

/**
 * Tier-2 enforcement error (`docs/proposals/storage-capacity-gating.md` §2, §3):
 * a storage-consuming op was attempted with no storage account bound. Thrown by
 * the proxy op impls via the capacity guard; surfaced to the client as a typed
 * failure. Fieldless marker.
 */
interface NoStorageError : TError {

    override val schema: CompositeSchema get() = Schema

    companion object {
        const val ID = "tenshu/storage/error/no-storage"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = TError.Schema,
            fields = { },
        )

        fun create(context: TContext): NoStorageError = context.create(
            type = ID,
            fields = emptyList(),
        )
    }
}
