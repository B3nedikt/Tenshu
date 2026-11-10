package app.tenshu.control.bridge

import app.tenshu.notify.PendingAlertSchedule
import app.tenshu.notify.ScheduledAlert
import org.koin.core.Koin
import org.tenshu.core.common.proxy.NativeBridgeServer

/**
 * Serves the reminders the macOS tray should hand to the OS
 * (`calendar-app.md` §4.6(a), CA-5d).
 *
 * ⚠⚠ **The daemon decides, the tray schedules, the OS delivers.** A JVM daemon
 * cannot post a macOS notification — `UNUserNotificationCenter` needs a bundle
 * identifier and authorization, which only the signed Swift agent has. So the
 * proxy plans reminders into [PendingAlertSchedule], this serves that set, and
 * the tray reconciles it into `UNCalendarNotificationTrigger`s.
 *
 * ⭐ Once the OS holds a trigger the reminder fires **whether or not Control is
 * running**, which is the whole reason §4.6 chose this over the desktop
 * hand-rolled alarm v0.3 proposed. Sleep, logout and restart become the OS's
 * problem.
 *
 * ⚠ Polling is the transport, as with `pendingRequests` — the bridge has no
 * server-push frame. That is a *latency* limit on when a change reaches the OS,
 * not on when a reminder fires: an already-scheduled trigger is unaffected by
 * the poll interval, and by the daemon being quit.
 *
 * ⚠⚠ **Text arrives already redacted.** `ReminderPlanner` applied the Q10
 * `sensitive` flag when it composed these, and it had to: the tray hands this
 * text to the OS days in advance and never sees it again. Nothing here may
 * enrich it.
 */
fun NativeBridgeServer.registerCalendarReminderMethods(koin: Koin) {
    register("calendarReminders") {
        val schedule = koin.get<PendingAlertSchedule>()
        calendarRemindersResult(schedule.current(), planned = schedule.isPlanned())
    }
}

/**
 * `{ reminders: [{ id, title, body, deliverAtEpochMs, tapPayload }], planned }`, soonest
 * first.
 *
 * ⚠⚠ `planned` = false: the daemon has not planned since it started, so an empty list means "unknown", not
 * "nothing due". A tray that diffs against the OS's own schedule (Windows) must then only add, never remove —
 * or a restart cancels every reminder armed before it ([PendingAlertSchedule.isPlanned]).
 *
 * ⚠ `id` is the planner's stable per-occurrence key, used as the OS notification
 * identifier so a re-plan **replaces** rather than stacking, and as the tray's
 * diff key. It is never displayed.
 *
 * ⚠ Shaped in its own function so it is unit-testable without a socket or a live
 * proxy — the `pendingRequestsResult` precedent.
 */
internal fun calendarRemindersResult(alerts: List<ScheduledAlert>, planned: Boolean = true): Map<String, Any?> = mapOf(
    "planned" to planned,
    "reminders" to alerts.map { scheduled ->
        mapOf(
            "id" to scheduled.id,
            "title" to scheduled.alert.title,
            "body" to scheduled.alert.body,
            "deliverAtEpochMs" to scheduled.deliverAt.toEpochMilliseconds(),
            "tapPayload" to scheduled.alert.tapPayload,
        )
    }
)
