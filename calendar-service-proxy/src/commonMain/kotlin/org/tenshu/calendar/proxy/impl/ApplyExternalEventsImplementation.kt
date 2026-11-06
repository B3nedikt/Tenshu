package org.tenshu.calendar.proxy.impl

import org.tenshu.sync.common.TombstoneEmitter
import org.tenshu.calendar.ApplyExternalEvents
import org.tenshu.calendar.Event
import org.tenshu.calendar.EventOverride
import org.tenshu.calendar.EventSource
import org.tenshu.calendar.Reminder
import org.tenshu.core.Data
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.entity.DataId
import org.tenshu.core.number.toTInteger

/**
 * Writes a batch from an external calendar (`calendar-external-sources.md` CX-0); see
 * [ApplyExternalEvents] for the order, which is the contract.
 *
 * ⚠ Every row goes through `saveData`, exactly as the per-row ops do, so the sync observer and
 * the relation maintainers see each one — the batch changes how many IPC round-trips carry the
 * rows, not how they are persisted. ⚠ And `ReminderRearmObserver` is coalesced, so a batch of a
 * thousand events re-plans reminders once, not a thousand times.
 */
internal class ApplyExternalEventsImplementation(
    /** ⚠ What the batch *sets* away (stale overrides and reminders) reaches peers only with it. */
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
        val events = params.listParam<Event>(ApplyExternalEvents.Params.EVENTS)
        val sources = params.listParam<EventSource>(ApplyExternalEvents.Params.SOURCES)
        val reminders = params.listParam<Reminder>(ApplyExternalEvents.Params.REMINDERS)
        val overrides = params.listParam<EventOverride>(ApplyExternalEvents.Params.OVERRIDES)

        var added = 0
        events.forEach { event ->
            val row = withEnd(executionContext, event)
            // ⚠ Probed before the save — the `RecordImportedHealth` rule. The ids are derived
            // from the source, so a re-copy lands on the same rows, and counting what was
            // *offered* would tell the user a second copy added everything again.
            val id = executionContext.getDataId(row)
            if (id == null || !executionContext.database.contains(id)) added++
            executionContext.saveData(row)
        }
        val eventIds = events.map { it.eventId.get() }.toSet()
        sources.forEach { executionContext.saveData(it) }
        setReminders(executionContext, eventIds, reminders)
        setOverrides(executionContext, eventIds, overrides)

        return added.toLong().toTInteger(executionContext.context) as T
    }

    /**
     * ⚠ The same implied end `SaveEvent` gives an event whose source named none — one day for an
     * all-day event, the realm's default duration for a timed one — so a batch-written row and an
     * editor-written row cannot disagree about how long an event with no end lasts.
     */
    private fun withEnd(executionContext: ExecutionContext, event: Event): Event {
        if (event.endLocal.get().isNotBlank()) return event
        val end = CalendarDefaults.impliedEnd(
            startLocal = event.startLocal.get(),
            allDay = event.allDay.get(),
            durationMinutes = CalendarDefaults.defaultDurationMinutes(executionContext),
        ) ?: return event
        return Event.create(
            context = executionContext.context,
            eventId = event.eventId.get(),
            calendarId = event.calendarId.get(),
            title = event.title.get(),
            startLocal = event.startLocal.get(),
            endLocal = end,
            timeZoneId = event.timeZoneId.get(),
            allDay = event.allDay.get(),
            location = event.location.get(),
            notes = event.notes.get(),
            recurrenceRule = event.recurrenceRule.get(),
            exceptionDates = event.exceptionDates.get(),
        )
    }

    /**
     * Makes each event's reminders exactly what the batch says.
     *
     * ⚠⚠ Diffed, never cleared-and-rewritten — the repository's `setReminders` rule, for its
     * reason: deleting a reminder cascades to its dismissal state, and the content-derived key
     * means an unchanged reminder would come back identical but *un*-dismissed.
     */
    private fun setReminders(
        executionContext: ExecutionContext,
        eventIds: Set<String>,
        wanted: List<Reminder>,
    ) {
        if (eventIds.isEmpty()) return
        val wantedIds = wanted.mapTo(mutableSetOf()) { it.reminderId.get() }
        val existing = executionContext.allReminders().filter { it.eventId.get() in eventIds }
        val existingIds = existing.mapTo(mutableSetOf()) { it.reminderId.get() }

        existing
            .filter { it.reminderId.get() !in wantedIds }
            .forEach { executionContext.deleteReminderCascade(it.reminderId.get(), tombstoneEmitter) }
        wanted
            .filter { it.reminderId.get() !in existingIds }
            .forEach { executionContext.saveData(it) }
    }

    /**
     * Makes each batch event's overrides exactly what the batch says (CX-1b).
     *
     * ⚠⚠ *Set*, not upsert, for the events in this batch: when the source moves an occurrence back
     * where it was, its exception disappears there, and an upsert-only write would keep showing it
     * moved here forever — nothing else ever deletes an override. Overrides for events **not** in
     * this batch are upserted untouched by the rule, so a caller must send a series together with
     * its overrides.
     */
    private fun setOverrides(
        executionContext: ExecutionContext,
        eventIds: Set<String>,
        wanted: List<EventOverride>,
    ) {
        val wantedKeys = wanted.mapTo(mutableSetOf()) { it.overrideId.get() }
        executionContext.allEventOverrides()
            .filter { it.eventId.get() in eventIds && it.overrideId.get() !in wantedKeys }
            .forEach { stale -> executionContext.deleteRow(EventOverride.ID, stale.overrideId.get(), tombstoneEmitter) }
        wanted.forEach { executionContext.saveData(it) }
    }

    private inline fun <reified R : Data> Map<String, Data>?.listParam(id: String): List<R> =
        (this?.get(id) as? TList<*>)?.toList()?.filterIsInstance<R>().orEmpty()
}
