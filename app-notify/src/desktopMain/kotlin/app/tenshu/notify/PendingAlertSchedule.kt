package app.tenshu.notify

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The desktop hand-off point for scheduled alerts (`calendar-app.md` §4.6(a)).
 *
 * ⚠⚠ **The JVM daemon cannot post a macOS notification, and that is the whole
 * reason this type exists.** `UNUserNotificationCenter` needs a bundle
 * identifier and notification authorization, which only the signed Swift tray
 * agent has — the daemon is a headless JVM process. So the daemon *decides* what
 * should be scheduled and parks it here; the tray polls, and hands it to the OS
 * with a `UNCalendarNotificationTrigger`.
 *
 * ⭐ That indirection is what buys §4.6's real win: once the OS holds a trigger,
 * the reminder fires **whether or not Control is running at all**, and sleep and
 * restart become the OS's problem rather than ours.
 *
 * ⚠ [canPost] is false: this is not a surface, it is a queue. An *immediate*
 * alert from the daemon has nowhere to go — the tray's existing
 * `PendingRequestNotifier` is the path for those — so [post] is a no-op rather
 * than a silent write into a set that only future-dated alerts are read from.
 */
@OptIn(ExperimentalAtomicApi::class)
class PendingAlertSchedule : SchedulingNotificationPresenter {

    // ⚠ Atomic because the daemon writes it from an op and the bridge reads it
    // from a socket thread — two threads, no lock between them.
    private val scheduled = AtomicReference<List<ScheduledAlert>>(emptyList())
    private val planned = AtomicReference(false)

    /** What the tray should be holding, soonest first. */
    fun current(): List<ScheduledAlert> = scheduled.load()

    /**
     * Whether [current] is a plan, or merely the empty list this starts with.
     *
     * ⚠⚠ Not the same thing, and a tray that confuses them deletes reminders. The plan is made only when calendar
     * data changes (`ReminderRearmObserver`), so after a restart this is empty until the first change — while the OS
     * still holds every reminder armed before it. A tray that reconciles against its own OS schedule must treat an
     * unplanned empty list as "unknown", never as "cancel everything".
     */
    fun isPlanned(): Boolean = planned.load()

    override fun canPost(): Boolean = false

    override fun post(alert: AppAlert) = Unit

    override fun cancel(ids: Collection<String>) = Unit

    override fun replaceScheduled(alerts: List<ScheduledAlert>) {
        scheduled.store(alerts)
        planned.store(true)
    }
}
