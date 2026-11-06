package org.tenshu.sync.proxy

/**
 * What the metered content gate is holding right now (`sync-metered-plane-split.md` §8): the
 * figures behind Drive's *"Waiting for Wi-Fi. N files"* banner, read from the deferral journal by
 * [SyncObserver.deferredContentFigures] and published on the `ContentTransferStatus` row.
 *
 * Deliberately **files and bytes, not blocks**: the banner used to report the realm-wide
 * `PENDING_BLOCKS` backlog, which counts every app's `Syncable` writes rather than files and — since
 * the plane split — is not metered-gated at all.
 */
data class DeferredContentFigures(
    val count: Int = 0,
    val bytes: Long = 0L,
)
