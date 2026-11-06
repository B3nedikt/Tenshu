package org.tenshu.calendar.common.recurrence

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number

/** `FREQ` — the recurrence's base cadence. */
enum class Frequency { DAILY, WEEKLY, MONTHLY, YEARLY }

/**
 * One `BYDAY` entry: a weekday, optionally ordinal-qualified.
 *
 * [ordinal] is `0` for a bare weekday (`MO` — "every Monday"), positive for
 * nth-from-the-start (`2MO` — "the second Monday"), negative for nth-from-the-end
 * (`-1FR` — "the last Friday"). ⚠ The negative form is not an exotic case: "the
 * last Friday of the month" is a common real-world schedule, and an expander
 * that only handles positives silently produces nothing for it.
 */
data class WeekDayNum(val ordinal: Int, val day: DayOfWeek) {

    /** Whether [date] satisfies this entry within its month. */
    fun matchesInMonth(date: LocalDate): Boolean {
        if (date.dayOfWeek != day) return false
        return when {
            ordinal == 0 -> true
            ordinal > 0 -> (date.day - 1) / 7 + 1 == ordinal
            else -> {
                val lastDay = lengthOfMonth(date.year, date.month.number)
                val fromEnd = (lastDay - date.day) / 7 + 1
                fromEnd == -ordinal
            }
        }
    }
}

/**
 * A parsed RFC 5545 `RRULE` (`calendar-app.md` §3 D2, Q2).
 *
 * ⚠⚠ **The canonical stored form is the raw string, not this type.** Q2 settled
 * that deliberately: nothing ever queries *by rule* — you query expanded
 * occurrences — so a string keeps `Event`'s positional shape frozen while the
 * expander's capability grows, and adding `BYSETPOS` later costs expander code
 * rather than a schema reshape under House Rule #9. This type exists only
 * between [parse] and expansion.
 *
 * ## Supported subset, and why it stops where it does
 *
 * `FREQ`, `INTERVAL`, `COUNT`, `UNTIL`, `BYDAY`, `BYMONTHDAY`, `BYMONTH`,
 * `WKST`. That is the common subset — everything the CA-3 editor will offer and
 * essentially everything real calendars emit. ⚠ Unsupported *parts* are
 * preserved in [unsupportedParts] rather than dropped, so an imported rule
 * round-trips through `EventSource` intact even though we do not expand by it;
 * ⚠⚠ but an event whose rule carries one expands by the parts we *do*
 * understand, which may over-produce. [isFullyUnderstood] is how a caller can
 * tell, and CA-3's UI should refuse to *edit* a rule it cannot fully model
 * rather than silently rewriting it.
 */
data class RecurrenceRule(
    val frequency: Frequency,
    val interval: Int = 1,
    /** Total occurrence count including the first; `null` for unbounded. */
    val count: Int? = null,
    /** Inclusive last date; `null` for unbounded. */
    val until: LocalDate? = null,
    val byDay: List<WeekDayNum> = emptyList(),
    /** Days of month; negative counts from the end (`-1` = last day). */
    val byMonthDay: List<Int> = emptyList(),
    /** 1-based months. */
    val byMonth: List<Int> = emptyList(),
    val weekStart: DayOfWeek = DayOfWeek.MONDAY,
    /** `NAME=VALUE` parts we parsed but do not expand by. */
    val unsupportedParts: List<String> = emptyList(),
) {

    val isFullyUnderstood: Boolean get() = unsupportedParts.isEmpty()

    companion object {

        private val SUPPORTED = setOf(
            "FREQ", "INTERVAL", "COUNT", "UNTIL", "BYDAY", "BYMONTHDAY", "BYMONTH", "WKST",
        )

        /**
         * Parses an `RRULE` value — the part *after* `RRULE:`, though a leading
         * `RRULE:` is tolerated because real-world strings carry it either way.
         *
         * Returns `null` for blank input (a one-off event) or for a rule with no
         * usable `FREQ`. ⚠ Null is "this does not recur", never an error
         * channel: a malformed rule must not make the event itself unreadable,
         * so the event simply shows once at its start.
         */
        fun parse(value: String): RecurrenceRule? {
            val body = value.trim().removePrefix("RRULE:").trim()
            if (body.isEmpty()) return null

            val parts = body.split(';').mapNotNull { part ->
                val idx = part.indexOf('=')
                if (idx <= 0) null else part.substring(0, idx).uppercase() to part.substring(idx + 1)
            }.toMap()

            val frequency = when (parts["FREQ"]?.uppercase()) {
                "DAILY" -> Frequency.DAILY
                "WEEKLY" -> Frequency.WEEKLY
                "MONTHLY" -> Frequency.MONTHLY
                "YEARLY" -> Frequency.YEARLY
                else -> return null
            }

            // ⚠ An INTERVAL of 0 would make every expansion loop forever;
            // coerce rather than trust, since this string may have come from a
            // third party's exporter.
            val interval = parts["INTERVAL"]?.toIntOrNull()?.takeIf { it > 0 } ?: 1

            return RecurrenceRule(
                frequency = frequency,
                interval = interval,
                count = parts["COUNT"]?.toIntOrNull()?.takeIf { it > 0 },
                until = parts["UNTIL"]?.let(::parseUntilDate),
                byDay = parts["BYDAY"]?.let(::parseByDay).orEmpty(),
                byMonthDay = parts["BYMONTHDAY"]
                    ?.split(',')
                    ?.mapNotNull { it.trim().toIntOrNull() }
                    ?.filter { it != 0 && it >= -31 && it <= 31 }
                    .orEmpty(),
                byMonth = parts["BYMONTH"]
                    ?.split(',')
                    ?.mapNotNull { it.trim().toIntOrNull() }
                    ?.filter { it in 1..12 }
                    .orEmpty(),
                weekStart = parts["WKST"]?.let(::parseWeekDay) ?: DayOfWeek.MONDAY,
                unsupportedParts = parts.keys
                    .filter { it !in SUPPORTED }
                    .sorted()
                    .map { "$it=${parts[it]}" },
            )
        }

        /**
         * `UNTIL` is a date or a UTC date-time (`20261231` / `20261231T235959Z`).
         * ⚠ We keep only the date half: comparing an `UNTIL` instant against a
         * *floating* or zoned local start would need a zone this type does not
         * have, and the date is what users actually mean by "until the 31st".
         */
        private fun parseUntilDate(raw: String): LocalDate? {
            val digits = raw.trim().takeWhile { it.isDigit() }
            if (digits.length < 8) return null
            val year = digits.substring(0, 4).toIntOrNull() ?: return null
            val month = digits.substring(4, 6).toIntOrNull() ?: return null
            val day = digits.substring(6, 8).toIntOrNull() ?: return null
            if (month !in 1..12 || day !in 1..31) return null
            return runCatching { LocalDate(year, month, day) }.getOrNull()
        }

        private fun parseByDay(raw: String): List<WeekDayNum> =
            raw.split(',').mapNotNull { entry ->
                val token = entry.trim().uppercase()
                if (token.length < 2) return@mapNotNull null
                val dayPart = token.takeLast(2)
                val ordinalPart = token.dropLast(2)
                val day = parseWeekDay(dayPart) ?: return@mapNotNull null
                val ordinal = if (ordinalPart.isEmpty()) 0 else {
                    ordinalPart.toIntOrNull() ?: return@mapNotNull null
                }
                WeekDayNum(ordinal, day)
            }

        private fun parseWeekDay(raw: String): DayOfWeek? = when (raw.trim().uppercase()) {
            "MO" -> DayOfWeek.MONDAY
            "TU" -> DayOfWeek.TUESDAY
            "WE" -> DayOfWeek.WEDNESDAY
            "TH" -> DayOfWeek.THURSDAY
            "FR" -> DayOfWeek.FRIDAY
            "SA" -> DayOfWeek.SATURDAY
            "SU" -> DayOfWeek.SUNDAY
            else -> null
        }
    }
}

