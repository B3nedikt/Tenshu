package org.tenshu.mia.plan

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * "what is on my calendar tomorrow" → a range of **days** (`mia-query-layer.md` MQ-D4a, the read
 * half).
 *
 * ⚠⚠ **The third time grammar, and it has to be.** §11.2 recorded that a scheduling grammar looks
 * FORWARD while the search grammar looks BACK; a calendar *read* is a third thing again, and it
 * matches neither:
 *
 * | Grammar | Question | Resolves to |
 * |---|---|---|
 * | [TimeGrammar] | "what did I do **last** Saturday" | a past instant window |
 * | [EventTimeGrammar] | "lunch **Saturday at 1**" | one future `LocalDateTime`, and **null without a clock time** |
 * | this | "what's on **Saturday**" | a future range of whole **days** |
 *
 * Reusing [EventTimeGrammar] would have refused every agenda question that named no hour — which
 * is nearly all of them. Reusing [TimeGrammar] would have answered "what's on Saturday" about the
 * Saturday that has already gone, which is §11.2's warning exactly.
 *
 * ⭐ **Days, not instants, and that is what makes the string-typed `Event.startLocal` harmless.**
 * Nothing here is compared against a stored field: the window is handed to `OccurrenceResolver`,
 * which expands recurrence rules into occurrences and selects by `LocalDate`. §8.4's finding — that
 * `inRange` over `startLocal` answers "nothing" silently — is avoided by not filtering in the chain
 * at all.
 */
object AgendaTimeGrammar {

    private val WEEKDAYS: Map<String, DayOfWeek> = mapOf(
        "monday" to DayOfWeek.MONDAY, "mon" to DayOfWeek.MONDAY,
        "tuesday" to DayOfWeek.TUESDAY, "tue" to DayOfWeek.TUESDAY,
        "wednesday" to DayOfWeek.WEDNESDAY, "wed" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY, "thu" to DayOfWeek.THURSDAY,
        "friday" to DayOfWeek.FRIDAY, "fri" to DayOfWeek.FRIDAY,
        "saturday" to DayOfWeek.SATURDAY, "sat" to DayOfWeek.SATURDAY,
        "sunday" to DayOfWeek.SUNDAY, "sun" to DayOfWeek.SUNDAY,
    )

    /**
     * Resolves the window, or `null` when the utterance named no time.
     *
     * ⚠ `null` is **not** an error and must not be turned into one: "what's on my calendar" is a
     * perfectly ordinary question, and the caller supplies [today] as the default. It is returned
     * separately only so the explanation can say *which* day it picked — a default the user cannot
     * see is a guess, and a named one is a fact they can correct (MQ-D1).
     */
    fun resolve(words: List<String>, nowMs: Long, zone: TimeZone): AgendaWindow? {
        val today = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(zone).date

        if (words.any { it == "tomorrow" }) {
            return day(today.plus(DatePeriod(days = 1)), "tomorrow")
        }
        if (words.any { it == "today" || it == "tonight" }) return today(today)
        if (words.any { it == "yesterday" }) {
            // ⚠ Backwards, in a forward-looking grammar, and deliberately: "what was on my
            // calendar yesterday" is a real question and the calendar keeps its past. It is the
            // *default* that leans forward, not the vocabulary.
            return day(today.minus(DatePeriod(days = 1)), "yesterday")
        }

        val next = words.any { it == "next" }
        if (words.any { it == "week" }) {
            val monday = today.minus(DatePeriod(days = today.dayOfWeek.ordinal))
            return if (next) {
                val from = monday.plus(DatePeriod(days = 7))
                AgendaWindow(from, from.plus(DatePeriod(days = 6)), "next week")
            } else {
                // ⚠ "This week" runs from **today**, not from Monday: an agenda is what is left to
                // happen, and listing Monday's meetings on Thursday answers a question about the
                // past that nobody asked.
                AgendaWindow(today, monday.plus(DatePeriod(days = 6)), "this week")
            }
        }
        if (words.any { it == "weekend" }) {
            val saturday = forward(today, DayOfWeek.SATURDAY, includeToday = !next)
            return AgendaWindow(saturday, saturday.plus(DatePeriod(days = 1)), "the weekend")
        }

        words.forEach { word ->
            WEEKDAYS[word]?.let { dayOfWeek ->
                // ⚠ FORWARD, matching [EventTimeGrammar]: "what's on Tuesday" asked on a Tuesday
                // means the day you are standing in, though — unlike scheduling, where "see you
                // Tuesday" cannot mean five minutes ago. So this one DOES include today.
                val date = forward(today, dayOfWeek, includeToday = !next)
                return day(date, word)
            }
        }
        return null
    }

    /** The default when nothing was named, kept here so the label and the range cannot drift. */
    fun today(today: LocalDate): AgendaWindow = day(today, "today")

    private fun day(date: LocalDate, label: String) = AgendaWindow(date, date, label)

    private fun forward(today: LocalDate, day: DayOfWeek, includeToday: Boolean): LocalDate {
        val ahead = (day.ordinal - today.dayOfWeek.ordinal + 7) % 7
        return today.plus(DatePeriod(days = if (ahead == 0 && !includeToday) 7 else ahead))
    }
}

/**
 * An **inclusive** range of whole days, plus the label the answer quotes back.
 *
 * ⚠⚠ Inclusive, unlike [TimeWindow], which is half-open — and the difference is not an
 * inconsistency. `TimeWindow` bounds *instants*, where a shared midnight would double-count a row;
 * this bounds *dates* handed to `OccurrenceResolver.resolve(from, to)`, whose contract is inclusive
 * on both ends. Converting to a half-open instant range here would mean re-deriving a boundary the
 * calendar has already settled, in a second place, from a different timezone assumption.
 */
data class AgendaWindow(
    val from: LocalDate,
    val to: LocalDate,
    val label: String,
)
