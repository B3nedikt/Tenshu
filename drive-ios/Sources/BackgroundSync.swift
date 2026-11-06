import SwiftUI
import BackgroundTasks
import TenshuSdk

// P6a (ios-drive-parity.md): the BGAppRefreshTask driver for the route-1 background drain.
// Drive is the iOS background syncer — adoption rehydrates from the persisted joiner payload
// with no user present, so an OS wake runs a REAL drain (Control's realm stays sealed until
// the user unlocks; it registers nothing). Registration must happen during app launch
// (App.init); scheduling re-arms every time the app leaves the foreground AND at the start of
// every wake (grants are one-shot). The OS owns actual wake timing and the simulator never
// fires these on its own — the live wake is part of the P7 on-device pass.
enum BackgroundSync {
    static let taskId = "app.tenshu.drive.refresh"

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

    /// Arm (or replace) the next wake. Pointless without provisioning — the wake would no-op.
    static func scheduleIfProvisioned() {
        guard DriveLocalRuntime.shared.isProvisioned() else { return }
        let request = BGAppRefreshTaskRequest(identifier: taskId)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        do {
            try BGTaskScheduler.shared.submit(request)
            print("[drive-bg] refresh armed")
        } catch {
            // Expected on the simulator in some configurations; harmless — foreground sync
            // still covers the user-visible path.
            print("[drive-bg] submit failed: \(error)")
        }
    }

    private static func handle(task: BGAppRefreshTask) {
        scheduleIfProvisioned() // one-shot grants: always arm the next wake first
        let work = Task {
            let status = (try? await DriveLocalRuntime.shared.runBackgroundRefresh(maxWaitSeconds: 15))
                ?? "bg-refresh threw"
            print("[drive-bg] \(status)")
            task.setTaskCompleted(success: true)
        }
        task.expirationHandler = {
            work.cancel()
            task.setTaskCompleted(success: false)
        }
    }
}
