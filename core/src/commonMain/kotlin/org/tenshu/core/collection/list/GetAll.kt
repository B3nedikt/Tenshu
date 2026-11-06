package org.tenshu.core.collection.list

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.listType
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.type
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

suspend fun <T : Data> TList<T>.getAll(): TResult<TList<T>> {
    val operation = findOperation<GetAll<T>>(GetAll.ID)
    return operation.getAll()
        .asTaskInteractor<TList<T>>()
        .execute()
}

interface GetAll<T : Data> : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun getAll(): RequestObject

    companion object {
        const val ID = "tenshu/core/collection/list/getAll"

        val Schema: OperationSchema = operation(
            id = ID,
            receiver = listType("E"),
            typeParams = { type("E") },
            returnType = listType("E"),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }
}
