import Foundation
import TenshuSdk
import UIKit

// PIO-7 PR-5, slice 5c (ios-on-device-inference.md §14.11): the transport of Photos' model download.
//
// Kotlin (`BackgroundModelDownloader`) decides everything: which artifacts, whether there is room,
// the journal, the digests, the commit. This owns the one thing only Swift can: a
// `URLSession(configuration: .background)` whose transfers run in `nsurlsessiond` while Photos is
// suspended or dead, and relaunch it when they end. Drive's `BackgroundUpload` is the precedent.
//
// ⚠⚠ D6 is enforced HERE, in the request: `allowsCellularAccess` and `allowsExpensiveNetworkAccess`
// are both false unless that download's own choice allows cellular. iOS's default allows both, so a
// download begun on Wi-Fi would otherwise carry on over cellular, ~709 MB of it.
//
// ⚠⚠ One plain GET an artifact, and one whole file back. Nothing here sends a `Range` or appends:
// whatever the system does to resume a transfer, the delivered file is checked whole in Kotlin, by
// size and sha256, before anything is installed.
//
// ⚠ Nothing sensitive: the URLs are public weights on the model CDN, and no header is set.
//
// ⚠ Hardware-only, like Drive's: the simulator runs a transfer but exercises neither a real
// suspension and relaunch nor a cellular interface. §14.15 lists the on-phone checks.
final class ModelDownload: NSObject, ModelTransferBridge, URLSessionDownloadDelegate {

    static let shared = ModelDownload()

    /// Stable: the relaunch hook re-attaches to THIS session to replay its events.
    static let sessionId = "app.tenshu.photos.model-download"

    /// Stored by `handleEventsForBackgroundURLSession`, called once the session has replayed them.
    var backgroundCompletionHandler: (() -> Void)?

    /// Who holds which ref, and in what order starts and cancels were asked for. ⚠ All of that
    /// logic is Kotlin's (`ModelTransferLedger`), where a test runs it without a session: this
    /// file asks it what to do at each moment and does only that.
    /// ⚠⚠ `offered: false` is a build that offers no model, met by a session an enabled build
    /// left behind: the ledger then admits nothing, lets no task speak, and names every task
    /// for cancelling (`drain`).
    private let ledger = ModelTransferLedger(offered: PhotosLocalRuntime.shared.modelDownloadsOffered())

    /// This process's own tasks, so a cancel reaches them without waiting for the session to list
    /// them. Guarded by `own`.
    private let own = NSLock()
    private var ownTasks: [Int: URLSessionTask] = [:]

    /// Whether `task` holds its ref now. Every report into Kotlin asks first.
    private func speaks(_ task: URLSessionTask) -> Bool {
        guard let ref = task.taskDescription else { return false }
        return ledger.speaksFor(taskId: Int64(task.taskIdentifier), ref: ref)
    }

    private func live(_ tasks: [URLSessionTask]) -> [SessionTransfer] {
        tasks
            .filter { $0.state == .running || $0.state == .suspended }
            .compactMap { task in
                task.taskDescription.map { SessionTransfer(taskId: Int64(task.taskIdentifier), ref: $0) }
            }
    }

    private lazy var session: URLSession = {
        let config = URLSessionConfiguration.background(withIdentifier: ModelDownload.sessionId)
        // The user asked for this download and is watching a bar: not "discretionary" batching.
        config.isDiscretionary = false
        config.sessionSendsLaunchEvents = true
        // ⚠ `allowsCellularAccess` stays at its default here: a request can only narrow what its
        // session allows, and the choice is per download.
        return URLSession(configuration: config, delegate: self, delegateQueue: nil)
    }()

    /// Creates the session so it re-attaches to `nsurlsessiond`: at launch, and from the relaunch hook.
    func reattach() { _ = session }

    /// For a build that offers no model (the flag is off, or a static gate fails) and finds what
    /// an enabled build left: attaches the session, cancels every task in it and deletes the
    /// delivered files. Needs no runtime. ⚠ Which tasks is the ledger's answer: all of them here,
    /// none in a build that offers the model. The journal and the staging stay (§14.11).
    func drain() {
        session.getAllTasks { tasks in
            let stop = Set(self.ledger.notOffered(live: self.live(tasks)).map { Int(truncating: $0) })
            tasks.filter { stop.contains($0.taskIdentifier) }.forEach { $0.cancel() }
        }
        PhotosLocalRuntime.shared.discardDeliveredModelFiles()
    }

    // MARK: ModelTransferBridge (called from Kotlin, off the main thread)

