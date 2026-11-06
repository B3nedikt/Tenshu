package org.tenshu.calendar.common.reminder

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * What to do at a wake-up: what to show, what is too late to show, and when to
 * wake next.
 *
 * ⚠ [missed] is reported rather than discarded so a caller can record it. A
 * reminder that did not fire is a fact the user may need — silently dropping it
 * makes an OS that throttled us indistinguishable from one that did not.
 */
data class ReminderDrain(
    val deliver: List<DueReminder>,
    val missed: List<DueReminder>,
    /** When to arm the next wake; `null` when there is nothing left to wait for. */
    val nextWakeAt: Instant?,
)

/**
 * The wake-up decision for calendar reminders (`calendar-app.md` §4.1).
 *
 * ⭐⭐ **Pure, because the thing above it cannot be tested at all.** `AlarmManager`
 * firing, a notification appearing, an OS deciding to throttle us — none of that
 * is reachable from a unit test. What *is* reachable is every decision made in
 * the moment we wake, and that is all of it: this is the whole of what the
 * `Punctual` op does, minus the two effects.
 *
 * ## ⚠⚠ The catch-up window, and why it is not the routines engine's
 *
 * `routines-engine.md` RE-D6 fires if late by ≤ 15 minutes and records `Missed`
 * otherwise. Calendar wants the same three-way answer with a **tighter**
 * constant: a routine that runs the heating 12 minutes late still heats the
 * house, but a reminder for a meeting that started 12 minutes ago is worse than
 * useless — it tells you about something you have already missed, and trains you
 * to distrust the ones that arrive on time. Five minutes is where a late
 * reminder stops being a reminder.
 */
object ReminderDrainCalculator {

    /**
     * How late a reminder may be and still be worth showing.
     *
     * ⚠ Measured from the reminder's own [DueReminder.fireAt], not from the
     * event's start: "15 minutes before" fired 4 minutes late is still 11
     * minutes of warning, which is the thing the user asked for.
     */
    val DEFAULT_CATCH_UP: Duration = 5.minutes

    /**
     * Splits [planned] at [now].
     *
     * @param planned everything `ReminderPlanner` produced, in any order. ⚠ It
     *   must cover **both sides of [now]** — anything already due *and* the
     *   upcoming ones — because the same call decides what to show and when to
     *   come back. A caller that planned only the future would deliver nothing
     *   and re-arm forever.
     */
    fun drain(
        planned: List<DueReminder>,
        now: Instant,
        catchUpWindow: Duration = DEFAULT_CATCH_UP,
    ): ReminderDrain {
        val deliver = mutableListOf<DueReminder>()
        val missed = mutableListOf<DueReminder>()
        var nextWakeAt: Instant? = null

        planned.forEach { due ->
            when {
                // ⚠ Strictly after `now` is a *future* reminder, and the wake is
                // what will deliver it. Folding "due exactly now" into the
                // future would arm a zero-delay alarm and wake again immediately.
                due.fireAt > now ->
                    if (nextWakeAt == null || due.fireAt < nextWakeAt!!) nextWakeAt = due.fireAt

                now - due.fireAt <= catchUpWindow -> deliver += due

                else -> missed += due
            }
        }

        return ReminderDrain(
            // ⚠ Sorted so a burst arrives in the order the events do, and so the
            // result is deterministic for a test and readable in a log.
            deliver = deliver.sortedWith(compareBy({ it.fireAt }, { it.key })),
            missed = missed.sortedWith(compareBy({ it.fireAt }, { it.key })),
            nextWakeAt = nextWakeAt,
        )
    }
}
