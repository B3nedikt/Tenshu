package org.tenshu.core.trait

import org.tenshu.core.Operation
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.hasTrait
import org.tenshu.core.schema.trait

/**
 * Trait that marks an operation as performing I/O.
 * Such operations are executed asynchronously and are tied to the request lifecycle.
 */
interface IoOperation : Trait {
    companion object {
        const val ID = "tenshu/core/operation/io"
        val Schema: TraitSchema = trait(ID)
    }
}

fun Operation.isIo(): Boolean =
    schema.isIo()

fun OperationSchema.isIo(): Boolean =
    hasTrait(IoOperation.ID)
