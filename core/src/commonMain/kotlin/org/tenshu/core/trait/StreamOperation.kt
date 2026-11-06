package org.tenshu.core.trait

import org.tenshu.core.Operation
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.hasTrait
import org.tenshu.core.schema.trait

/**
 * Trait that marks an operation as a stream.
 */
interface StreamOperation : Trait {
    companion object {
        const val ID = "tenshu/core/operation/stream"
        val Schema: TraitSchema = trait(ID)
    }
}

fun Operation.isStream(): Boolean =
    schema.isStream()

fun OperationSchema.isStream(): Boolean =
    hasTrait(StreamOperation.ID)
