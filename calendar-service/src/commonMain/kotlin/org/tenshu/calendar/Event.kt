package org.tenshu.calendar

import org.tenshu.core.TBoolean
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.CompositeDataIdProvider
import org.tenshu.core.entity.StringDataIdProviderSchema
import org.tenshu.core.Composite
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.boolean
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.schema.stringDataId
import org.tenshu.core.trait.SyncStrategy
import org.tenshu.core.trait.Syncable

/**
 * A calendar event — the master row (`calendar-app.md` §3 D1/D2/D3).
 *
 * ## The time model, which is the part most likely to be got wrong
 *
 * ⚠⚠ **An event is not an instant.** [startLocal] / [endLocal] are *civil*
 * date-times (ISO-8601 local, no offset) paired with [timeZoneId], because
 * "09:00 in Berlin" must stay 09:00 in Berlin when the owner reads it from
 * Tokyo, and its UTC instant changes at a DST boundary — and can change *again*
 * when the IANA tz database is updated. A stored instant would silently become
 * wrong, and nothing would report it. Instants are therefore **derived at read
 * time and never stored as truth** (`calendar-app.md` §3 D3 / Q3).
 *
 * ⭐ This mirrors the discipline the routines engine already arrived at, where
 * `FiringCalculator` takes an explicit `RoutineLocale` so a device's own zone
 * cannot leak into the answer. Here the zone rides *on the event*, which makes
 * the same class of bug unrepresentable rather than merely documented.
 *
 * Three shapes are expressible, and all three are real:
 *
 * | Shape | [timeZoneId] | [allDay] | Meaning |
 * |---|---|---|---|
 * | Zoned | an IANA id | `false` | "09:00 Europe/Berlin" — the common case |
 * | Floating | `""` | `false` | "09:00 wherever I am" — a daily habit |
 * | All-day | ignored | `true` | a **date range**, not an instant range |
 *
 * ⚠ For [allDay] the time halves of [startLocal] / [endLocal] are meaningless
 * and must not be rendered: an all-day event is a date range, and treating
 * "1 Aug" as "1 Aug 00:00 UTC" is exactly how all-day events drift a day for
 * users west of Greenwich. [endLocal] is **exclusive** for all-day events (the
 * iCal convention), so a single-day event ends on the following date.
 *
 * ## Recurrence
 *
 * [recurrenceRule] holds a raw RFC 5545 `RRULE` (Q2: the *string* is canonical,
 * because nothing ever queries by rule — you query expanded occurrences — and a
 * string keeps the wire shape stable while the expander grows). Empty means a
 * one-off event.
 *
 * ⚠⚠ Occurrences are **expanded on read, never materialised as rows**: "every
 * Monday, forever" is unbounded, and a store that materialised it would either
 * invent a horizon and silently stop showing events past it, or write thousands
 * of sync rows per event. Expansion lives in
 * `org.tenshu.calendar.common.recurrence`.
 *
 * [exceptionDates] is a `,`-delimited list of ISO local dates that the rule
 * would have produced but which the user deleted. ⚠ Deliberately a delimited
 * `TString` and **not** a `TList<TString>`: list members need a
 * `DataIdProvider`, which a bare date has no business carrying — the same
 * workaround `SchemaFenceStatus` uses for its offending-id list.
 *
 * A single *modified* occurrence is an [EventOverride] row instead.
 */
interface Event : Composite {

    override val schema: CompositeSchema get() = Schema

    /** Stable opaque id; the row key. */
    val eventId: TString get() = getField(Field.EVENT_ID)

    /** The owning [Calendar]'s [Calendar.calendarId]. */
    val calendarId: TString get() = getField(Field.CALENDAR_ID)

    val title: TString get() = getField(Field.TITLE)

    /** Free-text location. ⚠ Not a `Place` reference — see `Calendar` KDoc. */
    val location: TString get() = getField(Field.LOCATION)

    val notes: TString get() = getField(Field.NOTES)

    /** ISO-8601 *local* date-time, e.g. `2026-08-05T09:00`. No offset, ever. */
    val startLocal: TString get() = getField(Field.START_LOCAL)

    /** ISO-8601 *local* date-time. Exclusive when [allDay]. */
    val endLocal: TString get() = getField(Field.END_LOCAL)

    /** IANA zone id, or `""` for a floating time. Ignored when [allDay]. */
    val timeZoneId: TString get() = getField(Field.TIME_ZONE_ID)

    /** True when this is a date range rather than a time range. */
    val allDay: TBoolean get() = getField(Field.ALL_DAY)

    /** Raw RFC 5545 `RRULE` value, or `""` for a one-off. */
    val recurrenceRule: TString get() = getField(Field.RECURRENCE_RULE)

