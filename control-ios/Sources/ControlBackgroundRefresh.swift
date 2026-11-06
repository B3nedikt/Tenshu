import SwiftUI
import BackgroundTasks
import TenshuSdk

// NT-2b (`ios-notification-elevation.md` v0.2, D1-rev): the BGAppRefreshTask driver that lets
// Control notice an inbound actionable request — a membership grant, a redeemed contact invite —
// that arrived while it was closed, and post a notification about it.
//
// ⚠⚠ **Why this exists at all, given NT-2 v0.1 said "decline".** v0.1 rejected a background wake
// for being best-effort rather than on-arrival. Android's closed-process path is *also*
// best-effort — `AndroidPendingRequestNotifier.notifyCurrent` runs from the `DrainSync` hook,
// "~hourly, OS-bounded" — so the comparison was against an ideal no platform meets. This buys
// approximate Android parity, not on-arrival delivery, and must not be described as the latter.
//
// ⚠ Deliberately a SEPARATE task identifier from Drive's `app.tenshu.drive.refresh`: identifiers
// are per app, and Drive's wake drains sync while this one only re-polls the request sources.
// Registering both under one id is not possible and would not be desirable — the two apps have
// independent budgets and independent provisioning.
//
// ⚠ The realm may be **sealed** here and that is a legitimate outcome. Control's default is
// headless (the onboarding setup step leads with device protection; a passphrase is opt-in), so
// on most devices the runtime autoloads with no user present. A user who chose "Encrypt on unlock
// only" has no in-memory keys in a background-launched process, so this reads nothing and posts
// nothing — exactly what that setting promises. Do not try to unlock here.
enum ControlBackgroundRefresh {
    static let taskId = "app.tenshu.control.refresh"

    /// Call during app launch — BGTaskScheduler rejects registrations after launch completes.
    static func register() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: taskId, using: nil) { task in
            guard let refresh = task as? BGAppRefreshTask else {
                task.setTaskCompleted(success: false)
                return
            }
            handle(task: refresh)
        }
    }

    /// Arm (or replace) the next wake. Re-armed when the app backgrounds and at the start of
    /// every wake, because a submitted request is one-shot.
    static func schedule() {
        let request = BGAppRefreshTaskRequest(identifier: taskId)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 30 * 60)
        do {
            try BGTaskScheduler.shared.submit(request)
            print("[control-bg] request refresh armed")
        } catch {
            // Expected on the simulator in some configurations, and harmless: the in-app
            // notification center is unaffected and the live observer still covers the
            // foreground path.
            print("[control-bg] submit failed: \(error)")
        }
    }

    private static func handle(task: BGAppRefreshTask) {
        schedule() // one-shot grants: always arm the next wake first
        let work = Task {
            let status = (try? await ControlSurface.shared.runPendingRequestRefresh(maxWaitSeconds: 10))
                ?? "request-refresh threw"
            print("[control-bg] \(status)")
            task.setTaskCompleted(success: true)
        }
        task.expirationHandler = {
            work.cancel()
            task.setTaskCompleted(success: false)
        }
    }
}
