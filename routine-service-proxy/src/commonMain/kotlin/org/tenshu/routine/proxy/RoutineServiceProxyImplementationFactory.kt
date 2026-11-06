package org.tenshu.routine.proxy

import org.tenshu.core.common.proxy.executor.OperationImplementation
import org.tenshu.routine.DeleteRoutine
import org.tenshu.routine.EvaluateDueRoutines
import org.tenshu.routine.Routine
import org.tenshu.routine.RunRoutineNow
import org.tenshu.routine.SaveRoutine
import org.tenshu.routine.SetRoutineEnabled
import org.tenshu.routine.proxy.impl.DeleteRoutineImplementation
import org.tenshu.routine.proxy.impl.EvaluateDueRoutinesImplementation
import org.tenshu.routine.proxy.impl.RoutineExecutionRefusedImplementation
import org.tenshu.routine.proxy.impl.RunRoutineNowImplementation
import org.tenshu.routine.proxy.impl.SaveRoutineImplementation
import org.tenshu.routine.proxy.impl.SetRoutineEnabledImplementation
import org.tenshu.sync.common.TombstoneEmitter

/**
 * Whether this proxy may run routines at all. A platform decision made at the wiring, never a
 * row: [NEVER] still stores, lists and edits routines, and builds no engine.
 *
 * ⚠⚠ [NEVER] is local only. Other devices build their roster from `Device` rows, which carry no
 * platform signal, so they still count this device as an eligible executor
 * (`routines-engine.md` RE-D13).
 */
enum class RoutineExecution { EXECUTES, NEVER }

/**
 * @param nowEpochMs proxy wall clock — stamps [Routine.createdAtEpochMs] /
 *        [Routine.updatedAtEpochMs].
 * @param authorDeviceId this device's realm device id, `null` while unpaired
 *        (recorded as `""`). Read later by the T-RE-02 rule: revocation removes
 *        a *device*, not its writes, so a routine authored on a
 *        stolen-while-unlocked phone must be suspendable by author.
 * @param execution ⚠⚠ required, deliberately without a default: a platform that forgot to
 *        decide would otherwise execute, or silently not.
 *
 * @param tombstoneEmitter what makes `DeleteRoutine` reach every device. ⚠⚠ Required, deliberately
 *        without a default (Calendar's `null` default hid exactly this gap): `null` makes the
 *        delete device-local, acceptable only in a sync-less test proxy.
 *
 * ⭐ `RunRoutineNow` and `EvaluateDueRoutines` now resolve to the engine (§4.9).
 */
fun routineServiceProxyImplementationFactory(
    id: String,
    nowEpochMs: () -> Long,
    authorDeviceId: () -> String?,
    execution: RoutineExecution,
    tombstoneEmitter: TombstoneEmitter?,
    hostsDeviceBridge: Boolean = false,
): OperationImplementation? {
    if (execution == RoutineExecution.NEVER && (id == RunRoutineNow.ID || id == EvaluateDueRoutines.ID)) {
        return RoutineExecutionRefusedImplementation()
    }
    return when (id) {
        SaveRoutine.ID -> SaveRoutineImplementation(nowEpochMs, authorDeviceId)
        DeleteRoutine.ID -> DeleteRoutineImplementation(tombstoneEmitter)
        SetRoutineEnabled.ID -> SetRoutineEnabledImplementation(nowEpochMs)
        RunRoutineNow.ID -> RunRoutineNowImplementation(
            nowEpochMs = nowEpochMs,
            thisDeviceId = authorDeviceId,
            hostsDeviceBridge = hostsDeviceBridge,
        )

        EvaluateDueRoutines.ID -> EvaluateDueRoutinesImplementation(
            nowEpochMs = nowEpochMs,
            thisDeviceId = authorDeviceId,
            hostsDeviceBridge = hostsDeviceBridge,
        )

        else -> null
    }
}
