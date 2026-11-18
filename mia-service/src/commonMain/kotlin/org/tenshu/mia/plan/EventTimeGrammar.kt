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

    /** "at one" … "at twelve". ⚠ Read as an hour only straight after "at" (see [hourAt]). */
    val NUMBER_WORDS: Map<String, Int> = listOf(
        "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
    ).withIndex().associate { (index, word) -> word to index + 1 }

    /** A part of the day, and whether it puts a bare hour after noon. */
    val DAY_PARTS: Map<String, Boolean> = mapOf(
        "morning" to false,
        "afternoon" to true, "evening" to true, "tonight" to true, "night" to true,
    )

    /** Words a part of day can follow and still be part of the time: "tomorrow evening". */
    private val DAY_PART_ANCHORS: Set<String> = WEEKDAYS.keys + setOf("this", "today", "tomorrow")

    /**
     * The indices of the day-part words that belong to the time expression.
     *
     * ⚠⚠ Bound by position, never "any day part anywhere": in "add night shift handover tomorrow
     * at 8 in the morning", "night" is title and "morning" is the time, and reading the first one
     * found proposed 20:00. A day part counts as "tonight", after "in the", after a day
     * ("tomorrow evening", "friday night") or straight after the hour ("at 8 tonight").
     */
    fun boundDayParts(words: List<String>): Set<Int> = words.indices.filter { i ->
        val word = words[i]
        val before = words.getOrNull(i - 1)
        word in DAY_PARTS && (
            word == "tonight" ||
                (before == "the" && words.getOrNull(i - 2) == "in") ||
                before in DAY_PART_ANCHORS ||
                isHourToken(before)
            )
    }.toSet()

    private fun isHourToken(word: String?): Boolean {
        if (word == null) return false
        if (word == "am" || word == "pm" || word in NUMBER_WORDS) return true
        if (word.split(':', '.').size == 2) return true
        return word.removeSuffix("pm").removeSuffix("am").toIntOrNull()?.let { it in 0..23 } == true
    }

    /**
     * A resolved start, plus the positions in the utterance the time expression used.
     *
     * ⭐ [consumed] is what lets the title keep everything else: "lunch at five guys" and "one on
     * one with tom" are title because the grammar did not read them as time, whatever they look
     * like.
     */
    data class EventTime(
        val start: LocalDateTime,
        val consumed: Set<Int>,
        /**
         * The other half of the day for a **bare** hour (§25, CL-D6): "dinner at 8" is 08:00 by the
         * convention, with 20:00 offered as one tap on the card. `null` whenever the words settled
         * it: am/pm, a part of day, a clock time ("18:30"), a relative time.
         */
        val alternative: LocalDateTime? = null,
    )

    /**
     * Resolves the time expression in [words], or `null` when there is none — in which case
     * Mia must **not** invent one. An event with a guessed time is a wrong row that fires a
     * reminder weeks later (MQ-D1's amendment), so "when?" is the right answer.
     */
    fun resolve(words: List<String>, nowMs: Long, zone: TimeZone): EventTime? {
        val now = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(zone)

        relativeOffset(words)?.let { (minutes, consumed) ->
            return EventTime(Instant.fromEpochMilliseconds(nowMs + minutes * 60_000L).toLocalDateTime(zone), consumed)
        }

        val (time, timeWords) = timeOfDay(words) ?: return null
        val (date, dateWords) = date(words, now.date) ?: (now.date to emptySet())
        val start = LocalDateTime(date.year, date.month, date.day, time.first, time.second)
        return EventTime(
            start = start,
            consumed = timeWords + dateWords + dayPartWords(words),
            alternative = if (isBareHour(words, timeWords)) otherHalf(start) else null,
        )
    }

    /**
     * Whether the hour was said with nothing to settle its half of the day: a bare 1–11, digit or
     * word, with no am/pm, no bound part of day and no clock form. ⚠ 12 is left alone: "at 12" is
     * noon far more often than midnight, and offering midnight would be noise.
     */
    private fun isBareHour(words: List<String>, timeWords: Set<Int>): Boolean {
        if (boundDayParts(words).isNotEmpty()) return false
        val used = timeWords.map { words[it] }
        if (used.any { it == "am" || it == "pm" || it.endsWith("am") || it.endsWith("pm") || ':' in it || '.' in it }) return false
        val hour = used.firstNotNullOfOrNull { it.toIntOrNull() ?: NUMBER_WORDS[it] } ?: return false
        return hour in 1..11
    }

    private fun otherHalf(start: LocalDateTime): LocalDateTime =
        LocalDateTime(start.date.year, start.date.month, start.date.day, (start.hour + 12) % 24, start.minute)

    /**
     * What an utterance said about the start **without** a time: the day and the part of day it
     * named, and the positions they used (§25, CL-D1). "add dentist tomorrow morning" → tomorrow,
     * morning. The planner asks for the rest instead of refusing, and the title still loses only
     * these positions.
     */
    data class Partial(val day: LocalDate?, val dayPart: String?, val consumed: Set<Int>)

    fun partial(words: List<String>, nowMs: Long, zone: TimeZone): Partial {
        val today = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(zone).date
        val date = date(words, today)
        return Partial(
            day = date?.first,
            dayPart = boundDayParts(words).minOrNull()?.let { words[it] },
            consumed = date?.second.orEmpty() + dayPartWords(words),
        )
    }

    /**
     * A reply read **only** as the start's time (§25, CL-D5), or `null` when it is not one, in
     * which case the reply is a new question.
     *
     * [day] and [dayPart] are what the question already knew: "9" answering *"What time tomorrow
     * morning?"* is 09:00 tomorrow, and "8" answering *"What time this evening?"* is 20:00. A
     * canonical chip reply (`2026-11-17T09:00`) is taken as it is.
     *
     * ⚠ A bare reply is read as if it followed "at" ("9", "nine"), because that is the question's
     * own grammar. The reply's own day or part of day still wins over the question's.
     */
    fun resolveReply(reply: List<String>, day: LocalDate?, dayPart: String?, nowMs: Long, zone: TimeZone): LocalDateTime? =
        resolveReplyTime(reply, day, dayPart, nowMs, zone)?.start

    /**
     * [resolveReply] with what it consumed and, for a bare hour, the other half of the day: a typed
     * "8" answering *"What time tomorrow?"* is as ambiguous as "dinner at 8" (CL-D6). ⚠ Not when the
     * question already knew the part of day ("What time this evening?" → "8" is 20:00).
     */
    fun resolveReplyTime(reply: List<String>, day: LocalDate?, dayPart: String?, nowMs: Long, zone: TimeZone): EventTime? {
        reply.singleOrNull()?.let { only ->
            runCatching { LocalDateTime.parse(only) }.getOrNull()?.let { return EventTime(it, setOf(0)) }
        }
        // ⚠ Trailing filler means nothing in a reply, so it goes before parsing: "nine please" is
        // "nine". Kept, it failed the spoken hour's end-of-time check (Codex review). The request
        // grammar keeps that check whole, which is what tells "at five guys" from "at five".
        val words = reply.dropLastWhile { it in REPLY_FILLERS && it != "at" }
        if (words.isEmpty()) return null
        val now = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(zone)

        // ⚠ Relative first, as `resolve` does: "in 10 minutes" is ten minutes from now, and read as a
        // clock it would be 10:00 (Codex review).
        relativeOffset(words)?.let { (minutes, used) ->
            if (!onlyTime(words, used)) return null
            return EventTime(Instant.fromEpochMilliseconds(nowMs + minutes * 60_000L).toLocalDateTime(zone), used)
        }

        // The question's own grammar: a bare reply reads as if it followed "at", so an "at" goes
        // straight before the first hour-like word ("how about nine" → "how about at nine").
        val hourAt = words.indexOfFirst { isHourToken(it) || it == "noon" }
        val asked = if ("at" in words || hourAt < 0) words else words.take(hourAt) + "at" + words.drop(hourAt)
        // ⚠ In a reply the whole utterance is the time, so a part of day counts wherever it sits:
        // a typed chip label ("Evening 19:00") must parse like the chip.
        val dayParts = asked.indices.filter { asked[it] in DAY_PARTS }
        val afternoon = dayParts.firstOrNull()?.let { DAY_PARTS[asked[it]] } ?: dayPart?.let { DAY_PARTS[it] }
        val (time, timeUsed) = timeOfDay(asked, defaultAfternoon = afternoon) ?: return null
        val dated = date(asked, now.date)
        // With their lead-ins, as [dayPartWords] has for a request: "in the evening", "this evening".
        val partWords = dayParts.flatMap { i ->
            when {
                asked.getOrNull(i - 1) == "the" && asked.getOrNull(i - 2) == "in" -> listOf(i - 2, i - 1, i)
                asked.getOrNull(i - 1) == "this" -> listOf(i - 1, i)
                else -> listOf(i)
            }
        }
        val used = timeUsed + dated?.second.orEmpty() + partWords
        if (!onlyTime(asked, used)) return null

        // ⚠ "this evening" names today, over the question's stored day: answering "What time
        // tomorrow?" with "this evening at 8" moves the event to tonight (Codex review).
        val thisDay = dayParts.any { asked.getOrNull(it - 1) == "this" }
        val date = dated?.first ?: (if (thisDay) now.date else null) ?: day ?: now.date
        val start = LocalDateTime(date.year, date.month, date.day, time.first, time.second)
        val bare = dayPart == null && dayParts.isEmpty() && isBareHour(asked, timeUsed)
        return EventTime(start, used, alternative = if (bare) otherHalf(start) else null)
    }

    /**
     * ⚠⚠ Whether a reply is a time and nothing else. Every word must be part of the time
     * expression or harmless filler ("make it 9", "how about nine"). Anything more and the reply
     * is a new question: "actually, what's on my calendar tomorrow at 3pm" holds a time, and
     * reading it as the answer booked the dentist for 15:00 (Codex review).
     */
    private fun onlyTime(words: List<String>, used: Set<Int>): Boolean =
        words.indices.all { it in used || words[it] in REPLY_FILLERS }

    private val REPLY_FILLERS = setOf(
        "at", "around", "about", "maybe", "make", "it", "let's", "lets", "say", "how", "um", "uh",
        "ok", "okay", "please", "then", "sharp", "oclock", "o'clock", "the",
    )

    /** "in an hour", "in 30 minutes", with the three positions it used. */
    private fun relativeOffset(words: List<String>): Pair<Long, Set<Int>>? {
        val at = words.indexOf("in")
        if (at < 0) return null
        val amountWord = words.getOrNull(at + 1) ?: return null
        val unit = words.getOrNull(at + 2) ?: return null
        val amount = when (amountWord) {
            "an", "a" -> 1L
            else -> amountWord.toLongOrNull() ?: return null
        }
        val minutes = when (unit) {
            "hour", "hours" -> amount * 60
            "minute", "minutes" -> amount
            else -> return null
        }
        return minutes to setOf(at, at + 1, at + 2)
    }

    /** The date and the positions it used, or `null` for "today" by omission. */
    private fun date(words: List<String>, today: LocalDate): Pair<LocalDate, Set<Int>>? {
        words.indexOf("tomorrow").takeIf { it >= 0 }?.let { return today.plus(DatePeriod(days = 1)) to setOf(it) }
        words.indexOfFirst { it == "today" || it == "tonight" }.takeIf { it >= 0 }?.let { return today to setOf(it) }

        words.forEachIndexed { index, word ->
            WEEKDAYS[word]?.let { day ->
                // ⚠ FORWARD: the next such day, and never today — "see you Tuesday" said on a
                // Tuesday means next week, not five minutes ago.
                val ahead = ((day.ordinal - today.dayOfWeek.ordinal + 7) % 7).let {
                    if (it == 0) 7 else it
                }
                // "on friday", "next friday", "on next friday": the lead-in is time too.
                var first = index
                while (words.getOrNull(first - 1) in setOf("on", "next", "this")) first--
                return today.plus(DatePeriod(days = ahead)) to (first..index).toSet()
            }
        }
        return null
    }

    /** Bound day parts, with the "in the" or "this" that introduces each. */
    private fun dayPartWords(words: List<String>): Set<Int> = boundDayParts(words).flatMap { i ->
        when {
            words.getOrNull(i - 1) == "the" && words.getOrNull(i - 2) == "in" -> listOf(i - 2, i - 1, i)
            words.getOrNull(i - 1) == "this" -> listOf(i - 1, i)
            else -> listOf(i)
        }
    }.toSet()

    /** `(hour, minute)` in 24h with the positions it used, or `null` when no time was given. */
    private fun timeOfDay(words: List<String>, defaultAfternoon: Boolean? = null): Pair<Pair<Int, Int>, Set<Int>>? {
        val at = words.indexOfFirst { it == "at" }
        val offset = if (at >= 0) at + 1 else 0
        // ⚠⚠ An explicit part of day outranks the bare-hour convention below: "at 8 in the
        // evening" is 20:00, and the convention (8 → morning) used to win over the words.
        // [defaultAfternoon] is a part of day the question already knew (a reply, §25).
        val afternoon = boundDayParts(words).minOrNull()?.let { DAY_PARTS[words[it]] } ?: defaultAfternoon

        // ⚠⚠ A clock time anywhere wins over a spoken one: in "add lunch at five guys tomorrow at
        // 8pm", "five" is a venue and "8pm" the time (Codex review). A number word is the hour
        // only when no digit time exists at all.
        val (time, index) = clockTime(words.drop(offset), afternoon)?.let { (t, i) -> t to i + offset }
            ?: spokenTime(words, afternoon)
            ?: return null
        return time to hourWords(words, index)
    }

    /** The hour's own position, the "at" before it, and an am/pm or "o'clock" after it. */
    private fun hourWords(words: List<String>, index: Int): Set<Int> = buildSet {
        add(index)
        if (words.getOrNull(index - 1) == "at") add(index - 1)
        if (words.getOrNull(index + 1) in setOf("am", "pm", "oclock", "o'clock")) add(index + 1)
    }

    private fun clockTime(candidates: List<String>, afternoon: Boolean?): Pair<Pair<Int, Int>, Int>? {
        candidates.forEachIndexed { index, word ->
            // "15:00" / "9.30"
            val parts = word.split(':', '.')
            if (parts.size == 2) {
                val h = parts[0].toIntOrNull()
                val m = parts[1].toIntOrNull()
                if (h != null && m != null && h in 0..23 && m in 0..59) return (h to m) to index
            }

            // "3pm" / "3" followed by "pm"
            val hour = word.removeSuffix("pm").removeSuffix("am").toIntOrNull() ?: return@forEachIndexed
            if (hour !in 0..23) return@forEachIndexed
            val suffix = when {
                word.endsWith("pm") -> "pm"
                word.endsWith("am") -> "am"
                else -> candidates.getOrNull(index + 1)?.takeIf { it == "am" || it == "pm" }
            }
            return hourOf(hour, suffix, afternoon) to index
        }
        return null
    }

    /**
     * "at noon", "at one" — a spoken hour, read only straight after an "at" **and only where the
     * time expression ends there**.
     *
     * ⚠⚠ A number word after "at" is as often a name: "add lunch at five guys tomorrow" names a
     * venue and gives no time, and reading "five" proposed 17:00 (Codex review); "noon" is no
     * different ("add lunch at noon cafe tomorrow"). So the word must be followed by nothing,
     * am/pm, "o'clock", a day or a bound part of day. Anything else is not a time, and the answer
     * is "when?" — the safe failure, since a guessed time is a wrong row.
     */
    private fun spokenTime(words: List<String>, afternoon: Boolean?): Pair<Pair<Int, Int>, Int>? {
        val dayParts = boundDayParts(words)
        val i = words.indices.firstOrNull {
            val word = words[it]
            words.getOrNull(it - 1) == "at" &&
                (word == "noon" || word in NUMBER_WORDS) && endsTheTime(words, it + 1, dayParts)
        } ?: return null
        if (words[i] == "noon") return (12 to 0) to i
        val suffix = words.getOrNull(i + 1)?.takeIf { it == "am" || it == "pm" }
        return hourOf(NUMBER_WORDS.getValue(words[i]), suffix, afternoon) to i
    }

    private fun endsTheTime(words: List<String>, next: Int, dayParts: Set<Int>): Boolean {
        val word = words.getOrNull(next) ?: return true
        return word in setOf("am", "pm", "oclock", "o'clock", "on", "next") ||
            word in DAY_PART_ANCHORS ||
            next in dayParts ||
            (word == "in" && (next + 2) in dayParts)
    }

    private fun hourOf(hour: Int, suffix: String?, afternoon: Boolean?): Pair<Int, Int> = when {
        suffix == "pm" -> (if (hour == 12) 12 else hour + 12) to 0
        suffix == "am" -> (if (hour == 12) 0 else hour) to 0
        afternoon == true && hour in 1..11 -> (hour + 12) to 0
        afternoon == false && hour in 1..12 -> (if (hour == 12) 0 else hour) to 0
        // ⚠⚠ A BARE hour is ambiguous and the cost of guessing wrong is a reminder at
        // 03:00 three weeks later. The convention — 1–7 reads as afternoon/evening,
        // 8–12 as morning — is what every calendar app converged on, and it is only
        // defensible BECAUSE MQ-D7's cell shows the resolved time before the row
        // exists. Never adopt it anywhere the user does not see the result.
        hour in 1..7 -> (hour + 12) to 0
        else -> hour to 0
    }
}
