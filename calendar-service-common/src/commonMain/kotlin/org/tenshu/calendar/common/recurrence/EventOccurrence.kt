package org.tenshu.calendar.common.recurrence

import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant

/**
 * The parsed time half of an `Event` (`calendar-app.md` §3 D3).
 *
 * ⚠⚠ **Canonical form is civil, not absolute.** [startLocal] / [endLocal] are
 * wall-clock date-times; the instant is *derived*, never stored. A stored
 * instant becomes wrong the moment a zone's rules change — and the IANA
 * database changes several times a year — with nothing to report it.
 *
 * ⚠ [zoneId] empty means **floating**: "09:00 wherever I am". Resolving a
 * floating time therefore needs the reader's zone passed in explicitly, which
 * is why [startInstant] takes a `readerZone` rather than reaching for a
 * system default. That parameter is the whole point — it makes "whose zone?"
 * a question the caller must answer.
 */
data class EventTime(
    val startLocal: LocalDateTime,
    val endLocal: LocalDateTime,
    /** IANA id, or `""` for floating. Ignored when [allDay]. */
    val zoneId: String,
    val allDay: Boolean,
) {
    val isFloating: Boolean get() = !allDay && zoneId.isEmpty()

    /** Duration in whole minutes, used to carry length onto each occurrence. */
    val durationMinutes: Long
        get() {
            val startMin = startLocal.date.toEpochDays().toLong() * 1440L +
                startLocal.hour * 60L + startLocal.minute
            val endMin = endLocal.date.toEpochDays().toLong() * 1440L +
                endLocal.hour * 60L + endLocal.minute
            return (endMin - startMin).coerceAtLeast(0L)
        }

    companion object {
        /**
         * Parses the four stored strings. Returns `null` if the local
         * date-times do not parse.
         *
         * ⚠ A missing/blank `endLocal` is never an error — an import frequently
         * omits `DTEND`, and refusing to read the row would lose the event
         * entirely. But the *implied* end differs by kind, and RFC 5545 is
         * explicit about it:
         *
         * - **all-day** → **one day**, `DTEND` being exclusive. ⚠⚠ Treating it
         *   as zero-length is the bug this branch exists to prevent: an
         *   imported public-holiday feed (which routinely omits `DTEND`) would
         *   render every entry as a zero-length blip instead of a day.
         * - **timed** → the same instant as the start, i.e. a point in time.
         */
        fun parse(
            startLocal: String,
            endLocal: String,
            zoneId: String,
            allDay: Boolean,
        ): EventTime? {
            val start = parseLocal(startLocal) ?: return null
            val end = parseLocal(endLocal) ?: impliedEnd(start, allDay)
            return EventTime(start, end, zoneId, allDay)
        }

        private fun impliedEnd(start: LocalDateTime, allDay: Boolean): LocalDateTime =
            if (allDay) LocalDateTime(start.date.plus(1, DateTimeUnit.DAY), LocalTime(0, 0))
            else start

        /** Accepts `2026-08-05T09:00`, `…T09:00:00`, and bare `2026-08-05`. */
        private fun parseLocal(raw: String): LocalDateTime? {
            val text = raw.trim()
            if (text.isEmpty()) return null
            runCatching { return LocalDateTime.parse(text) }
            runCatching { return LocalDateTime(LocalDate.parse(text), LocalTime(0, 0)) }
            return null
        }
    }
}

/**
 * One expanded occurrence: a concrete civil start/end, plus whatever the
 * series said about zone and all-day-ness.
 *
 * ⚠ [recurrenceId] is the occurrence's **original** start, formatted the way
 * `EventOverride.recurrenceId` stores it — so matching an override to an
 * occurrence is a string comparison and cannot drift between the two sides.
 * It is deliberately *not* recomputed from [startLocal], because an override
 * that moves an occurrence changes the latter and must not change the former.
 */
data class EventOccurrence(
    val startLocal: LocalDateTime,
    val endLocal: LocalDateTime,
    val zoneId: String,
    val allDay: Boolean,
    val recurrenceId: String,
) {

    /**
     * The UTC instant this occurrence starts at.
     *
     * @param readerZone used only when the event is floating or all-day — i.e.
     *   when the event itself does not name a zone. ⚠ Passing the device's own
     *   zone here is correct for *rendering*; it is wrong for anything durable,
     *   which is exactly why nothing stores the result.
     * @return `null` if [zoneId] is set but unknown to this platform's tz
     *   database — an honest answer, since guessing a zone would silently place
     *   the event at the wrong moment.
     */
    fun startInstant(readerZone: TimeZone): Instant? = instantOf(startLocal, readerZone)

    /** As [startInstant], for the end boundary. */
    fun endInstant(readerZone: TimeZone): Instant? = instantOf(endLocal, readerZone)

    private fun instantOf(local: LocalDateTime, readerZone: TimeZone): Instant? {
        val zone = when {
            allDay || zoneId.isEmpty() -> readerZone
            else -> runCatching { TimeZone.of(zoneId) }.getOrNull() ?: return null
        }
        // ⚠ A local time that does not exist (the spring-forward gap) or exists
        // twice (autumn fall-back) is resolved by kotlinx-datetime's own rule.
        // Deliberately not hand-rolled — the same call the routines engine
        // makes, and for the same reason: an event landing at a slightly
        // shifted instant beats a calendar with its own theory of DST.
        return local.toInstant(zone)
    }
}

