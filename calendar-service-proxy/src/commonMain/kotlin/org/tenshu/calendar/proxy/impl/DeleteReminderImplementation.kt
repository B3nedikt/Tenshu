package org.tenshu.calendar.proxy.impl

import org.tenshu.sync.common.TombstoneEmitter
import org.tenshu.calendar.CalendarService
import org.tenshu.calendar.Reminder
import org.tenshu.calendar.ReminderState
import org.tenshu.calendar.DeleteReminder
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.impl.reference.EntityListReferenceDto
import org.tenshu.core.common.impl.reference.ServiceTypeReferenceDto
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.proxy.executor.read
import org.tenshu.core.entity.DataId

/**
 * Deletes a [Reminder] and cascades to its [ReminderState] rows.
 *
 * ⚠⚠ The cascade is not tidiness. `Reminder`'s key is *content-derived*, so
 * re-adding the same offset to the same event mints the same id — and an
 * orphaned dismissal would then silently suppress the first occurrence of a
 * reminder the user had just asked for.
 */
internal class DeleteReminderImplementation(
    private val tombstoneEmitter: TombstoneEmitter? = null,
) : SyncOperationImplementation {

    @Suppress("UNCHECKED_CAST")
    override fun <T : Data?> execute(
        executionContext: ExecutionContext,
        operationContext: OperationContext?,
        implementationFactory: OperationImplementationFactory,
        caller: Any?,
        params: Map<String, Data>?,
    ): T {
        val reminderId: TString = params.read(DeleteReminder.Params.REMINDER_ID)

        executionContext.deleteReminderCascade(reminderId.get(), tombstoneEmitter)
        return null as T
    }
}

/** Removes a reminder and every state row filed under it, each with its tombstone ([deleteRow]). */
internal fun ExecutionContext.deleteReminderCascade(reminderId: String, tombstoneEmitter: TombstoneEmitter?) {
    allReminderStates()
        .filter { it.reminderId.get() == reminderId }
        .forEach { state -> deleteRow(ReminderState.ID, state.stateId.get(), tombstoneEmitter) }
    deleteRow(Reminder.ID, reminderId, tombstoneEmitter)
}

/** Every locally-known [Reminder]; a full scan, like [allEvents]. */
internal fun ExecutionContext.allReminders(): List<Reminder> {
    val arrayRef = resolveServiceData(
        service = ServiceTypeReferenceDto(CalendarService.ID),
        ref = EntityListReferenceDto(CalendarService.Relations.REMINDERS),
    )
    return resolveList(arrayRef)?.filterIsInstance<Reminder>()?.toList().orEmpty()
}

internal fun ExecutionContext.allReminderStates(): List<ReminderState> {
    val arrayRef = resolveServiceData(
        service = ServiceTypeReferenceDto(CalendarService.ID),
        ref = EntityListReferenceDto(CalendarService.Relations.REMINDER_STATES),
    )
    return resolveList(arrayRef)?.filterIsInstance<ReminderState>()?.toList().orEmpty()
}
