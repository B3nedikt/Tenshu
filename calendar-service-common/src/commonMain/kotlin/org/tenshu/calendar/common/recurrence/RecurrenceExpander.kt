package org.tenshu.calendar.common.recurrence

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.number
import kotlinx.datetime.plus

/**
 * Expands a recurrence rule into concrete occurrence dates.
 *
 * ⚠⚠ **Pure, and deliberately clockless.** No `Clock`, no `TimeZone`, no
 * "today". Every boundary is a parameter, so the same inputs give the same
 * answer on every device and in every test — and a caller cannot accidentally
 * make expansion depend on where the reader happens to be standing. This is the
 * discipline `FiringCalculator` arrived at with its explicit `RoutineLocale`,
 * and it is the single most likely way to build a subtly wrong calendar.
 *
 * ⚠ Everything here is in **civil** (local) dates. Converting an occurrence to
 * an instant needs the event's own zone and happens in [EventOccurrence], never
 * inside the rule walk — a rule is a statement about calendar days, and pulling
 * a zone in here would make "every Monday" mean different days at different
 * longitudes.
 */
object RecurrenceExpander {

    /**
     * A hard ceiling on candidate dates examined per expansion.
     *
     * ⚠ This is not a horizon on the *answer*, it is a guard against a rule
     * whose constraints are unsatisfiable — `FREQ=MONTHLY;BYMONTHDAY=31;BYMONTH=2`
     * matches no date, ever, and without a bound the walk would run to the end
     * of time looking for one. 40 000 days is ~110 years of daily candidates,
     * far past any window a UI asks for.
     */
    private const val MAX_CANDIDATES = 40_000

    /**
     * Occurrence start dates for [rule] anchored at [start], intersected with
     * `[windowStart, windowEnd]` (both inclusive).
     *
     * ⭐ [start] is the series anchor and is **always the first candidate**, per
     * RFC 5545: `DTSTART` is an occurrence even when it does not satisfy the
     * rule's own `BYDAY`/`BYMONTHDAY` parts. Dropping it is a classic expander
     * bug — the series appears to begin a week late.
     *
     * ⚠ `COUNT` counts from the anchor, not from [windowStart], so the walk
     * cannot start at the window: a windowed read of the 40th occurrence of a
     * `COUNT=20` series must produce nothing, and only counting from the anchor
     * gets that right.
     */
    fun expand(
        rule: RecurrenceRule?,
        start: LocalDate,
        windowStart: LocalDate,
        windowEnd: LocalDate,
        exceptions: Set<LocalDate> = emptySet(),
    ): List<LocalDate> {
        if (windowEnd < windowStart) return emptyList()

        // A one-off: the anchor is the whole series.
        if (rule == null) {
            return if (start in windowStart..windowEnd && start !in exceptions) {
                listOf(start)
            } else {
                emptyList()
            }
        }

        val result = mutableListOf<LocalDate>()
        var emitted = 0
        var examined = 0
        var cursor = start

        while (examined < MAX_CANDIDATES) {
            examined++

            // `until` and `count` bound the SERIES, so they stop the walk
            // outright — unlike the window, which only filters what we keep.
            rule.until?.let { if (cursor > it) break }
            if (rule.count != null && emitted >= rule.count) break
            if (cursor > windowEnd && rule.count == null) break

            val isAnchor = cursor == start
            if (isAnchor || matches(rule, cursor, start)) {
                emitted++
                if (cursor in windowStart..windowEnd && cursor !in exceptions) {
                    result += cursor
                }
                // ⚠ An exception date still consumes a COUNT slot: deleting one
                // occurrence of "5 times" leaves four, it does not pull a fifth
                // forward from beyond the end. RFC 5545 agrees, and the other
                // reading silently extends every series the user prunes.
            }

            // Past the window with a COUNT still running: keep counting, but
            // there is nothing further to collect.
            if (cursor > windowEnd && result.isNotEmpty() && rule.count == null) break

            cursor = nextCandidate(rule, cursor, start) ?: break
        }

        return result
    }

    /**
     * Steps to the next date worth testing.
     *
     * ⭐ The step size is what keeps this cheap: a `YEARLY` rule advances a day
     * at a time only *within* a candidate year, and `MONTHLY`/`YEARLY` rules
     * with no `BY*` parts jump whole months or years rather than walking days.
     */
    private fun nextCandidate(
        rule: RecurrenceRule,
        cursor: LocalDate,
        start: LocalDate,
    ): LocalDate? = when (rule.frequency) {
        Frequency.DAILY -> cursor.plus(rule.interval, DateTimeUnit.DAY)

        // Weekly rules with BYDAY need every day tested (the rule may name
        // several weekdays); without BYDAY the cadence is exactly one step.
        Frequency.WEEKLY ->
            if (rule.byDay.isEmpty()) cursor.plus(rule.interval * 7, DateTimeUnit.DAY)
            else cursor.plus(1, DateTimeUnit.DAY)

        Frequency.MONTHLY ->
            if (rule.byDay.isEmpty() && rule.byMonthDay.isEmpty()) {
                addMonthsClamped(start, monthsBetween(start, cursor) + rule.interval)
            } else {
                cursor.plus(1, DateTimeUnit.DAY)
            }

        Frequency.YEARLY ->
            if (rule.byDay.isEmpty() && rule.byMonthDay.isEmpty() && rule.byMonth.isEmpty()) {
                addMonthsClamped(start, monthsBetween(start, cursor) + rule.interval * 12)
            } else {
                cursor.plus(1, DateTimeUnit.DAY)
            }
    }

