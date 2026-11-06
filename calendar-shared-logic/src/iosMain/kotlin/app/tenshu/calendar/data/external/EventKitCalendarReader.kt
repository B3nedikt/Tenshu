package app.tenshu.calendar.data.external

import kotlin.time.Clock
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn
import platform.CoreGraphics.CGColorGetComponents
import platform.CoreGraphics.CGColorGetNumberOfComponents
import platform.EventKit.EKAlarm
import platform.EventKit.EKAuthorizationStatusAuthorized
import platform.EventKit.EKAuthorizationStatusFullAccess
import platform.EventKit.EKCalendar
import platform.EventKit.EKEntityType
import platform.EventKit.EKEvent
import platform.EventKit.EKEventStore
import platform.EventKit.EKRecurrenceDayOfWeek
import platform.EventKit.EKRecurrenceRule
import platform.EventKit.EKSourceType
import platform.Foundation.NSDate
import platform.Foundation.NSNumber
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeIntervalSince1970

/**
 * The iOS calendar store, read through EventKit (`calendar-external-sources.md` CX-2).
 *
 * ⚠ Copies fields and decides nothing — [AppleConversion] does, in common code. ⚠ Assumes access is
 * already granted: asking is the SwiftUI shell's job, at the one button that needs it (both the
 * iOS 17 `requestFullAccessToEvents` and the iOS 16 `requestAccess(to: .event)` paths live there),
 * and this throws [ExternalCalendarAccessDenied] when it is not.
 *
 * ⚠⚠ EventKit answers with **occurrences in a window**, never series, and caps a predicate at four
 * years. The window is [BACK] behind today to [AHEAD] after it — three years, one predicate — and
 * [AppleConversion] infers deleted occurrences only inside it.
 */
