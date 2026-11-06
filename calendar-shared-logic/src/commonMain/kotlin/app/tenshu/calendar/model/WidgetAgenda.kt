package app.tenshu.calendar.model

import kotlinx.datetime.LocalDate
import org.tenshu.calendar.common.recurrence.ResolvedOccurrence

/** One line on the widget. */
data class WidgetAgendaRow(
    val eventId: String,
    val recurrenceId: String,
    val title: String,
    /** `"09:00"`, or blank for an all-day event. */
    val time: String,
    /** True once the occurrence has started — the widget dims it rather than hiding it. */
    val started: Boolean,
    /** True when it falls on a later day than the one the widget opened on. */
    val tomorrowOrLater: Boolean,
)

/**
 * What the widget is showing.
 *
 * ⚠⚠ **`NotConnected` and `Empty` are different states and must stay different.**
 * A widget reads over IPC to Control, and a failed read yields an empty list —
 * exactly what a free day also yields. Rendering both as "Nothing today" is how a
 * user concludes their calendar is empty when in fact the app cannot see it. This
 * is the same conflation `ios-drive-parity.md` P1a found in Drive's file list and
 * `timeline-ios` P1 found again in the Timeline shell.
 */
sealed interface WidgetAgenda {
    /** The first paint, before the read returns. */
    data object Loading : WidgetAgenda

    /** Control could not be reached — not the same as an empty calendar. */
    data object NotConnected : WidgetAgenda

    /** Read fine; there is genuinely nothing coming up. */
    data object Empty : WidgetAgenda

    data class Items(val rows: List<WidgetAgendaRow>) : WidgetAgenda
}

/**
 * Builds the widget's list from a window of occurrences (CA-7c).
 *
 * ⚠⚠ **Pure, and that is the point of it existing at all.** A Glance composable
 * cannot be tested here, so everything that *decides* — which occurrences appear,
 * in what order, which are dimmed, when the list is empty — lives on this side of
 * the line and the widget only draws the answer.
 */
object WidgetAgendaBuilder {

    /** ⚠ Four rows fits the smallest useful widget without scrolling. */
    const val DEFAULT_LIMIT = 4

    /**
     * @param occurrences a window starting at [today]; anything earlier is ignored.
     * @param nowMinuteOfDay minutes since midnight *on [today]*, for the dimming.
     * @param lookAheadDays how far past today to fill from when today runs dry.
     */
    fun build(
        occurrences: List<ResolvedOccurrence>,
        today: LocalDate,
        nowMinuteOfDay: Int,
        limit: Int = DEFAULT_LIMIT,
        lookAheadDays: Int = 7,
    ): WidgetAgenda {
        if (limit <= 0) return WidgetAgenda.Empty
        val horizon = LocalDate.fromEpochDays(today.toEpochDays() + lookAheadDays)

        val rows = occurrences
            .asSequence()
            .filter { it.startLocal.date >= today && it.startLocal.date <= horizon }
            // ⚠⚠ **An event already under way still shows.** "Standup, started 10
            // minutes ago" is the single most useful thing a calendar widget can
            // say at 09:10, and dropping it would leave the glanceable surface
            // blank during exactly the meeting the user is in.
            .filterNot { it.hasEnded(today, nowMinuteOfDay) }
            .sortedWith(compareBy({ it.startLocal.date }, { it.startLocal.time }, { it.title }))
            .take(limit)
            .map { occurrence ->
                WidgetAgendaRow(
                    eventId = occurrence.eventId,
                    recurrenceId = occurrence.recurrenceId,
                    title = occurrence.title.ifBlank { UNTITLED },
                    // ⚠ Blank rather than "00:00" for an all-day event: midnight is
                    // not when it happens, it is how it is stored.
                    time = if (occurrence.allDay) "" else occurrence.startLocal.time.hhmm(),
                    started = occurrence.hasStarted(today, nowMinuteOfDay),
                    tomorrowOrLater = occurrence.startLocal.date > today,
                )
            }
            .toList()

        return if (rows.isEmpty()) WidgetAgenda.Empty else WidgetAgenda.Items(rows)
    }

    /**
     * ⚠ An all-day event on today is *never* "ended" — it runs the whole day, and
     * treating its 00:00 start as past would drop every holiday from the widget by
     * 00:01.
     */
    private fun ResolvedOccurrence.hasEnded(today: LocalDate, nowMinuteOfDay: Int): Boolean {
        if (startLocal.date > today) return false
        if (allDay) return endLocal.date <= today
        // ⚠ Compared on the *end*, so a two-hour meeting stays up for its whole
        // length rather than vanishing the moment it begins.
        if (endLocal.date > today) return false
        return endLocal.time.minuteOfDay() <= nowMinuteOfDay
    }

    private fun ResolvedOccurrence.hasStarted(today: LocalDate, nowMinuteOfDay: Int): Boolean =
        when {
            startLocal.date < today -> true
            startLocal.date > today -> false
            allDay -> true
            else -> startLocal.time.minuteOfDay() <= nowMinuteOfDay
        }

    const val UNTITLED = "(No title)"
}

private fun kotlinx.datetime.LocalTime.minuteOfDay(): Int = hour * 60 + minute

private fun kotlinx.datetime.LocalTime.hhmm(): String =
    hour.toString().padStart(2, '0') + ":" + minute.toString().padStart(2, '0')
