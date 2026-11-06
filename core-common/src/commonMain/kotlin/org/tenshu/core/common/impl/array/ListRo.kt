package org.tenshu.core.common.impl.array

import org.tenshu.core.Data
import org.tenshu.core.collection.list.TList
import org.tenshu.core.core.TComponent
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.DataSchema

/**
 * Phase 5.1 of `docs/adr/0009-operation-schema-typing.md`: [elementType] is the construction-time
 * element schema, derived by the registered factory from the requested `Type.params`.
 */
open class ListRo<out T : Data>(
    override val context: TContext,
    override val requestChain: RequestChain,
    val elementType: DataSchema = Data.Schema,
) : TList<T>, TContext, RequestObject {

    override val components: Map<String, TComponent>
        get() = context.components

    override fun getElement(index: Int): T {
        error("ListRo.getElement not implemented")
    }

    override fun <T : Data> getField(reference: String): T {
        return getOptionalField(reference) ?: error("Field $reference not found in ListRo")
    }

    override fun <T : Data> getOptionalField(reference: String): T? {
        return null
    }
}
