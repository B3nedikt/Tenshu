package org.tenshu.sdk.ios

import app.tenshu.control.notifications.PendingRequest
import app.tenshu.control.notifications.PendingRequestAlertHandle
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatusNotDetermined
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNUserNotificationCenter

/**
 * NT-2b (`ios-notification-elevation.md` v0.2, D1-rev): the iOS effect behind
 * [PendingRequestAlertHandle] — a system notification when a new inbound actionable request
 * (membership grant / redeemed contact invite) appears, and a withdrawal when it resolves.
 *
 * The iOS twin of `AndroidPendingRequestNotifier` and of the macOS tray's
 * `PendingRequestNotifier.swift`. All three share the `PendingRequestAlerts` diff, so what lives
 * here is only the platform call.
 *
 * ### Why this is not the `NotificationPresenter`
 *
 * ⚠⚠ **Do not route these through [org.tenshu.notify.NotificationPresenter].** That seam is bound
 * to `NoopNotificationPresenter` in Control on purpose (D3): `UNUserNotificationCenter` is per app,
 * every route-1 app runs the same proxy graph, and exactly one app — Calendar — overrides it for
 * reminders. Posting requests through it would give the user every Calendar reminder twice.
 * `PendingRequestAlertHandle` is a separate path with no such collision, which is why Android and
 * desktop drive their elevation from it too.
 *
 * ### What "pre-arm vs wake" means here
 *
 * ⚠ A reminder is *pre-armed* — handed to the OS in advance with a trigger, delivered whether or
 * not the process lives. An inbound request cannot be: it arrives when the counterparty acts. So
 * these post with a **null trigger** (immediate), and the only question is whether this process is
 * running to make the call. That is what `ControlBackgroundRefresh` on the Swift side is for.
 *
 * ### Copy
 *
 * ⚠⚠ **Generic and PII-free**, the same constraint Android and the macOS tray carry: the request
 * *kind* only, never the grantor's fingerprint and never the peer's *claimed* name. A claimed name
 * is quarantined until the SAS is confirmed (that is the whole point of the invite ceremony), and a
 * lock screen is the last place to render an unverified string an attacker chose. The detail lives
 * behind the tap, in the notification center, where the SAS sits next to it.
 */
class IosPendingRequestNotifier(
    /**
     * ⚠⚠ A **provider**, not a resolved center, and not a default-argument call.
     * `UNUserNotificationCenter.currentNotificationCenter()` throws
     * `NSInternalInconsistencyException: bundleProxyForCurrentProcess is nil` when there is no app
     * bundle — which is exactly the case in a Kotlin/Native **test** binary. Resolving it in the
     * constructor made merely *instantiating* this class crash the test process before a single
     * assertion ran. Deferring it means construction is free and the OS is touched only when
     * something is actually posted.
     */
    private val centerProvider: () -> UNUserNotificationCenter =
        { UNUserNotificationCenter.currentNotificationCenter() },
) : PendingRequestAlertHandle {

    private val center: UNUserNotificationCenter get() = centerProvider()

    override fun onRequestArrived(request: PendingRequest) {
        val content = UNMutableNotificationContent().apply {
            setTitle(title(request))
            setBody(BODY)
            // Groups this app's request alerts together in the shade, and matches the
            // thread the resolved-side withdrawal targets.
            setThreadIdentifier(THREAD_ID)
        }
        // ⚠ `trigger = null` means "deliver now". A request has no future instant to arm for.
        center.addNotificationRequest(
            UNNotificationRequest.requestWithIdentifier(
                identifier = request.id,
                content = content,
                trigger = null,
            ),
            withCompletionHandler = null,
        )
    }

    /**
     * ⚠ Withdraws from **both** queues. A notification the user has not seen yet is *pending*
     * (delivered later by the OS); one already on the lock screen is *delivered*. Cancelling only
     * one leaves a request the user already handled sitting in the shade, which is precisely the
     * "acted on another device" case this exists to clean up.
     */
    override fun onRequestResolved(id: String) {
        center.removePendingNotificationRequestsWithIdentifiers(listOf(id))
        center.removeDeliveredNotificationsWithIdentifiers(listOf(id))
    }

    /**
     * ⚠ `internal` rather than private so `IosPendingRequestNotifierTest` can pin the PII rule.
     * The interesting property is not which string is chosen but what is **absent** from it.
     */
    internal fun title(request: PendingRequest): String = when (request) {
        is PendingRequest.MembershipGrant -> "Someone wants to share a folder with you"
        is PendingRequest.ContactInviteRedeemed -> "A contact accepted your invite"
    }

    companion object {
        private const val THREAD_ID = "tenshu.actionable-requests"

        /** Mirrors Android's `actionable_request_body` — deliberately says nothing specific. */
        private const val BODY = "Open Control to review it."

        /**
         * Ask for notification permission, but **only if it has never been asked**.
         *
         * ⚠⚠ Called when a request actually arrives, never at launch. A permission prompt at first
         * launch is asking about something the user has no context for, and a denial is sticky —
         * iOS will not ask twice, so a badly-timed prompt permanently disables the feature. At the
         * moment a real grant or invite lands, the prompt has an answer the user can give.
         *
         * ⚠ `[.alert, .sound]` only, matching the macOS tray. **No `.badge`**: the app-icon badge
         * can only be set while the process runs, so it would go stale in exactly the case that
         * matters (a request arriving while Control is closed) — see this doc's Q2.
         */
        fun requestAuthorizationIfNeeded(
            center: UNUserNotificationCenter = UNUserNotificationCenter.currentNotificationCenter(),
        ) {
            center.getNotificationSettingsWithCompletionHandler { settings ->
                if (settings?.authorizationStatus == UNAuthorizationStatusNotDetermined) {
                    center.requestAuthorizationWithOptions(
                        UNAuthorizationOptionAlert or UNAuthorizationOptionSound,
                    ) { _, _ -> }
                }
            }
        }
    }
}
