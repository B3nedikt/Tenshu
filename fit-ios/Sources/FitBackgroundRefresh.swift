import BackgroundTasks
import SwiftUI
import TenshuSdk

// R-iOS-5 (fit-app.md §11.5): the BGAppRefreshTask driver for a background HealthKit import
// refresh + recorder-outbox drain. Provisioned for route 1, Fit is its own background syncer; not
// provisioned, the Kotlin refresh no-ops ("not live") — there is no EPC fallback any more (the
// co-resident `ControlAutoConnect` was deleted 2026-11-05). Registration must happen during app launch
// (App.init — BGTaskScheduler rejects registrations afterwards); scheduling re-arms on
// backgrounding AND at the start of every wake (grants are one-shot). The OS owns wake timing and
// the simulator never fires these — the live wake is the R-iOS-4 on-device pass.
enum FitBackgroundRefresh {
    static let taskId = "app.tenshu.fit.refresh"

    /// Call during app launch.
    static func register() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: taskId, using: nil) { task in
            guard let refresh = task as? BGAppRefreshTask else {
                task.setTaskCompleted(success: false)
                return
            }
            handle(task: refresh)
        }
    }

    /// Arm (or replace) the next wake.
    static func schedule() {
        let request = BGAppRefreshTaskRequest(identifier: taskId)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        do {
            try BGTaskScheduler.shared.submit(request)
            print("[fit-bg] refresh armed")
        } catch {
            // Expected on the simulator (BGTaskSchedulerErrorDomain Code=1); harmless — the
            // foreground route-1 probe + launch refresh still cover the user-visible path.
            print("[fit-bg] submit failed: \(error)")
        }
    }

    private static func handle(task: BGAppRefreshTask) {
        schedule() // one-shot grants: always arm the next wake first
        let work = Task {
            let status: String
            if await FitLocalRuntime.shared.isProvisioned() {
                // Route-1 (ios-first-party-route-1-migration.md P2): Fit is its OWN background syncer —
                // adoption rehydrates from the persisted joiner payload with no user present, so an OS
                // wake runs a REAL drain + import (unlike the EPC path, which no-ops unless Control is
                // co-resident + hosting).
                status = (try? await FitLocalRuntime.shared.runBackgroundRefresh(maxWaitSeconds: 15))
                    ?? "bg-refresh threw"
            } else {
                // Not provisioned: the Kotlin refresh no-ops unless a (DEV_HARNESS) live EPC client
                // happens to be active.
                status = (try? await FitSurface.shared.runBackgroundRefresh(maxWaitSeconds: 15))
                    ?? "bg-refresh threw"
            }
            print("[fit-bg] \(status)")
            task.setTaskCompleted(success: true)
        }
        task.expirationHandler = {
            work.cancel()
            task.setTaskCompleted(success: false)
        }
    }
}
