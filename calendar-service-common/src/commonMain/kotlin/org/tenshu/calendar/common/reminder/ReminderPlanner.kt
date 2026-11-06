package org.tenshu.calendar.common.reminder

import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.tenshu.calendar.common.recurrence.ResolvedOccurrence

/** One reminder row, as the planner needs it. */
data class ReminderInput(
    val reminderId: String,
    val eventId: String,
    /** Minutes **before** the occurrence starts; negative means after it. */
    val minutesBefore: Int,
)

/** One per-occurrence state row; absent means "not yet acted on". */
data class ReminderStateInput(
    val reminderId: String,
    val recurrenceId: String,
    val snoozedUntilEpochMs: Long = 0L,
    val dismissed: Boolean = false,
)

/**
 * A reminder that should be armed, with everything the OS needs to show it.
 *
 * ⚠⚠ [title] and [body] are **already redacted** where the calendar is
 * sensitive. That is not a convenience — see [ReminderPlanner] on why redaction
 * cannot be deferred to display time.
 */
data class DueReminder(
    /**
     * Stable identity across recomputations.
     *
     * ⚠⚠ Load-bearing, not decorative. On Apple platforms the OS holds the
     * pending notification and the only way to update or cancel one is to
     * address it by identifier; on Android it is the `dedupKey`. Derived from
     * (reminder, occurrence) so the *same* reminder re-planned an hour later
     * replaces its own pending copy instead of stacking a second one.
     */
    val key: String,
    val eventId: String,
    val recurrenceId: String,
    val reminderId: String,
    val calendarId: String,
    /** When to alert. Snooze moves this; it is not always `start − minutesBefore`. */
    val fireAt: Instant,
    val occurrenceStart: Instant,
    val title: String,
    val body: String,
    /** True when [title]/[body] were replaced because the calendar is sensitive. */
    val redacted: Boolean,
    /** True when [fireAt] came from a snooze rather than the reminder's offset. */
    val snoozed: Boolean,
)

/**
 * Works out which reminders should be armed in a window (`calendar-app.md` §4).
 *
 * ⭐⭐ **Pure, clockless, and platform-free**, for the same reason `DayLayout`
 * and `OccurrenceResolver` are: the two mechanisms above it could not be more
 * different — Android wakes at an instant and decides, Apple platforms are told
 * in advance and hold the schedule themselves (§4.6) — and the *only* way both
 * agree about what is due is to compute it in one place neither of them owns.
 *
 * ## ⚠⚠ Redaction happens here, not at display time
 *
 * Q10's per-calendar `sensitive` flag suppresses event text on a lock screen. It
 * would be natural to apply that where the notification is drawn — and on Apple
 * platforms that is **impossible**, because we hand the OS the finished text
 * days in advance and never see it again. So the text is redacted at the moment
 * it is composed, which is here, and a presenter that wanted to show details it
 * was not given cannot. That asymmetry is worth the loss of flexibility: the
 * failure mode of the other order is a "Health" calendar announcing itself on a
 * lock screen in a meeting.
 *
 * ## ⚠ What this deliberately does not know
 *
 * Whether a reminder has **already been delivered**. Nothing is stored when a
 * reminder fires and is ignored — only when the user acts ([ReminderStateInput]) —
 * so the planner answers "what should be armed for this window", and not
 * re-delivering is the presenter's duty: the stable [DueReminder.key] replaces
 * rather than stacks on every platform, which is exactly what makes that
 * duty cheap.
 */
object ReminderPlanner {

