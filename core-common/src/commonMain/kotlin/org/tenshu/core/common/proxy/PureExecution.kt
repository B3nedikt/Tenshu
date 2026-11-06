package org.tenshu.core.common.proxy

import org.tenshu.core.Data
import org.tenshu.core.Operation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.PureOperationImplementation
import org.tenshu.core.core.TContext
import org.tenshu.core.id
import org.tenshu.core.common.proxy.executor.implementationFactory as defaultExecutorFactory

fun <T : Data> Operation.executePure(
    context: TContext,
    caller: Data? = null,
    factory: OperationImplementationFactory = OperationImplementationFactory { id ->
        defaultExecutorFactory(id) ?: error("No executor found for $id")
    }
): T {
    val executor = factory(this.id) ?: error("No executor found for ${this.id}")
    if (executor !is PureOperationImplementation) {
        error("Operation ${this.id} is not a pure operation")
    }

    return executor.execute(
        context = context,
        implementationFactory = factory,
        caller = caller,
        params = this.params
    )
}
