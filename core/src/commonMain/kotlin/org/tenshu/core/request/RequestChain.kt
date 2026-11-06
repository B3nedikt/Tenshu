package org.tenshu.core.request

import org.tenshu.core.Data
import org.tenshu.core.TArray
import org.tenshu.core.TResult

/**
 * A builder for a chain of operations to be executed on the proxy side.
 */
interface RequestChain {

    fun addReference(id: String): RequestChain

    fun addArray(array: TArray): RequestChain

    fun addNestedArray(block: (RequestChain) -> RequestChain): RequestChain

    fun getCallstack(): List<Data>

    /**
     * Executes the chain and returns the result.
     * This is the boundary between Building and Executing.
     */
    suspend fun getResult(): TResult<Data>
}