    func start(ref: String, urlString: String, allowsCellular: Bool) {
        guard let url = URL(string: urlString) else {
            _ = PhotosLocalRuntime.shared.onModelTransferNotStarted(ref: ref, errorMessage: "invalid url: \(urlString)")
            return
        }
        // ⚠⚠ Numbered BEFORE the session is asked what it holds: the answer comes later, and a
        // cancel asked for in between must find this start to overtake it.
        let asked = ledger.startAsked(ref: ref)
        session.getAllTasks { tasks in
            // A cancel overtook it, or the ref is held (by a task of an earlier process, or by
            // another runtime of this one that asked first).
            guard self.ledger.admit(ref: ref, asked: asked, live: self.live(tasks)) else { return }
            // ⚠ The request is built here, from THIS call's choice: a task is never reused.
            let task = self.session.downloadTask(
                with: ModelDownload.request(for: url, allowsCellular: allowsCellular), ref: ref
            )
            self.own.lock()
            self.ownTasks[task.taskIdentifier] = task
            self.own.unlock()
            // ⚠ A cancel asked for while the task was being made: it never runs.
            if self.ledger.bind(ref: ref, asked: asked, taskId: Int64(task.taskIdentifier)) {
                task.resume()
            } else {
                task.cancel()
            }
        }
    }

    /// The request of one artifact. ⚠⚠ D6: both flags follow the download's choice and nothing else.
    static func request(for url: URL, allowsCellular: Bool) -> URLRequest {
        var request = URLRequest(url: url)
        request.allowsCellularAccess = allowsCellular
        request.allowsExpensiveNetworkAccess = allowsCellular
        return request
    }

    func liveTransferRefs(completion: @escaping ([String]) -> Void) {
        session.getAllTasks { tasks in
            // ⚠ Every task the session lists is known to the ledger from here on: one of an
            // earlier process takes its ref's hold now, before Kotlin decides what to start.
            // ⚠⚠ And the answer is the LEDGER's, not the session's list: the refs somebody holds.
            // The list both leaves out a transfer that has ended and is not yet acted on, and
            // names a task whose cancel has not reached it.
            completion(self.ledger.liveRefs(live: self.live(tasks)))
        }
    }

    func cancel(ref: String) {
        // This process's own tasks at once; the session's answer covers an earlier process's.
        let cancel = ledger.cancelAsked(ref: ref)
        own.lock()
        let mine = cancel.taskIds.compactMap { ownTasks[Int(truncating: $0)] }
        own.unlock()
        mine.forEach { $0.cancel() }
        session.getAllTasks { tasks in
            let stop = Set(self.ledger.cancelFromSession(ref: ref, asked: cancel.asked, live: self.live(tasks)).map { Int(truncating: $0) })
            tasks.filter { stop.contains($0.taskIdentifier) }.forEach { $0.cancel() }
        }
    }

    // MARK: URLSessionDownloadDelegate

