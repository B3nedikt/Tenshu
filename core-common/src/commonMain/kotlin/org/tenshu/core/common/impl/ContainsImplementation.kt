package org.tenshu.core.common.impl

import org.tenshu.core.Contains
import org.tenshu.core.Data
import org.tenshu.core.TBoolean
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.containsLogic

internal class ContainsImplementation : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {

        checkNotNull(caller)

        val other = params
            ?.get(Contains.Params.OTHER)
            ?: error("Param ${Contains.Params.OTHER} not provided")

        // Optional — absent means the (default) case-sensitive match.
        val ignoreCase = (params[Contains.Params.IGNORE_CASE] as? TBoolean)?.get() ?: false

        return containsLogic(caller as Data, other, ignoreCase) as T
    }
}
