package org.tenshu.calendar.proxy.impl

import org.tenshu.calendar.EventOverride
import org.tenshu.calendar.SaveEventOverride
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.executor.read

/**
 * Upserts an [EventOverride].
 *
 * ⚠⚠ Absent params stay **absent**, they are not coerced to `""`. That is the
 * whole point of the row's per-field optionality: `null` inherits from the
 * series, `""` overrides with an empty value, and flattening the two here would
 * make it impossible to clear a field and would silently rewrite every
 * unmentioned field to blank.
 */
internal class SaveEventOverrideImplementation : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val eventId: TString = params.read(SaveEventOverride.Params.EVENT_ID)
        val recurrenceId: TString = params.read(SaveEventOverride.Params.RECURRENCE_ID)

        executionContext.saveData(
            EventOverride.create(
                context = executionContext.context,
                eventId = eventId.get(),
                recurrenceId = recurrenceId.get(),
                startLocal = params.optional(SaveEventOverride.Params.START_LOCAL),
                endLocal = params.optional(SaveEventOverride.Params.END_LOCAL),
                title = params.optional(SaveEventOverride.Params.TITLE),
                location = params.optional(SaveEventOverride.Params.LOCATION),
                notes = params.optional(SaveEventOverride.Params.NOTES),
            )
        )
        return null as T
    }
}
