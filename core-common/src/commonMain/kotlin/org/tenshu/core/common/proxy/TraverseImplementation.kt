package org.tenshu.core.common.proxy

import org.tenshu.core.Data
import org.tenshu.core.collection.list.toList
import org.tenshu.core.collection.list.toTList
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation

/**
 * `traverse` — returns every node reachable by recursively descending the
 * caller's nested lists (the receiver's elements + all descendants), flattened.
 * Same depth-first walk as `searchAll` but with no predicate; both share
 * [recursivelyWalk].
 */
internal class TraverseImplementation : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val array = executionContext.resolveList(caller)
            ?: error("traverse must be called on a list. Caller: $caller")

        val all = recursivelyWalk(array.toList(), executionContext)

        return all.toTList(executionContext.context) as T
    }
}
