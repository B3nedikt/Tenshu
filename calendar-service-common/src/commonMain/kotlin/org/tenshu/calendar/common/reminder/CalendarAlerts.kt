package org.tenshu.calendar.common.reminder

import app.tenshu.notify.AlertAction
import app.tenshu.notify.AlertChannel
import app.tenshu.notify.AlertImportance
import app.tenshu.notify.AppAlert
import app.tenshu.notify.ScheduledAlert

/**
 * Turns a planned reminder into something the OS can hold (CA-5b).
 *
 * ⭐ The one place Calendar's vocabulary meets the platform seam's, so the
 * Android armer (CA-5c) and the Apple pre-arm (CA-5d) cannot disagree about what
 * a reminder looks like once it leaves the app.
 *
 * ⚠ Lives beside `ReminderPlanner` rather than in `-shared-logic`, where CA-5b
 * first put it: the **proxy** is what posts a reminder, and it cannot depend on
 * an app module. Same reason the planner is here.
 */
object CalendarAlerts {

    /**
     * The channel every event reminder arrives on.
     *
     * ⚠⚠ `HIGH`, and that is a promise we cannot take back: Android fixes a
     * channel's importance at creation, so this cannot be quietly lowered later
     * for anyone who already has the app. It is the right level — a reminder the
     * user explicitly asked for, at a moment they chose, is the definition of
     * time-sensitive — but it means a *quieter* Calendar notification later
     * (say, "your calendar synced") needs its **own** channel rather than
     * reusing this one.
     *
     * ⚠ One channel for all calendars, not one per calendar. Per-calendar
     * channels would let the user silence "Health" in Android settings — which
     * sounds like a feature until you notice the channel *name* is then the
     * calendar name, listed in the OS UI, which is exactly the disclosure the
     * Q10 `sensitive` flag exists to prevent.
     */
    val Channel = AlertChannel(
        id = "tenshu_calendar_reminders",
        name = "Event reminders",
        importance = AlertImportance.HIGH,
    )

    /**
     * The alert for one due reminder.
     *
     * ⚠⚠ **[DueReminder.title] and [DueReminder.body] are copied verbatim,
     * never enriched.** They were already redacted at plan time where the
     * calendar is sensitive, and on Apple platforms the OS keeps this text for
     * days — so anything added here could not be taken back. That is why this
     * function reads nothing but the planned reminder.
     */
    fun scheduledAlert(due: DueReminder): ScheduledAlert = ScheduledAlert(
        alert = AppAlert(
            id = due.key,
            channel = Channel,
            title = due.title,
            body = due.body,
            tapPayload = tapPayload(due),
            actions = ACTIONS,
            targetApp = ANDROID_PACKAGE,
        ),
        deliverAt = due.fireAt,
    )

    /**
     * Snooze and dismiss, on every reminder (CA-5f).
     *
     * ⚠⚠ **Both, and in this order.** *Dismiss* alone is what swiping already
     * does — except that a swipe writes nothing, so the reminder re-arms and comes
     * back. The button is what makes "I'm done with this" durable, and the planner
     * has honoured the row since CA-5a with nothing able to write it.
     *
     * ⚠ Snooze carries no duration in its id. Offering "10 min / 1 hour /
     * tomorrow" would need three buttons, and Android shows at most three total
     * with the third routinely collapsed — so one fixed interval, chosen where the
     * clock is (`SNOOZE_MINUTES` lives with the dispatcher, not here).
     */
    val ACTIONS: List<AlertAction> = listOf(
        AlertAction(id = ACTION_SNOOZE, label = "Snooze"),
        AlertAction(id = ACTION_DISMISS, label = "Dismiss"),
    )

    const val ACTION_SNOOZE = "calendar.snooze"
    const val ACTION_DISMISS = "calendar.dismiss"

    /**
     * Where a Calendar reminder's tap should land on Android.
     *
     * ⚠⚠ An Android application id in a platform-neutral module, deliberately. It
     * is a string every other platform ignores, and the alternative — a
     * payload-prefix→package map somewhere in Control's DI — hides Calendar's own
     * identity in an app that should not need to know it. ⚠ It must match
     * `calendar-android`'s `applicationId`; nothing enforces that but the smoke
     * gate, where a tap landing in Control is the visible symptom.
     */
    const val ANDROID_PACKAGE = "app.tenshu.calendar.android"

    /**
     * Where a tap should land: the occurrence, addressed by **id**.
     *
     * ⚠⚠ Identifiers, deliberately — never the title. A tap payload rides in a
     * `PendingIntent` extra, so putting the event's text there would smuggle a
     * *redacted* reminder's title back onto the device in the clear, defeating
     * Q10 through the back door. The app resolves ids to text after the user has
     * already unlocked and opened it.
     */
    fun tapPayload(due: DueReminder): String = "$PAYLOAD_PREFIX${due.eventId}|${due.recurrenceId}"

    /** Parses [tapPayload] back to `(eventId, recurrenceId)`, or `null` if it is not ours. */
    fun parseTapPayload(payload: String): Pair<String, String>? {
        if (!payload.startsWith(PAYLOAD_PREFIX)) return null
        val body = payload.removePrefix(PAYLOAD_PREFIX)
        // ⚠ `indexOf`, not `split`: a recurrenceId is an ISO date-time and an
        // eventId is opaque, so neither is guaranteed free of the separator
        // forever. The first one wins, which matches how the payload is built.
        val separator = body.indexOf(SEPARATOR)
        if (separator <= 0 || separator == body.length - 1) return null
        return body.substring(0, separator) to body.substring(separator + 1)
    }

    private const val PAYLOAD_PREFIX = "calendar/occurrence/"
    private const val SEPARATOR = '|'
}
