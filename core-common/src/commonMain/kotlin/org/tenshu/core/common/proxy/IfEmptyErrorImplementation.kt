package org.tenshu.core.common.proxy

import org.tenshu.core.Data
import org.tenshu.core.Maybe
import org.tenshu.core.NotFoundError
import org.tenshu.core.TenshuException
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.PureOperationImplementation
import org.tenshu.core.core.TContext

internal class IfEmptyErrorImplementation : PureOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        context: TContext,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        return when (caller) {
            is Maybe.Some<*> -> caller.value as T

            is Maybe.Empty<*> -> throw TenshuException(
                NotFoundError.create(
                    context = context,
                    message = "Required element not found"
                )
            )

            else -> error("ifEmptyError must be called on a Maybe. Caller was: $caller")
        }
    }
}
