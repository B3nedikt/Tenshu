package app.tenshu.notify

import kotlin.concurrent.Volatile
import kotlinx.datetime.TimeZone
import platform.Foundation.NSDateComponents
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNCalendarNotificationTrigger
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationInterruptionLevel
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter

/**
 * The Apple **pre-arm** presenter (CA-8c, `calendar-app.md` §4.3 Gap 1 / §4.6(a)).
 *
 * ⭐⭐ **This is the half of the reminder design that had no implementation.** §4.3's principle is
 * *"Android wakes; Apple platforms pre-arm"* — there is no reliable "wake me at 09:00" on iOS
 * (`BGTaskScheduler` is best-effort, and `ios-drive-parity.md` P6a already found live wake to be
 * hardware-gated), so the answer is a different mechanism: hand the OS the next N alerts and let
 * `UNCalendarNotificationTrigger` deliver them whether or not this process is alive.
 *
 * Everything above it already existed — `ReminderPlanner` composes the alerts (and redacts them,
 * which matters *here* specifically: on Apple platforms the OS is handed this text days in advance
 * and the app never sees it again), `DeliverDueReminders` hands over the whole forward set, and
 * `AlertReconciler` diffs it. This class is the API call.
 *
 * ⚠⚠ **On iOS route 1, Calendar posts its own reminders — Control must not.**
 * `UNUserNotificationCenter` is per *app*, and both processes run the same proxy graph over the
 * shared replica. If both bound this presenter the user would get every reminder twice, once from
 * each app. So the base iOS module binds [NoopNotificationPresenter] and only `CalendarLocalRuntime`
 * overrides it — the mirror of `ios-drive-parity.md` P6a, where Drive rather than Control is the
 * background syncer on route 1.
 *
 * ⚠⚠ **No action buttons yet.** [AppAlert.actions] carries CA-5f's *Snooze* and *Dismiss*, and
 * this drops them: an Apple action needs a registered `UNNotificationCategory` plus a
 * `UNUserNotificationCenterDelegate` in the Swift shell to receive the response and call back into
 * Kotlin — a whole second path, not a parameter. A tap opens the app. Recorded here rather than
 * left to be discovered by pressing a button that is not there.
 */
