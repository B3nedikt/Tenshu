import BackgroundTasks
import Foundation
import TenshuSdk

// *Index now* continued (ios-on-device-inference.md PR-7, §15): on iOS 26+, a pass the user started
// goes on after they leave Photos, as a `BGContinuedProcessingTask` the system shows with its
// progress.
//
// ⚠⚠ Swift decides nothing. Kotlin (`ContinuedIndexing`) asks for a request only once the pass
// has started, chooses the identifier, mirrors the progress and says when the task is complete;
// this file registers, submits, and forwards the task's three events.
//
// ⚠ Behind the build flag and the static gates (`modelDownloadsOffered`), like the model download:
// with either against it nothing here is installed, and nothing registers or submits.

/// The process's driver. Installed once, at launch ([install]).
final class IndexNowContinuation: NSObject, IndexNowContinuationBridge {
    static let shared = IndexNowContinuation()

    /// ⚠ iOS 26+ only: below it there is no continued task, and every press stays PR-5's
    /// foreground pass, which a leave cancels.
    static func install() {
        guard #available(iOS 26, *) else { return }
        PhotosLocalRuntime.shared.installIndexNowContinuation(bridge: shared)
    }

    /// Kotlin → Swift. ⚠⚠ The handler is registered for the **fully composed** identifier, right
    /// before the request is submitted under it (continued-task handlers may be registered when
    /// the user acts, not only at launch). The suffix is new for every request: registering one
    /// identifier twice is a kill.
    func submit(identifier: String, title: String, subtitle: String) {
        guard #available(iOS 26, *) else {
            PhotosLocalRuntime.shared.onIndexNowTaskAnswered(identifier: identifier, accepted: false)
            return
        }
        // ⚠ Off the caller's thread: Apple asks that a submission not be made on the main thread.
        DispatchQueue.global(qos: .userInitiated).async {
            Self.registerAndSubmit(identifier: identifier, title: title, subtitle: subtitle)
        }
    }

    /// ⚠ BackgroundTasks has no call to unregister a handler, so the process keeps one closure
    /// per press (a fresh identifier each time) until it exits. Accepted: a few hundred bytes
    /// each, bounded by how often one person presses *Index now* in one process's life.
    @available(iOS 26, *)
    private static func registerAndSubmit(identifier: String, title: String, subtitle: String) {
        let registered = BGTaskScheduler.shared.register(forTaskWithIdentifier: identifier, using: nil) { task in
            guard let continued = task as? BGContinuedProcessingTask else {
                task.setTaskCompleted(success: false)
                return
            }
            // ⚠ Before Kotlin is told: an expiry from here on reaches it.
            continued.expirationHandler = {
                PhotosLocalRuntime.shared.onIndexNowTaskExpired(identifier: identifier)
            }
            PhotosLocalRuntime.shared.onIndexNowTaskLaunched(identifier: identifier, task: ContinuedTaskHandle(continued))
        }
        // ⚠ `false` is an identifier `BGTaskSchedulerPermittedIdentifiers` does not cover.
        guard registered else {
            PhotosLocalRuntime.shared.onIndexNowTaskAnswered(identifier: identifier, accepted: false)
            return
        }
        let request = BGContinuedProcessingTaskRequest(identifier: identifier, title: title, subtitle: subtitle)
        // ⚠⚠ `.fail`, never the default `.queue`: a queued request may not launch while the user is
        // still in front, and the pass it was for would then be cancelled by the leave.
        request.strategy = .fail
        // ⚠ `#if compiler`, not only `#available`: `submitTaskRequest(_:completion:)` exists in the
        // iOS 27 SDK alone, and `#available` decides at RUN time — the call must still compile.
        // Swift 6.4 is Xcode 27's; an Xcode 26 build (CI's runner) takes the iOS 26 path below.
        #if compiler(>=6.4)
        if #available(iOS 27, *) {
            BGTaskScheduler.shared.submitTaskRequest(request) { error in
                PhotosLocalRuntime.shared.onIndexNowTaskAnswered(identifier: identifier, accepted: error == nil)
            }
            return
        }
        #endif
        do {
            try BGTaskScheduler.shared.submit(request)
            PhotosLocalRuntime.shared.onIndexNowTaskAnswered(identifier: identifier, accepted: true)
        } catch {
            PhotosLocalRuntime.shared.onIndexNowTaskAnswered(identifier: identifier, accepted: false)
        }
    }
}

/// One launched task, as Kotlin sees it. ⚠ Kotlin completes it exactly once.
@available(iOS 26, *)
private final class ContinuedTaskHandle: NSObject, IndexNowContinuedTask {
    private let task: BGContinuedProcessingTask

    init(_ task: BGContinuedProcessingTask) {
        self.task = task
    }

    /// ⚠⚠ A task that reports no progress is expired by the system as stalled.
    func progress(done: Int64, total: Int64) {
        task.progress.totalUnitCount = total
        task.progress.completedUnitCount = done
    }

    func complete(success: Bool) {
        task.setTaskCompleted(success: success)
    }
}
