package app.tenshu.calendar.data.external

import org.tenshu.calendar.common.ics.CalendarEventIds

/**
 * The ids a *Copy once* writes under (`calendar-external-sources.md` §5.2).
 */
object ExternalCopyIds {

    /**
     * The Tenshu calendar a source is copied into.
     *
     * ⭐ Derived from the source, never random and never from the name: copying the same calendar
     * a second time — after fixing something at the source, or from a second phone — lands in
     * the calendar the first copy made rather than beside it. ⚠ Not from the name either, because
     * a user's own "Work" and their employer's "Work" are different calendars.
     */
    fun calendarId(source: ExternalCalendar): String =
        "copy-" + "${source.kind.name}|${source.sourceKey}".hashCode().toUInt().toString(16)

    /** The id one copied event is stored under — [CalendarEventIds.inCalendar]'s rule. */
    fun eventId(targetCalendarId: String, uid: String, targetCalendarIds: Set<String>): String =
        CalendarEventIds.inCalendar(targetCalendarId, uid, targetCalendarIds)
}
