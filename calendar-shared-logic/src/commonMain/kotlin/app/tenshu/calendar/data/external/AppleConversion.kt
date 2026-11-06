package app.tenshu.calendar.data.external

import kotlin.time.Instant
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.tenshu.calendar.common.ics.ExportableEvent
import org.tenshu.calendar.common.ics.IcsExport
import org.tenshu.calendar.common.ics.ImportedEvent
import org.tenshu.calendar.common.ics.serializeIcs
import org.tenshu.calendar.common.recurrence.Frequency
import org.tenshu.calendar.common.recurrence.RecurrenceExpander
import org.tenshu.calendar.common.recurrence.RecurrenceRule
import org.tenshu.calendar.common.recurrence.WeekDayNum
import org.tenshu.calendar.common.recurrence.format
import org.tenshu.calendar.common.recurrence.formatRecurrenceId

/**
 * One `EKCalendar`, as the iOS reader hands it over (`calendar-external-sources.md` CX-2).
 *
 * ⚠ Plain values, no EventKit types — the [ProviderCalendarRow] discipline: the reader copies,
 * [AppleConversion] decides, in common code a JVM test can reach.
 */
data class AppleCalendarRow(
    /** `calendarIdentifier` — ⚠ device-local, and may change after an account re-sync (§5.11). */
    val identifier: String,
    val title: String,
    /** `source.title` — the account ("iCloud", "Gmail", an Exchange address). */
    val sourceTitle: String,
    /** `source.sourceType`, as a name ("calDAV", "exchange", "local", "subscribed", "birthdays"). */
    val sourceType: String,
    val colorArgb: Int?,
)

/**
 * `EKRecurrenceRule`, structured. ⚠ EventKit never hands over an RRULE string; ours is formatted
 * from this ([AppleConversion.ruleOf]).
 */
data class AppleRecurrenceRow(
    /** `EKRecurrenceFrequency`: 0 daily, 1 weekly, 2 monthly, 3 yearly. */
    val frequency: Int,
    val interval: Int = 1,
    /** `daysOfTheWeek`: (ISO weekday, 1 = Monday … 7 = Sunday; week number, 0 = every week). */
    val daysOfTheWeek: List<Pair<Int, Int>> = emptyList(),
    val daysOfTheMonth: List<Int> = emptyList(),
    val monthsOfTheYear: List<Int> = emptyList(),
    /** `setPositions` — ⚠ our expander has no `BYSETPOS`; such a rule is skipped, never guessed. */
    val setPositions: List<Int> = emptyList(),
    /** `daysOfTheYear` — ⚠ no `BYYEARDAY` either: skipped, like [setPositions]. */
    val daysOfTheYear: List<Int> = emptyList(),
    /** `weeksOfTheYear` — ⚠ no `BYWEEKNO` either: skipped, like [setPositions]. */
    val weeksOfTheYear: List<Int> = emptyList(),
    /**
     * `firstDayOfTheWeek` as an ISO weekday (1 = Monday … 7 = Sunday); 0 = not set. ⚠ It decides
     * which weeks an every-other-week rule with several weekdays lands in.
     */
    val firstDayOfWeek: Int = 0,
    /** `recurrenceEnd.occurrenceCount`; 0 when the rule ends by date or never. */
    val count: Int = 0,
    /** `recurrenceEnd.endDate`, epoch ms. */
    val untilMs: Long? = null,
)

/**
 * One occurrence an `EKEventStore` predicate query returned, plus what the reader learned about its
 * series.
 *
 * ⚠⚠ EventKit returns **occurrences**, never series: a weekly meeting in a year-long window is 52
 * rows. [AppleConversion] folds them back into one series (§5.6).
 */
