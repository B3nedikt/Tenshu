package app.tenshu.control.bridge

import app.tenshu.control.notifications.PendingRequest
import app.tenshu.control.notifications.PendingRequestsRepository
import org.koin.core.Koin
import org.tenshu.core.common.proxy.NativeBridgeServer

/**
 * Registers the native-bridge method backing the macOS tray's **OS-level
 * elevation** of inbound actionable requests (Phase 3b of
 * `docs/proposals/control-central-notifications.md` §4.3.4) — the desktop
 * counterpart to Android's `AndroidPendingRequestNotifier`.
 *
 *  - `pendingRequests` — the current inbound actionable requests (membership
 *    grants + redeemed contact invites), read from the in-process
 *    [PendingRequestsRepository] (the same `StateFlow` Control's notification
 *    center + pinned Home cards render). The tray polls this on a background
 *    timer, diffs arrived/resolved, and posts/cancels a `UNUserNotificationCenter`
 *    notification — so the user is alerted with Control backgrounded.
 *
 * **Why poll, not push.** The bridge is request/response (no server-push frame),
 * and the macOS notification can only be posted by the Swift agent (the GUI app
 * that holds notification authorization), not by this JVM daemon. So the daemon
 * just serves current state and the tray owns the arrived/resolved diff (mirroring
 * the Kotlin `PendingRequestAlerts`). The daemon is the always-on resident process
 * (the tray/daemon split), so while Control runs there is something to poll; when
 * the daemon is fully quit the tray simply finds it unreachable and stays quiet.
 *
 * **Payload shape & proxy isolation (§6).** Each row carries the stable [id]
 * (opaque to the tray — used only as the notification identifier so the shade
 * replaces, not stacks, and for the arrived/resolved diff; never displayed), the
 * request [kind], and the timestamp. The tray composes a **generic, PII-free**
 * display string from `kind` alone (ADR 0016: each surface owns its strings) — it
 * never shows the grantor fingerprint or the peer's quarantined claimed name, the
 * same constraint the Android notification and the foreground-service notification
 * honour. The JSON-shaping is factored into [pendingRequestsResult] so it is
 * unit-testable without a socket or a live repository.
 */
fun NativeBridgeServer.registerPendingRequestMethods(koin: Koin) {
    register("pendingRequests") {
        // `unread` (Phase 4): the tray elevates only not-yet-dismissed requests, so a
        // synced dismissal on another device cancels the macOS notification here too.
        pendingRequestsResult(koin.get<PendingRequestsRepository>().unread.value)
    }
}

/**
 * `{ requests: [{ id, kind, timestampMillis }] }`, newest first (the repository
 * already sorts). `kind` is a stable code (`membership_grant` / `contact_invite`)
 * the tray maps to a localised, PII-free title; `id` is the opaque stable request
 * id (notification identifier + diff key), deliberately *not* surfaced as copy.
 */
internal fun pendingRequestsResult(requests: List<PendingRequest>): Map<String, Any?> = mapOf(
    "requests" to requests.map { request ->
        mapOf(
            "id" to request.id,
            "kind" to request.kind(),
            "timestampMillis" to request.timestampMillis,
        )
    }
)

/** The stable, PII-free kind code the tray renders (never the underlying fingerprint/name). */
private fun PendingRequest.kind(): String = when (this) {
    is PendingRequest.MembershipGrant -> "membership_grant"
    is PendingRequest.ContactInviteRedeemed -> "contact_invite"
}