    func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didFinishDownloadingTo location: URL
    ) {
        // ⚠⚠ Synchronously: the file at `location` is deleted when this returns. Kotlin moves it
        // into Photos' private, backup-excluded directory and verifies nothing here.
        let status = (downloadTask.response as? HTTPURLResponse)?.statusCode ?? -1
        // ⚠⚠ Only the task that holds the ref NOW speaks for it (the ledger's rule): a ref outlives
        // a task, and a file from the task of a download that was cancelled and asked for again
        // is not the new download's file. Not taken, it is deleted with this callback's return.
        // ⚠⚠ Asked BEFORE the move and AGAIN AFTER it: a cancel can land in between, delete the
        // destination first, and the move would then put the file back for nobody.
        let ref = downloadTask.taskDescription ?? ""
        let id = Int64(downloadTask.taskIdentifier)
        guard ledger.beginDelivery(taskId: id, ref: ref) else { return }
        _ = PhotosLocalRuntime.shared.onModelTransferDelivered(
            ref: ref,
            temporaryPath: location.path,
            statusCode: Int32(status)
        )
        if !ledger.endDelivery(taskId: id, ref: ref) {
            PhotosLocalRuntime.shared.discardDeliveredModelFile(ref: ref)
        }
    }

    func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didWriteData bytesWritten: Int64,
        totalBytesWritten: Int64,
        totalBytesExpectedToWrite: Int64
    ) {
        let ref = downloadTask.taskDescription ?? ""
        // ⚠⚠ About once a MiB, and only from the task that holds the ref. Both are the ledger's
        // to say, per TASK: this file keeps nothing by ref, because a ref outlives a task.
        guard ledger.reportsProgress(
            taskId: Int64(downloadTask.taskIdentifier), ref: ref, written: totalBytesWritten, step: 1 << 20
        ) else { return }
        PhotosLocalRuntime.shared.onModelTransferProgress(ref: ref, bytesWritten: totalBytesWritten)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        let ref = task.taskDescription ?? ""
        let id = Int64(task.taskIdentifier)
        // ⚠⚠ A cancelled transfer reports nothing. Kotlin cancels a ref and may start the same ref
        // again at once; a late "cancelled" would be read as the new transfer's failure. A transfer
        // the system cancelled (the user force-quit Photos) is found missing at the next launch.
        let failure = error as NSError?
        let cancelled = failure?.domain == NSURLErrorDomain && failure?.code == NSURLErrorCancelled
        // ⚠⚠ The ledger decides whether this completion is reported: only the task that holds
        // its ref speaks. And a task that IS reported keeps its ref until Kotlin has acted on
        // the report (`settled` below): Kotlin acts later, behind its own lock, and a ref freed
        // now would be started again by the step that holds that lock.
        // ⚠⚠ The report is the ledger's own object, and Kotlin asks IT, when it acts, whether the
        // transfer still holds its ref: a cancel may have been queued ahead of the report.
        let report = ledger.completed(taskId: id, ref: ref, cancelled: cancelled)
        own.lock()
        ownTasks[task.taskIdentifier] = nil
        own.unlock()
        guard let report else { return }
        let status = (task.response as? HTTPURLResponse)?.statusCode ?? -1
        let taken = PhotosLocalRuntime.shared.onModelTransferCompleted(
            ref: ref,
            statusCode: Int32(status),
            errorMessage: error?.localizedDescription,
            report: report
        )
        // ⚠ Nobody took it (no runtime is installed in a background relaunch, and nothing is
        // settled while Photos is in the background): nobody will settle it, so the ref is freed
        // here. The next return to the foreground reads the journal and the disk.
        if !taken { report.settled() }
    }

    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        // Back on the main queue, tell UIKit the relaunch's events are handled.
        DispatchQueue.main.async {
            let handler = self.backgroundCompletionHandler
            self.backgroundCompletionHandler = nil
            handler?()
        }
    }
}

private extension URLSession {
    /// A download task whose description is its ref: the one thing of a task that survives a relaunch.
    func downloadTask(with request: URLRequest, ref: String) -> URLSessionDownloadTask {
        let task = downloadTask(with: request)
        task.taskDescription = ref
        return task
    }
}

/// Photos' application delegate. It exists for one callback: iOS re-delivers a background
/// session's events through `handleEventsForBackgroundURLSession`, which SwiftUI's `App` does not
/// receive (Drive's `AppDelegate` is the precedent).
///
/// ⚠ With this adaptor Photos is, like Drive, an app with an application delegate: when Photos
/// gains a receiver for its `tenshu-photos://` URLs, `control-cross-app-open.md` §5i.7 says where
/// they then land.
final class PhotosAppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        handleEventsForBackgroundURLSession identifier: String,
        completionHandler: @escaping () -> Void
    ) {
        // ⚠ Nothing but the re-attach happens here: what the session delivers is only moved into
        // place, whether this is a process iOS started for the events (no runtime is installed
        // for it) or a suspended Photos it woke (nothing is settled in the background:
        // `modelTransfersSettleNow`). It is verified and installed at the next foreground.
        guard identifier == ModelDownload.sessionId else {
            completionHandler()
            return
        }
        // ⚠⚠ Attached whether or not this build offers a model: iOS is saying the session EXISTS,
        // and a session nobody attaches to is never drained. Its tasks would run on, for a
        // build that will never install what they fetch.
        ModelDownload.shared.backgroundCompletionHandler = completionHandler
        ModelDownload.shared.reattach()
        if !PhotosLocalRuntime.shared.modelDownloadsOffered() { ModelDownload.shared.drain() }
    }

    /// ⚠⚠ A swipe-away from the app switcher of a Photos that was still running: the scene went
    /// only to `.inactive`, which is not a leaving (ios-on-device-inference.md §14.5 item 0), so
    /// this is where the scene's record says the user ended it. iOS calls it for a running app
    /// the user ends, never for a jetsam or a crash: a load cut off here is not counted as a kill.
    func applicationWillTerminate(_ application: UIApplication) {
        PhotosLocalRuntime.shared.modelTransfersSettleNow(inForeground: false)
    }
}
