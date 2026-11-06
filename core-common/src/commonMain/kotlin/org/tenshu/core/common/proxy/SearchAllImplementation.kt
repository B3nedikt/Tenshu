package org.tenshu.core.common.proxy

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TBoolean
import org.tenshu.core.collection.list.SearchAll
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.collection.list.toTList
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.entity.Entity

internal class SearchAllImplementation : SyncOperationImplementation {

    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val predicateCallstack = (params?.get(SearchAll.Params.PREDICATE) as? TList<*>)
            ?.toList()
            ?: error("No predicate provided")

        val array = executionContext.resolveList(caller)
            ?: error("SearchAll must be called on a list. Caller: $caller")

        val allItems = recursivelyWalk(array.toList(), executionContext)
        val accumulated = mutableListOf<Data>()

        for (element in allItems) {
            val elementWithContext = if (element is Entity<*>) {
                val dataId = executionContext.getDataId(element)
                if (dataId != null) {
                    EntityDatabaseReference(element, dataId)
                } else element
            } else element

            // Phase 4 of `docs/proposals/core-error-channel.md`: predicate
            // evaluation now throws on typed failure; the wire catch at
            // `LocalTaskExecutor` wraps to `TResult.Failure`.
            val predicateResult = evaluateCallstack(
                executionContext = executionContext,
                contextData = elementWithContext as Composite,
                callstack = predicateCallstack,
                allowDeferring = false
            )

            if (predicateResult is TBoolean && predicateResult.get()) {
                accumulated.add(element)
            }
        }

        @Suppress("UNCHECKED_CAST")
        return accumulated.toTList(executionContext.context) as T
    }
}
