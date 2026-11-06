package org.tenshu.calendar

import org.tenshu.core.Composite
import org.tenshu.core.TBoolean
import org.tenshu.core.TString
import org.tenshu.core.core.TContext
import org.tenshu.core.core.create
import org.tenshu.core.schema.CompositeSchema
import org.tenshu.core.schema.boolean
import org.tenshu.core.schema.composite
import org.tenshu.core.schema.string
import org.tenshu.core.toTBoolean
import org.tenshu.core.toTString

/**
 * One **occurrence** of an event — the series' rule expanded and its override applied
 * (`mia-query-layer.md` §15.1, `calendar-app.md` CA-8).
 *
 * ⚠⚠ **Never stored, never synced**, exactly like `CapturedPhoto`: no `DataIdProvider`, no
 * `Syncable` trait, no row anywhere. **The rule is stored; the occurrence is computed.** A weekly
 * stand-up created in 2024 has exactly one stored [Event] and an unbounded number of these, so a
 * stored copy could only be a cache that drifts.
 *
 * ⚠⚠ **It mirrors `ResolvedOccurrence` field for field, deliberately.** This is a *wire* shape, so
 * narrowing it now and widening it later is a positional reshape under House Rule #9 — a version
 * bump and a migration to add a field a caller could have had for free. The fields cost bytes per
 * call and nothing else, and the second consumer (the Calendar app's own window query) needs the
 * ones Mia does not.
 *
 * ⚠⚠ **The id is `resolvedEventOccurrence`, NOT `eventOccurrence`** — that string is already
 * taken by [org.tenshu.calendar.common.recurrence.OccurrenceId], the `DataId` **type** a citation
 * addresses and `HandlerTable` routes on. Two different things under one id in one namespace is a
 * lookup waiting to resolve to the wrong one.
 *
 * ⚠ [startLocal] / [endLocal] are the **effective** values, so an occurrence an override moved
 * reports where it moved *to*. [recurrenceId] still identifies it by its **original** start,
 * because that is the key an override is filed under and the handle needed to move or skip it
 * again — and it is the second half of the `OccurrenceId` a citation addresses.
 */
interface ResolvedEventOccurrence : Composite {

    override val schema: CompositeSchema get() = Schema

    val eventId: TString get() = getField(Field.EVENT_ID)

    val calendarId: TString get() = getField(Field.CALENDAR_ID)

    /** The occurrence's **original** start, as an ISO local date-time. */
    val recurrenceId: TString get() = getField(Field.RECURRENCE_ID)

    val title: TString get() = getField(Field.TITLE)

    val location: TString get() = getField(Field.LOCATION)

    val notes: TString get() = getField(Field.NOTES)

    /** Effective start, ISO local date-time (`2026-07-16T09:00`). */
    val startLocal: TString get() = getField(Field.START_LOCAL)

    /** Effective end, ISO local date-time. */
    val endLocal: TString get() = getField(Field.END_LOCAL)

    val zoneId: TString get() = getField(Field.ZONE_ID)

    val allDay: TBoolean get() = getField(Field.ALL_DAY)

    /** True when an override supplied any of the values above. */
    val isOverridden: TBoolean get() = getField(Field.IS_OVERRIDDEN)

    /**
     * The **series'** raw `RRULE`, or `""` for a one-off.
     *
     * ⚠⚠ The series' rule even on an overridden occurrence: an override moves one occurrence, it
     * never re-writes the cadence.
     */
    val recurrenceRule: TString get() = getField(Field.RECURRENCE_RULE)

    companion object {
        const val ID = "tenshu/calendar/resolvedEventOccurrence"

        val Schema: CompositeSchema = composite(
            id = ID,
            parent = Composite.Schema,
            fields = {
                string(Field.EVENT_ID)
                string(Field.CALENDAR_ID)
                string(Field.RECURRENCE_ID)
                string(Field.TITLE)
                string(Field.LOCATION)
                string(Field.NOTES)
                string(Field.START_LOCAL)
                string(Field.END_LOCAL)
                string(Field.ZONE_ID)
                boolean(Field.ALL_DAY)
                boolean(Field.IS_OVERRIDDEN)
                string(Field.RECURRENCE_RULE)
            },
        )

        @Suppress("LongParameterList")
        fun create(
            context: TContext,
            eventId: String,
            calendarId: String,
            recurrenceId: String,
            title: String,
            location: String,
            notes: String,
            startLocal: String,
            endLocal: String,
            zoneId: String,
            allDay: Boolean,
            isOverridden: Boolean,
            recurrenceRule: String,
        ): ResolvedEventOccurrence = context.create(
            type = ID,
            fields = listOf(
                eventId.toTString(context),
                calendarId.toTString(context),
                recurrenceId.toTString(context),
                title.toTString(context),
                location.toTString(context),
                notes.toTString(context),
                startLocal.toTString(context),
                endLocal.toTString(context),
                zoneId.toTString(context),
                allDay.toTBoolean(context),
                isOverridden.toTBoolean(context),
                recurrenceRule.toTString(context),
            ),
        )
    }

    object Field {
        const val EVENT_ID = "$ID/eventId"
        const val CALENDAR_ID = "$ID/calendarId"
        const val RECURRENCE_ID = "$ID/recurrenceId"
        const val TITLE = "$ID/title"
        const val LOCATION = "$ID/location"
        const val NOTES = "$ID/notes"
        const val START_LOCAL = "$ID/startLocal"
        const val END_LOCAL = "$ID/endLocal"
        const val ZONE_ID = "$ID/zoneId"
        const val ALL_DAY = "$ID/allDay"
        const val IS_OVERRIDDEN = "$ID/isOverridden"
        const val RECURRENCE_RULE = "$ID/recurrenceRule"
    }
}
