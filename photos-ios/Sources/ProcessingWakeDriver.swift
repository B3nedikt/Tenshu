import BackgroundTasks
import Foundation
import TenshuSdk

// The overnight index wake (ios-on-device-inference.md PR-6, §16): a `BGProcessingTask` iOS grants
// when the phone is charging and idle, in which Photos indexes what is left of the library.
//
// ⚠⚠ Swift decides nothing. Kotlin (`ProcessingWake`) says when a request is submitted (Photos left
// the foreground with something asked and photos left, and again at the start of every wake),
// when it is withdrawn, and when the task is complete; this file registers, submits, and forwards
// the task's launch and expiry.
//
// ⚠ Registered in `App.init`, ALWAYS: a `BGProcessingTask` handler must be registered before
// launch finishes, and a wake a flag-on build armed may still come to a flag-off one. Only the
// submitting half is behind the build flag and the static gates (`modelDownloadsOffered`); with them
// against it a wake that comes is completed at once by Kotlin, and the request is withdrawn.
final class ProcessingWakeDriver: NSObject, ProcessingWakeBridge {
    static let shared = ProcessingWakeDriver()

    /// ⚠ Call during app launch: `BGTaskScheduler` refuses a registration after launch completes.
    static func register() {
        let identifier = PhotosLocalRuntime.shared.processingWakeIdentifier()
        let registered = BGTaskScheduler.shared.register(forTaskWithIdentifier: identifier, using: nil) { task in
            guard let processing = task as? BGProcessingTask else {
                task.setTaskCompleted(success: false)
                return
            }
            handle(processing)
        }
        // ⚠ `false` is an identifier `BGTaskSchedulerPermittedIdentifiers` does not cover.
        if !registered { print("[photos-wake] the handler was not registered for \(identifier)") }
        if PhotosLocalRuntime.shared.modelDownloadsOffered() {
            PhotosLocalRuntime.shared.installProcessingWake(bridge: shared)
        } else {
            // A request an enabled build armed is not left behind in a build that indexes nothing.
            BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: identifier)
        }
    }

    /// The wake's launch handler, on BackgroundTasks' own queue.
    private static func handle(_ task: BGProcessingTask) {
        // ⚠ A build that indexes nothing completes a stale wake at once: no runtime is installed
        // for it, and the request an enabled build left is withdrawn.
        guard PhotosLocalRuntime.shared.modelDownloadsOffered() else {
            BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: task.identifier)
            task.setTaskCompleted(success: false)
            return
        }
        let wakeId = UUID().uuidString
        // ⚠ Before the expirationHandler: the wake's place, so an expiry that comes before the run
        // is kept (and one after the completion finds nothing).
        PhotosLocalRuntime.shared.onProcessingWakeBegan(wakeId: wakeId)
        // ⚠ Before Kotlin is told anything else: an expiry from here on reaches it. ⚠ Which thread
        // iOS calls this on is not documented [I]; Kotlin blocks it for the store's drain (up to
        // ~2 s, `ios-shared-store-sqlite.md` B.23.5 item 3, a 📱 gate).
        task.expirationHandler = {
            PhotosLocalRuntime.shared.onProcessingWakeExpired(wakeId: wakeId)
        }
        let handle = ProcessingTaskHandle(task)
        // ⚠⚠ A background launch builds no view, so nothing else installs the runtime: installed here,
        // on the main thread as `PhotosRootView.adopt()` does (one writer of the installed graph).
        DispatchQueue.main.async {
            if PhotosLocalRuntime.shared.isProvisioned() {
                PhotosLocalRuntime.shared.install()
            }
            // ⚠ Off main: Kotlin blocks while it takes the store's task token.
            DispatchQueue.global(qos: .utility).async {
                PhotosLocalRuntime.shared.runProcessingWake(wakeId: wakeId, task: handle)
            }
        }
    }

    /// Kotlin → Swift. ⚠ Off the caller's thread: Apple asks that a submission not be made on main.
    func submit(identifier: String, requiresExternalPower: Bool, requiresNetworkConnectivity: Bool) {
        DispatchQueue.global(qos: .utility).async {
            let request = BGProcessingTaskRequest(identifier: identifier)
            // ⚠⚠ D5: never on battery. The values are Kotlin's (`PhotosProcessingWakeTask`).
            request.requiresExternalPower = requiresExternalPower
            request.requiresNetworkConnectivity = requiresNetworkConnectivity
            do {
                try BGTaskScheduler.shared.submit(request)
                PhotosLocalRuntime.shared.onProcessingWakeSubmitAnswered(accepted: true, detail: nil)
            } catch {
                // The simulator refuses every submission, and so does a phone with Background App
                // Refresh off; the journal says so. On a phone the next leave or wake tries again.
                PhotosLocalRuntime.shared.onProcessingWakeSubmitAnswered(accepted: false, detail: "\(error)")
            }
        }
    }

    func withdraw(identifier: String) {
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: identifier)
        print("[photos-wake] withdrawn: the library is current")
    }
}

/// One launched wake, as Kotlin sees it. ⚠ Kotlin completes it exactly once.
private final class ProcessingTaskHandle: NSObject, ProcessingWakeTask {
    private let task: BGProcessingTask

    init(_ task: BGProcessingTask) {
        self.task = task
    }

    func complete(success: Bool) {
        task.setTaskCompleted(success: success)
    }
}