class IosNotificationPresenter(
    private val center: UNUserNotificationCenter =
        UNUserNotificationCenter.currentNotificationCenter(),
    private val zone: () -> TimeZone = { TimeZone.currentSystemDefault() },
) : SchedulingNotificationPresenter {

    /**
     * ⚠⚠ Cached, because [canPost] is **synchronous** and Apple's settings query is not. The
     * alternative — blocking on a completion handler — would deadlock the moment it were called
     * from the main thread, which is exactly where an op runs.
     *
     * ⚠ It starts `false`. An un-refreshed presenter reporting "yes" would let a caller believe an
     * alert was delivered; reporting "no" only costs a skipped post, and [refreshAuthorization]
     * runs at construction.
     */
    @Volatile
    private var authorized: Boolean = false

    init {
        refreshAuthorization {}
    }

    /**
     * Re-reads the OS's authorization state into the [canPost] cache.
     *
     * ⚠ Worth calling on every foreground: authorization can be revoked in Settings while the app
     * is backgrounded, and a presenter that kept saying yes would silently drop every post.
     */
    fun refreshAuthorization(onResult: (Boolean) -> Unit) {
        center.getNotificationSettingsWithCompletionHandler { settings ->
            val status = settings?.authorizationStatus
            // ⚠ `Provisional` counts. It is the quiet-delivery grant Apple gives without a prompt —
            // the notification really does arrive, just in the notification centre rather than on
            // the lock screen — so treating it as "cannot post" would suppress alerts that would
            // have been shown.
            val allowed = status == UNAuthorizationStatusAuthorized ||
                status == UNAuthorizationStatusProvisional
            authorized = allowed
            // ⚠ A callback rather than only a cached read. The query is asynchronous, so a caller
            // that refreshed and then read would answer with the PREVIOUS value — and a UI that
            // shows "turn on reminders" to someone who just turned them on is the exact failure
            // this row exists to avoid.
            onResult(allowed)
        }
    }

    /**
     * Asks the OS for permission, then refreshes the cache.
     *
     * ⚠⚠ Deliberately **not** part of [NotificationPresenter]. Its KDoc says asking is the shell's
     * job, and the reason holds here: the prompt is once-per-install and unrepeatable, so it has to
     * fire in a moment the user understands rather than whenever a service happens to initialise.
     */
    fun requestAuthorization(onResult: (Boolean) -> Unit = {}) {
        val options = UNAuthorizationOptionAlert or UNAuthorizationOptionSound or
            UNAuthorizationOptionBadge
        center.requestAuthorizationWithOptions(options) { granted, _ ->
            authorized = granted
            onResult(granted)
        }
    }

    override fun canPost(): Boolean = authorized

    override fun post(alert: AppAlert) {
        // ⚠ `trigger = null` means "deliver now" — the immediate path, used on the catch-up branch
        // and by anything that is not a reminder.
        center.addNotificationRequest(request(alert, trigger = null)) { }
    }

    override fun cancel(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val list = ids.toList()
        // ⚠ Both, and in this order. A reminder that already fired is *delivered*, not pending, so
        // cancelling only the pending set leaves it sitting in the notification centre for an
        // event the user has since deleted.
        center.removePendingNotificationRequestsWithIdentifiers(list)
        center.removeDeliveredNotificationsWithIdentifiers(list)
    }

    /**
     * Hands the OS the whole desired forward set.
     *
     * ⚠⚠ The pending query is asynchronous and this method is not, so the diff and the writes all
     * happen **inside the completion handler**. That is fine — nothing downstream reads a result,
     * and `DeliverDueReminders` returns immediately by design.
     *
     * ⚠ `diffAgainstIds`, not `diff`: Apple reports pending requests, and reconstructing an
     * [AppAlert] from one would mean trusting a round-trip through `UNNotificationContent` to have
     * preserved every field. `AlertReconciler` documents what that costs — an already-scheduled
     * alert is re-scheduled anyway, because ids alone cannot say whether its text changed. A
     * redundant write is cheaper than a stale reminder.
     */
    override fun replaceScheduled(alerts: List<ScheduledAlert>) {
        val desired = AppleAlertSchedule.toSchedule(alerts)
        center.getPendingNotificationRequestsWithCompletionHandler { pending ->
            val knownIds = pending
                ?.mapNotNull { (it as? UNNotificationRequest)?.identifier }
                ?.toSet()
                .orEmpty()

            val diff = AlertReconciler.diffAgainstIds(desired, knownIds)
            if (diff.cancel.isNotEmpty()) {
                center.removePendingNotificationRequestsWithIdentifiers(diff.cancel)
            }
            val currentZone = zone()
            diff.schedule.forEach { scheduled ->
                center.addNotificationRequest(
                    request(scheduled.alert, trigger = trigger(scheduled, currentZone)),
                ) { }
            }
        }
    }

    private fun request(alert: AppAlert, trigger: UNCalendarNotificationTrigger?) =
        UNNotificationRequest.requestWithIdentifier(
            // ⚠ The alert's own id, which is stable by contract — every platform replaces a
            // matching request rather than stacking a second copy, and that is what makes
            // re-planning safe.
            identifier = alert.id,
            content = content(alert),
            trigger = trigger,
        )

    private fun content(alert: AppAlert) = UNMutableNotificationContent().apply {
        setTitle(alert.title)
        if (alert.body.isNotEmpty()) setBody(alert.body)
        if (alert.channel.importance == AlertImportance.HIGH) {
            setSound(UNNotificationSound.defaultSound)
            // ⚠ Time-sensitive, so a reminder pierces Focus. It is the one interruption level that
            // fits an alert the user explicitly asked for at a specific minute — and getting this
            // wrong means the alarm is silently held until the user next unlocks.
            setInterruptionLevel(UNNotificationInterruptionLevel.UNNotificationInterruptionLevelTimeSensitive)
        }
        // ⚠ The tap payload rides in `userInfo`, which is the Apple twin of the Android intent
        // extra. `AlertIntents.EXTRA_TAP_PAYLOAD` is reused as the key so the two platforms name
        // the same thing — it is contract, not an Android implementation detail.
        if (alert.tapPayload.isNotEmpty()) {
            setUserInfo(mapOf(AlertIntents.EXTRA_TAP_PAYLOAD to alert.tapPayload))
        }
    }

    /**
     * ⚠ A **calendar** trigger, not a time-interval one. An interval is fixed at scheduling time,
     * so a device that sleeps across a DST change or has its clock corrected fires at the wrong
     * wall-clock minute — and the wall clock is what a reminder means (`calendar-app.md` §4.6(a)).
     */
    private fun trigger(
        scheduled: ScheduledAlert,
        currentZone: TimeZone,
    ): UNCalendarNotificationTrigger {
        val parts = AppleAlertSchedule.fireComponents(scheduled.deliverAt, currentZone)
        val components = NSDateComponents().apply {
            year = parts.year.toLong()
            month = parts.month.toLong()
            day = parts.day.toLong()
            hour = parts.hour.toLong()
            minute = parts.minute.toLong()
            second = parts.second.toLong()
        }
        return UNCalendarNotificationTrigger.triggerWithDateMatchingComponents(
            dateComponents = components,
            repeats = false,
        )
    }
}
