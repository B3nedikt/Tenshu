package org.tenshu.core

import org.tenshu.core.core.findOperation
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.maybeType
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.type
import org.tenshu.core.trait.ShortcutOperation

fun <T : Data> Maybe<T>.ifEmptyError(): T {
    val operation = findOperation<IfEmptyError<T>>(IfEmptyError.ID)
    return operation.ifEmptyError()
}

interface IfEmptyError<out T : Data> : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun ifEmptyError(): T

    companion object {
        const val ID = "tenshu/core/maybe/ifEmptyError"

        val Schema: OperationSchema = operation(
            id = ID,
            receiver = maybeType("E"),
            typeParams = { type("E") },
            params = {},
            traits = listOf(ShortcutOperation.Schema)
        )
    }
}