    /** `,`-delimited ISO local dates deleted from the expansion. */
    val exceptionDates: TString get() = getField(Field.EXCEPTION_DATES)

    companion object {
        const val ID = "tenshu/calendar/event"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            // `Eager`: an event is small, and a reminder that syncs after the
            // event has started is worthless. `LastWriterWins` (the default)
            // is right for the same reason it is right for `Device` — an event
            // is edited as a whole by one person at a time, so a semantic merge
            // has nothing useful to contribute.
            //
            // ⚠⚠ **Amended by CA-2j: true of every field except [exceptionDates].**
            // The reasoning above holds for the scalar block — and there is nothing
            // to merge *on* anyway, since an `Event` carries no per-field version.
            // But `exceptionDates` is not edited as a whole: `DeleteOccurrence` reads
            // it, appends one date and writes it back, so two devices deleting
            // different occurrences of one series each write a set missing the
            // other's date — and last-writer-wins **resurrects** one of the deleted
            // occurrences. `EventMerger` (`:calendar-service-proxy`) unions them and
            // leaves every other field to LWW exactly as this comment describes.
            traits = listOf(EventDataIdProvider.Schema, Syncable(SyncStrategy.Eager)),
            fields = {
                string(Field.EVENT_ID)
                string(Field.CALENDAR_ID)
                string(Field.TITLE)
                string(Field.LOCATION)
                string(Field.NOTES)
                string(Field.START_LOCAL)
                string(Field.END_LOCAL)
                string(Field.TIME_ZONE_ID)
                boolean(Field.ALL_DAY)
                string(Field.RECURRENCE_RULE)
                string(Field.EXCEPTION_DATES)
            }
        )

        @Suppress("LongParameterList")
        fun create(
            context: TContext,
            eventId: String,
            calendarId: String,
            title: String,
            startLocal: String,
            endLocal: String,
            timeZoneId: String = "",
            allDay: Boolean = false,
            location: String = "",
            notes: String = "",
            recurrenceRule: String = "",
            exceptionDates: String = "",
        ): Event = context.create(
            type = ID,
            fields = listOf(
                eventId,
                calendarId,
                title,
                location,
                notes,
                startLocal,
                endLocal,
                timeZoneId,
                allDay,
                recurrenceRule,
                exceptionDates,
            ),
        )
    }

    object Field {
        const val EVENT_ID = "$ID/eventId"
        const val CALENDAR_ID = "$ID/calendarId"
        const val TITLE = "$ID/title"
        const val LOCATION = "$ID/location"
        const val NOTES = "$ID/notes"
        const val START_LOCAL = "$ID/startLocal"
        const val END_LOCAL = "$ID/endLocal"
        const val TIME_ZONE_ID = "$ID/timeZoneId"
        const val ALL_DAY = "$ID/allDay"
        const val RECURRENCE_RULE = "$ID/recurrenceRule"
        const val EXCEPTION_DATES = "$ID/exceptionDates"
    }
}

/** Field accessors for a projected [Event] row — see [Calendar]'s on why. */
val Entity<out Event>.eventId: TString get() = getFieldValue(Event.Field.EVENT_ID)

val Entity<out Event>.calendarId: TString get() = getFieldValue(Event.Field.CALENDAR_ID)

val Entity<out Event>.title: TString get() = getFieldValue(Event.Field.TITLE)

val Entity<out Event>.location: TString get() = getFieldValue(Event.Field.LOCATION)

val Entity<out Event>.notes: TString get() = getFieldValue(Event.Field.NOTES)

val Entity<out Event>.startLocal: TString get() = getFieldValue(Event.Field.START_LOCAL)

val Entity<out Event>.endLocal: TString get() = getFieldValue(Event.Field.END_LOCAL)

val Entity<out Event>.timeZoneId: TString get() = getFieldValue(Event.Field.TIME_ZONE_ID)

val Entity<out Event>.allDay: TBoolean get() = getFieldValue(Event.Field.ALL_DAY)

val Entity<out Event>.recurrenceRule: TString get() = getFieldValue(Event.Field.RECURRENCE_RULE)

val Entity<out Event>.exceptionDates: TString get() = getFieldValue(Event.Field.EXCEPTION_DATES)


interface EventDataIdProvider : CompositeDataIdProvider<Event> {

    override val schema: StringDataIdProviderSchema get() = Schema

    companion object {
        const val ID = "tenshu/calendar/event/dataIdProvider"

        val Schema: StringDataIdProviderSchema = stringDataId(
            id = ID,
            fieldRef = Event.Field.EVENT_ID,
        )
    }
}
