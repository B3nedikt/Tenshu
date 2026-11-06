package org.tenshu.sdk.ios

/**
 * The Swift↔Kotlin seam for O3-B background uploads (ios-drive-parity.md P3b).
 *
 * Kotlin owns everything portable — encrypt/chunk (`SyncObserver.emitFileContent`), stage,
 * journal, finalize. Swift owns the one thing only it can: a
 * `URLSession(configuration: .background)` whose out-of-process `nsurlsessiond` transfers
 * survive app suspension/termination and relaunch the app on completion. [BackgroundUploadManager]
 * stages a sealed chunk to a file in the App-Group container and calls [enqueue]; the Swift
 * implementation (`drive-ios/Sources/BackgroundUpload.swift`) builds the `PUT` and hands the
 * file to the background session, then reports completion back through
 * `DriveLocalRuntime.onBackgroundUploadCompleted`.
 *
 * House Rule #1 unchanged: the staged file is opaque AEAD ciphertext (the CEK-sealed chunk),
 * never a key or plaintext; the [headers] carry a short-lived device-signed JWT (A4) re-minted
 * per enqueue, not persisted. Fire-and-forget: [enqueue] returns immediately and the transfer
 * may run much later, even while the app is suspended.
 */
interface BackgroundUploadBridge {

    /**
     * Enqueue a background `PUT` of the file at [filePath] to [urlString] carrying [headers]
     * (Authorization + entitlement), tagged by the content-addressed [ref].
     *
     * Idempotent at the relay — content blobs are content-addressed and immutable — so a
     * duplicate enqueue after a crash, or a re-enqueue after a 401, is safe.
     */
    fun enqueue(ref: String, filePath: String, urlString: String, headers: Map<String, String>)

    /**
     * The refs of the transfers the background session holds right now, one entry per transfer,
     * handed to [completion] (possibly on another thread, possibly later). `nsurlsessiond`
     * outlives the app, so this is how a fresh process learns what is already in flight
     * (`ios-sync-activity-indicator.md` §6a.1).
     */
    fun liveTaskRefs(completion: (List<String>) -> Unit)
}