/** Days in [month] of [year], Gregorian. */
internal fun lengthOfMonth(year: Int, month: Int): Int = when (month) {
    1, 3, 5, 7, 8, 10, 12 -> 31
    4, 6, 9, 11 -> 30
    2 -> if (isLeapYear(year)) 29 else 28
    else -> 30
}

internal fun isLeapYear(year: Int): Boolean =
    (year % 4 == 0 && year % 100 != 0) || year % 400 == 0

/** ISO day number (Mon=1) — re-exported so the expander need not import it. */
internal fun DayOfWeek.isoNumber(): Int = isoDayNumber

/**
 * Renders a rule back to an RFC 5545 `RRULE` value.
 *
 * ⚠⚠ **Lossy for a rule that carries [RecurrenceRule.unsupportedParts]**, and
 * deliberately so: re-emitting a part we did not expand by would claim an
 * understanding we do not have. A caller holding such a rule must not round-trip
 * it through here — `isFullyUnderstood` is how it can tell, and an editor should
 * refuse to *edit* rather than silently rewrite (see the class KDoc).
 *
 * ⭐ Lives beside [RecurrenceRule.parse] rather than in the editor because CA-6's
 * ICS export needs exactly this, and two renderers would drift.
 */
fun RecurrenceRule.format(): String {
    val parts = mutableListOf("FREQ=${frequency.name}")
    if (interval > 1) parts += "INTERVAL=$interval"
    if (byDay.isNotEmpty()) {
        parts += "BYDAY=" + byDay.joinToString(",") { entry ->
            val ordinal = if (entry.ordinal == 0) "" else entry.ordinal.toString()
            ordinal + entry.day.rruleCode()
        }
    }
    if (byMonthDay.isNotEmpty()) parts += "BYMONTHDAY=" + byMonthDay.joinToString(",")
    if (byMonth.isNotEmpty()) parts += "BYMONTH=" + byMonth.joinToString(",")
    // ⚠ COUNT and UNTIL are mutually exclusive in RFC 5545; COUNT wins here
    // because an editor that offers both has already chosen one for the user.
    count?.let { parts += "COUNT=$it" }
    if (count == null) {
        until?.let {
            val y = it.year.toString().padStart(4, '0')
            val m = it.month.number.toString().padStart(2, '0')
            val d = it.day.toString().padStart(2, '0')
            parts += "UNTIL=$y$m$d"
        }
    }
    if (weekStart != DayOfWeek.MONDAY) parts += "WKST=${weekStart.rruleCode()}"
    return parts.joinToString(";")
}

private fun DayOfWeek.rruleCode(): String = when (this) {
    DayOfWeek.MONDAY -> "MO"
    DayOfWeek.TUESDAY -> "TU"
    DayOfWeek.WEDNESDAY -> "WE"
    DayOfWeek.THURSDAY -> "TH"
    DayOfWeek.FRIDAY -> "FR"
    DayOfWeek.SATURDAY -> "SA"
    DayOfWeek.SUNDAY -> "SU"
}