data class AppleOccurrenceRow(
    /** `eventIdentifier` — shared by every occurrence of a series; device-local. */
    val eventIdentifier: String,
    /** `calendarItemExternalIdentifier` — the server's / iCalendar `UID`, where there is one. */
    val externalIdentifier: String?,
    val title: String,
    val notes: String,
    val location: String,
    val startMs: Long,
    val endMs: Long,
    val allDay: Boolean,
    /** `timeZone.name`; null for a floating event. ⚠ Ignored for an all-day event. */
    val timeZoneId: String?,
    /** `occurrenceDate` — where this occurrence *would* start; differs from [startMs] once moved. */
    val occurrenceDateMs: Long,
    /** `isDetached` — this occurrence was changed on its own (an override). */
    val isDetached: Boolean,
    /** The series' rule, read off the master; null for a one-off. */
    val recurrence: AppleRecurrenceRow?,
    /** The series' first start and end (the master event's), epoch ms — the rule's anchor. */
    val seriesStartMs: Long,
    val seriesEndMs: Long,
    /** `alarms[].relativeOffset`, seconds; negative is before the start. */
    val alarmOffsetsSeconds: List<Long> = emptyList(),
    /**
     * The series master's own fields, when the reader resolved one. ⚠ Needed because the window
     * may hold only edited occurrences of a series, and an edit's title must not become the
     * series'. Null for a one-off, or a row shaped without a master.
     */
    val series: AppleSeriesFields? = null,
)

/** What a series master says about the whole series, separate from any one occurrence. */
data class AppleSeriesFields(
    val title: String,
    val notes: String,
    val location: String,
    val allDay: Boolean,
    val timeZoneId: String?,
)

/**
 * EventKit → the `ImportedEvent`s every other path writes (`calendar-external-sources.md` §5.6).
 *
 * ⚠⚠ **EventKit exposes no exception dates** — checked against the iOS 27.2 SDK headers, which offer
 * only `isDetached` and `occurrenceDate`. A deleted occurrence is simply not returned, so a series'
 * `EXDATE`s are **inferred**: expand the rule over the window the reader queried and keep the dates
 * that came back neither as an occurrence nor as a moved one. That is sound only inside that window,
 * which is why the window is a parameter and never assumed.
 *
 * ⚠ All-day events are dates in no zone (§5.4): EventKit reports them at local midnight, so they
 * are read in [deviceZone], never in UTC — the opposite of Android's provider.
 */
object AppleConversion {

    fun calendar(row: AppleCalendarRow): ExternalCalendar = ExternalCalendar(
        kind = ExternalSourceKind.APPLE_EVENTKIT,
        // ⚠ (account, title), never the identifier: `calendarIdentifier` is device-local and can
        // change after a re-sync (§5.11), so a second iPhone — or the same one later — would mint a
        // second copy. A rename at the source therefore reads as a new calendar.
        sourceKey = "${row.sourceType}|${row.sourceTitle}|${row.title}",
        handle = row.identifier,
        name = row.title.ifBlank { row.sourceTitle.ifBlank { "Calendar" } },
        accountName = row.sourceTitle,
        accountType = row.sourceType,
        colorArgb = row.colorArgb,
        // EventKit lists only what the OS syncs; there is no visible-but-not-synced state.
        holdsEvents = true,
    )

