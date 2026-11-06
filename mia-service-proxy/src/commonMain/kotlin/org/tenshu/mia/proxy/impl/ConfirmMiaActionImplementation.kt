package org.tenshu.mia.proxy.impl

import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.AsyncOperationImplementation
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.util.runCatchingCancellable
import org.tenshu.core.get
import org.tenshu.core.toTString
import org.tenshu.home.device.DeviceCommand
import org.tenshu.home.device.IssueDeviceCommand
import org.tenshu.mia.ConfirmMiaAction
import org.tenshu.mia.MiaAnswer
import org.tenshu.calendar.SaveEvent
import org.tenshu.core.toTBoolean
import org.tenshu.mia.proxy.MiaActionRegistry
import org.tenshu.mia.proxy.dispatchOperation
import org.tenshu.mia.proxy.PendingMiaAction
import org.tenshu.routine.RunRoutineNow

/**
 * The tap (`mia-query-layer.md` MQ-D7). Consumes the token, issues the command, and answers.
 *
 * ⚠⚠ **Dispatches to the shipped `IssueDeviceCommand` implementation rather than writing a
 * `DeviceCommand` row itself.** That op stamps `issuedAtEpochMs` and `issuedByDeviceId`, which
 * are what the hub's 2-minute staleness window and its consumed journal key off. Re-writing
 * the row here would duplicate those rules in a second place and let them drift — and the
 * whole point of MQ-D7's "reuse `DeviceControlSource`" is that Mia inherits the correlation
 * semantics rather than inventing a parallel set.
 *
 * ⚠ Everything it can honestly claim is that the command was **issued**. Whether the bulb
 * moved arrives later on the device's state row, correlated by `commandId` — the
 * `DeviceControlSource` contract, unchanged.
 */
internal class ConfirmMiaActionImplementation(
    private val actions: MiaActionRegistry,
    private val newCommandId: () -> String,
) : AsyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val token = (params?.get(ConfirmMiaAction.Params.TOKEN) as? TString)?.get().orEmpty()

        val action = actions.take(token)
            ?: return answer(
                executionContext,
                // ⚠ Says the shape of the problem rather than "failed". An expired or
                // already-tapped confirmation is an ordinary thing to hit, and the useful
                // instruction is to ask again — not to retry the tap.
                "That's no longer waiting to be confirmed — ask me again if you still want it.",
            ) as T

        // ⚠ `IssueDeviceCommand` is a SYNC implementation — it writes one row and returns.
        // Assuming async here cast-failed at runtime, a long way from the assumption; the
        // dispatcher normally hides which kind an op is, and a direct call does not.
        if (action is PendingMiaAction.Event) return saveEvent(executionContext, implementationFactory, action) as T
        if (action is PendingMiaAction.Routine) {
            return runRoutine(executionContext, implementationFactory, action) as T
        }
        action as PendingMiaAction.Device

        val commandId = newCommandId()
        val issued = dispatchOperation(
            operationId = IssueDeviceCommand.ID,
            executionContext = executionContext,
            implementationFactory = implementationFactory,
            params = mapOf(
                IssueDeviceCommand.Params.COMMAND_ID to commandId.toTString(executionContext.context),
                IssueDeviceCommand.Params.TARGET_DEVICE_ID to
                    action.deviceId.toTString(executionContext.context),
                IssueDeviceCommand.Params.VERB to
                    DeviceCommand.VERB_SET_POWER.toTString(executionContext.context),
                IssueDeviceCommand.Params.ARG_VALUE to
                    (if (action.on) DeviceCommand.ARG_ON else DeviceCommand.ARG_OFF)
                        .toTString(executionContext.context),
            ),
        )
        if (!issued) {
            return answer(executionContext, "I can't reach your smart-home devices from here.") as T
        }

        return answer(
            executionContext,
            if (action.on) {
                "Turning on ${action.deviceName}."
            } else {
                "Turning off ${action.deviceName}."
            },
        ) as T
    }

    /**
     * Fires the routine the user confirmed (`routines-engine.md` RE-5).
     *
     * ⚠⚠ **By id, and the name is only for the acknowledgement.** The id was resolved when the cell
     * was shown; re-resolving the name here would let a routine renamed in between fire under a
     * title nobody agreed to — the same rule `saveEvent` follows by passing its resolved slots
     * rather than re-reading the defaults.
     *
     * ⚠ `RunRoutineNow` deliberately bypasses the RE-D2 executor claim: the user is here, on this
     * device, asking for it now. It still evaluates conditions, honours the safety gate and writes
     * a `RoutineRun` row, so what Mia fires is a firing like any other — not a side door.
     */
    private suspend fun runRoutine(
        executionContext: ExecutionContext,
        implementationFactory: OperationImplementationFactory,
        action: PendingMiaAction.Routine,
    ): MiaAnswer {
        val fired = dispatchOperation(
            operationId = RunRoutineNow.ID,
            executionContext = executionContext,
            implementationFactory = implementationFactory,
            params = mapOf(
                RunRoutineNow.Params.ROUTINE_ID to
                    action.routineId.toTString(executionContext.context),
            ),
        )
        if (!fired) {
            return answer(executionContext, "I couldn't run \u201C${action.routineName}\u201D just now.")
        }
        return answer(executionContext, "Running \u201C${action.routineName}\u201D.")
    }

    /**
     * ⚠⚠ Passes every resolved slot **explicitly** — calendar, end time, zone — rather than
     * letting `SaveEvent` re-derive its defaults. The user agreed to a specific row; re-reading
     * the preferences at execution would let a default changed in between silently move the
     * event somewhere they never saw.
     */
    private suspend fun saveEvent(
        executionContext: ExecutionContext,
        implementationFactory: OperationImplementationFactory,
        action: PendingMiaAction.Event,
    ): MiaAnswer {
        val context = executionContext.context
        val saved = runCatchingCancellable {
            dispatchOperation(
                operationId = SaveEvent.ID,
                executionContext = executionContext,
                implementationFactory = implementationFactory,
                params = mapOf(
                    SaveEvent.Params.EVENT_ID to newCommandId().toTString(context),
                    SaveEvent.Params.TITLE to action.title.toTString(context),
                    SaveEvent.Params.START_LOCAL to action.startLocal.toTString(context),
                    SaveEvent.Params.END_LOCAL to action.endLocal.toTString(context),
                    SaveEvent.Params.CALENDAR_ID to action.calendarId.toTString(context),
                    SaveEvent.Params.TIME_ZONE_ID to action.timeZoneId.toTString(context),
                    SaveEvent.Params.ALL_DAY to false.toTBoolean(context),
                ),
            )
        }.getOrDefault(false)
        if (!saved) {
            // ⚠ Names the failure rather than reporting success. A write that silently did
            // nothing is the worst outcome available here.
            return answer(executionContext, "I couldn't add that to your calendar.")
        }

        return answer(executionContext, "Added “${action.title}” to ${action.calendarName}.")
    }

    private fun answer(executionContext: ExecutionContext, text: String): MiaAnswer =
        MiaAnswer.create(
            context = executionContext.context,
            // ⚠ `answered` with no hits, not `confirm`: the tap is spent either way, so a
            // surface that re-rendered a confirmation button would offer to do it twice.
            verdict = MiaAnswer.VERDICT_ANSWERED,
            explanation = text,
        )
}
