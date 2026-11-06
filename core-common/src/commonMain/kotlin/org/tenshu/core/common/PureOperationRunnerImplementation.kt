package org.tenshu.core.common

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.PureOperationRunner
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.PureOperationImplementation
import org.tenshu.core.core.TContext
import org.tenshu.core.get
import org.tenshu.core.id
import org.tenshu.core.trait.isPure

class PureOperationRunnerImplementation(
    private val tContextProvider: () -> TContext,
    private val implementationFactoryProvider: () -> OperationImplementationFactory
) : PureOperationRunner {

    private val context by lazy { tContextProvider() }
    private val implementationFactory by lazy { implementationFactoryProvider() }

    override fun <T : Data?> execute(operation: Operation, caller: Data?): T {
        if (!operation.isPure()) {
            error("PureOperationRunnerImplementation can only execute pure operations. Operation ${operation.id} is not marked as pure.")
        }

        val implementation =
            implementationFactory(operation.id)
                ?: error("No implementation found for ${operation.id}")

        return (implementation as PureOperationImplementation).execute<T>(
            context = context,
            implementationFactory = { implementationFactory(it)!! },
            caller = caller,
            params = operation.params
        )
    }
}
