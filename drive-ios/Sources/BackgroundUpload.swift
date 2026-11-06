import Foundation
import TenshuSdk

// P3b (ios-drive-parity.md): the O3-B background-upload transport.
//
// Kotlin (`BackgroundUploadManager`) owns everything portable — encrypt/chunk
// (`SyncObserver.emitFileContent`), stage the sealed chunk to a file in the App-Group
// container, journal it, finalize. This owns the one thing only Swift can: a
// `URLSession(configuration: .background)` whose out-of-process `nsurlsessiond` transfers
// survive app suspension/termination and relaunch the app on completion.
//
// House Rule #1: the staged file is opaque AEAD ciphertext (a CEK-sealed chunk), never a key
// or plaintext; the headers carry a short-lived device-signed JWT (A4) re-minted per enqueue,
// nothing sensitive persisted. Completion is reported back to Kotlin via
// `DriveLocalRuntime.onBackgroundUploadCompleted`, which acks / re-enqueues / defers.
//
// ⚠ The live survives-suspension behaviour is hardware-only, exactly like P6a's BGTask: the
// simulator runs the transfer but does not exercise true suspension + relaunch, so the real
// proof is the P7 on-device pass.
final class BackgroundUpload: NSObject, BackgroundUploadBridge, URLSessionTaskDelegate {

    static let shared = BackgroundUpload()

    /// Stable identifier: the App-relaunch hook re-attaches to THIS session to replay events.
    static let sessionId = "app.tenshu.drive.content-upload"

    /// Stored by the AppDelegate's `handleEventsForBackgroundURLSession`, fired once the session
    /// finishes replaying background completion events after a relaunch.
    var backgroundCompletionHandler: (() -> Void)?

    private lazy var session: URLSession = {
        let config = URLSessionConfiguration.background(withIdentifier: BackgroundUpload.sessionId)
        // Upload user-initiated content promptly (not "discretionary" batching), and let the
        // system relaunch the app to deliver completion events after termination.
        config.isDiscretionary = false
        config.sessionSendsLaunchEvents = true
        return URLSession(configuration: config, delegate: self, delegateQueue: nil)
    }()

    /// Instantiate the singleton session so it re-attaches to `nsurlsessiond` — call at launch
    /// and from the relaunch hook so pending transfers deliver their events.
    func reattach() { _ = session }

    // MARK: BackgroundUploadBridge (called from Kotlin, possibly off the main thread)

    func enqueue(ref: String, filePath: String, urlString: String, headers: [String: String]) {
        guard let url = URL(string: urlString) else {
            DriveLocalRuntime.shared.onBackgroundUploadCompleted(
                ref: ref, statusCode: -1, errorMessage: "invalid url: \(urlString)"
            )
            return
        }
        var request = URLRequest(url: url)
        request.httpMethod = "PUT"
        for (field, value) in headers { request.setValue(value, forHTTPHeaderField: field) }
        // Background sessions upload only from a file (not in-memory Data) — the staged chunk.
        let task = session.uploadTask(with: request, fromFile: URL(fileURLWithPath: filePath))
        task.taskDescription = ref // survives relaunch, so completion maps back to the ref
        task.resume()
    }

    /// What the session holds right now, one ref per task (`ios-sync-activity-indicator.md` §6a.1).
    /// `nsurlsessiond` outlives the app, so a fresh process asks instead of assuming none.
    ///
    /// ⚠ Running and suspended only: a task that is completing has its delegate call on the way,
    /// and one that is cancelling will report too, but neither is still sending.
    func liveTaskRefs(completion: @escaping ([String]) -> Void) {
        session.getAllTasks { tasks in
            completion(
                tasks
                    .filter { $0.state == .running || $0.state == .suspended }
                    .compactMap(\.taskDescription)
            )
        }
    }

    // MARK: URLSessionTaskDelegate

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        let ref = task.taskDescription ?? ""
        let status = (task.response as? HTTPURLResponse)?.statusCode ?? (error == nil ? 200 : -1)
        DriveLocalRuntime.shared.onBackgroundUploadCompleted(
            ref: ref,
            statusCode: Int32(status),
            errorMessage: error?.localizedDescription
        )
    }

    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        // Back on the main queue, tell UIKit we've finished handling the relaunch events.
        DispatchQueue.main.async {
            let handler = self.backgroundCompletionHandler
            self.backgroundCompletionHandler = nil
            handler?()
        }
    }
}