    /**
     * Every reminder whose alert falls in `[from, until]`, soonest first.
     *
     * @param floatingZoneId the zone to read an event that carries none
     *   ("09:00 wherever I am", §3 D3 kind 2). ⚠ Passed in rather than read from
     *   the device, the `RoutineLocale` discipline: a travelling laptop must not
     *   quietly compute a different instant than the phone in your pocket — but
     *   for a *floating* event the local zone genuinely is the definition, which
     *   is why this is a parameter and not a prohibition.
     * @param limit the most reminders to return. ⚠ Not a nicety: iOS caps an app
     *   at **64 pending notifications** (§4.3), and a caller that pre-arms more
     *   than the cap silently loses the overflow — with no error, and with the
     *   *later* ones dropped, which is the wrong end. Taking the soonest N here
     *   means the overflow is the far future, which the next re-plan will cover.
     */
    fun plan(
        occurrences: List<ResolvedOccurrence>,
        reminders: List<ReminderInput>,
        states: List<ReminderStateInput> = emptyList(),
        sensitiveCalendarIds: Set<String> = emptySet(),
        from: Instant,
        until: Instant,
        floatingZoneId: String,
        limit: Int = DEFAULT_LIMIT,
    ): List<DueReminder> {
        if (reminders.isEmpty() || limit <= 0) return emptyList()

        val byEvent = reminders.groupBy { it.eventId }
        if (byEvent.isEmpty()) return emptyList()

        val stateByKey = states.associateBy { it.reminderId to it.recurrenceId }

        return occurrences
            .flatMap { occurrence ->
                byEvent[occurrence.eventId]
                    .orEmpty()
                    .mapNotNull { reminder ->
                        due(occurrence, reminder, stateByKey, sensitiveCalendarIds, floatingZoneId)
                    }
            }
            .filter { it.fireAt >= from && it.fireAt <= until }
            // ⚠ Tie-broken by key so the result is stable: two reminders due at
            // the same instant must not swap places between plans, or the
            // presenter's diff sees churn that is not there.
            .sortedWith(compareBy({ it.fireAt }, { it.key }))
            .take(limit)
    }

    private fun due(
        occurrence: ResolvedOccurrence,
        reminder: ReminderInput,
        states: Map<Pair<String, String>, ReminderStateInput>,
        sensitiveCalendarIds: Set<String>,
        floatingZoneId: String,
    ): DueReminder? {
        val state = states[reminder.reminderId to occurrence.recurrenceId]
        // ⚠ A dismissal wins over a snooze. The two fields are independent so a
        // dismissal need not remember to clear the snooze, which means the
        // precedence has to be decided *here* — and "I am done with this" must
        // beat "remind me again", never the other way round.
        if (state?.dismissed == true) return null

        val zone = zoneFor(occurrence.zoneId.ifBlank { floatingZoneId }) ?: return null
        val start = occurrence.startLocal.toInstant(zone)

        val snoozedUntil = state?.snoozedUntilEpochMs?.takeIf { it > 0L }
        val fireAt = snoozedUntil
            ?.let { Instant.fromEpochMilliseconds(it) }
            ?: start.minusMinutes(reminder.minutesBefore)

        val sensitive = occurrence.calendarId in sensitiveCalendarIds

        return DueReminder(
            key = keyFor(reminder.reminderId, occurrence.recurrenceId),
            eventId = occurrence.eventId,
            recurrenceId = occurrence.recurrenceId,
            reminderId = reminder.reminderId,
            calendarId = occurrence.calendarId,
            fireAt = fireAt,
            occurrenceStart = start,
            title = if (sensitive) REDACTED_TITLE else occurrence.title.ifBlank { UNTITLED },
            // ⚠ The body carries the location, which is as identifying as the
            // title — "Oncology, 3rd floor" says everything the title would.
            body = if (sensitive) REDACTED_BODY else occurrence.location,
            redacted = sensitive,
            snoozed = snoozedUntil != null,
        )
    }

    /** The stable OS-level identity; see [DueReminder.key]. */
    fun keyFor(reminderId: String, recurrenceId: String): String = "$reminderId@$recurrenceId"

    /**
     * ⚠ A bad zone id yields `null`, dropping the reminder rather than throwing.
     * The `EventOccurrence`/`FiringCalculator` house shape: a row that survived
     * storage must not be able to break the whole plan, and the event itself
     * still shows in the grid.
     */
    private fun zoneFor(id: String): TimeZone? =
        runCatching { TimeZone.of(id) }.getOrNull()

    private fun Instant.minusMinutes(minutes: Int): Instant =
        Instant.fromEpochMilliseconds(toEpochMilliseconds() - minutes * 60_000L)

    /**
     * ⚠ Matches the documented iOS pending-notification cap, and §4.6 says to
     * assume it applies on macOS until checked. Android does not have it, but
     * arming a different number per platform would mean the two disagree about
     * what is scheduled — and the cost of the smaller window is one re-plan.
     */
    const val DEFAULT_LIMIT: Int = 64

    /**
     * ⚠ Deliberately says nothing — not the calendar name, which would leak the
     * category the flag exists to hide, and not a count, which leaks a pattern.
     */
    const val REDACTED_TITLE: String = "Reminder"
    const val REDACTED_BODY: String = ""

    private const val UNTITLED = "(No title)"
}
