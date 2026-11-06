package org.tenshu.routine.proxy.impl

import org.tenshu.core.Data
import org.tenshu.core.GenericError
import org.tenshu.core.TString
import org.tenshu.core.TenshuException
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.routine.RunRoutineNow
import org.tenshu.routine.proxy.FiringResult
import org.tenshu.routine.proxy.RoutineEngineFactory

/**
 * The manual "Run now" gesture — RE-1's cheapest end-to-end proof of the engine,
 * and something users want anyway.
 *
 * ⚠ A stand-down is a *typed error*, not silence: the user pressed a button, so
 * "nothing happened" needs a reason they can read.
 */
internal class RunRoutineNowImplementation(
    private val nowEpochMs: () -> Long,
    private val thisDeviceId: () -> String?,
    private val hostsDeviceBridge: Boolean,
) : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val routineId: TString = params.read(RunRoutineNow.Params.ROUTINE_ID)

        val result = RoutineEngineFactory.create(
            executionContext = executionContext,
            implementationFactory = implementationFactory,
            nowEpochMs = nowEpochMs,
            thisDeviceId = thisDeviceId,
            // RE-3: the real roster, read from the ExecutionContext the
            // dispatcher hands us — the only place it is actually available.
            roster = RoutineEngineFactory.realmRoster(executionContext, thisDeviceId),
            hostsDeviceBridge = hostsDeviceBridge,
        ).runNow(routineId.get())

        if (result is FiringResult.StoodDown) {
            throw TenshuException(
                GenericError.create(
                    executionContext.context,
                    "Could not run routine: ${result.reason}",
                )
            )
        }

        return null as T
    }
}
