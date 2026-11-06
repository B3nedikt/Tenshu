package app.tenshu.notify

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * The Android effect behind [NotificationPresenter].
 *
 * ⚠ Deliberately **not** a [SchedulingNotificationPresenter]. Android has no
 * mechanism that holds a notification for us — `AlarmManager` wakes the process
 * and *then* something posts (`calendar-app.md` §4.6). A caller that needs a
 * future alert here arms its own wake; the type says so.
 *
 * ⚠⚠ Channels are created **lazily, on first use of each channel**, and never
 * re-created with different importance. Android fixes a channel's importance at
 * creation and ignores later changes for anyone who already has the app, so
 * re-declaring is at best a no-op and at worst a false belief that a setting
 * took effect. A behaviour change needs a new channel id.
 *
 * ⚠ `notify` no-ops rather than throwing when `POST_NOTIFICATIONS` is denied —
 * the same contract `AndroidPendingRequestNotifier` and the foreground-service
 * notifications already rely on. Only the alert goes unshown.
 */
class AndroidNotificationPresenter(
    private val context: Context,
    /**
     * The icon every alert shows.
     *
     * ⚠ Required, and required *here* rather than per-alert: Android silently
     * drops a notification with no small icon, which reads as "notifications are
     * broken" rather than as a missing argument. A per-alert icon would let one
     * call site forget it and the rest work.
     */
    private val smallIconRes: Int,
) : NotificationPresenter {

    private val manager get() = NotificationManagerCompat.from(context)
    private val createdChannels = mutableSetOf<String>()

    override fun canPost(): Boolean = manager.areNotificationsEnabled()

    @SuppressLint("MissingPermission")
    override fun post(alert: AppAlert) {
        ensureChannel(alert.channel)
        manager.notify(notificationId(alert.id), build(alert))
    }

    override fun cancel(ids: Collection<String>) {
        ids.forEach { manager.cancel(notificationId(it)) }
    }

    private fun build(alert: AppAlert) =
        NotificationCompat.Builder(context, alert.channel.id)
            .setContentTitle(alert.title)
            .setContentText(alert.body)
            .setSmallIcon(smallIconRes)
            .setPriority(alert.channel.importance.toPriority())
            // ⚠⚠ Set explicitly even though it matches the framework default,
            // because it is security-relevant: `PRIVATE` is what lets the user's
            // own "hide sensitive notifications" setting redact this alert on the
            // lock screen. Relying on a default for that would mean a future
            // platform change could silently un-redact every alert Tenshu posts
            // (threat model A5, the calendar-reminder amendment). ⚠ Not
            // `SECRET`: hiding a reminder from the lock screen unconditionally
            // would defeat the point of a reminder — the per-calendar
            // `sensitive` flag is where *we* decide to withhold text, upstream.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .apply {
                tapIntent(alert)?.let(::setContentIntent)
                alert.actions.forEach { action ->
                    // ⚠ Icon 0: Android has not rendered action icons since API
                    // 24, and `NotificationCompat` accepts 0 for exactly this.
                    addAction(0, action.label, actionIntent(alert, action))
                }
            }
            .build()

    /**
     * Opens the alert's app, carrying the payload.
     *
     * ⚠⚠ **[AppAlert.targetApp] first, this package second.** On Android the
     * process that owns the alarm is what posts — Control — so resolving the
     * launcher of `context.packageName` unconditionally sent every Calendar
     * reminder into Control, which has no idea what an occurrence is. The alert
     * names its own app; blank keeps the old behaviour.
     *
     * ⚠ Resolved through the **package launcher** rather than a class reference,
     * so this module stays app-agnostic — the `AndroidPendingRequestNotifier`
     * precedent. The app reads [AlertIntents.EXTRA_TAP_PAYLOAD] and routes on it.
     *
     * ⚠⚠ A cross-package launch needs the target declared in the posting app's
     * manifest `<queries>` on API 30+, or `getLaunchIntentForPackage` returns
     * null and the tap silently does nothing. Falling back to our own launcher
     * would be worse: the user would land in the wrong app with no explanation,
     * so an unresolvable target yields **no tap intent at all**.
     */
    private fun tapIntent(alert: AppAlert): PendingIntent? {
        if (alert.tapPayload.isBlank()) return null
        val target = alert.targetApp.ifBlank { context.packageName }
        val intent = context.packageManager
            .getLaunchIntentForPackage(target)
            ?.apply { putExtra(AlertIntents.EXTRA_TAP_PAYLOAD, alert.tapPayload) }
            ?: return null
        return PendingIntent.getActivity(
            context,
            notificationId(alert.id),
            intent,
            // ⚠ `UPDATE_CURRENT` so a re-post carries the *new* payload:
            // without it a PendingIntent matching an existing one keeps the old
            // extras, and a re-armed reminder would deep-link to the previous
            // occurrence. `IMMUTABLE` is required from API 31 and correct
            // regardless — nothing outside the app may rewrite these extras.
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /**
     * The broadcast one action button sends.
     *
     * ⚠⚠ **Handled in *this* process, not the target app's.** An action's whole
     * value is that it resolves without opening anything, and only the posting
     * process (which hosts the proxy) can write without a round trip through a
     * cold app.
     *
     * ⚠ The identity of a `PendingIntent` ignores extras, so alert and action id
     * ride in the **data URI** — otherwise Snooze and Dismiss on the same alert
     * would collapse into one intent and both buttons would do whatever was
     * registered last. The `PunctualAlarmScheduler` trap, verbatim.
     */
    private fun actionIntent(alert: AppAlert, action: AlertAction): PendingIntent {
        val intent = Intent(context, AlertActionReceiver::class.java).apply {
            data = android.net.Uri.parse("tenshu-alert-action://${alert.id}/${action.id}")
            putExtra(AlertActionReceiver.KEY_TAP_PAYLOAD, alert.tapPayload)
            putExtra(AlertActionReceiver.KEY_ACTION_ID, action.id)
            putExtra(AlertActionReceiver.KEY_ALERT_ID, alert.id)
        }
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun ensureChannel(channel: AlertChannel) {
        if (!createdChannels.add(channel.id)) return
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(channel.id, channel.importance.toImportance())
                .setName(channel.name)
                .build()
        )
    }

    private companion object {
        /**
         * ⚠ A stable `Int` per alert id, so re-posting **replaces** in the shade
         * rather than stacking. `hashCode` can collide in principle; in practice
         * ids within one app are few and the precedent
         * (`AndroidPendingRequestNotifier`) has used exactly this since it
         * shipped.
         */
        fun notificationId(id: String): Int = id.hashCode()

        fun AlertImportance.toImportance(): Int = when (this) {
            AlertImportance.HIGH -> NotificationManagerCompat.IMPORTANCE_HIGH
            AlertImportance.DEFAULT -> NotificationManagerCompat.IMPORTANCE_DEFAULT
            AlertImportance.LOW -> NotificationManagerCompat.IMPORTANCE_LOW
        }

        /**
         * ⚠ Priority is the pre-API-26 twin of channel importance, and
         * `NotificationCompat` still reads it below 26. Setting only the channel
         * would leave `minSdk` 24 devices with every alert at default priority.
         */
        fun AlertImportance.toPriority(): Int = when (this) {
            AlertImportance.HIGH -> NotificationCompat.PRIORITY_HIGH
            AlertImportance.DEFAULT -> NotificationCompat.PRIORITY_DEFAULT
            AlertImportance.LOW -> NotificationCompat.PRIORITY_LOW
        }
    }
}
