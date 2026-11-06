package org.tenshu.routine.proxy.impl

import org.tenshu.core.Data
import org.tenshu.core.GenericError
import org.tenshu.core.TenshuException
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.routine.proxy.RoutineExecution

/**
 * `RunRoutineNow` and `EvaluateDueRoutines` on a proxy wired [RoutineExecution.NEVER].
 *
 * ⚠ A typed error, not a no-op: a "Run now" that returns success having run nothing is the one
 * answer the gesture must never get. No engine is built, so nothing is journalled, armed or
 * written.
 */
internal class RoutineExecutionRefusedImplementation : SyncOperationImplementation {

    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T = throw TenshuException(GenericError.create(executionContext.context, MESSAGE))

    internal companion object {
        const val MESSAGE = "Routines do not run on this device"
    }
}
