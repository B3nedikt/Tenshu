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
 * Filters a list based on a predicate, returning a new list.
 *
 * ⚠ **Fusable predicates.** Ahead of a sort and a finite window (`… .filter { … }
 * .sortBy(k).take(n).select { … }`, optionally after [traverse]) the chain runs as
 * one thin walk with a bounded top-N instead of materialise → filter → full sort
 * (`docs/proposals/core-ordered-window-fusion.md` §9). That holds only while EVERY
 * predicate in the chain is a test on one field, value property or synchronous
 * dynamic property of the element, against CONSTANT operands, with the operators
 * §9.2 allows (`equalTo`, `oneOf`, `inRange`, `contains`, `lowercase`,
 * `substringAfterLast`). Anything else — an operation on the element, a
 * non-constant operand, multi-step navigation, a reference or deferred property —
 * is still correct, but makes the whole chain fall back to full materialisation,
 * with no error to say so. Keep the predicates of a hot, windowed read inside that
 * set.
 */
@Suppress("UNCHECKED_CAST")
inline fun <reified T : Data> TList<*>.filter(
    noinline predicate: (T).() -> TBoolean
): TList<T> {
    val context = this as TContext
    // Narrow the predicate's element schema when the caller used a more specific reified
    // type than the receiver carries (e.g. `root.files.filter<Folder> { ... }`).
    // Phase 5.4 of `docs/adr/0009-operation-schema-typing.md`.
    val narrowed = context.schemaProvider().getSchema(
        context.schemaProvider().getTypeId(T::class as KClass<Data>)
    )
    val operation = findOperation<Filter<T>>(Filter.ID)
    (operation as? OperationReceiverHolder)?.narrowedElementType = narrowed
    return operation.filter(predicate)
}

interface Filter<T : Data> : Operation {

    override val schema: OperationSchema
        get() = Schema

    fun filter(predicate: (T) -> TBoolean): TList<T>

    companion object {
        const val ID = "tenshu/core/collection/list/filter"

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
