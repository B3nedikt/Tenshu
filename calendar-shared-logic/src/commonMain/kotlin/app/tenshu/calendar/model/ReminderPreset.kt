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
     * ⚠ Not localised, and that is a known gap rather than an oversight: unlike
     * a weekday name this really is copy, so it belongs in the ADR 0016
     * catalogue rather than in a platform date formatter. Left here, visible,
     * until the app's other strings move too — every string in Calendar is in
     * the same position.
     */
    fun timedLabel(minutesBefore: Int): String = when {
        minutesBefore == 0 -> "At start"
        minutesBefore % 1440 == 0 -> "${minutesBefore / 1440} day${plural(minutesBefore / 1440)} before"
        minutesBefore % 60 == 0 -> "${minutesBefore / 60} hour${plural(minutesBefore / 60)} before"
        else -> "$minutesBefore min before"
    }

    /**
     * A label for [minutesBefore], as an *all-day* event reads it.
     *
     * ⚠⚠ The same number means something different here, which is exactly why
     * the two labellers are separate functions rather than one with a flag: an
     * all-day offset is a *time of day on a day*, and rendering it as "540
     * minutes before" would be technically true and completely unreadable.
     */
    fun allDayLabel(minutesBefore: Int): String {
        // Minutes after midnight on the event's own day.
        val afterMidnight = -minutesBefore
        val dayOffset = floorDiv(afterMidnight, MINUTES_PER_DAY)
        val minuteOfDay = afterMidnight - dayOffset * MINUTES_PER_DAY
        val time = "${pad(minuteOfDay / 60)}:${pad(minuteOfDay % 60)}"

        return when (dayOffset) {
            0 -> "On the day at $time"
            -1 -> "Day before at $time"
            else -> "${-dayOffset} days before at $time"
        }
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
