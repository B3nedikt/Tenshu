package org.tenshu.sdk.ios

import app.tenshu.ingest.backup.MediaBucket
import app.tenshu.ingest.backup.NewFolderAnnouncer

/**
 * The Swift↔Kotlin seam for the "a new album appeared — back it up?" prompt
 * (`drive-synced-folders-new-folder-prompt.md`), shaped exactly like
 * [BackgroundUploadBridge]: Kotlin owns everything portable (detection, the
 * anti-nag known-set, applying the answer), Swift owns the one thing only it can —
 * a `UNUserNotificationCenter` request, its action category, and the delegate that
 * receives the tap.
 *
 * The delegate has to live in Swift regardless: notification *actions* are
 * registered against the app's `UNUserNotificationCenter` delegate at launch, which
 * is app-lifecycle territory. Posting from Kotlin and answering in Swift would
 * split the feature across the seam twice, so the whole surface sits Swift-side and
 * answers come back through [DriveBackupSurface.answerNewFolderPrompt].
 */
interface NewFolderPromptBridge {

    /**
     * Post a prompt for one album. Called at most once per album, ever — the
     * anti-nag guarantee is enforced Kotlin-side before this is reached, so the
     * Swift implementation needs no de-duplication of its own.
     */
    fun announce(bucketId: String, displayName: String, itemCount: Int)
}

/**
 * Adapts the shared [NewFolderAnnouncer] onto the Swift [NewFolderPromptBridge].
 * A missing bridge (Swift never installed one — DEV harnesses, unit tests) makes
 * the prompt inert rather than failing: the album is still recorded as known, so
 * behaviour matches "announced and ignored", never a repeat-forever loop.
 */
class IosNewFolderAnnouncer(
    private val bridge: () -> NewFolderPromptBridge?,
) : NewFolderAnnouncer {

    override suspend fun announce(bucket: MediaBucket) {
        bridge()?.announce(
            bucketId = bucket.id,
            displayName = bucket.displayName,
            itemCount = bucket.itemCount,
        )
    }
}
