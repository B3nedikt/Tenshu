package org.tenshu.calendar.proxy.impl

import org.tenshu.calendar.ReminderState
import org.tenshu.calendar.SetReminderState
import org.tenshu.core.Data
import org.tenshu.core.TBoolean
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.number.TInteger

/** Upserts the per-occurrence dismissal/snooze row. */
internal class SetReminderStateImplementation : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val reminderId: TString = params.read(SetReminderState.Params.REMINDER_ID)
        val recurrenceId: TString = params.read(SetReminderState.Params.RECURRENCE_ID)
        val dismissed: TBoolean = params.read(SetReminderState.Params.DISMISSED)
        val snoozedUntil: TInteger = params.read(SetReminderState.Params.SNOOZED_UNTIL_EPOCH_MS)

        executionContext.saveData(
            ReminderState.create(
                context = executionContext.context,
                reminderId = reminderId.get(),
                recurrenceId = recurrenceId.get(),
                snoozedUntilEpochMs = snoozedUntil.get(),
                dismissed = dismissed.get(),
            )
        )
        return null as T
    }
}
