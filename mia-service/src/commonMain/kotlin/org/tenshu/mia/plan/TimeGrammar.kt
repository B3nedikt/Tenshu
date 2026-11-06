package org.tenshu.mia.plan

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * English time expressions → a resolved [TimeWindow] (`mia-query-layer.md` MQ-D4).
 *
 * ⚠⚠ **This is pure code, not ML, and it is the fiddly part.** "Last summer", "at the
 * weekend", "in March" carry hemisphere, week-start and calendar assumptions that a
 * model would guess at and a grammar has to *decide*. Being pure is what makes those
 * decisions assertable: clock and zone come in as arguments, so hundreds of cases run
 * with no IO and no flakiness.
 *
 * ⚠ It is also **localisation-bearing in a way string resources are not** (ADR 0016): a
 * grammar is per-language, not per-catalogue. v1 is English; a second language is a
 * second grammar, and that cost is real and should be sized before promising Mia in
 * another locale.
 *
 * ⚠⚠ **Seasons are northern-hemisphere meteorological, and that is a stated limitation
 * rather than a hidden one.** Nothing in a realm tells us where the user lives, so
 * "summer" cannot be resolved correctly for everyone. The mitigation is MQ-D3's
 * explainability: the answer quotes the resolved range back ("photos taken
 * 1 Jun – 31 Aug 2025"), so a user in the southern hemisphere sees the wrong months
 * named instead of receiving a confidently wrong set of photos. Asking for the months by
 * name works everywhere.
 */
object TimeGrammar {

    /** Monday, matching ISO-8601 and every locale this grammar currently speaks. */
    private val WEEK_START = DayOfWeek.MONDAY

    private val MONTHS: Map<String, Month> = mapOf(
        "january" to Month.JANUARY, "jan" to Month.JANUARY,
        "february" to Month.FEBRUARY, "feb" to Month.FEBRUARY,
        "march" to Month.MARCH, "mar" to Month.MARCH,
        "april" to Month.APRIL, "apr" to Month.APRIL,
        "may" to Month.MAY,
        "june" to Month.JUNE, "jun" to Month.JUNE,
        "july" to Month.JULY, "jul" to Month.JULY,
        "august" to Month.AUGUST, "aug" to Month.AUGUST,
        "september" to Month.SEPTEMBER, "sep" to Month.SEPTEMBER, "sept" to Month.SEPTEMBER,
        "october" to Month.OCTOBER, "oct" to Month.OCTOBER,
        "november" to Month.NOVEMBER, "nov" to Month.NOVEMBER,
        "december" to Month.DECEMBER, "dec" to Month.DECEMBER,
    )

    private val WEEKDAYS: Map<String, DayOfWeek> = mapOf(
        "monday" to DayOfWeek.MONDAY, "mon" to DayOfWeek.MONDAY,
        "tuesday" to DayOfWeek.TUESDAY, "tue" to DayOfWeek.TUESDAY,
        "wednesday" to DayOfWeek.WEDNESDAY, "wed" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY, "thu" to DayOfWeek.THURSDAY,
        "friday" to DayOfWeek.FRIDAY, "fri" to DayOfWeek.FRIDAY,
        "saturday" to DayOfWeek.SATURDAY, "sat" to DayOfWeek.SATURDAY,
        "sunday" to DayOfWeek.SUNDAY, "sun" to DayOfWeek.SUNDAY,
    )

    /** Meteorological seasons: first month, length in months. Northern hemisphere. */
    private val SEASONS: Map<String, Pair<Month, Int>> = mapOf(
        "spring" to (Month.MARCH to 3),
        "summer" to (Month.JUNE to 3),
        "autumn" to (Month.SEPTEMBER to 3),
        "fall" to (Month.SEPTEMBER to 3),
        "winter" to (Month.DECEMBER to 3),
    )

    /** Words that only ever mean "the previous one", never a month or a season. */
    private const val LAST = "last"
    private const val THIS = "this"

    /**
     * Resolves the first time expression in [words], or `null` when the utterance names
     * no time at all — which is a real answer ("show me my photos"), not a failure.
     *
     * @param words the utterance, lowercased and split on whitespace
     */
    fun resolve(words: List<String>, nowMs: Long, zone: TimeZone): TimeWindow? {
        val today = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(zone).date

        words.forEachIndexed { index, word ->
            val previous = words.getOrNull(index - 1)
            val next = words.getOrNull(index + 1)

            when {
                // ⭐ "two weeks ago" — checked FIRST because the same words otherwise fall into
                // the bare-unit arms below ("week" with no "this"/"last" prefix reads as nothing,
                // and the count word leaks out as a name search: `FindFilesNamed("two ago")`).
                word == AGO -> agoWindow(words, index, today, zone)?.let { return it }

                word == "today" -> return days(today, 1, zone, "today")
                WEEKDAYS.containsKey(word) -> return weekdayOf(WEEKDAYS.getValue(word), today, zone, word)
                word == "yesterday" -> return days(today.minus(DatePeriod(days = 1)), 1, zone, "yesterday")

                word == "week" && previous == THIS -> return weekOf(today, zone, "this week")
                word == "week" && previous == LAST ->
                    return weekOf(today.minus(DatePeriod(days = 7)), zone, "last week")

                word == "month" && previous == THIS -> return monthOf(today, zone)
                word == "month" && previous == LAST -> return monthOf(today.minus(DatePeriod(months = 1)), zone)

                word == "year" && previous == THIS -> return yearOf(today.year, zone)
                word == "year" && previous == LAST -> return yearOf(today.year - 1, zone)

                SEASONS.containsKey(word) -> return seasonOf(word, previous, today, zone)

                MONTHS.containsKey(word) && previous != THIS && previous != LAST ->
                    return namedMonth(MONTHS.getValue(word), next, today, zone)

                isYear(word) -> return yearOf(word.toInt(), zone)
            }
        }
        return null
    }

    /**
     * Whether [word] participates in a time expression at all — what the planner strips
     * before deciding whether anything is left to search *by name*.
     *
     * ⚠ Deliberately generous: "last" and "this" are stripped even though on their own
     * they name no window, because leaving them in turns "photos from last summer" into
     * a name search for the string "last".
     */
    fun isTimeWord(word: String): Boolean =
        word in RELATIVE_WORDS ||
            // ⚠⚠ The count belongs to the time expression too. Leaving "two" behind is exactly
            // how "what did I do two weeks ago" became a file search for "two ago".
            word in NUMBER_WORDS ||
            word.toIntOrNull() != null ||
            word in SEASONS ||
            word in MONTHS ||
            isYear(word)

    private val RELATIVE_WORDS = setOf(
        "today", "yesterday", "week", "weeks", "month", "months", "year", "years", THIS, LAST,
        "day", "days", AGO,
    ) + WEEKDAYS.keys

    /**
     * "**N units ago**" — `two weeks ago`, `3 days ago`, `a month ago`.
     *
     * ⚠⚠ **A window, never an instant.** "Two weeks ago" means *that week*, not the moment
     * fourteen days back: answering with a single day is the kind of false precision that reads
     * as a bug, because nobody counting backwards in weeks means a particular Tuesday.
     *
     * ⚠ The unit decides the *granularity*, so the window is snapped to the calendar — the ISO
     * week for weeks, the calendar month for months, the year for years. `days` is the exception
     * and is snapped to the day, which is what "three days ago" plainly means.
     *
     * @param at the index of the word `ago`; the count and unit sit immediately before it
     */
    private fun agoWindow(
        words: List<String>,
        at: Int,
        today: LocalDate,
        zone: TimeZone,
    ): TimeWindow? {
        val unit = words.getOrNull(at - 1) ?: return null
        // ⚠ "a week ago" has a count word; "week ago" does not, and defaults to one.
        val count = countOf(words.getOrNull(at - 2)) ?: 1
        if (count <= 0) return null
        val label = "$count ${unit.removeSuffix("s")}${if (count == 1) "" else "s"} ago"
        return when (unit) {
            "day", "days" -> days(today.minus(DatePeriod(days = count)), 1, zone, label)
            "week", "weeks" -> weekOf(today.minus(DatePeriod(days = 7 * count)), zone, label)
            "month", "months" -> monthOf(today.minus(DatePeriod(months = count)), zone)
            "year", "years" -> yearOf(today.year - count, zone)
            else -> null
        }
    }

    /**
     * A small-number word or digit, or `null` when [word] is neither.
     *
     * ⚠ Deliberately stops at twelve. Beyond that people write digits, and every extra word is
     * another thing that could shadow a place name — "seven" is a bar in Berlin.
     */
    private fun countOf(word: String?): Int? {
        if (word == null) return null
        NUMBER_WORDS[word]?.let { return it }
        return word.toIntOrNull()?.takeIf { it in 1..999 }
    }

    private const val AGO = "ago"

    private val NUMBER_WORDS: Map<String, Int> = mapOf(
        "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11,
        "twelve" to 12,
    )

    /** A bare four-digit number in a plausible range reads as a year, nothing else. */
    private fun isYear(word: String): Boolean =
        word.length == 4 && word.all { it.isDigit() } && word.toInt() in 1900..2999

    /**
     * A named weekday means **the most recent one that has passed**, and "last Saturday"
     * means the one before that.
     *
     * ⚠ Today deliberately does not count as "Saturday" when it *is* Saturday: someone
     * asking on a Saturday evening about "Saturday" almost always means the day that is
     * over, and "today" is the word for the other reading. Stepping back a full week is the
     * behaviour every calendar app converged on.
     */
    private fun weekdayOf(day: DayOfWeek, today: LocalDate, zone: TimeZone, label: String): TimeWindow {
        val back = ((today.dayOfWeek.ordinal - day.ordinal + 7) % 7).let { if (it == 0) 7 else it }
        val date = today.minus(DatePeriod(days = back))
        return days(date, 1, zone, label)
    }

    private fun days(start: LocalDate, count: Int, zone: TimeZone, label: String): TimeWindow =
        window(start, start.plus(DatePeriod(days = count)), zone, label)

    private fun weekOf(anyDayInWeek: LocalDate, zone: TimeZone, label: String): TimeWindow {
        val offset = (anyDayInWeek.dayOfWeek.ordinal - WEEK_START.ordinal + 7) % 7
        val monday = anyDayInWeek.minus(DatePeriod(days = offset))
        return window(monday, monday.plus(DatePeriod(days = 7)), zone, label)
    }

    private fun monthOf(anyDayInMonth: LocalDate, zone: TimeZone): TimeWindow {
        val first = LocalDate(anyDayInMonth.year, anyDayInMonth.month, 1)
        return window(first, first.plus(DatePeriod(months = 1)), zone, "${monthName(first.month)} ${first.year}")
    }

    private fun yearOf(year: Int, zone: TimeZone): TimeWindow {
        val first = LocalDate(year, Month.JANUARY, 1)
        return window(first, first.plus(DatePeriod(years = 1)), zone, year.toString())
    }

    /**
     * "March" with no year means **the most recent March that has begun** — asking about
     * a month is nearly always asking about the past, and resolving forward would answer
     * about a month that has not happened.
     */
    private fun namedMonth(month: Month, next: String?, today: LocalDate, zone: TimeZone): TimeWindow {
        val explicitYear = next?.takeIf { isYear(it) }?.toInt()
        val year = explicitYear ?: mostRecentStartYear(month, today)
        val first = LocalDate(year, month, 1)
        return window(first, first.plus(DatePeriod(months = 1)), zone, "${monthName(month)} $year")
    }

    /**
     * A season, anchored the same way as a bare month: the most recent one that has
     * begun, unless "this"/"last" says otherwise.
     *
     * ⚠ Winter spans a year boundary (Dec–Feb), so its window deliberately starts in the
     * *previous* calendar year when we are in January or February.
     */
    private fun seasonOf(season: String, previous: String?, today: LocalDate, zone: TimeZone): TimeWindow {
        val (firstMonth, months) = SEASONS.getValue(season)
        val recent = mostRecentStartYear(firstMonth, today)
        val year = if (previous == LAST) recent - 1 else recent
        val first = LocalDate(year, firstMonth, 1)
        val end = first.plus(DatePeriod(months = months))
        return window(first, end, zone, "$season $year")
    }

    /**
     * The year of the most recent [month] that has **already begun** — asking about a
     * month or a season is nearly always asking about the past, and resolving forward
     * would answer about a period that has not happened yet.
     *
     * ⚠ Compares whole dates rather than month ordinals so it stays correct for a season
     * that spans the year boundary: in January, winter's most recent start is *December
     * of last year*, not a December still eleven months away.
     */
    private fun mostRecentStartYear(month: Month, today: LocalDate): Int =
        if (today >= LocalDate(today.year, month, 1)) today.year else today.year - 1

    private fun window(startDate: LocalDate, endDateExclusive: LocalDate, zone: TimeZone, label: String) =
        TimeWindow(
            startMs = startDate.atStartOfDayIn(zone).toEpochMilliseconds(),
            endMsExclusive = endDateExclusive.atStartOfDayIn(zone).toEpochMilliseconds(),
            label = label,
        )

    private fun monthName(month: Month): String =
        month.name.lowercase().replaceFirstChar { it.uppercase() }
}