    /** Whether [date] satisfies every part of [rule], relative to [start]. */
    private fun matches(rule: RecurrenceRule, date: LocalDate, start: LocalDate): Boolean {
        if (!matchesImplicitAnchorParts(rule, date, start)) return false

        if (rule.byMonth.isNotEmpty() && date.month.number !in rule.byMonth) return false

        if (rule.byMonthDay.isNotEmpty()) {
            val len = lengthOfMonth(date.year, date.month.number)
            val ok = rule.byMonthDay.any { d ->
                if (d > 0) date.day == d else date.day == len + d + 1
            }
            if (!ok) return false
        }

        if (rule.byDay.isNotEmpty()) {
            val ok = when (rule.frequency) {
                // For DAILY/WEEKLY an ordinal is meaningless — RFC 5545 says
                // BYDAY ordinals only apply to MONTHLY/YEARLY — so compare
                // weekdays alone and ignore any ordinal a producer emitted.
                Frequency.DAILY, Frequency.WEEKLY ->
                    rule.byDay.any { it.day == date.dayOfWeek }
                Frequency.MONTHLY, Frequency.YEARLY ->
                    rule.byDay.any { it.matchesInMonth(date) }
            }
            if (!ok) return false
        }

        return matchesInterval(rule, date, start)
    }

    /**
     * The parts RFC 5545 takes from `DTSTART` when the rule omits them.
     *
     * ⚠⚠ **Absent is not "any".** "If BYMONTHDAY is not present, the day is
     * taken from DTSTART" — so a bare `FREQ=MONTHLY` anchored on the 31st means
     * *the 31st*, and February is **skipped**, exactly as if `BYMONTHDAY=31` had
     * been written out. Without this, [addMonthsClamped]'s clamped candidate
     * (28 Feb) sails through unchallenged and the series silently gains
     * occurrences on dates the user never asked for — a *different* wrong answer
     * from the un-clamped roll-into-March, and a quieter one.
     *
     * ⭐ Found by mutating [addMonthsClamped] and watching every test still
     * pass: the clamp is only reachable for rules with no `BY*` parts, and
     * nothing covered that path. The clamp and this check are two halves of one
     * behaviour — the clamp keeps the walk on real dates, this rejects the ones
     * that were only reached by clamping.
     */
    private fun matchesImplicitAnchorParts(
        rule: RecurrenceRule,
        date: LocalDate,
        start: LocalDate,
    ): Boolean {
        // BYDAY selects days within the period, so it displaces the anchor's
        // day-of-month entirely; an explicit BYMONTHDAY does the same.
        val dayIsImplicit = rule.byDay.isEmpty() && rule.byMonthDay.isEmpty()
        return when (rule.frequency) {
            Frequency.DAILY, Frequency.WEEKLY -> true
            Frequency.MONTHLY -> !dayIsImplicit || date.day == start.day
            Frequency.YEARLY -> {
                val monthOk = rule.byMonth.isNotEmpty() || date.month.number == start.month.number
                monthOk && (!dayIsImplicit || date.day == start.day)
            }
        }
    }

    /**
     * Whether [date] falls in an *active* period given `INTERVAL`.
     *
     * ⚠ The unit differs per frequency and each is easy to get wrong:
     * fortnightly counts **weeks from the anchor's week** (not days / 7, which
     * would drift whenever the anchor is not the week's first day), and
     * every-other-month counts calendar months (not days / 30).
     */
    private fun matchesInterval(
        rule: RecurrenceRule,
        date: LocalDate,
        start: LocalDate,
    ): Boolean {
        if (rule.interval == 1) return true
        return when (rule.frequency) {
            Frequency.DAILY -> daysBetween(start, date) % rule.interval == 0L

            Frequency.WEEKLY -> {
                val anchorWeek = weekStartOf(start, rule.weekStart)
                val candidateWeek = weekStartOf(date, rule.weekStart)
                val weeks = daysBetween(anchorWeek, candidateWeek) / 7
                weeks % rule.interval == 0L
            }

            Frequency.MONTHLY -> monthsBetween(start, date) % rule.interval == 0

            Frequency.YEARLY -> (date.year - start.year) % rule.interval == 0
        }
    }

    private fun weekStartOf(date: LocalDate, weekStart: kotlinx.datetime.DayOfWeek): LocalDate {
        val diff = (date.dayOfWeek.isoNumber() - weekStart.isoNumber() + 7) % 7
        return date.minusDays(diff)
    }

    private fun LocalDate.minusDays(days: Int): LocalDate = plus(-days, DateTimeUnit.DAY)

    private fun daysBetween(from: LocalDate, to: LocalDate): Long =
        (to.toEpochDays() - from.toEpochDays()).toLong()

    private fun monthsBetween(from: LocalDate, to: LocalDate): Int =
        (to.year - from.year) * 12 + (to.month.number - from.month.number)

    /**
     * [base] advanced by [months], with the day-of-month **clamped** to the
     * target month's length.
     *
     * ⚠⚠ Clamping is the load-bearing part. "The 31st, monthly" has no February
     * — RFC 5545 says such an occurrence is *skipped*, but a naive
     * `plus(1, MONTH)` on a 31st silently rolls into the 1st or 2nd of the next
     * month and quietly invents dates the user never asked for. Clamping keeps
     * the walk on the last real day and lets [matches]' `BYMONTHDAY` test do the
     * skipping, which is where that decision belongs.
     */
    private fun addMonthsClamped(base: LocalDate, months: Int): LocalDate {
        val totalMonths = (base.year * 12 + (base.month.number - 1)) + months
        val year = totalMonths / 12
        val month = totalMonths % 12 + 1
        val day = minOf(base.day, lengthOfMonth(year, month))
        return LocalDate(year, month, day)
    }
}

/** Combines an occurrence [date] with a wall-clock [time]. */
fun LocalDate.atLocalTime(time: LocalTime): LocalDateTime = LocalDateTime(this, time)
