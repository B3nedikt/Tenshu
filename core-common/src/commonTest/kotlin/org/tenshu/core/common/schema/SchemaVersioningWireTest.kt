package org.tenshu.core.common.schema

import org.tenshu.core.app.AppService
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TENSHU_STORAGE_FORMAT_VERSION
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.core.TContext
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Serialize→parse round-trips for the FROZEN schema-versioning composites
 * (`core-schema-evolution.md` §6.2) — the wire-registration gap: in-process
 * the local connection passes live objects and never hits the BinaryParser,
 * so only these explicit round-trips prove the `coreCommonModule`
 * registrations. These types are the negotiation channel; if one of these
 * tests ever needs its expectations changed, the freeze has been broken —
 * that is a design violation, not a test to update.
 */
class SchemaVersioningWireTest {

    private lateinit var tContext: TContext

    @BeforeTest
    fun setup() {
        tContext = createTestContext()
    }

    private fun roundTrip(data: org.tenshu.core.Data): org.tenshu.core.Data {
        val registry = createCoreTestRegistry()
        val serializer = BinarySerializer()
        val parser = BinaryParser(registry, registry)
        return parser.parse(serializer.serialize(data))
    }

    @Test
    fun serviceVersionRoundTrips() {
        val parsed = roundTrip(
            ServiceVersion.create(tContext, "tenshu/file/service", 3)
        ) as ServiceVersion
        assertEquals("tenshu/file/service", parsed.serviceId.get())
        assertEquals(3L, parsed.version.get())
    }

    @Test
    fun versionVectorRoundTrips() {
        val vector = VersionVector.create(
            context = tContext,
            coreVersion = 14,
            services = listOf(
                ServiceVersion.create(tContext, "tenshu/file/service", 2),
                ServiceVersion.create(tContext, "tenshu/sync/service", 5),
            ),
        )
        val parsed = roundTrip(vector) as VersionVector
        assertEquals(14L, parsed.coreVersion.get())
        assertEquals(
            mapOf("tenshu/file/service" to 2, "tenshu/sync/service" to 5),
            parsed.toMap(),
        )
    }

    @Test
    fun serviceVersionMismatchRoundTrips() {
        val parsed = roundTrip(
            ServiceVersionMismatch.create(
                context = tContext,
                serviceId = "tenshu/health/service",
                clientCurrent = 7,
                clientRequires = 6,
                proxyFloor = 3,
                proxyCurrent = 5,
            )
        ) as ServiceVersionMismatch
        // ⚠ Four distinct numbers so a positional swap between any two fields fails here.
        assertEquals("tenshu/health/service", parsed.serviceId.get())
        assertEquals(7L, parsed.clientCurrent.get())
        assertEquals(6L, parsed.clientRequires.get())
        assertEquals(3L, parsed.proxyFloor.get())
        assertEquals(5L, parsed.proxyCurrent.get())
    }

    @Test
    fun sessionAdmissionRoundTrips() {
        val vector = VersionVector.create(
            tContext, 14, listOf(ServiceVersion.create(tContext, "tenshu/sync/service", 2))
        )
        val floors = VersionVector.create(
            tContext, 13, listOf(ServiceVersion.create(tContext, "tenshu/sync/service", 1))
        )
        val admission = SessionAdmission.create(
            context = tContext,
            updateProxy = true,
            updateClient = false,
            proxyVector = vector,
            wireFloors = floors,
            mismatches = listOf(
                ServiceVersionMismatch.create(tContext, "tenshu/sync/service", 3, 3, 1, 2)
            ),
            proxyAppId = "app.tenshu.control",
            proxyAppVersion = "1.4.0",
        )
        val parsed = roundTrip(admission) as SessionAdmission
        assertEquals(false, parsed.admitted.get())
        assertEquals(true, parsed.updateProxy.get())
        assertEquals(false, parsed.updateClient.get())
        assertEquals(14L, parsed.proxyVector.coreVersion.get())
        assertEquals(13L, parsed.wireFloors.coreVersion.get())
        assertEquals(mapOf("tenshu/sync/service" to 1), parsed.wireFloors.toMap())
        val mismatch = parsed.mismatches.toList().single()
        assertEquals("tenshu/sync/service", mismatch.serviceId.get())
        assertEquals(3L, mismatch.clientRequires.get())
        assertEquals("app.tenshu.control", parsed.proxyAppId.get())
        assertEquals("1.4.0", parsed.proxyAppVersion.get())
    }

    @Test
    fun currentVersionVectorWalksRegisteredServiceSchemas() {
        val registry = createCoreTestRegistry()
        val vector = registry.currentVersionVector(tContext)
        assertEquals(TENSHU_STORAGE_FORMAT_VERSION.toLong(), vector.coreVersion.get())
        val services = vector.toMap()
        // coreCommonModule registers AppService; every entry defaults to version 1
        // until a service declares otherwise.
        assertTrue(
            services.containsKey("tenshu/app/service"),
            "expected the AppService schema in the walk, got: ${services.keys}"
        )
        assertTrue(services.values.all { it >= 1 })
    }

    /**
     * ⚠⚠ **Floors == current for *every* core service holds only while core has no migration
     * chain.** Once a core service registers one, its floor drops below current — that is the
     * derivation working, not broken.
     *
     * ⭐ The rule the derivation promises, and what is asserted: a service with **no** registered
     * chain has floor == current, and a service **with** one has a floor at the bottom of its chain
     * — which is the whole point of registering one, since the floor is what stops a bump from
     * wiping the store.
     *
     * At the release baseline core registers no chain at all, so every floor equals its current
     * version (all v1). The chained half of the rule is pinned with synthetic services in
     * `SchemaMigrationRegistryTest`.
     */
    @Test
    fun onlyServicesWithAMigrationChainHaveAFloorBelowCurrent() {
        val registry = createCoreTestRegistry()
        val current = registry.currentVersionVector(tContext).toMap()
        val floors = registry.currentVersionFloors(tContext).toMap()

        assertEquals(1, current[AppService.ID], "AppService is at the release baseline")

        // Nothing core registers is chained at the baseline, so every floor is its current version.
        assertEquals(current, floors)
    }
}
