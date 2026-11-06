package org.tenshu.core.common.proxy

import org.tenshu.core.Data
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.number.toTInteger

/**
 * In-memory fallback for `count()`: counts a materialised list. The hot
 * path never gets here — [evaluateCallstack] resolves `count` on a raw
 * DB-backed array from the member-hash list (no deserialisation) before
 * the materialise-and-dispatch machinery runs.
 */
internal class ListCountImplementation : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val array = executionContext.resolveList(caller)
            ?: error("Count must be called on a list. Caller: $caller")

        return array.count().toTInteger(executionContext.context) as T
    }
}
