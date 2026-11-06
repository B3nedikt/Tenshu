package org.tenshu.core.collection.list

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.core.findOperation
import org.tenshu.core.number.TInteger
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.listType
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.toType
import org.tenshu.core.schema.type

/**
 * The number of elements in this list, **without materialising them**.
 *
 * Proxy-side a DB-backed list resolves this from the array's member-hash
 * count (an O(1)-per-member read of the list's own row — no element
 * deserialisation), which is what makes it the right way to surface
 * "N items" counters for large collections; a `size`-via-read pays a full
 * deserialisation of every element. Falls back to counting a materialised
 * list when the receiver isn't DB-backed.
 */
fun <T : Data> TList<T>.count(): TInteger {
    val operation = findOperation<Count<T>>(Count.ID)
    return operation.count()
}

interface Count<T : Data> : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun count(): TInteger

    companion object {
        const val ID = "tenshu/core/collection/list/count"

        val Schema = operation(
            id = ID,
            receiver = listType("E"),
            typeParams = { type("E") },
            returnType = TInteger.Schema.toType(),
        )
    }
}
