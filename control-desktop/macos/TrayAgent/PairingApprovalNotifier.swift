// Host pairing-approval OS elevation — macOS tray side (Phase 3-desktop).
//
// docs/proposals/control-pairing-ux.md §3.6. The pairing counterpart to
// PendingRequestNotifier: when a device asks to join this realm while the
// Control window is hidden/backgrounded (the close-to-tray case — a hidden
// window's Compose modal can't raise the OS window on its own), post a
// UNUserNotificationCenter notification; cancel it once the request is gone.
//
// The daemon serves the current state over the `pairingApproval` bridge method
// (`{ pending, id }`); the tray polls it and this class owns the arrived/resolved
// diff — the macOS notification can only be posted by this GUI agent (which holds
// notification authorization), not by the headless JVM daemon.
//
// Copy is GENERIC / PII-free and SAS-free by construction: the bridge ships only
// the opaque joiner device id (never displayed) + a pending flag — no device
// name, no SAS. The notification therefore can NEVER approve; tapping it only
// raises Control, where the in-app modal shows the SAS for the user to compare on
// both devices before approving (§3.3). This surface owns its strings (ADR 0016).
//
// Delegate ownership: `UNUserNotificationCenter` has a single delegate, so this
// class deliberately does NOT register as one — `PendingRequestNotifier` owns the
// shared delegate, and since every Tenshu notification tap does the same thing
// (raise Control via `openControl()`), its `didReceive` already routes pairing
// taps correctly. This notifier only posts/cancels its one notification.

import AppKit
import UserNotifications

/// Posts/cancels the single "a device wants to join" macOS notification for the
/// host pairing session.
final class PairingApprovalNotifier {

    /// The opaque joiner id currently alerted, so a request that persists across
    /// polls is neither re-posted nor cancelled (idempotent, like the Kotlin
    /// diff). Per-process; a request still pending after a tray restart re-alerts
    /// once — fine, the stable id makes the shade replace, not stack.
    private var known: String?

    /// Fixed notification identifier so re-posting replaces (not stacks). The
    /// pairing session is singular host-side, so one slot is enough.
    private static let notificationId = "tenshu.pairing.approval"

    /// Whether the OS-notification path is live. `UNUserNotificationCenter`
    /// requires a bundle identifier, which a bare `swiftc` binary lacks — so in
    /// dev (`./tray-agent`) this is false and the OS calls no-op. It lights up in
    /// the signed, packaged `.app`.
    private let notificationsAvailable: Bool

    init() {
        self.notificationsAvailable = Bundle.main.bundleIdentifier != nil
    }

    /// Diff the current [pendingId] (the opaque joiner id, or nil when no device
    /// is asking) against the known one: post when a new id appears, cancel when
    /// it clears. Idempotent for an id that persists across polls. [pendingId] is
    /// only ever passed on a *successful* poll — an unreachable daemon / failed
    /// call must not call this (it would spuriously cancel a live alert).
    func reconcile(pendingId: String?) {
        guard notificationsAvailable else { known = pendingId; return }

        if let pendingId {
            if pendingId != known { post() }
        } else if known != nil {
            UNUserNotificationCenter.current()
                .removeDeliveredNotifications(withIdentifiers: [Self.notificationId])
        }
        known = pendingId
    }

    private func post() {
        let content = UNMutableNotificationContent()
        content.title = "A device wants to join"
        content.body = "Open Control to review and approve."
        content.sound = .default
        let request = UNNotificationRequest(
            identifier: Self.notificationId, content: content, trigger: nil
        )
        UNUserNotificationCenter.current().add(request) { error in
            if let error {
                NSLog("Tenshu tray: failed to post pairing notification: \(error.localizedDescription)")
            }
        }
    }
}
