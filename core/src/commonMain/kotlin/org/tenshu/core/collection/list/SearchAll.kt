package org.tenshu.core.collection.list

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.TBoolean
import org.tenshu.core.core.OperationReceiverHolder
import org.tenshu.core.core.TContext
import org.tenshu.core.core.findOperation
import org.tenshu.core.core.schemaProvider
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.list
import org.tenshu.core.schema.listType
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.type
import kotlin.reflect.KClass

/**
 * Deeply searches a list and its children for all elements matching a predicate.
 */
@Suppress("UNCHECKED_CAST")
inline fun <reified T : Data> TList<*>.searchAll(
    noinline predicate: (T).() -> TBoolean
): TList<T> {
    val context = this as TContext
    val narrowed = context.schemaProvider().getSchema(
        context.schemaProvider().getTypeId(T::class as KClass<Data>)
    )
    val operation = findOperation<SearchAll<T>>(SearchAll.ID)
    (operation as? OperationReceiverHolder)?.narrowedElementType = narrowed
    return operation.searchAll(predicate)
}

interface SearchAll<T : Data> : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun searchAll(predicate: (T) -> TBoolean): TList<T>

    companion object {
        const val ID = "tenshu/core/collection/list/searchAll"

        val Schema = operation(
            id = ID,
            receiver = listType("E"),
            typeParams = { type("E") },
            params = {
                list(id = Params.PREDICATE)
            },
            returnType = listType("E")
        )
    }

    object Params {
        const val PREDICATE = "$ID/predicate"
    }
}