@OptIn(ExperimentalForeignApi::class)
class EventKitCalendarReader(
    newStore: () -> EKEventStore = { EKEventStore() },
) : ExternalCalendarReader {

    /**
     * ⚠ Built on first read, not with the reader: the view model (and so this) exists before the
     * user grants access, and a store made before the grant can go on listing no calendars.
     */
    private val store: EKEventStore by lazy(newStore)

    override suspend fun calendars(): List<ExternalCalendar> = io {
        requireAccess()
        store.calendarsForEntityType(EKEntityType.EKEntityTypeEvent)
            .filterIsInstance<EKCalendar>()
            .map { calendar ->
                AppleConversion.calendar(
                    AppleCalendarRow(
                        identifier = calendar.calendarIdentifier,
                        title = calendar.title,
                        sourceTitle = calendar.source?.title.orEmpty(),
                        sourceType = sourceTypeName(calendar.source?.sourceType),
                        colorArgb = argbOf(calendar),
                    ),
                )
            }
    }

    override suspend fun read(calendar: ExternalCalendar): ExternalSnapshot = io {
        requireAccess()
        val ekCalendar = store.calendarWithIdentifier(calendar.handle)
            ?: return@io ExternalSnapshot(emptyList(), skipped = 0)
        val zone = TimeZone.currentSystemDefault()
        val today = Clock.System.todayIn(zone)
        val windowStart = today.minus(BACK)
        val windowEnd = today.plus(AHEAD)
        // ⚠ One pair of instants for the predicate AND the conversion's deletion inference.
        val startMs = windowStart.atStartMs(zone)
        val endMs = windowEnd.plus(DatePeriod(days = 1)).atStartMs(zone)
        val predicate = store.predicateForEventsWithStartDate(
            startDate = dateOf(startMs),
            endDate = dateOf(endMs),
            calendars = listOf(ekCalendar),
        )
        val masters = mutableMapOf<String, EKEvent?>()
        val rows = store.eventsMatchingPredicate(predicate)
            .filterIsInstance<EKEvent>()
            .map { occurrence ->
                val id = occurrence.eventIdentifier.orEmpty()
                // `eventWithIdentifier` answers with a series' FIRST occurrence — the rule's anchor.
                // ⚠ By the SERIES id: a moved occurrence's own id resolves to itself, rule-less.
                val masterId = AppleConversion.seriesIdentifier(id)
                val master = masters.getOrPut(masterId) { store.eventWithIdentifier(masterId) } ?: occurrence
                val rule = (master.recurrenceRules ?: occurrence.recurrenceRules)
                    ?.filterIsInstance<EKRecurrenceRule>()?.firstOrNull()
                AppleOccurrenceRow(
                    eventIdentifier = id,
                    externalIdentifier = occurrence.calendarItemExternalIdentifier,
                    title = occurrence.title.orEmpty(),
                    notes = occurrence.notes.orEmpty(),
                    location = occurrence.location.orEmpty(),
                    startMs = msOf(occurrence.startDate),
                    endMs = msOf(occurrence.endDate),
                    allDay = occurrence.allDay,
                    timeZoneId = occurrence.timeZone?.name,
                    occurrenceDateMs = msOf(occurrence.occurrenceDate ?: occurrence.startDate),
                    isDetached = occurrence.isDetached(),
                    recurrence = rule?.let(::recurrenceOf),
                    seriesStartMs = msOf(master.startDate),
                    seriesEndMs = msOf(master.endDate),
                    alarmOffsetsSeconds = (master.alarms ?: emptyList<Any>())
                        .filterIsInstance<EKAlarm>()
                        .filter { it.absoluteDate == null }
                        .map { it.relativeOffset.toLong() },
                    // ⚠ Only from a real master, never the occurrence standing in for one.
                    series = master.takeIf { it !== occurrence || !occurrence.isDetached() }?.let {
                        AppleSeriesFields(
                            title = it.title.orEmpty(),
                            notes = it.notes.orEmpty(),
                            location = it.location.orEmpty(),
                            allDay = it.allDay,
                            timeZoneId = it.timeZone?.name,
                        )
                    },
                )
            }
        AppleConversion.snapshot(rows, startMs, endMs, zone)
    }

    private fun requireAccess() {
        val status = EKEventStore.authorizationStatusForEntityType(EKEntityType.EKEntityTypeEvent)
        // `Authorized` before iOS 17, `FullAccess` from it (the same raw value, 3). ⚠ Write-only
        // access (iOS 17) cannot read events, so it is a refusal here.
        if (status != EKAuthorizationStatusFullAccess && status != EKAuthorizationStatusAuthorized) {
            throw ExternalCalendarAccessDenied()
        }
    }

    private fun recurrenceOf(rule: EKRecurrenceRule): AppleRecurrenceRow = AppleRecurrenceRow(
        frequency = rule.frequency.value.toInt(),
        interval = rule.interval.toInt(),
        daysOfTheWeek = (rule.daysOfTheWeek ?: emptyList<Any>())
            .filterIsInstance<EKRecurrenceDayOfWeek>()
            // EKWeekday counts Sunday = 1 … Saturday = 7; ISO counts Monday = 1 … Sunday = 7.
            .map { day ->
                val w = day.dayOfTheWeek.toInt()
                (if (w == 1) 7 else w - 1) to day.weekNumber.toInt()
            },
        daysOfTheMonth = rule.daysOfTheMonth.ints(),
        monthsOfTheYear = rule.monthsOfTheYear.ints(),
        setPositions = rule.setPositions.ints(),
        daysOfTheYear = rule.daysOfTheYear.ints(),
        weeksOfTheYear = rule.weeksOfTheYear.ints(),
        // EKWeekday-numbered (Sunday = 1), 0 when unset.
        firstDayOfWeek = rule.firstDayOfTheWeek.toInt().let { w -> if (w == 0) 0 else if (w == 1) 7 else w - 1 },
        count = rule.recurrenceEnd?.occurrenceCount?.toInt() ?: 0,
        untilMs = rule.recurrenceEnd?.endDate?.let(::msOf),
    )

    private fun List<*>?.ints(): List<Int> = this.orEmpty().filterIsInstance<NSNumber>().map { it.intValue }

    private fun argbOf(calendar: EKCalendar): Int? {
        val color = calendar.CGColor ?: return null
        if (CGColorGetNumberOfComponents(color) < 3uL) return null
        val c = CGColorGetComponents(color) ?: return null
        fun channel(i: Int) = (c[i] * 255.0).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (channel(0) shl 16) or (channel(1) shl 8) or channel(2)
    }

    private fun sourceTypeName(type: EKSourceType?): String = when (type) {
        EKSourceType.EKSourceTypeLocal -> "local"
        EKSourceType.EKSourceTypeExchange -> "exchange"
        EKSourceType.EKSourceTypeCalDAV -> "calDAV"
        EKSourceType.EKSourceTypeMobileMe -> "mobileMe"
        EKSourceType.EKSourceTypeSubscribed -> "subscribed"
        EKSourceType.EKSourceTypeBirthdays -> "birthdays"
        else -> "other"
    }

    private fun msOf(date: NSDate?): Long = ((date?.timeIntervalSince1970 ?: 0.0) * 1000).toLong()

    private fun dateOf(ms: Long): NSDate = NSDate.dateWithTimeIntervalSince1970(ms / 1000.0)

    private fun kotlinx.datetime.LocalDate.atStartMs(zone: TimeZone): Long =
        atStartOfDayIn(zone).toEpochMilliseconds()

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        /** How far back a read reaches — the doc's Q3: twelve months. */
        val BACK = DatePeriod(years = 1)

        /** How far ahead. ⚠ BACK + AHEAD must stay under EventKit's four-year predicate limit. */
        val AHEAD = DatePeriod(years = 2)
    }
}
