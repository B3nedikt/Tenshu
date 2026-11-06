package app.tenshu.calendar.model

import kotlinx.datetime.LocalDate
import org.tenshu.calendar.common.recurrence.ResolvedOccurrence

/** One day's worth of the agenda list. */
data class CalendarAgendaSection(
    /**
     * ⚠ The day as an epoch-day count, not a `LocalDate`. It is the list's identity, and an
     * identity has to be something a renderer can hash — SwiftUI's `ForEach` needs `Hashable`,
     * which a bridged Kotlin class does not conform to. A number needs no conformance and cannot
     * be conjured back into a *different* date by accident. (`Long`, which is what
     * `LocalDate.toEpochDays` returns and what Swift sees as `Int64`.)
     */
    val epochDay: Long,
    val date: LocalDate,
    /** Already localised — "Today", "Tomorrow", or the medium date. */
    val heading: String,
    val occurrences: List<ResolvedOccurrence>,
)

/**
 * The agenda's shape: which days appear, in what order, with what heading (CA-8a).
 *
 * ⭐ **Pure, common, and tested, because the alternative is a renderer doing date arithmetic.**
 * The iOS shell cannot be compiled or tested in this repo's environment, so every decision that
 * could be wrong — which days show, how a heading reads, what a time column says — lives here and
 * the SwiftUI view draws the answer. Same discipline as `WidgetAgendaBuilder` (CA-7c), and for the
 * same reason.
 *
 * ⚠ A Compose agenda could use this too. It does not yet — `CalendarScreen` groups inline — and
 * folding it over is a change to a shipped screen that does not belong in an iOS phase.
 */
object CalendarAgenda {

    /**
     * Groups [occurrences] into day sections.
     *
     * ⚠⚠ **Days come from the occurrences, not from the window.** An agenda padded out with empty
     * day headers is not an agenda — it is a calendar with the grid removed. This is the one place
     * that distinction is made, and it is why the caller passes what it loaded rather than a range.
     *
     * ⚠ Anything before [today] is dropped even if the window handed it over: an agenda is what is
     * *coming*, and the load window reaches one day back for the time grid's sake (see
     * `CalendarViewModel.loadWindow`).
     */
    fun sections(
        occurrences: List<ResolvedOccurrence>,
        today: LocalDate,
    ): List<CalendarAgendaSection> = occurrences
        .filter { it.startLocal.date >= today }
        .groupBy { it.startLocal.date }
        .toList()
        .sortedBy { (date, _) -> date }
        .map { (date, rows) ->
            CalendarAgendaSection(
                epochDay = date.toEpochDays(),
                date = date,
                heading = heading(date, today),
                // ⚠ Sorted again inside the day: the repository sorts by effective start across
                // the whole window, which is the same order within a day — but relying on that
                // makes this list's order a property of somebody else's contract.
                occurrences = rows.sortedWith(compareBy({ it.startLocal.time }, { it.title })),
            )
        }

    /**
     * "Today" / "Tomorrow" / the medium date.
     *
     * ⚠ Only two relative labels. "Yesterday" cannot occur here (the filter above), and going
     * further — "in 3 days" — trades a date the user can act on for arithmetic they have to redo.
     */
    fun heading(day: LocalDate, today: LocalDate): String =
        when (day.toEpochDays() - today.toEpochDays()) {
            0L -> TODAY
            1L -> TOMORROW
            else -> CalendarDateFormat.mediumDate(day)
        }

    /**
     * The agenda's left column: a locale-formatted start time, or [ALL_DAY].
     *
     * ⚠⚠ Never "00:00" for an all-day event. Midnight is how it is *stored*, not when it happens,
     * and printing the storage detail is how a public holiday ends up looking like a midnight
     * meeting.
     */
    fun timeLabel(occurrence: ResolvedOccurrence): String =
        if (occurrence.allDay) ALL_DAY else CalendarDateFormat.shortTime(occurrence.startLocal.time)

    /**
     * The detail screen's line: start–end, or the whole day.
     *
     * ⚠ An en dash with spaces, not a hyphen: this is a range, and the two read differently at a
     * glance in a list of times.
     */
    fun spanLabel(occurrence: ResolvedOccurrence): String = when {
        occurrence.allDay -> ALL_DAY_TITLE
        else -> CalendarDateFormat.shortTime(occurrence.startLocal.time) +
            " – " +
            CalendarDateFormat.shortTime(occurrence.endLocal.time)
    }

    /** ⚠ A blank title gets a placeholder — an empty row reads as a rendering bug. */
    fun titleOf(occurrence: ResolvedOccurrence): String =
        occurrence.title.ifBlank { UNTITLED }

    const val TODAY: String = "Today"
    const val TOMORROW: String = "Tomorrow"
    const val ALL_DAY: String = "all day"
    const val ALL_DAY_TITLE: String = "All day"
    const val UNTITLED: String = "(No title)"
}
