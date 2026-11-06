package org.tenshu.sync.proxy.impl

import org.tenshu.core.Data
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory

/**
 * Runs the E4-5 compaction sweep (`core-schema-evolution.md` §8.3).
 *
 * ⚖️ Takes no parameters and returns nothing on purpose: it is maintenance, so
 * there is no caller decision to express and no result anyone waits on. That is
 * also what makes it honestly `HeadlessSafe`.
 */
internal class CompactSchemaImplementation(
    private val compact: suspend () -> Unit,
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        compact()
        return null as T
    }
}
