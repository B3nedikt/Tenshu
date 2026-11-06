package org.tenshu.routine.proxy.impl

import org.tenshu.core.Data
import org.tenshu.core.GenericError
import org.tenshu.core.TBoolean
import org.tenshu.core.TString
import org.tenshu.core.TenshuException
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.entity.DataId
import org.tenshu.routine.Routine
import org.tenshu.routine.SetRoutineEnabled

/**
 * Read-modify-write of [Routine.enabled] — the **user's** switch.
 *
 * ⚠⚠ The engine never routes through here. Writing `enabled = false` for a
 * runaway would be indistinguishable from the user's own toggle (RE-D9);
 * suspension is [Routine.STATE_SUSPENDED] with a reason.
 *
 * Enabling also clears a suspension, because "re-enable" is one gesture in the
 * UI and the user has, by acting, acknowledged the explanation.
 */
internal class SetRoutineEnabledImplementation(
    private val nowEpochMs: () -> Long,
) : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?
    ): T {
        val routineId: TString = params.read(SetRoutineEnabled.Params.ROUTINE_ID)
        val enabled: TBoolean = params.read(SetRoutineEnabled.Params.ENABLED)

        val context = executionContext.context
        val existing = executionContext.database.getData(
            DataId.StringDataId.create(context = context, type = Routine.ID, keyValue = routineId.get())
        ) as? Routine
            ?: throw TenshuException(
                GenericError.create(context, "Unknown routine: ${routineId.get()}")
            )

        val clearsSuspension = enabled.get() && existing.state.get() == Routine.STATE_SUSPENDED
        if (existing.enabled.get() == enabled.get() && !clearsSuspension) {
            // No-op write would only burn a sync block.
            return null as T
        }

        val updated = Routine.create(
            context = context,
            routineId = existing.routineId.get(),
            name = existing.name.get(),
            enabled = enabled.get(),
            state = if (clearsSuspension) Routine.STATE_ACTIVE else existing.state.get(),
            suspendedReason = if (clearsSuspension) "" else existing.suspendedReason.get(),
            timeZoneId = existing.timeZoneId.get(),
            homeLatE5 = existing.homeLatE5.get(),
            homeLonE5 = existing.homeLonE5.get(),
            authorDeviceId = existing.authorDeviceId.get(),
            createdAtEpochMs = existing.createdAtEpochMs.get(),
            updatedAtEpochMs = nowEpochMs(),
            programJson = existing.programJson.get(),
            programVersion = existing.programVersion.get(),
            extras = existing.extras,
        )

        executionContext.saveData(updated)

        return null as T
    }
}
