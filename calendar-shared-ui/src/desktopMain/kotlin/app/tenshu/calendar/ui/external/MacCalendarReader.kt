package app.tenshu.calendar.ui.external

import app.tenshu.calendar.data.external.AppleCalendarRow
import app.tenshu.calendar.data.external.AppleConversion
import app.tenshu.calendar.data.external.AppleOccurrenceRow
import app.tenshu.calendar.data.external.AppleRecurrenceRow
import app.tenshu.calendar.data.external.AppleSeriesFields
import app.tenshu.calendar.data.external.ExternalCalendar
import app.tenshu.calendar.data.external.ExternalCalendarAccessDenied
import app.tenshu.calendar.data.external.ExternalCalendarReader
import app.tenshu.calendar.data.external.ExternalSnapshot
import app.tenshu.macos.MacEventKit
import kotlin.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn

/**
 * The macOS calendar store, read through EventKit over FFM (`calendar-external-sources.md` CX-2c) —
 * the desktop twin of the iOS `EventKitCalendarReader`, feeding the same common [AppleConversion].
 *
 * ⚠ Bound only where [MacEventKit.isUsable] — a packaged `.app` whose bundle declares the usage
 * string; elsewhere no reader is bound and the flow stays hidden. ⚠ Asking is the UI's job, at the
 * button; this throws [ExternalCalendarAccessDenied] when access is not held.
 *
 * ⚠⚠ The same window as iOS — today − 1 y … + 2 y, under EventKit's four-year predicate cap — and
 * the snapshot carries it, so a mirror never reads absence outside it as deletion.
 */
class MacCalendarReader : ExternalCalendarReader {

    override suspend fun calendars(): List<ExternalCalendar> = withContext(Dispatchers.IO) {
        requireAccess()
        MacEventKit.calendars().map { calendar ->
            AppleConversion.calendar(
                AppleCalendarRow(
                    identifier = calendar.identifier,
                    title = calendar.title,
                    sourceTitle = calendar.sourceTitle,
                    sourceType = sourceTypeName(calendar.sourceType),
                    colorArgb = calendar.colorArgb,
                ),
            )
        }
    }

    override suspend fun read(calendar: ExternalCalendar): ExternalSnapshot = withContext(Dispatchers.IO) {
        requireAccess()
        val zone = TimeZone.currentSystemDefault()
        val today = Clock.System.todayIn(zone)
        val startMs = today.minus(BACK).atStartOfDayIn(zone).toEpochMilliseconds()
        val endMs = today.plus(AHEAD).plus(DatePeriod(days = 1)).atStartOfDayIn(zone).toEpochMilliseconds()
        val occurrences = MacEventKit.occurrences(calendar.handle, startMs, endMs)
            ?: return@withContext ExternalSnapshot(emptyList(), skipped = 0)
        val rows = occurrences.map { o ->
            val master = o.master
            AppleOccurrenceRow(
                eventIdentifier = o.eventIdentifier,
                externalIdentifier = o.externalIdentifier,
                title = o.title,
                notes = o.notes,
                location = o.location,
                startMs = o.startMs,
                endMs = o.endMs,
                allDay = o.allDay,
                timeZoneId = o.timeZoneId,
                occurrenceDateMs = o.occurrenceMs,
                isDetached = o.detached,
                recurrence = master?.recurrence?.let(::recurrenceOf),
                seriesStartMs = master?.startMs ?: o.startMs,
                seriesEndMs = master?.endMs ?: o.endMs,
                alarmOffsetsSeconds = master?.alarmOffsetsSeconds.orEmpty(),
                series = master?.let {
                    AppleSeriesFields(it.title, it.notes, it.location, it.allDay, it.timeZoneId)
                },
            )
        }
        AppleConversion.snapshot(rows, startMs, endMs, zone)
    }

    private fun requireAccess() {
        if (!MacEventKit.hasFullAccess()) throw ExternalCalendarAccessDenied()
    }

    internal fun recurrenceOf(rule: MacEventKit.Recurrence) = AppleRecurrenceRow(
        frequency = rule.frequency.toInt(),
        interval = rule.interval.toInt(),
        // EKWeekday counts Sunday = 1 … Saturday = 7; ISO counts Monday = 1 … Sunday = 7.
        daysOfTheWeek = rule.daysOfTheWeek.map { (day, week) -> iso(day) to week },
        daysOfTheMonth = rule.daysOfTheMonth,
        monthsOfTheYear = rule.monthsOfTheYear,
        setPositions = rule.setPositions,
        daysOfTheYear = rule.daysOfTheYear,
        weeksOfTheYear = rule.weeksOfTheYear,
        firstDayOfWeek = if (rule.firstDayOfTheWeek == 0) 0 else iso(rule.firstDayOfTheWeek),
        count = rule.count,
        untilMs = rule.untilMs,
    )

    private fun iso(ekWeekday: Int) = if (ekWeekday == 1) 7 else ekWeekday - 1

    /** `EKSourceType`, by the names the iOS reader uses — so a sourceKey reads the same on both. */
    internal fun sourceTypeName(type: Long): String = when (type) {
        0L -> "local"
        1L -> "exchange"
        2L -> "calDAV"
        3L -> "mobileMe"
        4L -> "subscribed"
        5L -> "birthdays"
        else -> "other"
    }

    companion object {
        val BACK = DatePeriod(years = 1)
        val AHEAD = DatePeriod(years = 2)
    }
}
