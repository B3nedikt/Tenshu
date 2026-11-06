package org.tenshu.core.common.proxy

import org.tenshu.core.ChainTemplate
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.core.TContext
import org.tenshu.core.number.TInteger
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.SimpleRequestChain
import org.tenshu.core.schema.toType

/**
 * Fallback callstack materialiser invoked at resolve time by
 * [ExecutionContext.resolveDynamicProperty] when the relation schema does not carry
 * a pre-baked `chain` field.
 *
 * Two cases hit this path: (a) the relation declares runtime `params`, so the chain
 * bakes runtime values and can't be eager (`File.Relation.Preview(w, h)` style);
 * (b) the schema was deserialised from the wire, where neither the [ChainTemplate]
 * nor `chain` field are present. Param-less locally-defined relations capture their
 * chain at schema-materialisation time via `dynamicPropertyRelation(receiver = ...)`
 * (`core-relation-system.md` Phase 4).
 */
class CallstackMaterializer(
    private val schemaRegistry: SchemaRegistry,
    private val tContext: TContext
) {

    fun <T : Data, R : Data> materialize(
        type: String,
        template: ChainTemplate<T, R>,
        params: TList<Data>? = null
    ): List<Data> {
        val initialChain = SimpleRequestChain(
            context = tContext
        )

        val schema = schemaRegistry.getSchema(type)
            ?: error("No schema registered for type: $type")
        val requestObject = schemaRegistry.createRequestObject<Data>(
            type = schema.toType(),
            requestChain = initialChain,
            context = tContext
        )

        @Suppress("UNCHECKED_CAST")
        val result =
            template.block.invoke(requestObject as T, params?.toList()?.unwrap() ?: emptyList())

        return result.getRequestChain()?.getCallstack() ?: emptyList()
    }

    private fun Data.getRequestChain(): RequestChain? {
        if (this is RequestObject) return this.requestChain
        if (this is TResult.Success<*>) {
            val value = this.value
            if (value is Data) return value.getRequestChain()
        }
        return null
    }

    fun List<Data>.unwrap(): List<Any> {
        return map {
            when (it) {
                is TInteger -> it.get()
                is TList<*> -> it.toList()
                else -> it
            }
        }
    }
}
