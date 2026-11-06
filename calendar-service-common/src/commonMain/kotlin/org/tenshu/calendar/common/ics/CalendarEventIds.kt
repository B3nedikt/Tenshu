package org.tenshu.calendar.common.ics

/**
 * The id an event brought in from elsewhere is stored under — one rule for every path that
 * brings events in: a phone copy or mirror, an `.ics` import, a subscribed feed
 * (`calendar-external-sources.md` §5.2 and §7).
 *
 * Lives here, not in the app, because the feed refresh runs in the proxy.
 */
object CalendarEventIds {

    /**
     * The id [uid] is stored under in [targetCalendarId].
     *
     * ⚠⚠ **Namespaced by the calendar it lands in**, never the bare `UID`. A meeting invite
     * carries the *same* `UID` in every attendee's calendar, so two calendars holding it would
     * otherwise share one row: the second write moves the event out of the first calendar.
     * Writing the same `UID` into the same calendar again still converges.
     *
     * ⚠ Plain concatenation, not a hash: a 32-bit hash over thousands of events collides often
     * enough to lose one, and the stored id is never shown to anyone.
     *
     * ⚠ One exception: a row already stored in the target under the bare [uid] (written before
     * namespacing) is adopted rather than doubled. That is why no stored row needs re-keying.
     *
     * @param targetCalendarIds the event ids already stored in [targetCalendarId].
     */
    fun inCalendar(targetCalendarId: String, uid: String, targetCalendarIds: Set<String>): String =
        if (uid in targetCalendarIds) uid else "$targetCalendarId~$uid"
}
