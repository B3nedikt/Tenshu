package org.tenshu.core.common.schema.migration

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shared store-open version rules (`core-schema-evolution.md` §8.9, E2j-3).
 *
 * ⚠ Only the CORE gate lives here now. Per-service drift moved to the real
 * migrator via `InMemoryStoreMigration` once the twins gained a migration seam —
 * see [InMemoryStoreMigrationTest]. The stopgap that judged drift here was
 * deleted rather than left as a second rule that could disagree with the first.
 */
class StoreVersionGateTest {

    // --- core range -----------------------------------------------------------

    @Test
    fun theCoreVersionIsReachableInsideItsFloorAndCurrent() {
        assertTrue(coreVersionReachable(recorded = 15, current = 15, floor = 15))
        assertTrue(coreVersionReachable(recorded = 14, current = 15, floor = 14))
        assertTrue(coreVersionReachable(recorded = 15, current = 16, floor = 14))
    }

    @Test
    fun aCoreVersionBelowTheFloorOrAboveCurrentIsUnreachable() {
        assertFalse(coreVersionReachable(recorded = 13, current = 15, floor = 14), "below floor")
        // Written by newer code: a chain migrates forward, never backward.
        assertFalse(coreVersionReachable(recorded = 16, current = 15, floor = 14), "newer code")
        // A fresh/unstamped store reads as 0 — below every real floor, and the
        // caller's wipe is a no-op that doubles as "stamp a new store".
        assertFalse(coreVersionReachable(recorded = 0, current = 15, floor = 15), "fresh")
    }

    @Test
    fun anUnreachableCoreWipesAndSaysWhichRangeItFellOutside() {
        val reason = storeWipeReason(
            coreReachable = false,
            recordedCore = 13,
            currentCore = 15,
            coreFloor = 14,
        )!!
        assertTrue("v13" in reason && "[14, 15]" in reason, reason)
    }

}
