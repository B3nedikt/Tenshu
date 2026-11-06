package org.tenshu.calendar

import org.tenshu.core.Service
import org.tenshu.core.collection.list.TList
import org.tenshu.core.schema.ServiceSchema
import org.tenshu.core.schema.service
import org.tenshu.core.schema.serviceDataRelation
import org.tenshu.core.schema.typeOf

/**
 * Service surface for calendars and events (`calendar-app.md`, phase CA-0).
 *
 * Four relations, traversed with the standard chain DSL:
 *
 * ```
 * service.events
 *     .searchAll<Event> { calendarId equalTo work }
 *     .flow()
 * ```
 *
 * ⚠ **CA-0 declares no operations.** Reads and writes land in CA-1 together
 * with `:calendar-service-client` / `-proxy`; this phase exists to settle the
 * schema and the recurrence engine, which are the parts that are expensive to
 * change once rows exist. What is here is the shape, plus the pure expansion
 * logic in `:calendar-service-common`.
 *
 * ⚠ **Version 1, and no `SchemaMigration`** — correct for a service whose types
 * have never shipped: there is no earlier shape to migrate *from*, and the
 * derived floor starts here. House Rule #9 begins to apply at the first reshape
 * *after* this lands, at which point the bump goes on `service(id, version =)`
 * and **not** on `TENSHU_STORAGE_FORMAT_VERSION` (which is `:core`'s alone, and
 * moving it wipes every store).
 */
interface CalendarService : Service {

    override val schema: ServiceSchema get() = Schema

    val calendars: TList<Calendar>

    val events: TList<Event>

    /** Modified single occurrences; see [EventOverride]. */
    val eventOverrides: TList<EventOverride>

    /** Lossless iCal sidecars for imported events; see [EventSource]. */
    val eventSources: TList<EventSource>

    /** Alerts attached to events; see [Reminder]. */
    val reminders: TList<Reminder>

    /** Per-occurrence dismissal and snooze; see [ReminderState]. */
    val reminderStates: TList<ReminderState>

    /** Remote `.ics` feeds a calendar is kept in step with; see [CalendarSubscription]. */
    val subscriptions: TList<CalendarSubscription>

    /** OS calendars a calendar is kept in step with; see [CalendarMirror]. */
    val mirrors: TList<CalendarMirror>

    /** When each device last refreshed each mirror; see [CalendarMirrorStamp]. */
    val mirrorStamps: TList<CalendarMirrorStamp>

    companion object {
        const val ID = "tenshu/calendar/service"

        // `maintained = true` opts each relation into generic maintenance
        // (`core-relation-system.md` §9.2.1): the proxy derives the element
        // type, the array key and the element's own key from what is already
        // declared here, so CA-1's `:calendar-service-proxy` registers no
        // observer of its own.
        val Schema: ServiceSchema = service(
            id = ID,
            // v1 = release baseline (re-cut 2026-11-05, core-schema-evolution.md Appendix A.6).
            version = 1,
            relations = listOf(
                serviceDataRelation(
                    id = Relations.CALENDARS,
                    type = TList.typeOf(Calendar.Schema),
                    maintained = true,
                ),
                serviceDataRelation(
                    id = Relations.EVENTS,
                    type = TList.typeOf(Event.Schema),
                    maintained = true,
                ),
                serviceDataRelation(
                    id = Relations.EVENT_OVERRIDES,
                    type = TList.typeOf(EventOverride.Schema),
                    maintained = true,
                ),
                serviceDataRelation(
                    id = Relations.EVENT_SOURCES,
                    type = TList.typeOf(EventSource.Schema),
                    maintained = true,
                ),
                serviceDataRelation(
                    id = Relations.REMINDERS,
                    type = TList.typeOf(Reminder.Schema),
                    maintained = true,
                ),
                serviceDataRelation(
                    id = Relations.SUBSCRIPTIONS,
                    type = TList.typeOf(CalendarSubscription.Schema),
                    maintained = true,
                ),
                serviceDataRelation(
                    id = Relations.MIRRORS,
                    type = TList.typeOf(CalendarMirror.Schema),
                    maintained = true,
                ),
                serviceDataRelation(
                    id = Relations.MIRROR_STAMPS,
                    type = TList.typeOf(CalendarMirrorStamp.Schema),
                    maintained = true,
                ),
                serviceDataRelation(
                    id = Relations.REMINDER_STATES,
                    type = TList.typeOf(ReminderState.Schema),
                    maintained = true,
                ),
            ),
        )
    }

    object Relations {
        const val CALENDARS = "$ID/calendars"
        const val EVENTS = "$ID/events"
        const val EVENT_OVERRIDES = "$ID/eventOverrides"
        const val EVENT_SOURCES = "$ID/eventSources"
        const val REMINDERS = "$ID/reminders"
        const val REMINDER_STATES = "$ID/reminderStates"
        const val SUBSCRIPTIONS = "$ID/subscriptions"
        const val MIRRORS = "$ID/mirrors"
        const val MIRROR_STAMPS = "$ID/mirrorStamps"
    }
}
