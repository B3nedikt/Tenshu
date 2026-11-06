package org.tenshu.sync.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * E4-4 (`core-schema-evolution.md` §8.3): when old migration steps may be
 * deleted. Every wrong answer here is irreversible in one direction — cut too
 * early and history nobody re-emitted becomes unreadable — so the bias is the
 * same as [RosterSchemaFloor]'s: **unknown blocks**.
 */
class FloorRetirementTest {

    private val service = "tenshu/file/service"

    @Test
    fun `both conditions met means the steps below the safe point may go`() {
        val verdict = FloorRetirement.evaluate(
            serviceId = service,
            currentFloor = 1,
            rosterMinVersion = 5,
            receiptVersion = 5,
        )

        assertEquals(FloorRetirement.Verdict.Retirable(uptoVersion = 5), verdict)
    }

    @Test
    fun `the cut goes no higher than the laggard device allows`() {
        // Compaction lifted history to v5, but a device still runs v3: it is still
        // PRODUCING v3 blocks, and deleting their upcasters would strand them.
        val verdict = FloorRetirement.evaluate(
            serviceId = service,
            currentFloor = 1,
            rosterMinVersion = 3,
            receiptVersion = 5,
        )

        assertEquals(FloorRetirement.Verdict.Retirable(uptoVersion = 3), verdict)
    }

    @Test
    fun `the cut goes no higher than compaction reached`() {
        // Every device is current, but history below v2 was never re-emitted, so
        // those blocks are still the only copy of that state.
        val verdict = FloorRetirement.evaluate(
            serviceId = service,
            currentFloor = 1,
            rosterMinVersion = 5,
            receiptVersion = 2,
        )

        assertEquals(FloorRetirement.Verdict.Retirable(uptoVersion = 2), verdict)
    }

    @Test
    fun `an incomplete roster blocks and says so`() {
        val verdict = FloorRetirement.evaluate(
            serviceId = service,
            currentFloor = 1,
            rosterMinVersion = null,
            receiptVersion = 5,
        )

        // ⚠ Not "assume the devices we know about are all of them" — an unknown
        // roster is never evidence of safety.
        assertTrue(verdict is FloorRetirement.Verdict.Blocked)
        assertTrue(verdict.reasons.single().contains("roster is incomplete"))
    }

    @Test
    fun `a service never compacted blocks and says so`() {
        val verdict = FloorRetirement.evaluate(
            serviceId = service,
            currentFloor = 1,
            rosterMinVersion = 5,
            receiptVersion = null,
        )

        assertTrue(verdict is FloorRetirement.Verdict.Blocked)
        assertTrue(verdict.reasons.single().contains("never been compacted"))
    }

    @Test
    fun `already at the floor is blocked with the ordinary no-op reason`() {
        // The state of every service in the repo today. Not a failure — but it must
        // read as "nothing to do", not as "safe to cut".
        val verdict = FloorRetirement.evaluate(
            serviceId = service,
            currentFloor = 5,
            rosterMinVersion = 5,
            receiptVersion = 5,
        )

        assertTrue(verdict is FloorRetirement.Verdict.Blocked)
        assertTrue(verdict.reasons.single().contains("nothing to retire"))
    }

    @Test
    fun `a blocking reason names which side has to move`() {
        val laggardDevice = FloorRetirement.evaluate(
            serviceId = service,
            currentFloor = 3,
            rosterMinVersion = 3,
            receiptVersion = 5,
        )
        assertTrue(laggardDevice is FloorRetirement.Verdict.Blocked)
        assertTrue(
            laggardDevice.reasons.single().contains("must update, or be revoked"),
            "a stuck device is a user action (ADR 0007), and the reason should say so",
        )

        val staleCompaction = FloorRetirement.evaluate(
            serviceId = service,
            currentFloor = 3,
            rosterMinVersion = 5,
            receiptVersion = 3,
        )
        assertTrue(staleCompaction is FloorRetirement.Verdict.Blocked)
        assertTrue(
            staleCompaction.reasons.single().contains("run compaction"),
            "a stale receipt is OUR action, and the reason should say that instead",
        )
    }
}