    /**
     * @param windowStartMs the predicate's start instant, inclusive.
     * @param windowEndMs the predicate's end instant, exclusive. ⚠ Instants, not dates: an event in
     *   another zone sees the window's edges on different local dates, and a deletion may only be
     *   inferred for an occurrence the predicate would actually have returned.
     */
    fun snapshot(
        occurrences: List<AppleOccurrenceRow>,
        windowStartMs: Long,
        windowEndMs: Long,
        deviceZone: TimeZone,
    ): ExternalSnapshot {
        var skipped = 0
        val unreadable = mutableSetOf<String>()
        val events = mutableListOf<ImportedEvent>()
        val observed = mutableMapOf<String, Set<String>>()

        occurrences.groupBy { it.seriesKey() }.values.forEach { rows ->
            // ⚠ The series' own fields come from an occurrence that was NOT moved: a detached one
            // carries its own title and, before the reader resolves its master, no rule.
            val first = rows.filter { !it.isDetached }.minByOrNull { it.occurrenceDateMs }
                ?: rows.minBy { it.occurrenceDateMs }
            val uid = first.externalIdentifier?.let(::seriesIdentifier)?.trim()?.takeIf { it.isNotEmpty() }
                ?: "apple-event-${seriesIdentifier(first.eventIdentifier)}"
            // ⚠ The master's fields when the reader resolved them, else the chosen occurrence's.
            val master = first.series ?: AppleSeriesFields(
                first.title, first.notes, first.location, first.allDay, first.timeZoneId,
            )
            val allDay = master.allDay
            val namedZone = master.timeZoneId?.let(::zoneOf)
            val zone = if (allDay) deviceZone else namedZone ?: deviceZone
            val zoneId = if (allDay || namedZone == null) "" else master.timeZoneId.orEmpty()
            fun local(ms: Long): LocalDateTime {
                val t = Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone)
                return if (allDay) LocalDateTime(t.date, LocalTime(0, 0)) else t
            }

            val rule = first.recurrence?.let { recurrence ->
                ruleOf(recurrence, zone) ?: run {
                    // A rule we cannot express (BYSETPOS): skipped and named, never flattened
                    // into a one-off that would silently drop every other occurrence.
                    skipped++
                    unreadable += uid
                    return@forEach
                }
            }

            val seriesStart = local(first.seriesStartMs)
            if (rule != null) observed[uid] = rows.mapTo(mutableSetOf()) { local(it.occurrenceDateMs).date.toString() }
            val exdates = if (rule == null) emptyList() else {
                val returned = rows.mapTo(mutableSetOf()) { local(it.occurrenceDateMs).date }
                // A day of slack each side in the event's zone, then cut by instant.
                val from = local(windowStartMs).date.minus(DatePeriod(days = 1))
                val to = local(windowEndMs).date.plus(DatePeriod(days = 1))
                RecurrenceExpander.expand(rule, seriesStart.date, from, to)
                    .filter { date ->
                        val at = LocalDateTime(date, seriesStart.time).toInstant(zone).toEpochMilliseconds()
                        at in windowStartMs until windowEndMs
                    }
                    .filter { it !in returned }
            }

            events += sidecarred(ImportedEvent(
                uid = uid,
                title = master.title,
                startLocal = formatRecurrenceId(seriesStart),
                endLocal = formatRecurrenceId(local(first.seriesEndMs)),
                timeZoneId = zoneId,
                allDay = allDay,
                location = master.location,
                notes = master.notes,
                recurrenceRule = rule?.format().orEmpty(),
                exceptionDates = exdates.sorted().joinToString(","),
                recurrenceId = null,
                // Negative offsets are *before*, ours are positive-before.
                reminderMinutes = first.alarmOffsetsSeconds.map { (-it / 60).toInt() }.distinct().sorted(),
                raw = "",
            ))
            rows.filter { it.isDetached }.forEach { row ->
                events += ImportedEvent(
                    uid = uid,
                    title = row.title,
                    startLocal = formatRecurrenceId(local(row.startMs)),
                    endLocal = formatRecurrenceId(local(row.endMs)),
                    timeZoneId = zoneId,
                    allDay = allDay,
                    location = row.location,
                    notes = row.notes,
                    recurrenceRule = "",
                    exceptionDates = "",
                    recurrenceId = formatRecurrenceId(local(row.occurrenceDateMs)),
                    reminderMinutes = emptyList(),
                    raw = "",
                )
            }
        }
        return ExternalSnapshot(
            events = events,
            skipped = skipped,
            unreadableUids = unreadable,
            // ⚠⚠ EventKit read only this span; a mirror must not read absence outside it as deletion.
            window = SnapshotWindow(windowStartMs, windowEndMs),
            observedOccurrenceDates = observed,
        )
    }

    /**
     * The `VEVENT` a copied series' sidecar holds (§4.1), as `ProviderConversion` writes it.
     *
     * ⚠⚠ It carries the source's `UID`: the stored id is namespaced, and the export reads the
     * identity back from here — without it an exported copy re-imported at the source duplicates
     * every event instead of matching it.
     */
    private fun sidecarred(event: ImportedEvent): ImportedEvent {
        val component = IcsExport.eventComponent(
            ExportableEvent(
                eventId = event.uid,
                title = event.title,
                startLocal = event.startLocal,
                endLocal = event.endLocal,
                timeZoneId = event.timeZoneId,
                allDay = event.allDay,
                location = event.location,
                notes = event.notes,
                recurrenceRule = event.recurrenceRule,
                exceptionDates = event.exceptionDates,
                reminderMinutes = event.reminderMinutes,
            )
        )
        return event.copy(raw = serializeIcs(component).trimEnd())
    }

    /** [AppleRecurrenceRow] → our rule; null for what the expander cannot express. */
    fun ruleOf(row: AppleRecurrenceRow, zone: TimeZone): RecurrenceRule? {
        if (row.setPositions.isNotEmpty() || row.daysOfTheYear.isNotEmpty() || row.weeksOfTheYear.isNotEmpty()) {
            return null
        }
        // ⚠ A yearly day selector with no month spans the whole YEAR in EventKit ("the 20th Monday",
        // "every Monday", "the 1st and 15th"); our expander pins a month-less yearly rule to the
        // anchor's month and counts ordinals within it, so each would silently drop occurrences.
        val yearlyDaySelector = row.daysOfTheWeek.isNotEmpty() || row.daysOfTheMonth.isNotEmpty()
        if (row.frequency == 3 && row.monthsOfTheYear.isEmpty() && yearlyDaySelector) {
            return null
        }
        val frequency = when (row.frequency) {
            0 -> Frequency.DAILY
            1 -> Frequency.WEEKLY
            2 -> Frequency.MONTHLY
            3 -> Frequency.YEARLY
            else -> return null
        }
        return RecurrenceRule(
            frequency = frequency,
            interval = row.interval.coerceAtLeast(1),
            count = row.count.takeIf { it > 0 },
            until = if (row.count > 0) null else row.untilMs?.let {
                Instant.fromEpochMilliseconds(it).toLocalDateTime(zone).date
            },
            byDay = row.daysOfTheWeek.map { (weekday, week) -> WeekDayNum(week, DayOfWeek(weekday)) },
            byMonthDay = row.daysOfTheMonth,
            byMonth = row.monthsOfTheYear,
            weekStart = row.firstDayOfWeek.takeIf { it in 1..7 }?.let { DayOfWeek(it) } ?: DayOfWeek.MONDAY,
        )
    }

    /**
     * The series an EventKit identifier belongs to.
     *
     * ⚠⚠ A **moved** occurrence gets identifiers of its own — `eventIdentifier` and
     * `calendarItemExternalIdentifier` both gain a `/RID=<seconds>` suffix (seen on the iOS 27.2
     * simulator's local calendar), and `eventWithIdentifier` on it answers with the occurrence
     * itself, rule-less. Grouped as-is it becomes a second series, and the slot it left reads as
     * a deleted occurrence. Stripping the suffix is what folds it back in as an override.
     */
    fun seriesIdentifier(identifier: String): String = identifier.substringBefore(RID_SUFFIX)

    private const val RID_SUFFIX = "/RID="

    private fun AppleOccurrenceRow.seriesKey(): String =
        externalIdentifier?.let(::seriesIdentifier)?.trim()?.takeIf { it.isNotEmpty() }
            ?: seriesIdentifier(eventIdentifier)

    private fun zoneOf(id: String): TimeZone? = runCatching { TimeZone.of(id) }.getOrNull()
}
