package org.tenshu.core.trait

import org.tenshu.core.Operation
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.hasTrait
import org.tenshu.core.schema.trait

/**
 * Trait that marks an operation as a background task.
 * These operations may outlive the individual request.
 */
interface BackgroundOperation : Trait {
    companion object {
        const val ID = "tenshu/core/operation/background"
        val Schema: TraitSchema = trait(ID)
    }
}

fun Operation.isBackground(): Boolean =
    schema.isBackground()

fun OperationSchema.isBackground(): Boolean =
    hasTrait(BackgroundOperation.ID)
