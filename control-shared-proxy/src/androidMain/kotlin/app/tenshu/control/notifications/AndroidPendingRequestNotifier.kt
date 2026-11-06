package app.tenshu.control.notifications

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.tenshu.control.proxy.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Phase 3 OS elevation (`docs/proposals/control-central-notifications.md` §4.3.4) —
 * the Android effect behind [PendingRequestAlertHandle]: a **HIGH-importance** system
 * notification when a new actionable request (membership grant / redeemed contact
 * invite) arrives, on a channel distinct from the LOW task-liveness channel
 * (`ProxyService`). Tapping deep-links into the notification center.
 *
 * Two drivers feed it, both via this one class so the stable per-request id keeps the
 * shade idempotent:
 *  - [start] — a process-lifetime observer of [PendingRequestsRepository.requests],
 *    for near-real-time alerts **while Control's process is alive** (UI open, Drive
 *    bound, a task running).
 *  - [notifyCurrent] — a one-shot pass invoked from the `DrainSync` post-drain hook,
 *    the only thing that wakes a *closed* process on a schedule (~hourly, OS-bounded).
 *
 * **Proxy isolation + threat model (§6).** Copy is **generic and PII-free** — request
 * *kind* only, never the grantor fingerprint or the peer's quarantined claimed name
 * (same constraint as the foreground-service notification). No app injects this content.
 */
class AndroidPendingRequestNotifier(
    private val context: Context,
) : PendingRequestAlertHandle {

    private val manager get() = NotificationManagerCompat.from(context)

    /** Create the channel, then alert on the [requests] feed for the life of [scope]. */
    fun start(scope: CoroutineScope, requests: StateFlow<List<PendingRequest>>) {
        createChannel()
        PendingRequestAlerts(this).launchIn(scope, requests)
    }

    /**
     * Post (idempotently, by stable id) a notification for every currently-pending
     * [requests] — the background-wake path. Does not cancel resolved ones; the live
     * observer / the next wake handles removal.
     */
    fun notifyCurrent(requests: List<PendingRequest>) {
        createChannel()
        requests.forEach(::onRequestArrived)
    }

    // notify() no-ops (never throws) when POST_NOTIFICATIONS is denied — same
    // contract as the FGS notification; only the alert goes unshown.
    @SuppressLint("MissingPermission")
    override fun onRequestArrived(request: PendingRequest) {
        manager.notify(notificationId(request.id), build(request))
    }

    override fun onRequestResolved(id: String) {
        manager.cancel(notificationId(id))
    }

    private fun build(request: PendingRequest) =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(titleRes(request)))
            .setContentText(context.getString(R.string.actionable_request_body))
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setAutoCancel(true)
            .setContentIntent(openCenterIntent(request.id))
            .build()

    private fun titleRes(request: PendingRequest): Int = when (request) {
        is PendingRequest.MembershipGrant -> R.string.actionable_request_grant_title
        is PendingRequest.ContactInviteRedeemed -> R.string.actionable_request_invite_title
    }

    /**
     * Deep-links into the notification center via Control's launcher intent + the
     * deep-link extra (MainActivity reads it → `Destination.Notifications`). Built from
     * the package launcher rather than a `MainActivity` class reference so this lives in
     * `control-shared` without depending on the app module. The extra key/value mirror
     * `MainActivity.EXTRA_DEEPLINK` / `DEEPLINK_NOTIFICATIONS` (kept in sync there).
     */
    private fun openCenterIntent(requestId: String): PendingIntent? {
        val intent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.apply { putExtra(DEEPLINK_EXTRA, DEEPLINK_NOTIFICATIONS) }
            ?: return null
        return PendingIntent.getActivity(
            context,
            notificationId(requestId),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun createChannel() {
        val channel = NotificationChannelCompat.Builder(
            CHANNEL_ID,
            NotificationManagerCompat.IMPORTANCE_HIGH,
        )
            .setName(context.getString(R.string.actionable_requests_channel))
            .build()
        manager.createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID = "tenshu_actionable_requests"

        // Mirrors MainActivity.EXTRA_DEEPLINK / DEEPLINK_NOTIFICATIONS (app module).
        const val DEEPLINK_EXTRA = "app.tenshu.control.deeplink"
        const val DEEPLINK_NOTIFICATIONS = "notifications"

        /** Stable per-request id so re-posting replaces (not stacks) in the shade. */
        fun notificationId(requestId: String): Int = requestId.hashCode()
    }
}

/**
 * `DrainSync` post-drain hook (bgproc Phase 1): after a background sync drain, alert
 * on whatever the pull surfaced. This is what delivers the OS notification when Control
 * is **closed** — bounded by the WorkManager cadence, but reliable per-wake (it runs
 * inside `doWork`, before the process is reaped, instead of racing it).
 */
class PendingRequestPostDrainHook(
    private val notifier: AndroidPendingRequestNotifier,
    private val repository: PendingRequestsRepository,
) : org.tenshu.sync.proxy.PostDrainHook {
    override suspend fun onDrained() {
        // `unread` (Phase 4): the wake re-posts only not-yet-dismissed requests, so a
        // request the user already dismissed on another device doesn't re-alert here.
        notifier.notifyCurrent(repository.unread.value)
    }
}
