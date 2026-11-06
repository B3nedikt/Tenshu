package org.tenshu.calendar.common.recurrence

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime

/**
 * One occurrence, after the series' rule has been expanded and any
 * [org.tenshu.calendar.EventOverride] applied — the shape a window query
 * actually answers with.
 *
 * ⚠ [startLocal] / [endLocal] are the **effective** values, so a moved
 * occurrence reports where it moved *to*. [recurrenceId] still identifies it by
 * its **original** start, because that is the key an override is filed under and
 * the handle a caller needs to move or skip it again.
 */
data class ResolvedOccurrence(
    val eventId: String,
    val calendarId: String,
    val recurrenceId: String,
    val title: String,
    val location: String,
    val notes: String,
    val startLocal: LocalDateTime,
    val endLocal: LocalDateTime,
    val zoneId: String,
    val allDay: Boolean,
    /** True when an override supplied any of the values above. */
    val isOverridden: Boolean,
    /**
     * The series' raw `RRULE`, or `""` for a one-off.
     *
     * ⚠ The **rule**, not merely a "does it recur" flag, because the editor
     * (CA-3) has to classify it to decide which recurrence choice to show — and
     * a flag would have sent it back for a second read of a row it already had.
     * ⚠⚠ It is the *series'* rule even on an overridden occurrence: an override
     * moves one occurrence, it never re-writes the cadence.
     */
    val recurrenceRule: String,
) {
    /** True when the event carries a recurrence rule at all. */
    val isRecurring: Boolean get() = recurrenceRule.isNotBlank()
}

/** The stored halves of one series, as the resolver needs them. */
data class SeriesInput(
    val eventId: String,
    val calendarId: String,
    val title: String,
    val location: String,
    val notes: String,
    val startLocal: String,
    val endLocal: String,
    val timeZoneId: String,
    val allDay: Boolean,
    val recurrenceRule: String,
    val exceptionDates: String,
)

/** One override row, as the resolver needs it. `null` means "inherit". */
data class OverrideInput(
    val eventId: String,
    val recurrenceId: String,
    val startLocal: String?,
    val endLocal: String?,
    val title: String?,
    val location: String?,
    val notes: String?,
)

/**
 * Turns stored rows into the occurrences falling in a civil date window.
 *
 * ⭐ **Pure, and it lives here rather than in the repository on purpose.** The
 * app's window query is not the only consumer: CA-5's reminder scheduler runs
 * proxy-side and needs exactly the same "what happens between these dates"
 * answer. Two implementations of that would drift, and the one that drifted
 * would be the one nobody was looking at.
 *
 * ⚠ Clockless, like [RecurrenceExpander] — the window is a parameter. Nothing
 * here asks what today is.
 */
object OccurrenceResolver {

    /**
     * Every occurrence of [series] intersecting `[windowStart, windowEnd]`,
     * with [overrides] applied, sorted by effective start.
     *
     * ⚠ Sorted by the **effective** start, so a moved occurrence sorts where it
     * now is rather than where the rule put it — which is what a day or week
     * view needs, and is *not* the same order the expander produced.
     */
    fun resolve(
        series: List<SeriesInput>,
        overrides: List<OverrideInput>,
        windowStart: LocalDate,
        windowEnd: LocalDate,
    ): List<ResolvedOccurrence> {
        val overridesByKey = overrides.associateBy { it.eventId to it.recurrenceId }

        return series
            .flatMap { input -> resolveOne(input, overridesByKey, windowStart, windowEnd) }
            .sortedWith(compareBy({ it.startLocal.toString() }, { it.eventId }))
    }

    private fun resolveOne(
        input: SeriesInput,
        overridesByKey: Map<Pair<String, String>, OverrideInput>,
        windowStart: LocalDate,
        windowEnd: LocalDate,
    ): List<ResolvedOccurrence> {
        val time = EventTime.parse(
            startLocal = input.startLocal,
            endLocal = input.endLocal,
            zoneId = input.timeZoneId,
            allDay = input.allDay,
        ) ?: return emptyList()   // ⚠ An unparseable row is skipped, never fatal.

        val rule = RecurrenceRule.parse(input.recurrenceRule)

        return expandOccurrences(
            time = time,
            rule = rule,
            windowStart = windowStart,
            windowEnd = windowEnd,
            exceptions = parseExceptionDates(input.exceptionDates),
        ).mapNotNull { occurrence ->
            val override = overridesByKey[input.eventId to occurrence.recurrenceId]
            val resolved = apply(input, occurrence, override)

            // ⚠⚠ Re-check the window *after* applying the override. An
            // occurrence moved out of the window must not be reported, and one
            // moved in from outside it is genuinely missed — a known limit of
            // expanding first and overriding second, called out below.
            if (resolved.startLocal.date in windowStart..windowEnd) resolved else null
        }
    }

    private fun apply(
        input: SeriesInput,
        occurrence: EventOccurrence,
        override: OverrideInput?,
    ): ResolvedOccurrence {
        // ⚠ `?:` is correct here precisely because absent stayed absent at the
        // storage layer: `null` inherits, and an explicit "" overrides with
        // empty rather than falling through to the series' value.
        val start = override?.startLocal?.let { parseLocal(it) } ?: occurrence.startLocal
        val end = override?.endLocal?.let { parseLocal(it) } ?: run {
            // A moved occurrence with no explicit end keeps the series' length.
            if (override?.startLocal != null) start.plusMinutesOf(occurrence) else occurrence.endLocal
        }

        return ResolvedOccurrence(
            eventId = input.eventId,
            calendarId = input.calendarId,
            recurrenceId = occurrence.recurrenceId,
            title = override?.title ?: input.title,
            location = override?.location ?: input.location,
            notes = override?.notes ?: input.notes,
            startLocal = start,
            endLocal = end,
            zoneId = input.timeZoneId,
            allDay = input.allDay,
            isOverridden = override != null,
            recurrenceRule = input.recurrenceRule,
        )
    }

    /** The original occurrence's length, re-applied at a new start. */
    private fun LocalDateTime.plusMinutesOf(occurrence: EventOccurrence): LocalDateTime {
        val minutes = minutesBetween(occurrence.startLocal, occurrence.endLocal)
        return addMinutes(this, minutes)
    }

    private fun parseLocal(raw: String): LocalDateTime? =
        runCatching { LocalDateTime.parse(raw.trim()) }.getOrNull()
}
