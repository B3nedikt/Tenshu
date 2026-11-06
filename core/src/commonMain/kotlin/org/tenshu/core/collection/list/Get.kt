package org.tenshu.core.collection.list

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.listType
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.type
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

suspend operator fun <T : Data> TList<T>.get(index: Int): TResult<T> {
    val operation = findOperation<Get<T>>(Get.ID)
    return operation.get(index)
        .asTaskInteractor<T>()
        .execute()
}

interface Get<T : Data> : Operation {

    fun get(index: Int): RequestObject

    companion object {
        const val ID = "tenshu/core/collection/list/get"

        val Schema = operation(
            id = ID,
            receiver = listType("E"),
            typeParams = { type("E") },
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema)
        )
    }
}
