package app.tenshu.calendar.data.external

import kotlin.time.Instant
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import org.tenshu.calendar.common.recurrence.RecurrenceExpander
import org.tenshu.calendar.common.recurrence.RecurrenceRule

/**
 * The span a **windowed** reader actually read (`calendar-external-sources.md` CX-2b) — EventKit
 * answers only inside a predicate, so an iOS snapshot is complete only between these instants.
 *
 * ⚠⚠ A mirror refresh may only conclude something from absence *inside* the window: a series with
 * no occurrence there is missing because it was not asked about, not because it was deleted — and
 * likewise its exception dates and changed occurrences outside it. Treating the snapshot as whole
 * deleted every mirrored event once it aged past a year, and resurrected old deletions.
 *
 * ⚠ Judged on **civil dates with a day's margin each side**, never on instants: a stored row's zone
 * is not always known (floating, all-day), and a day of margin covers every UTC offset. Everything
 * on the margin counts as *outside* — so the error is always to keep, never to delete.
 *
 * @param startMs inclusive. @param endMs exclusive.
 */
data class SnapshotWindow(val startMs: Long, val endMs: Long) {

    private val safeFrom: LocalDate = utcDate(startMs).plus(DatePeriod(days = 1))
    private val safeTo: LocalDate = utcDate(endMs).minus(DatePeriod(days = 2))

    /** Whether [date] is certainly inside, in whatever zone it was meant. */
    fun covers(date: LocalDate): Boolean = date in safeFrom..safeTo

    /** [covers] for a stored date or date-time string (`2026-07-15`, `2026-07-15T09:00`). */
    fun coversLocal(value: String): Boolean = localDateOf(value)?.let(::covers) ?: false

    /**
     * Whether a stored series has an occurrence certainly inside — i.e. whether a reader of this
     * window would have returned it had it still existed.
     *
     * ⚠ An unparseable start or rule answers `false`: unknown is not deleted.
     *
     * @param movedOutSlots original slots whose occurrence was moved elsewhere — ⚠ they do not count:
     *   EventKit queries by where an occurrence is now, so a series whose in-window occurrences all
     *   moved out is not returned and is not deleted.
     */
    fun touchesSeries(
        startLocal: String,
        endLocal: String,
        recurrenceRule: String,
        exceptionDates: String,
        movedOutSlots: Set<String> = emptySet(),
    ): Boolean {
        val start = localDateOf(startLocal) ?: return false
        if (recurrenceRule.isBlank()) {
            val end = localDateOf(endLocal) ?: start
            return start <= safeTo && maxOf(start, end) >= safeFrom
        }
        val rule = RecurrenceRule.parse(recurrenceRule) ?: return false
        val exceptions = exceptionDates.split(',').mapNotNullTo(mutableSetOf()) { localDateOf(it) } +
            movedOutSlots.mapNotNull(::localDateOf)
        return RecurrenceExpander.expand(rule, start, safeFrom, safeTo, exceptions).isNotEmpty()
    }

    /**
     * The original slots of a stored series certainly inside the window (`yyyy-mm-dd`), minus its
     * cancellations and [movedOutSlots] — what a partial cancel marks cancelled.
     */
    fun coveredSlots(
        startLocal: String,
        recurrenceRule: String,
        exceptionDates: String,
        movedOutSlots: Set<String> = emptySet(),
    ): List<String> {
        val start = localDateOf(startLocal) ?: return emptyList()
        val rule = RecurrenceRule.parse(recurrenceRule) ?: return emptyList()
        val exceptions = exceptionDates.split(',').mapNotNullTo(mutableSetOf()) { localDateOf(it) } +
            movedOutSlots.mapNotNull(::localDateOf)
        return RecurrenceExpander.expand(rule, start, safeFrom, safeTo, exceptions).map { it.toString() }
    }

    /**
     * Whether a stored recurring series has occurrences OUTSIDE the window — before it, or after
     * it (an unbounded rule always does). ⚠ Unparseable answers `true`: unknown is not deleted.
     */
    fun reachesBeyond(startLocal: String, recurrenceRule: String, exceptionDates: String): Boolean {
        val start = localDateOf(startLocal) ?: return true
        val rule = RecurrenceRule.parse(recurrenceRule) ?: return true
        if (rule.count == null && rule.until == null) return true
        val exceptions = exceptionDates.split(',').mapNotNullTo(mutableSetOf()) { localDateOf(it) }
        val before = start < safeFrom &&
            RecurrenceExpander.expand(rule, start, start, safeFrom.minus(DatePeriod(days = 1)), exceptions).isNotEmpty()
        val horizon = rule.until ?: safeTo.plus(DatePeriod(years = 100))
        val after = horizon > safeTo &&
            RecurrenceExpander.expand(rule, start, safeTo.plus(DatePeriod(days = 1)), horizon, exceptions).isNotEmpty()
        return before || after
    }

    private companion object {
        fun utcDate(ms: Long): LocalDate = Instant.fromEpochMilliseconds(ms).toLocalDateTime(TimeZone.UTC).date

        fun localDateOf(raw: String): LocalDate? {
            val text = raw.trim()
            if (text.isEmpty()) return null
            runCatching { return LocalDateTime.parse(text).date }
            runCatching { return LocalDate.parse(text.take(10)) }
            return null
        }
    }
}
