package org.tenshu.core.common.proxy

import org.tenshu.core.Composite
import org.tenshu.core.Data
import org.tenshu.core.TBoolean
import org.tenshu.core.toTBoolean
import org.tenshu.core.collection.list.Filter
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.collection.list.toTList
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation

internal class FilterImplementation : SyncOperationImplementation {

    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val predicateCallstack = (params?.get(Filter.Params.PREDICATE) as? TList<*>)
            ?.toList()
            ?: error("No predicate provided")

        val array = executionContext.resolveList(caller)
            ?: error("Filter must be called on a list. Caller: $caller")

        return walkAndFilter(
            executionContext = executionContext,
            items = array.toList(),
            predicateCallstack = predicateCallstack,
            accumulated = mutableListOf()
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Data> walkAndFilter(
        executionContext: ExecutionContext,
        items: List<Data>,
        predicateCallstack: List<Data>,
        accumulated: MutableList<Data>,
        itemIndex: Int = 0
    ): T {
        for (i in itemIndex until items.size) {
            val element = items[i]

            // Phase 4 of `docs/proposals/core-error-channel.md`: evaluateCallstack
            // returns the value directly and throws TenshuException on typed
            // failure. The wire catch at `LocalTaskExecutor` wraps to
            // `TResult.Failure`; for async-resumption the callsite below catches
            // and re-throws so the AsyncReference resumer gets the same shape.
            val predicateData = evaluatePredicateOn(executionContext, element, predicateCallstack)
            if (predicateData is AsyncReference) {
                return AsyncReference.create(
                    asyncCall = {
                        val isMatch = (predicateData.asyncCall.await() as TBoolean).get()
                        if (isMatch) accumulated.add(element)
                        // Resume at next item
                        walkAndFilter<Data>(
                            executionContext,
                            items,
                            predicateCallstack,
                            accumulated,
                            i + 1
                        )
                    },
                    remainingCallstack = emptyList()
                ) as T
            }

            if ((predicateData as TBoolean).get()) {
                accumulated.add(element)
            }
        }

        return accumulated.toTList(executionContext.context) as T
    }
}

/**
 * Evaluate one `filter` predicate against one [element] — exactly as [FilterImplementation] does
 * per row: an entity is wrapped in an [EntityDatabaseReference] so the predicate's property reads
 * can address it, anything else is passed as it is.
 *
 * ⚠ Shared with the filter-aware ordered-window fusion, which falls back to it for any row whose
 * predicate it cannot answer off the thin row — so the fallback is this code, not a copy of it.
 */
internal fun evaluatePredicateOn(
    executionContext: ExecutionContext,
    element: Data,
    predicateCallstack: List<Data>,
): Data? {
    val elementWithContext = if (element is org.tenshu.core.entity.Entity<*>) {
        val dataId = executionContext.getDataId(element)
        if (dataId != null) {
            EntityDatabaseReference(element, dataId)
        } else element
    } else element

    return try {
        evaluateCallstack(
            executionContext = executionContext,
            contextData = elementWithContext as Composite,
            callstack = predicateCallstack,
            allowDeferring = false
        )
    } catch (_: AbsentPropertyException) {
        // ⚠⚠ A row without the stored property does not match — it is not an error that fails the
        // whole read (`photos-app.md` §40). `contentHash oneOf …` over a realm walks every folder
        // and every file uploaded before hashes were stamped; raising there made the predicate
        // unusable on any real realm. Only an ABSENT stored value is read this way: an unresolvable
        // dynamic property, reference property or entity relation still raises.
        false.toTBoolean(executionContext.context)
    }
}
