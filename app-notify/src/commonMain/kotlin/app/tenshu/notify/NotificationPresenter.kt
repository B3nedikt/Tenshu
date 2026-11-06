package app.tenshu.notify

/**
 * Shows something on *this device's* OS — the seam
 * `calendar-app.md` §4.3 calls gap 2.
 *
 * ⚠⚠ **Nothing like this existed.** `:notification-service` sounds like it and
 * is not: its `Notification` is a **synced data row**, and no part of it touches
 * an OS. Ten call sites hand-roll `NotificationCompat` today with no shared
 * builder and no shared channel registry, which is why the eleventh — Calendar's
 * reminders — pays for the seam.
 *
 * ⚠ Narrow on purpose, following the `NewFolderAnnouncer` template rather than
 * growing a notification framework: post, cancel, and a capability question.
 * Everything about *what* to show was decided upstream.
 *
 * ⚠ A platform with no surface binds [NoopNotificationPresenter] and callers
 * need no branch — the same default `NewFolderAnnouncer` uses for desktop.
 */
interface NotificationPresenter {

    /**
     * Whether an alert posted now would actually reach the user.
     *
     * ⚠ False when the OS permission is denied — Android 13+ requires
     * `POST_NOTIFICATIONS`, and iOS requires authorization. ⚠⚠ *Requesting* it
     * is deliberately **not** here: on Android that needs an `Activity`, which
     * a service-layer seam must not hold, and the request has to be made in a
     * moment the user understands. Asking is the shell's job; this reports.
     */
    fun canPost(): Boolean

    /**
     * Shows [alert] now, replacing any alert already showing under the same id.
     *
     * ⚠ **Not `suspend`**, deliberately. Handing a notification to the OS is a
     * synchronous call on every platform — `NotificationManager.notify`,
     * `UNUserNotificationCenter.add` — and nothing downstream reads a result.
     * Making it suspending would push every caller into a coroutine for no
     * benefit, and it did exactly that: the first version posted from a launched
     * scope, which made delivery unordered and unobservable to the op that asked
     * for it.
     */
    fun post(alert: AppAlert)

    /** Withdraws alerts by id; ids that are not showing are ignored. */
    fun cancel(ids: Collection<String>)
}

/**
 * A presenter on a platform where **the OS holds the schedule itself**.
 *
 * ⭐⭐ **This split is the whole architecture of reminders, made into types.**
 * `calendar-app.md` §4.6: *Android wakes and decides; Apple platforms pre-arm.*
 * On iOS and macOS a `UNCalendarNotificationTrigger` fires whether or not our
 * process is alive, so the right move is to hand the OS the next N alerts. On
 * Android nothing holds a notification for us — an alarm wakes us and *then* we
 * post.
 *
 * ⚠⚠ Modelled as a **sub-interface rather than a `deliverAt` field**, so
 * "schedule this for Tuesday" is unrepresentable against a presenter that cannot
 * do it. The field version's failure mode is an alert that fires *immediately*
 * instead of on Tuesday — silent, and exactly wrong.
 *
 * A caller that wants either does `presenter as? SchedulingNotificationPresenter`
 * and falls back to arming its own wake.
 */
interface SchedulingNotificationPresenter : NotificationPresenter {

    /**
     * Replaces everything currently scheduled with [alerts].
     *
     * ⚠⚠ **The whole desired set, not add/remove**, and this is a correction to
     * the shape CA-5b first gave it (`schedule` + `cancelScheduled` +
     * `scheduledIds`). Two problems with that: `scheduledIds()` had to be
     * `suspend` because Apple's pending-request query is asynchronous, which a
     * synchronous op cannot call; and it put the diff in the *caller*, which is
     * the one place that does **not** know what the OS is actually holding —
     * that set outlives our process. Handing over the desired set puts
     * `AlertReconciler` where the truth is, in the implementation.
     *
     * ⚠ Already ordered soonest-first and already capped by the planner, so an
     * implementation on a platform with a pending limit can take a prefix
     * without re-deciding what matters.
     */
    fun replaceScheduled(alerts: List<ScheduledAlert>)
}

/** Default binding for platforms with no surface, or none built yet. */
object NoopNotificationPresenter : NotificationPresenter {
    override fun canPost(): Boolean = false
    override fun post(alert: AppAlert) = Unit
    override fun cancel(ids: Collection<String>) = Unit
}
