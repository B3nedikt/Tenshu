// Pending-request OS elevation — macOS tray side (Phase 3b).
//
// docs/proposals/control-central-notifications.md §4.3.4. The desktop
// counterpart to Android's AndroidPendingRequestNotifier: when a new inbound
// actionable request (membership grant / redeemed contact invite) appears in
// the daemon's feed, post a UNUserNotificationCenter notification so the user is
// alerted with Control backgrounded; cancel it once the request is resolved.
//
// The daemon serves the current feed over the `pendingRequests` bridge method;
// the tray polls it on a timer and this class owns the *arrived* / *resolved*
// diff (mirroring the Kotlin `PendingRequestAlerts`) — because the macOS
// notification can only be posted by this GUI agent (which holds notification
// authorization), not by the headless JVM daemon.
//
// Copy is GENERIC / PII-free — the request *kind* only, never the grantor
// fingerprint or the peer's quarantined claimed name (the bridge never ships
// those; §6 proxy isolation, same constraint as the Android notification). This
// surface owns its display strings (ADR 0016).

import AppKit
import UserNotifications

/// One inbound actionable request as the tray sees it: an opaque stable [id]
/// (notification identifier + diff key, never displayed) and a [kind] code.
struct PendingRequestRow: Identifiable, Equatable {
    let id: String
    let kind: String   // "membership_grant" | "contact_invite"

    /// Generic, PII-free title for both the notification and the popover row.
    var title: String {
        switch kind {
        case "membership_grant": return "Someone wants to share a folder with you"
        case "contact_invite": return "A contact accepted your invite"
        default: return "A request needs your review"
        }
    }
}

/// Posts/cancels macOS notifications for the pending-request feed, and raises
/// Control when one is tapped. An `NSObject` so it can be the
/// `UNUserNotificationCenter` delegate.
final class PendingRequestNotifier: NSObject, UNUserNotificationCenterDelegate {

    /// Raise Control's window (the notification's tap action). Injected so this
    /// stays decoupled from the bridge/`TrayModel`.
    private let onActivate: () -> Void

    /// Request ids already alerted, so a feed that still contains a request
    /// across polls is neither re-posted nor cancelled (idempotent, like the
    /// Kotlin diff). Per-process: a request still pending after the tray
    /// restarts re-alerts once — fine, the stable id makes the shade replace.
    private var known: Set<String> = []

    /// Whether the OS-notification path is live. `UNUserNotificationCenter`
    /// requires a bundle identifier, which a bare `swiftc` binary lacks — so in
    /// dev (`./tray-agent`) this is false and the OS calls no-op (the popover
    /// still lists the requests). It lights up in the signed, packaged `.app`.
    private let notificationsAvailable: Bool

    init(onActivate: @escaping () -> Void) {
        self.onActivate = onActivate
        self.notificationsAvailable = Bundle.main.bundleIdentifier != nil
        super.init()
    }

    /// Set the delegate + ask for authorization. Call once at launch. No-op when
    /// notifications aren't available (unbundled dev binary).
    func start() {
        guard notificationsAvailable else {
            NSLog("Tenshu tray: unbundled binary (no bundle id) — pending-request notifications disabled; popover still lists requests")
            return
        }
        let center = UNUserNotificationCenter.current()
        center.delegate = self
        center.requestAuthorization(options: [.alert, .sound]) { granted, error in
            if let error {
                NSLog("Tenshu tray: notification authorization error: \(error.localizedDescription)")
            } else if !granted {
                NSLog("Tenshu tray: notification authorization denied — requests still show in the popover")
            }
        }
    }

    /// Diff [current] against the known set: post the newly-arrived, cancel the
    /// now-gone. Idempotent for ids that persist across polls. Always updates the
    /// known set so the diff is correct even when the OS path is unavailable.
    func reconcile(_ current: [PendingRequestRow]) {
        let currentIds = Set(current.map(\.id))

        if notificationsAvailable {
            for row in current where !known.contains(row.id) {
                post(row)
            }
            let resolved = known.subtracting(currentIds)
            if !resolved.isEmpty {
                UNUserNotificationCenter.current()
                    .removeDeliveredNotifications(withIdentifiers: Array(resolved))
            }
        }
        known = currentIds
    }

    private func post(_ row: PendingRequestRow) {
        let content = UNMutableNotificationContent()
        content.title = row.title
        content.body = "Open Control to review."
        content.sound = .default
        // Stable id ⇒ re-posting replaces (not stacks) in Notification Center,
        // and resolving removes exactly this one.
        let request = UNNotificationRequest(identifier: row.id, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request) { error in
            if let error {
                NSLog("Tenshu tray: failed to post request notification: \(error.localizedDescription)")
            }
        }
    }

    // MARK: - UNUserNotificationCenterDelegate

    /// Show the banner even while the agent is frontmost (it rarely is — it's an
    /// `.accessory` app — but this keeps behaviour predictable).
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .sound])
    }

    /// Tap → raise Control. It opens to Home, where Phase 2b's pinned request
    /// cards surface the same request with its Accept/Decline (or Confirm)
    /// actions — so no separate desktop deep-link-to-center plumbing is needed.
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        onActivate()
        completionHandler()
    }
}
