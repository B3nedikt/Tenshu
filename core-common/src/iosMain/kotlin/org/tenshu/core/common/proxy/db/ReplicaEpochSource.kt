package org.tenshu.core.common.proxy.db

/**
 * A shared-replica handle that can say **which generation of the store** it is on: the iOS SQLite
 * store's `meta.epoch` (`ios-shared-store-sqlite.md` §3), which a wipe and a migration move.
 *
 * What a device-local cache derived from the store's content keys itself to, so a store replaced
 * under it is seen even when the realm's owner did not change (`ios-face-grouping.md` §2.2 item 8;
 * threat model A40). Implemented by `SharedReplicaStore` and by the test replica that stands in for
 * it, so the reader is the same code in both.
 */
interface ReplicaEpochSource {
    /**
     * The store's epoch **now**, or `null` when this handle cannot vouch for it (fenced, replaced
     * since it was opened, refused, failed). ⚠⚠ `null` means "cannot tell": a caller must never read
     * it as "unchanged". Never throws; may block for one access.
     */
    fun currentEpoch(): Long?
}
