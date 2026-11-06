package org.tenshu.core.common.request

import org.tenshu.core.Data
import org.tenshu.core.TArray
import org.tenshu.core.TResult
import org.tenshu.core.core.TContext
import org.tenshu.core.core.dataFactory
import org.tenshu.core.core.schemaProvider
import org.tenshu.core.request.RequestChain

data class RequestChainBuilder(
    val context: TContext,
    private val callstack: List<Data> = emptyList()
) : RequestChain {

    override fun addReference(id: String): RequestChain {
        val reference = context.schemaProvider().getReference(id)
        return copy(callstack = callstack + reference)
    }

    override fun addArray(array: TArray): RequestChain {
        return copy(callstack = callstack + array)
    }

    override fun addNestedArray(block: (RequestChain) -> RequestChain): RequestChain {
        val arrayChain = block(RequestChainBuilder(context))
        val arrayData = context.dataFactory().createList<Data>(arrayChain.getCallstack())
        return addArray(arrayData)
    }

    override fun getCallstack(): List<Data> = callstack

    override suspend fun getResult(): TResult<Data> {
        error("RequestChainBuilder cannot be executed")
    }
}
