package org.tenshu.sync.common

import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.schema.ServiceVersion
import org.tenshu.core.common.schema.VersionVector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The rule that decides whether history may be deleted (E4,
 * `core-schema-evolution.md` §8.3). Worth testing on its own precisely because
 * every failure mode here is silent and irreversible: advance the floor while a
 * device still needs the old formats and that device's history is simply gone.
 *
 * The bias is therefore deliberate and one-directional — **every uncertainty
 * resolves to "do not advance"**.
 */
class RosterSchemaFloorTest {

    private val context = createTestContext(createCoreTestRegistry())

    private fun vector(core: Int, vararg services: Pair<String, Int>): VersionVector =
        VersionVector.create(
            context,
            coreVersion = core,
            services = services.map { (id, v) -> ServiceVersion.create(context, id, v) },
        )

    @Test
    fun `the floor is the MIN across devices and never the max or the local value`() {
        val verdict = RosterSchemaFloor.evaluate(
            rosterDeviceIds = setOf("a", "b", "c"),
            recorded = mapOf(
                "a" to vector(18, "tenshu/file/service" to 5),
                "b" to vector(18, "tenshu/file/service" to 3),
                "c" to vector(19, "tenshu/file/service" to 9),
            ),
        )

        assertTrue(verdict is RosterSchemaFloor.Verdict.Advanceable, "all devices spoke: $verdict")
        // The laggard sets the floor — that is the entire point.
        assertEquals(3, verdict.perService["tenshu/file/service"])
        assertEquals(18, verdict.coreVersion)
    }

    @Test
    fun `a device that has not spoken holds the floor back and is named`() {
        val verdict = RosterSchemaFloor.evaluate(
            rosterDeviceIds = setOf("a", "silent-one"),
            recorded = mapOf("a" to vector(18, "tenshu/file/service" to 5)),
        )

        // ⚠ NOT "advance using the devices we have heard from" — silence means
        // the device has not run since the row existed, which is exactly the
        // device that loses data if history is cut off underneath it.
        assertTrue(verdict is RosterSchemaFloor.Verdict.Unknown, "silence must block: $verdict")
        assertEquals(setOf("silent-one"), verdict.silentDeviceIds)
    }

    @Test
    fun `a revoked device stops holding the floor back even with a stale row`() {
        // "b" was revoked, so it is gone from the roster — but its old row is
        // still in the store, because revocation does not reach in and delete
        // synced rows. It must not lower the floor from beyond the grave.
        val verdict = RosterSchemaFloor.evaluate(
            rosterDeviceIds = setOf("a"),
            recorded = mapOf(
                "a" to vector(18, "tenshu/file/service" to 5),
                "b" to vector(11, "tenshu/file/service" to 1),
            ),
        )

        assertTrue(verdict is RosterSchemaFloor.Verdict.Advanceable, "revoked ⇒ no longer counted")
        assertEquals(5, verdict.perService["tenshu/file/service"])
        assertEquals(18, verdict.coreVersion)
    }

    @Test
    fun `a service only one device runs constrains nothing and is omitted`() {
        val verdict = RosterSchemaFloor.evaluate(
            rosterDeviceIds = setOf("a", "b"),
            recorded = mapOf(
                "a" to vector(18, "tenshu/file/service" to 5, "tenshu/health/service" to 2),
                "b" to vector(18, "tenshu/file/service" to 4),
            ),
        )

        assertTrue(verdict is RosterSchemaFloor.Verdict.Advanceable)
        assertEquals(4, verdict.perService["tenshu/file/service"])
        // ⚠ Absent, NOT 0. A device that does not run the health service cannot
        // read its history at any version, so "min including it" is meaningless —
        // and a caller reading a missing entry as a floor of 0 would then refuse
        // to ever advance it. The distinction is the reason for the omission.
        assertTrue(
            "tenshu/health/service" !in verdict.perService,
            "a service not every device runs must be omitted, not defaulted",
        )
    }

    @Test
    fun `an empty roster is unknown and never vacuously advanceable`() {
        // Vacuous truth would say "every device has version ≥ F" for any F, which
        // would authorise deleting all history on a realm whose roster failed to
        // load. Read the empty case as "we do not know the roster" instead.
        val verdict = RosterSchemaFloor.evaluate(rosterDeviceIds = emptySet(), recorded = emptyMap())

        assertTrue(verdict is RosterSchemaFloor.Verdict.Unknown, "empty roster must not advance")
    }
}
