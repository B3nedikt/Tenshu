package org.tenshu.core.collection.list

import org.tenshu.core.Data
import org.tenshu.core.Maybe
import org.tenshu.core.Operation
import org.tenshu.core.TBoolean
import org.tenshu.core.core.findOperation
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.listType
import org.tenshu.core.schema.maybeType
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.type

fun <T : Data> TList<T>.find(predicate: (T) -> TBoolean): Maybe<T> =
    findOperation<Find<T>>(Find.ID).find(predicate)

interface Find<T : Data> : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun find(predicate: (T) -> TBoolean): Maybe<T>

    companion object {
        const val ID = "tenshu/core/collection/list/find"

        val Schema: OperationSchema = operation(
            id = ID,
            receiver = listType("E"),
            typeParams = { type("E") },
            returnType = maybeType("E")
        )
    }
}
