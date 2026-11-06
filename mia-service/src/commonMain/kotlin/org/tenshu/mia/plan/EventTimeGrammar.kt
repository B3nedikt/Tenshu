package org.tenshu.mia.plan

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * "tomorrow at 3" → a `LocalDateTime` (`mia-query-layer.md` MQ-D4a).
 *
 * ⚠⚠ **A scheduling grammar looks FORWARD; the search grammar looks BACK.** They cannot be
 * the same code even where the words overlap. [TimeGrammar] resolves "saturday" to the most
 * recent one *that has passed*, because you ask about what you did; here "saturday" is the
 * *next* one, because you schedule what you will do. Sharing one resolver would make one of
 * the two silently wrong, and the wrong one would be the write.
 *
 * ⚠⚠ **This is where MQ-D4's localisation bill comes due in full.** A recording intent is
 * almost nothing *but* time expressions, so a second language is a second grammar here before
 * it is one anywhere else.
 *
 * ⭐ It resolves to a **`LocalDateTime`, not an instant**, which is exactly the shape
 * `Event.startLocal` wants (`calendar-app.md` §3 D2/D3: `(LocalDateTime, tzid)`). That is why
 * the string-typed field that blocks `QueryCalendar` (§8.4) does not block this at all — the
 * grammar's natural output *is* the write's natural input.
 */
object EventTimeGrammar {

    private val WEEKDAYS: Map<String, DayOfWeek> = mapOf(
        "monday" to DayOfWeek.MONDAY, "mon" to DayOfWeek.MONDAY,
        "tuesday" to DayOfWeek.TUESDAY, "tue" to DayOfWeek.TUESDAY,
        "wednesday" to DayOfWeek.WEDNESDAY, "wed" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY, "thu" to DayOfWeek.THURSDAY,
        "friday" to DayOfWeek.FRIDAY, "fri" to DayOfWeek.FRIDAY,
        "saturday" to DayOfWeek.SATURDAY, "sat" to DayOfWeek.SATURDAY,
        "sunday" to DayOfWeek.SUNDAY, "sun" to DayOfWeek.SUNDAY,
    )

    /** Every word this grammar consumes, so the title is what remains. */
    val TIME_WORDS: Set<String> = WEEKDAYS.keys + setOf(
        "today", "tomorrow", "tonight", "next", "this", "at", "on", "in", "an", "a",
        "am", "pm", "hour", "hours", "minute", "minutes", "morning", "afternoon", "evening",
    )

    /**
     * Resolves the time expression in [words], or `null` when there is none — in which case
     * Mia must **not** invent one. An event with a guessed time is a wrong row that fires a
     * reminder weeks later (MQ-D1's amendment), so "when?" is the right answer.
     */
    fun resolve(words: List<String>, nowMs: Long, zone: TimeZone): LocalDateTime? {
        val now = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(zone)

        relativeOffsetMinutes(words)?.let { minutes ->
            return Instant.fromEpochMilliseconds(nowMs + minutes * 60_000L).toLocalDateTime(zone)
        }

        val time = timeOfDay(words) ?: return null
        val date = date(words, now.date) ?: now.date
        return LocalDateTime(date.year, date.month, date.day, time.first, time.second)
    }

    /** "in an hour", "in 30 minutes". */
    private fun relativeOffsetMinutes(words: List<String>): Long? {
        val at = words.indexOf("in")
        if (at < 0) return null
        val amountWord = words.getOrNull(at + 1) ?: return null
        val unit = words.getOrNull(at + 2) ?: return null
        val amount = when (amountWord) {
            "an", "a" -> 1L
            else -> amountWord.toLongOrNull() ?: return null
        }
        return when (unit) {
            "hour", "hours" -> amount * 60
            "minute", "minutes" -> amount
            else -> null
        }
    }

    /** The date, or `null` for "today" by omission. */
    private fun date(words: List<String>, today: LocalDate): LocalDate? {
        if (words.any { it == "tomorrow" }) return today.plus(DatePeriod(days = 1))
        if (words.any { it == "today" || it == "tonight" }) return today

        words.forEach { word ->
            WEEKDAYS[word]?.let { day ->
                // ⚠ FORWARD: the next such day, and never today — "see you Tuesday" said on a
                // Tuesday means next week, not five minutes ago.
                val ahead = ((day.ordinal - today.dayOfWeek.ordinal + 7) % 7).let {
                    if (it == 0) 7 else it
                }
                return today.plus(DatePeriod(days = ahead))
            }
        }
        return null
    }

    /** `(hour, minute)` in 24h, or `null` when no time was given. */
    private fun timeOfDay(words: List<String>): Pair<Int, Int>? {
        val at = words.indexOfFirst { it == "at" }
        val candidates = if (at >= 0) words.drop(at + 1) else words

        candidates.forEachIndexed { index, word ->
            // "15:00" / "9.30"
            val parts = word.split(':', '.')
            if (parts.size == 2) {
                val h = parts[0].toIntOrNull()
                val m = parts[1].toIntOrNull()
                if (h != null && m != null && h in 0..23 && m in 0..59) return h to m
            }

            // "3pm" / "3" followed by "pm"
            val bare = word.removeSuffix("pm").removeSuffix("am")
            val hour = bare.toIntOrNull() ?: return@forEachIndexed
            if (hour !in 0..23) return@forEachIndexed

            val suffix = when {
                word.endsWith("pm") -> "pm"
                word.endsWith("am") -> "am"
                else -> candidates.getOrNull(index + 1)?.takeIf { it == "am" || it == "pm" }
            }
            return when {
                suffix == "pm" -> (if (hour == 12) 12 else hour + 12) to 0
                suffix == "am" -> (if (hour == 12) 0 else hour) to 0
                // ⚠⚠ A BARE hour is ambiguous and the cost of guessing wrong is a reminder at
                // 03:00 three weeks later. The convention — 1–7 reads as afternoon/evening,
                // 8–12 as morning — is what every calendar app converged on, and it is only
                // defensible BECAUSE MQ-D7's cell shows the resolved time before the row
                // exists. Never adopt it anywhere the user does not see the result.
                hour in 1..7 -> (hour + 12) to 0
                else -> hour to 0
            }
        }
        return null
    }
}