/**
 * Expands [time] + [rule] into occurrences overlapping the civil window
 * `[windowStart, windowEnd]`.
 *
 * ⭐ The window is widened by one day at the start before the rule walk, so a
 * multi-day event that *began* before the window but is still running inside it
 * is not missed. ⚠ Only by one day: widening by the event's own duration would
 * be more correct for very long events, and is a deliberate CA-1 concern once
 * there is a query layer to measure — a month view that silently walks back a
 * year for one open-ended event is the worse failure.
 */
fun expandOccurrences(
    time: EventTime,
    rule: RecurrenceRule?,
    windowStart: LocalDate,
    windowEnd: LocalDate,
    exceptions: Set<LocalDate> = emptySet(),
): List<EventOccurrence> {
    val dates = RecurrenceExpander.expand(
        rule = rule,
        start = time.startLocal.date,
        windowStart = windowStart.plus(-1, DateTimeUnit.DAY),
        windowEnd = windowEnd,
        exceptions = exceptions,
    )

    val startTime = time.startLocal.time
    val duration = time.durationMinutes

    return dates.map { date ->
        val start = LocalDateTime(date, startTime)
        EventOccurrence(
            startLocal = start,
            endLocal = start.plusMinutes(duration),
            zoneId = time.zoneId,
            allDay = time.allDay,
            recurrenceId = formatRecurrenceId(start),
        )
    }
}

/**
 * The canonical `recurrenceId` text for an occurrence start.
 *
 * ⚠ Single-sourced on purpose: this string is a *key* joining `EventOverride`
 * rows to occurrences, so the two sides formatting it differently would
 * silently orphan every override. Seconds are dropped because occurrence starts
 * are minute-granular by construction.
 */
fun formatRecurrenceId(start: LocalDateTime): String {
    val y = start.year.toString().padStart(4, '0')
    val mo = start.month.number.toString().padStart(2, '0')
    val d = start.day.toString().padStart(2, '0')
    val h = start.hour.toString().padStart(2, '0')
    val mi = start.minute.toString().padStart(2, '0')
    return "$y-$mo-${d}T$h:$mi"
}

/** Parses a `,`-delimited `Event.exceptionDates` into dates, ignoring junk. */
fun parseExceptionDates(raw: String): Set<LocalDate> =
    raw.split(',')
        .mapNotNull { entry ->
            val text = entry.trim()
            if (text.isEmpty()) null
            else runCatching { LocalDate.parse(text) }.getOrNull()
                ?: runCatching { LocalDateTime.parse(text).date }.getOrNull()
        }
        .toSet()

/** Whole minutes between two civil date-times. */
internal fun minutesBetween(from: LocalDateTime, to: LocalDateTime): Long {
    val f = from.date.toEpochDays().toLong() * 1440L + from.hour * 60L + from.minute
    val t = to.date.toEpochDays().toLong() * 1440L + to.hour * 60L + to.minute
    return t - f
}

/** [value] advanced by [minutes], rolling the date as needed. */
internal fun addMinutes(value: LocalDateTime, minutes: Long): LocalDateTime =
    value.plusMinutes(minutes)

private fun LocalDateTime.plusMinutes(minutes: Long): LocalDateTime {
    val totalMinutes = date.toEpochDays().toLong() * 1440L + hour * 60L + this.minute + minutes
    val dayOffset = floorDivLong(totalMinutes, 1440L)
    val minuteOfDay = (totalMinutes - dayOffset * 1440L).toInt()
    val newDate = LocalDate.fromEpochDays(dayOffset.toInt())
    return LocalDateTime(newDate, LocalTime(minuteOfDay / 60, minuteOfDay % 60))
}

/**
 * Floor division for [Long].
 *
 * ⚠ Hand-rolled rather than `Math.floorDiv`, which is JVM-only and would break
 * the iOS and JS targets — and rather than truncating `/`, which rounds toward
 * zero and would put a negative epoch-day on the wrong side of midnight.
 */
private fun floorDivLong(a: Long, b: Long): Long {
    val q = a / b
    return if (a % b != 0L && ((a xor b) < 0L)) q - 1 else q
}
