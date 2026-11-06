package org.tenshu.core.collection.list

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.core.findOperation
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.int
import org.tenshu.core.schema.listType
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.type

fun <T : Data> TList<T>.drop(n: Int): TList<T> {
    val operation = findOperation<Drop<T>>(Drop.ID)
    return operation.drop(n)
}

interface Drop<T : Data> : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun drop(n: Int): TList<T>

    companion object {
        const val ID = "tenshu/core/collection/list/drop"

        val Schema = operation(
            id = ID,
            receiver = listType("E"),
            typeParams = { type("E") },
            params = {
                int(Params.N)
            },
            returnType = listType("E")
        )
    }

    object Params {
        const val N = "$ID/n"
    }
}
