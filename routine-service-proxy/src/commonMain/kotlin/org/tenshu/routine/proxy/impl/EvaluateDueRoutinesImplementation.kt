package org.tenshu.routine.proxy.impl

import org.tenshu.core.Data
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.routine.proxy.RoutineEngineFactory

/**
 * The engine's scheduled wake (`routines-engine.md` §4.9). Parameter-less by
 * design — it finds its own work.
 *
 * ⚠⚠ Carries `HeadlessSafe` on its schema; `ScheduleImplementation` is
 * fail-closed and would refuse to arm it otherwise.
 */
internal class EvaluateDueRoutinesImplementation(
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
        RoutineEngineFactory.create(
            executionContext = executionContext,
            implementationFactory = implementationFactory,
            nowEpochMs = nowEpochMs,
            thisDeviceId = thisDeviceId,
            // RE-3: the real roster, read from the ExecutionContext the
            // dispatcher hands us — the only place it is actually available.
            roster = RoutineEngineFactory.realmRoster(executionContext, thisDeviceId),
            hostsDeviceBridge = hostsDeviceBridge,
        ).evaluateDue()

        return null as T
    }
}
