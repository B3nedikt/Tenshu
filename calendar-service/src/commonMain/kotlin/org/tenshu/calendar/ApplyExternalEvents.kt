package org.tenshu.calendar

import org.tenshu.core.Operation
import org.tenshu.core.TResult
import org.tenshu.core.asTaskInteractor
import org.tenshu.core.core.findOperation
import org.tenshu.core.number.TInteger
import org.tenshu.core.request.RequestObject
import org.tenshu.core.schema.OperationSchema
import org.tenshu.core.schema.list
import org.tenshu.core.schema.operation
import org.tenshu.core.schema.toType
import org.tenshu.core.trait.IoOperation
import org.tenshu.core.trait.TerminalOperation

/**
 * Writes a batch of events brought in from a calendar the user already has — the OS calendar
 * store today (`calendar-external-sources.md` CX-0/CX-1) — in **one** round-trip.
 *
 * ⚠⚠ **Why a batch op and not `SaveEvent` in a loop.** CA-6's `.ics` import calls `saveEvent`,
 * `saveEventSource` and the reminder ops once per event, which is fine for a file and ruinous for a
 * phone's calendar: a busy Google account is thousands of events, i.e. ten thousand IPC
 * round-trips. The Fit `RecordImportedHealth` precedent (`fit-app.md` §9.16) settled the shape.
 * ⚠ The caller still **chunks**: one call carrying a whole calendar would sit far past Binder's
 * ~1 MB transaction buffer.
 *
 * The rows arrive fully formed, ids included — the caller derives them (namespaced per target
 * calendar, `calendar-external-sources.md` §5.2), because only the caller knows the source.
 *
 * Applied in this order, and the order is the contract:
 * 1. [events] are upserted. A blank `endLocal` gets the same implied end `SaveEvent` gives one.
 * 2. [sources] — the lossless sidecars — are upserted.
 * 3. [reminders] are **set**, not added, for every event in [events]: an existing reminder the
 *    batch does not name is deleted (with its dismissal state), so a re-copy converges on what
 *    the source says rather than accumulating the union of every copy.
 * 4. [overrides] are **set** for every event in [events], like the reminders (CX-1b): an existing
 *    override of a batch event that the batch does not name is deleted — the only way an
 *    occurrence the source moved back stops showing as moved. ⚠ So a caller must send each series
 *    **together with** its overrides; an override for an event outside [events] is only upserted.
 *
 * ⚠ No `deletes` yet. *Copy once* never removes anything; *Keep in sync* will need them, and it
 * lands with its own HR#9 bump.
 *
 * @return how many [events] were **new** — not how many were offered. A re-copy of an unchanged
 * calendar lands on the same ids and answers 0, which is what lets the UI say "nothing new"
 * instead of claiming it copied everything again.
 */
suspend fun CalendarService.applyExternalEvents(
    events: List<Event>,
    sources: List<EventSource> = emptyList(),
    reminders: List<Reminder> = emptyList(),
    overrides: List<EventOverride> = emptyList(),
): TResult<TInteger> =
    findOperation<ApplyExternalEvents>(ApplyExternalEvents.ID)
        .applyExternalEvents(events, sources, reminders, overrides)
        .asTaskInteractor<TInteger>()
        .execute()

interface ApplyExternalEvents : Operation {

    override val schema: OperationSchema get() = Schema

    fun applyExternalEvents(
        events: List<Event>,
        sources: List<EventSource>,
        reminders: List<Reminder>,
        overrides: List<EventOverride>,
    ): RequestObject

    companion object {
        const val ID = "tenshu/calendar/service/applyExternalEvents"

        val Schema = operation(
            id = ID,
            receiver = CalendarService.Schema.toType(),
            // ⚠ All four required on the wire; an empty list is the "none of this kind" encoding —
            // no optional-param presence handling, the `RecordImportedHealth` rule.
            params = {
                list(id = Params.EVENTS, of = Event.Schema)
                list(id = Params.SOURCES, of = EventSource.Schema)
                list(id = Params.REMINDERS, of = Reminder.Schema)
                list(id = Params.OVERRIDES, of = EventOverride.Schema)
            },
            returnType = TInteger.Schema.toType(),
            traits = listOf(IoOperation.Schema, TerminalOperation.Schema),
        )
    }

    object Params {
        const val EVENTS = "$ID/events"
        const val SOURCES = "$ID/sources"
        const val REMINDERS = "$ID/reminders"
        const val OVERRIDES = "$ID/overrides"
    }
}
