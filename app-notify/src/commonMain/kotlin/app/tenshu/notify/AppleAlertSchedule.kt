package app.tenshu.notify

import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime

/**
 * When a pre-armed alert should fire, as the parts a calendar trigger matches on.
 *
 * ⚠⚠ **Seconds are carried, and that is not a detail.** `UNCalendarNotificationTrigger` matches
 * *components*: omit the second and it fires at the top of the minute, which turns "five minutes
 * before" into anything up to fifty-nine seconds late. Every field the trigger will match must be
 * supplied, or the OS fills in the gap with whatever comes first.
 */
data class AlertFireComponents(
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val second: Int,
)

/**
 * The bits of Apple pre-arming that are decisions rather than API calls (CA-8c).
 *
 * ⭐ Pure and tested because its only consumer is Kotlin/Native calling `UNUserNotificationCenter`,
 * which nothing in this repo's environment compiles — the same rule CA-8a and CA-8b followed.
 * `AlertReconciler` already owns the diff; this owns the two things left: how many alerts the OS
 * will hold, and what "fire at this instant" means in components.
 */
object AppleAlertSchedule {

    /**
     * The documented per-app limit on pending notification requests, on iOS and macOS alike.
     *
     * ⚠⚠ **Silently enforced by the OS, oldest-wins**: hand it a 65th request and it is simply
     * dropped, with no error anywhere. `calendar-app.md` §4.6 flags verifying this on macOS; the
     * cap is applied here so that on both platforms the alerts that survive are the ones *we*
     * chose rather than the ones the OS happened to keep.
     */
    const val PENDING_LIMIT: Int = 64

    /**
     * The alerts to hand the OS: the soonest [limit], in fire order.
     *
     * ⚠⚠ **Soonest wins, and the sort is done here rather than trusted.** `ReminderPlanner`
     * already caps and orders its output, but the OS's pending pool belongs to the whole *app*,
     * not to one feature — anything else the app ever schedules competes for the same 64. Taking a
     * prefix of an unsorted list would silently drop tomorrow morning's alarm in favour of one next
     * week.
     *
     * ⚠ Ties break on id so the surviving set does not shuffle between two runs over the same
     * data — a reschedule that reorders equal alerts would make `AlertReconciler`'s diff report
     * churn that is not there.
     */
    fun toSchedule(alerts: List<ScheduledAlert>, limit: Int = PENDING_LIMIT): List<ScheduledAlert> {
        if (limit <= 0) return emptyList()
        return alerts.sortedWith(compareBy({ it.deliverAt }, { it.id })).take(limit)
    }

    /**
     * [deliverAt] as the components a calendar trigger matches on, in [zone].
     *
     * ⚠ The device's own zone, passed in rather than read: an alert is armed against the wall
     * clock the user reads, and which wall clock that is belongs to the host (`calendar-app.md`
     * §3 D3, the same rule the whole calendar follows).
     */
    fun fireComponents(deliverAt: Instant, zone: TimeZone): AlertFireComponents {
        val local = deliverAt.toLocalDateTime(zone)
        return AlertFireComponents(
            year = local.year,
            month = local.month.number,
            day = local.day,
            hour = local.hour,
            minute = local.minute,
            second = local.second,
        )
    }
}
