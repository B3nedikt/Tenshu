package org.tenshu.calendar.proxy.impl

import org.tenshu.sync.common.TombstoneEmitter
import org.tenshu.calendar.CalendarService
import org.tenshu.calendar.DeleteEvent
import org.tenshu.calendar.Event
import org.tenshu.calendar.EventOverride
import org.tenshu.calendar.EventSource
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

/** Deletes the whole series — see [DeleteEvent]'s KDoc on why that is not [DeleteOccurrence]. */
internal class DeleteEventImplementation(
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
        val eventId: TString = params.read(DeleteEvent.Params.EVENT_ID)
        deleteEventCascade(executionContext, eventId.get(), tombstoneEmitter)
        return null as T
    }
}

/**
 * Removes an event and everything keyed to it, each row with its tombstone ([deleteRow]).
 *
 * ⚠ Overrides and the source sidecar are keyed to the event but are *separate
 * rows* — nothing removes them implicitly. An orphaned override is worse than
 * merely wasteful: it is a live row describing an occurrence of a series that
 * no longer exists.
 */
internal fun deleteEventCascade(
    executionContext: ExecutionContext,
    eventId: String,
    tombstoneEmitter: TombstoneEmitter?,
) {
    executionContext.overridesFor(eventId).forEach { override ->
        executionContext.deleteRow(EventOverride.ID, override.overrideId.get(), tombstoneEmitter)
    }
    // ⚠ Tombstoned even when this device holds no sidecar: a peer may hold one this device has not
    // pulled yet, and a tombstone for an absent row costs one relay write and harms nothing.
    executionContext.deleteRow(EventSource.ID, eventId, tombstoneEmitter)
    executionContext.deleteRow(Event.ID, eventId, tombstoneEmitter)
}

/** The [EventOverride] rows belonging to one event. */
internal fun ExecutionContext.overridesFor(eventId: String): List<EventOverride> {
    val arrayRef = resolveServiceData(
        service = ServiceTypeReferenceDto(CalendarService.ID),
        ref = EntityListReferenceDto(CalendarService.Relations.EVENT_OVERRIDES),
    )
    return resolveList(arrayRef)
        ?.filterIsInstance<EventOverride>()
        ?.filter { it.eventId.get() == eventId }
        ?.toList()
        .orEmpty()
}

/** One event by id, or `null`. */
internal fun ExecutionContext.eventById(eventId: String): Event? =
    database.getData(
        DataId.StringDataId.create(context = context, type = Event.ID, keyValue = eventId)
    ) as? Event
