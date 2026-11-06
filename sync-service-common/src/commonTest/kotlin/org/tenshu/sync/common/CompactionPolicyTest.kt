package org.tenshu.sync.common

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * E4-3a (`core-schema-evolution.md` §8.3): when a device should re-emit, and —
 * more importantly — when it must not.
 */
class CompactionPolicyTest {

    @Test
    fun `a service whose floor equals its version has nothing to compact`() {
        // ⚠ This is the state of EVERY service in the repo today: no real
        // migration chain ⇒ derived floor == current version ⇒ no older readable
        // format exists ⇒ nothing to lift. Compaction must be a no-op here, not a
        // timer that re-emits the whole store for no reason.
        assertFalse(
            CompactionPolicy.shouldCompact(serviceVersion = 3, receiptVersion = null, readableFloor = 3),
        )
    }

    @Test
    fun `history below the floor is compactable when nobody has done it`() {
        assertTrue(
            CompactionPolicy.shouldCompact(serviceVersion = 3, receiptVersion = null, readableFloor = 1),
            "floor 1 < current 3 means readable-but-old history exists, and no receipt claims it",
        )
    }

    @Test
    fun `a receipt at the current version means someone already did it`() {
        assertFalse(
            CompactionPolicy.shouldCompact(serviceVersion = 3, receiptVersion = 3, readableFloor = 1),
            "dedup by receipt — the race is decided by whoever wrote first, and losing costs nothing",
        )
    }

    @Test
    fun `a receipt from before the last bump does not count`() {
        // The work it recorded lifted history to v2; the service is now v3, so
        // v2-shaped blocks are again "old". A receipt is a claim about a version,
        // never a permanent "this service is done" flag.
        assertTrue(
            CompactionPolicy.shouldCompact(serviceVersion = 3, receiptVersion = 2, readableFloor = 1),
        )
    }
}
