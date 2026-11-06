package org.tenshu.sync.common

/**
 * Whether this device should compact a service now (E4,
 * `core-schema-evolution.md` §8.3). Pure, so the decision is testable and the
 * scheduling around it never has to be.
 *
 * ⚖️ Every answer here is **best-effort by design**. Compaction is maintenance:
 * a wrong "no" costs a delayed floor advance, a wrong "yes" costs one redundant
 * re-emission. Neither is a correctness failure, which is exactly why §8.3 can
 * do without election or quorum — so this function may be as simple as it looks,
 * and must never grow into a coordination protocol.
 */
object CompactionPolicy {

    /**
     * @param serviceVersion what this build runs for the service — the version
     *   its state would be re-emitted *at*.
     * @param receiptVersion the version named by the newest receipt for this
     *   service, or null if nobody has ever compacted it here.
     * @param readableFloor the service's derived floor: the oldest version this
     *   build can still read. Equal to [serviceVersion] when no migration chain
     *   is registered.
     */
    fun shouldCompact(
        serviceVersion: Int,
        receiptVersion: Int?,
        readableFloor: Int,
    ): Boolean {
        // ⚠ Nothing below the current version can exist as unconverted history
        // when the floor IS the current version: there is no older readable
        // format, so there is nothing a re-emission would convert. This is the
        // state the whole repo is in today (no real chains registered), and it
        // is why compaction must be a no-op rather than a busy-loop re-emitting
        // everything on a timer.
        if (readableFloor >= serviceVersion) return false

        // Somebody already re-emitted at this version — skip. A stale receipt
        // (naming an older version) does NOT count: the point of compacting is
        // to lift history to the *current* version, so a receipt from before the
        // last bump leaves the work undone.
        return receiptVersion == null || receiptVersion < serviceVersion
    }
}
