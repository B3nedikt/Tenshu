import Foundation
import UserNotifications

/// One calendar reminder the daemon wants macOS to hold.
///
/// - `id` is the planner's stable per-occurrence key: it is the notification
///   identifier, so a re-plan **replaces** rather than stacking, and it is the
///   diff key. Never displayed.
/// - `title`/`body` arrive **already redacted** where the calendar is marked
///   sensitive. Nothing here may enrich them — see `ReminderNotifier`.
struct ReminderRow: Equatable {
    let id: String
    let title: String
    let body: String
    let deliverAt: Date
    let tapPayload: String
}

/// Hands calendar reminders to macOS so they fire **whether or not Control is
/// running** (`calendar-app.md` §4.6(a)).
///
/// This is the whole reason the desktop half of CA-5 is not a hand-rolled
/// alarm. `UNCalendarNotificationTrigger` makes the OS the owner of the
/// schedule: sleep, logout, an app update and a crash all become its problem
/// rather than ours, and none of them can make a reminder late.
///
/// ## What this deliberately does not do
///
/// ⚠⚠ **It never composes copy from event data.** The daemon sends finished
/// text, already redacted for a `sensitive` calendar (threat model A5) — and it
/// has to, because once `add(request)` returns, the OS holds that string for
/// days and the app never sees it again. A tray that "improved" the title would
/// be un-redacting something it cannot take back.
///
/// ⚠ It also does not decide *what* is due. The daemon plans; this reconciles.
final class ReminderNotifier {

    /// ⚠ Mirrors `PendingRequestNotifier`: `UNUserNotificationCenter` requires a
    /// bundle identifier, which a bare `swiftc` dev binary lacks. The path
    /// no-ops in development and lights up in the signed, packaged `.app`.
    private let notificationsAvailable: Bool

    /// What we last asked the OS to hold, by id.
    ///
    /// ⚠ Per-process, like `PendingRequestNotifier.known`. A tray restart
    /// re-schedules everything once — harmless, because the identifiers are
    /// stable so each `add` replaces its own pending copy rather than adding a
    /// second.
    private var known: [String: ReminderRow] = [:]

    init() {
        notificationsAvailable = Bundle.main.bundleIdentifier != nil
    }

    /// Bring the OS's pending set in line with `current`.
    ///
    /// ⚠⚠ Content-aware, not id-aware: an unchanged reminder is left alone,
    /// because re-adding one macOS has already **delivered** shows it a second
    /// time. A reminder whose text or time changed is re-added under the same
    /// identifier, which replaces it.
    func reconcile(_ current: [ReminderRow]) {
        guard notificationsAvailable else {
            // ⚠ Still track, so the diff stays correct if the path ever lights
            // up mid-session — the `PendingRequestNotifier` shape.
            known = Dictionary(uniqueKeysWithValues: current.map { ($0.id, $0) })
            return
        }

        let center = UNUserNotificationCenter.current()
        let currentById = Dictionary(uniqueKeysWithValues: current.map { ($0.id, $0) })

        let removed = known.keys.filter { currentById[$0] == nil }
        if !removed.isEmpty {
            center.removePendingNotificationRequests(withIdentifiers: Array(removed))
            // ⚠ Pending only. A reminder already *delivered* stays in
            // Notification Center on purpose: the user may not have read it
            // yet, and silently retracting an alert they were shown is worse
            // than leaving one for an event that has since moved.
        }

        for row in current where known[row.id] != row {
            schedule(row, in: center)
        }

        known = currentById
    }

    /// Cancel everything we hold — for teardown, or when the daemon says the
    /// calendar is empty.
    func cancelAll() {
        guard notificationsAvailable, !known.isEmpty else {
            known = [:]
            return
        }
        UNUserNotificationCenter.current()
            .removePendingNotificationRequests(withIdentifiers: Array(known.keys))
        known = [:]
    }

    private func schedule(_ row: ReminderRow, in center: UNUserNotificationCenter) {
        // ⚠ A reminder whose time has already passed must not be scheduled: a
        // `UNCalendarNotificationTrigger` in the past never fires, so it would
        // sit pending forever and the diff would keep believing it is armed.
        guard row.deliverAt > Date() else { return }

        let content = UNMutableNotificationContent()
        content.title = row.title
        if !row.body.isEmpty { content.body = row.body }
        content.sound = .default
        if !row.tapPayload.isEmpty {
            // ⚠ Identifiers only — the daemon puts no event text in the payload,
            // precisely so a redacted reminder cannot leak its title here.
            content.userInfo = ["tapPayload": row.tapPayload]
        }

        // ⚠⚠ `UNCalendarNotificationTrigger`, not `UNTimeIntervalNotificationTrigger`.
        // An interval trigger counts from *now*, so it drifts across sleep and is
        // wrong the moment the machine suspends. A calendar trigger is anchored
        // to a wall-clock moment, which is what a reminder means.
        let parts = Calendar.current.dateComponents(
            [.year, .month, .day, .hour, .minute],
            from: row.deliverAt
        )
        let trigger = UNCalendarNotificationTrigger(dateMatching: parts, repeats: false)

        center.add(
            UNNotificationRequest(identifier: row.id, content: content, trigger: trigger)
        ) { error in
            if let error {
                NSLog("[tenshu] reminder schedule failed: \(error.localizedDescription)")
            }
        }
    }
}
