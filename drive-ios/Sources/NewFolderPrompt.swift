import Foundation
import UserNotifications
import TenshuSdk

// The Swift half of the "a new album appeared — back it up?" prompt
// (docs/proposals/drive-synced-folders-new-folder-prompt.md).
//
// Kotlin owns everything portable — detecting the album, the anti-nag known-set that
// guarantees each album is asked about at most once ever, and applying the answer.
// This owns the one thing only Swift can: a UNUserNotificationCenter request, the
// action category behind its two buttons, and the delegate callback that receives a
// tap. Mirrors the BackgroundUpload bridge shape (Kotlin declares the protocol,
// Swift implements it, results flow back through a plain Kotlin entry point).
final class NewFolderPrompt: NSObject, NewFolderPromptBridge {

    static let shared = NewFolderPrompt()

    private static let categoryId = "tenshu.drive.newFolder"
    private static let acceptAction = "tenshu.drive.newFolder.accept"
    private static let dismissAction = "tenshu.drive.newFolder.dismiss"
    private static let bucketKey = "bucketId"

    /// Register the action category. Must run at launch, before any prompt is posted —
    /// a notification whose category is unknown at delivery time renders with no buttons.
    static func registerCategory() {
        let category = UNNotificationCategory(
            identifier: categoryId,
            actions: [
                UNNotificationAction(
                    identifier: acceptAction,
                    title: NSLocalizedString("Back up", comment: "New-album prompt: accept"),
                    options: []
                ),
                UNNotificationAction(
                    identifier: dismissAction,
                    title: NSLocalizedString("No thanks", comment: "New-album prompt: decline"),
                    options: []
                ),
            ],
            intentIdentifiers: [],
            options: []
        )
        UNUserNotificationCenter.current().setNotificationCategories([category])
    }

    /// Ask for notification permission. Called when the user turns photo backup on —
    /// the moment the prompt becomes meaningful — rather than at launch, where it would
    /// be an unexplained system dialog. Declining is not an error: the prompt is a
    /// nicety, and backup itself works either way.
    static func requestAuthorizationIfNeeded() async {
        let center = UNUserNotificationCenter.current()
        let settings = await center.notificationSettings()
        guard settings.authorizationStatus == .notDetermined else { return }
        _ = try? await center.requestAuthorization(options: [.alert, .sound])
    }

    // MARK: - NewFolderPromptBridge

    // Called from Kotlin (IosNewFolderAnnouncer) at most once per album, ever — the
    // anti-nag guarantee is enforced Kotlin-side, so there is no de-duplication here.
    func announce(bucketId: String, displayName: String, itemCount: Int32) {
        let content = UNMutableNotificationContent()
        content.title = String(
            format: NSLocalizedString("Back up “%@”?", comment: "New-album prompt title"),
            displayName
        )
        content.body = String(
            format: NSLocalizedString(
                "This album has %d photos that aren't backed up.",
                comment: "New-album prompt body"
            ),
            Int(itemCount)
        )
        content.categoryIdentifier = Self.categoryId
        content.userInfo = [Self.bucketKey: bucketId]

        // One request id per album so two new albums don't overwrite one another, and a
        // re-post of the same album coalesces instead of stacking.
        let request = UNNotificationRequest(
            identifier: "\(Self.categoryId).\(bucketId)",
            content: content,
            trigger: nil // deliver immediately
        )
        UNUserNotificationCenter.current().add(request)
    }

    // MARK: - Answers

    /// Route a delivered action back into Kotlin. Returns false when the notification
    /// isn't ours, so the shared AppDelegate can keep dispatching to other handlers.
    @discardableResult
    func handle(response: UNNotificationResponse) -> Bool {
        let info = response.notification.request.content.userInfo
        guard response.notification.request.content.categoryIdentifier == Self.categoryId,
              let bucketId = info[Self.bucketKey] as? String
        else { return false }

        switch response.actionIdentifier {
        case Self.acceptAction:
            Task { try? await DriveBackupSurface.shared.answerNewFolderPrompt(bucketId: bucketId, accept: true) }
        case Self.dismissAction:
            Task { try? await DriveBackupSurface.shared.answerNewFolderPrompt(bucketId: bucketId, accept: false) }
        default:
            // Body tap (UNNotificationDefaultActionIdentifier) or a dismiss: deliberately
            // NOT an answer. The album is already recorded as known Kotlin-side, so it is
            // never asked about again; opening Settings is the path to reconsider.
            return true
        }
        return true
    }
}
