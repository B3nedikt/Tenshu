package org.tenshu.core.request

import org.tenshu.core.Data
import org.tenshu.core.TArray
import org.tenshu.core.TResult
import org.tenshu.core.core.TContext
import org.tenshu.core.core.dataFactory
import org.tenshu.core.core.schemaProvider

/**
 * In-memory [RequestChain] used by chain *construction* sites (proxy-side request DSL,
 * schema-side dynamic-property chain materialisation in `Dsl.kt`).
 *
 * Holds an accumulating `MutableList<Data>` of references / arrays / nested arrays.
 * `getResult()` is unsupported — execution is the consumer's concern, not the builder's.
 *
 * Was `DtoRequestChain` in `:core-common`; lifted to `:core` so the schema DSL
 * (`dynamicPropertyRelation` chain-on-schema materialisation, `core-relation-system.md`
 * Phase 4) can construct chains without depending on `:core-common`.
 */
class SimpleRequestChain(
    val context: TContext,
    private val callstack: MutableList<Data> = mutableListOf()
) : RequestChain {

    override fun addReference(id: String): RequestChain {
        val reference = context.schemaProvider().getReference(id)
        callstack.add(reference)
        return this
    }

    override fun addArray(array: TArray): RequestChain {
        callstack.add(array)
        return this
    }

    override fun addNestedArray(block: (RequestChain) -> RequestChain): RequestChain {
        val arrayChain = block(SimpleRequestChain(context))
        val arrayData = context.dataFactory().createList<Data>(arrayChain.getCallstack())
        callstack.add(arrayData)
        return this
    }

    override fun getCallstack(): List<Data> = callstack.toList()

    override suspend fun getResult(): TResult<Data> {
        error("SimpleRequestChain cannot be executed")
    }
}
