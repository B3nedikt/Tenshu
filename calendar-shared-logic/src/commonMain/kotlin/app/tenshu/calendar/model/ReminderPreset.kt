package app.tenshu.calendar.model

/**
 * The reminder offsets an editor offers (CA-5e).
 *
 * ⚠⚠ **Two lists, because a timed event and an all-day event are not asking the
 * same question.** A timed event alerts *before it starts*, so "10 minutes"
 * means ten minutes of warning. An all-day event starts at 00:00, so the same
 * choice would fire at 23:50 the night before — which is why every stock
 * calendar switches to a *time of day* ("on the day at 9 a.m.") the moment you
 * tick all-day. Offering one list for both is the bug that makes all-day
 * reminders arrive in the middle of the night.
 *
 * ⚠ Offsets are signed: positive is before the start, negative after. See
 * `Reminder`.
 */
object ReminderPresets {

    /** Offered for a timed event, in the order a picker shows them. */
    val TIMED: List<Int> = listOf(0, 5, 10, 15, 30, 60, 120, 1440)

    /**
     * Offered for an all-day event.
     *
     * ⚠ Negative values are *after* midnight on the day itself: `-540` is 09:00
     * that morning. The positive ones count back from midnight, so `540` is
     * 15:00 the previous afternoon — which is what "the day before" means to
     * someone packing for a trip.
     */
    val ALL_DAY: List<Int> = listOf(-540, -1080, 540, 900, 2340)

    /** The list to offer for [allDay]. */
    fun forEvent(allDay: Boolean): List<Int> = if (allDay) ALL_DAY else TIMED

    /**
     * A label for [minutesBefore], as a *timed* event reads it.
     *
     * ⚠ English only: the SwiftUI editor's words. The Compose editor renders
     * [describeTimed] from its ADR 0016 catalogue instead, so a change here does
     * not reach Android or desktop.
     */
    fun timedLabel(minutesBefore: Int): String = english(describeTimed(minutesBefore))

    /** What [timedLabel] says, as parts a localised renderer can word for itself. */
    fun describeTimed(minutesBefore: Int): ReminderLabel = when {
        minutesBefore == 0 -> ReminderLabel.AtStart
        minutesBefore % 1440 == 0 -> ReminderLabel.DaysBefore(minutesBefore / 1440)
        minutesBefore % 60 == 0 -> ReminderLabel.HoursBefore(minutesBefore / 60)
        else -> ReminderLabel.MinutesBefore(minutesBefore)
    }

    /**
     * A label for [minutesBefore], as an *all-day* event reads it.
     *
     * ⚠⚠ The same number means something different here, which is exactly why
     * the two labellers are separate functions rather than one with a flag: an
     * all-day offset is a *time of day on a day*, and rendering it as "540
     * minutes before" would be technically true and completely unreadable.
     */
    fun allDayLabel(minutesBefore: Int): String = english(describeAllDay(minutesBefore))

    /** What [allDayLabel] says, as parts a localised renderer can word for itself. */
    fun describeAllDay(minutesBefore: Int): ReminderLabel {
        // Minutes after midnight on the event's own day.
        val afterMidnight = -minutesBefore
        val dayOffset = floorDiv(afterMidnight, MINUTES_PER_DAY)
        val minuteOfDay = afterMidnight - dayOffset * MINUTES_PER_DAY
        val time = "${pad(minuteOfDay / 60)}:${pad(minuteOfDay % 60)}"

        return when (dayOffset) {
            0 -> ReminderLabel.OnTheDayAt(time)
            -1 -> ReminderLabel.DayBeforeAt(time)
            else -> ReminderLabel.DaysBeforeAt(-dayOffset, time)
        }
    }

    /** The parts for whichever kind of event is being edited — [label]'s localisable twin. */
    fun describe(minutesBefore: Int, allDay: Boolean): ReminderLabel =
        if (allDay) describeAllDay(minutesBefore) else describeTimed(minutesBefore)

    /** ⚠ The words the SwiftUI editor shows; the Compose one words [ReminderLabel] from its resources. */
    private fun english(label: ReminderLabel): String = when (label) {
        ReminderLabel.AtStart -> "At start"
        is ReminderLabel.DaysBefore -> "${label.days} day${plural(label.days)} before"
        is ReminderLabel.HoursBefore -> "${label.hours} hour${plural(label.hours)} before"
        is ReminderLabel.MinutesBefore -> "${label.minutes} min before"
        is ReminderLabel.OnTheDayAt -> "On the day at ${label.time}"
        is ReminderLabel.DayBeforeAt -> "Day before at ${label.time}"
        is ReminderLabel.DaysBeforeAt -> "${label.days} days before at ${label.time}"
    }

    /** The label for whichever kind of event is being edited. */
    fun label(minutesBefore: Int, allDay: Boolean): String =
        if (allDay) allDayLabel(minutesBefore) else timedLabel(minutesBefore)

    private fun plural(n: Int) = if (n == 1) "" else "s"

    private fun pad(v: Int) = v.toString().padStart(2, '0')

    /** ⚠ `Math.floorDiv` is JVM-only; this module is multiplatform. */
    private fun floorDiv(a: Int, b: Int): Int {
        val q = a / b
        return if (a % b != 0 && (a xor b) < 0) q - 1 else q
    }

    private const val MINUTES_PER_DAY = 24 * 60
}

/**
 * A reminder offset as parts rather than words, so a renderer can say it in its own language.
 * ⚠ [OnTheDayAt.time] and friends are the 24-hour `HH:mm` the English labels have always shown.
 */
sealed interface ReminderLabel {
    data object AtStart : ReminderLabel
    data class DaysBefore(val days: Int) : ReminderLabel
    data class HoursBefore(val hours: Int) : ReminderLabel
    data class MinutesBefore(val minutes: Int) : ReminderLabel
    data class OnTheDayAt(val time: String) : ReminderLabel
    data class DayBeforeAt(val time: String) : ReminderLabel
    data class DaysBeforeAt(val days: Int, val time: String) : ReminderLabel
}
