package org.tenshu.core.collection.list

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.core.findOperation
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.int
import org.tenshu.core.schema.listType
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.type

fun <T : Data> TList<T>.take(n: Int): TList<T> {
    val operation = findOperation<Take<T>>(Take.ID)
    return operation.take(n)
}

interface Take<T : Data> : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun take(n: Int): TList<T>

    companion object {
        const val ID = "tenshu/core/collection/list/take"

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
