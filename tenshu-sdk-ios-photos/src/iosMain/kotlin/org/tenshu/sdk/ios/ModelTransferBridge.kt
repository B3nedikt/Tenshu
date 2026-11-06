package org.tenshu.sdk.ios

/**
 * The Swift↔Kotlin seam for Photos' model download (`ios-on-device-inference.md` §14.11): the
 * twin of [BackgroundUploadBridge], for a download.
 *
 * Kotlin owns everything that decides anything: the journal, the free-space check, the digests,
 * the store. Swift owns the one thing only it can: a `URLSession(configuration: .background)`
 * whose transfers run in `nsurlsessiond` while Photos is suspended or dead
 * (`photos-ios/Sources/ModelDownload.swift`).
 *
 * What the Swift side reports back, through [PhotosLocalRuntime]:
 * - `onModelTransferDelivered`, **synchronously, from `didFinishDownloadingTo`**: the session's
 *   temporary file is gone when that callback returns;
 * - `onModelTransferCompleted`, from `didCompleteWithError`;
 * - `onModelTransferProgress`.
 *
 * ⚠⚠ Nothing sensitive crosses this seam: the URLs are public weights on the model CDN, no
 * header is set, and no realm, key or token is involved.
 */
interface ModelTransferBridge {

    /**
     * Starts one plain download of [urlString], tagged [ref] (which survives a relaunch as the
     * task's description).
     *
     * ⚠⚠ D6, enforced here and not in any copy: when [allowsCellular] is false the **request**
     * sets `allowsCellularAccess = false` and `allowsExpensiveNetworkAccess = false`. iOS's
     * default allows both. A transfer started on Wi-Fi then *waits* on cellular.
     */
    fun start(ref: String, urlString: String, allowsCellular: Boolean)

    /** The refs of the transfers still running or waiting, handed to [completion]. */
    fun liveTransferRefs(completion: (List<String>) -> Unit)

    /** ⚠ A cancelled transfer reports no completion: its ref may be started again at once. */
    fun cancel(ref: String)
}
