package app.tenshu.calendar.data.external

import android.content.ContentResolver
import android.database.Cursor
import android.provider.CalendarContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads Android's calendar provider (`calendar-external-sources.md` CX-1).
 *
 * ⚠⚠ **Copies columns and decides nothing.** Every rule — UTC all-day dates, cancelled exceptions
 * as `EXDATE`s, which reminders count — is in [ProviderConversion], in common code, where a test
 * can reach it. A content provider cannot be stood up in a unit test, so anything this file
 * decided would ship unverified.
 *
 * ⚠ Needs `READ_CALENDAR`, asked for by the UI at the one button that uses it. Without it the
 * provider throws `SecurityException`, which surfaces as [ExternalCalendarAccessDenied].
 *
 * ⚠ The `Events` table, never `Instances`: `Instances` is the expansion, and Tenshu stores the
 * rule (`calendar-app.md` §3 D2).
 */
class ProviderCalendarReader(private val resolver: ContentResolver) : ExternalCalendarReader {

    override suspend fun calendars(): List<ExternalCalendar> = io {
        query(CalendarContract.Calendars.CONTENT_URI, CALENDAR_COLUMNS) { cursor ->
            ProviderCalendarRow(
                id = cursor.getLong(0),
                accountName = cursor.stringOrNull(1),
                accountType = cursor.stringOrNull(2),
                displayName = cursor.stringOrNull(3),
                color = cursor.intOrNull(4),
                syncId = cursor.stringOrNull(5),
                syncEvents = cursor.intOrNull(6) == 1,
            )
        }.map(ProviderConversion::calendar)
    }

    override suspend fun read(calendar: ExternalCalendar): ExternalSnapshot = io {
        val events = query(
            uri = CalendarContract.Events.CONTENT_URI,
            projection = EVENT_COLUMNS,
            selection = "${CalendarContract.Events.CALENDAR_ID} = ?",
            args = arrayOf(calendar.handle),
        ) { cursor ->
            ProviderEventRow(
                id = cursor.getLong(0),
                syncId = cursor.stringOrNull(1),
                uid2445 = cursor.stringOrNull(2),
                title = cursor.stringOrNull(3),
                description = cursor.stringOrNull(4),
                location = cursor.stringOrNull(5),
                dtStart = cursor.longOrNull(6),
                dtEnd = cursor.longOrNull(7),
                duration = cursor.stringOrNull(8),
                eventTimezone = cursor.stringOrNull(9),
                allDay = cursor.intOrNull(10) == 1,
                rrule = cursor.stringOrNull(11),
                rdate = cursor.stringOrNull(12),
                exrule = cursor.stringOrNull(13),
                exdate = cursor.stringOrNull(14),
                originalId = cursor.longOrNull(15),
                originalSyncId = cursor.stringOrNull(16),
                originalInstanceTime = cursor.longOrNull(17),
                status = cursor.intOrNull(18),
                deleted = cursor.intOrNull(19) == 1,
            )
        }
        // ⚠ Read whole and filtered here rather than with an `IN (…)` over thousands of ids: a
        // selection that long has a ceiling nobody documents, and the table is three columns.
        val ids = events.mapTo(HashSet()) { it.id }
        val reminders = query(CalendarContract.Reminders.CONTENT_URI, REMINDER_COLUMNS) { cursor ->
            ProviderReminderRow(
                eventId = cursor.getLong(0),
                minutes = cursor.intOrNull(1) ?: -1,
                method = cursor.intOrNull(2) ?: 0,
            )
        }.filter { it.eventId in ids }
        ProviderConversion.snapshot(events, reminders)
    }

    private fun <T> query(
        uri: android.net.Uri,
        projection: Array<String>,
        selection: String? = null,
        args: Array<String>? = null,
        row: (Cursor) -> T,
    ): List<T> =
        resolver.query(uri, projection, selection, args, null)?.use { cursor ->
            buildList { while (cursor.moveToNext()) add(row(cursor)) }
        } ?: error("The calendar provider returned no cursor for $uri")

    /** Off the main thread, and a refused permission becomes the one failure the UI can explain. */
    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (denied: SecurityException) {
            throw ExternalCalendarAccessDenied(denied)
        }
    }

    private fun Cursor.stringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)
    private fun Cursor.longOrNull(index: Int): Long? = if (isNull(index)) null else getLong(index)
    private fun Cursor.intOrNull(index: Int): Int? = if (isNull(index)) null else getInt(index)

    private companion object {
        /** ⚠ Order is the index each `row` lambda reads — change both together. */
        val CALENDAR_COLUMNS = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.CALENDAR_COLOR,
            CalendarContract.Calendars._SYNC_ID,
            CalendarContract.Calendars.SYNC_EVENTS,
        )

        val EVENT_COLUMNS = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events._SYNC_ID,
            CalendarContract.Events.UID_2445,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.DURATION,
            CalendarContract.Events.EVENT_TIMEZONE,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.RRULE,
            CalendarContract.Events.RDATE,
            CalendarContract.Events.EXRULE,
            CalendarContract.Events.EXDATE,
            CalendarContract.Events.ORIGINAL_ID,
            CalendarContract.Events.ORIGINAL_SYNC_ID,
            CalendarContract.Events.ORIGINAL_INSTANCE_TIME,
            CalendarContract.Events.STATUS,
            CalendarContract.Events.DELETED,
        )

        val REMINDER_COLUMNS = arrayOf(
            CalendarContract.Reminders.EVENT_ID,
            CalendarContract.Reminders.MINUTES,
            CalendarContract.Reminders.METHOD,
        )
    }
}
