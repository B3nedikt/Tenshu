// "Update Control to keep syncing" OS notification — macOS tray side.
//
// The schema pull fence stops this Mac pulling once another device writes at a newer
// schema. Nothing else looks wrong while it holds (the relay answers, pushes go out), so a
// fenced Control can fall days behind with no visible signal — and, because reclamation waits
// on every device, stall the whole realm's content reclamation with it.
//
// The daemon reports the verdict on `getSyncState.schemaFenced` (true / false, or null when the
// fence has not been evaluated yet since the daemon started); the tray polls it and this class
// owns the engaged/cleared diff. Posted here rather than by the JVM daemon because only this GUI
// agent holds notification authorization (the PairingApprovalNotifier split).
//
// Once per engagement: posted on the false→true edge only, withdrawn when the fence lifts. A
// null verdict is never an edge — a restarting daemon must not withdraw and re-post the alert.
// The memory is per tray process and starts UNKNOWN, not "not alerted": a notification posted
// by a previous tray process may still be in Notification Center, so the first known clear
// withdraws it (removal is idempotent), and a tray restart while still fenced re-alerts once,
// which the fixed identifier makes replace rather than stack.
//
// No action button: there is no update route to point at (core-schema-skew-surface.md §8.5).
// A tap raises Control through PendingRequestNotifier's shared delegate, where the home banner
// says the same thing. This surface owns its strings (ADR 0016).

import AppKit
import UserNotifications

final class SchemaFenceNotifier {

    /// `nil` until the first known verdict of this tray process — see the header.
    private var alerted: Bool? = nil

    private static let notificationId = "tenshu.sync.schemaFence"

    /// See PairingApprovalNotifier: a bare `swiftc` binary has no bundle id, so the OS calls
    /// no-op in dev and light up in the packaged `.app`.
    private let notificationsAvailable: Bool

    init() {
        self.notificationsAvailable = Bundle.main.bundleIdentifier != nil
    }

    /// Feed one successful poll's verdict. Pass nothing on a failed poll or a `null` verdict.
    func reconcile(fenced: Bool) {
        guard fenced != alerted else { return }
        alerted = fenced
        guard notificationsAvailable else { return }
        if fenced {
            post()
        } else {
            UNUserNotificationCenter.current()
                .removeDeliveredNotifications(withIdentifiers: [Self.notificationId])
        }
    }

    private func post() {
        let content = UNMutableNotificationContent()
        content.title = "Update Control to keep syncing"
        content.body = "Tenshu Control on this Mac is older than your other devices, so new changes aren't arriving. Your files here are safe."
        content.sound = .default
        let request = UNNotificationRequest(
            identifier: Self.notificationId, content: content, trigger: nil
        )
        UNUserNotificationCenter.current().add(request) { error in
            if let error {
                NSLog("Tenshu tray: failed to post update notification: \(error.localizedDescription)")
            }
        }
    }
}
