package org.tenshu.calendar.client.impl

import org.tenshu.calendar.SetReminderState
import org.tenshu.core.Data
import org.tenshu.core.common.impl.OperationDto
import org.tenshu.core.common.impl.OperationRo
import org.tenshu.core.core.TContext
import org.tenshu.core.request.RequestChain
import org.tenshu.core.request.RequestObject
import org.tenshu.core.request.roProvider
import org.tenshu.core.schema.toType
import org.tenshu.core.toTBoolean
import org.tenshu.core.number.toTInteger
import org.tenshu.core.toTString

internal class SetReminderStateRo(
    requestChain: RequestChain,
    context: TContext,
) : OperationRo(requestChain, context), SetReminderState {

    override fun setReminderState(
        reminderId: String,
        recurrenceId: String,
        dismissed: Boolean,
        snoozedUntilEpochMs: Long,
    ): RequestObject {
        val params = buildMap<String, Data> {
            put(SetReminderState.Params.REMINDER_ID, reminderId.toTString(context))
            put(SetReminderState.Params.RECURRENCE_ID, recurrenceId.toTString(context))
            put(SetReminderState.Params.DISMISSED, dismissed.toTBoolean(context))
            put(SetReminderState.Params.SNOOZED_UNTIL_EPOCH_MS, snoozedUntilEpochMs.toTInteger(context))
        }
        val nextChain = requestChain.addArray(OperationDto(params = params, schema = schema))
        return context.roProvider().createRequestObject(
            type = Data.Schema.toType(),
            requestChain = nextChain,
            context = context,
        )
    }
}
