package org.tenshu.sync.common

import org.tenshu.core.common.TENSHU_STORAGE_FORMAT_VERSION
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.parser.BinaryParser
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.common.schema.ServiceVersion
import org.tenshu.core.common.schema.VersionVector
import org.tenshu.core.common.proxy.TraitResolverImpl
import org.tenshu.core.common.schema.currentVersionFloors
import org.tenshu.core.common.schema.currentVersionVector
import org.tenshu.core.common.schema.toMap
import org.tenshu.core.entity.DataIdProvider
import org.tenshu.core.schema.hasTrait
import org.tenshu.core.trait.ConflictStrategy
import org.tenshu.core.trait.Syncable
import org.tenshu.sync.SyncService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Wire + registration + version coverage for `RealmSchemaEpoch`
 * (`core-schema-evolution.md` §7.1, and §4/§8.4 for the version half).
 *
 * The round-trip closes the wire-registration gap deliberately: in-process
 * tests happily hand around a `RealmSchemaEpoch` built by `create(...)` even
 * when its DTO/trait registrations are missing, and only a real
 * serialize→parse surfaces that — the exact class of bug that would leave the
 * fence unable to read a peer's epoch.
 */
class RealmSchemaEpochSerializationTest {

    private val registry = createSyntheticTestRegistry().also {
        it.registerConfig(syncServiceCommonModule)
    }
    private val context = createTestContext(registry)
    private val serializer = BinarySerializer()
    private val parser = BinaryParser(registry, registry)

    @Test
    fun realmSchemaEpoch_roundTripsThroughTheWire() {
        val original = RealmSchemaEpoch.create(
            context = context,
            versions = VersionVector.create(
                context = context,
                coreVersion = 15,
                services = listOf(
                    ServiceVersion.create(context, "tenshu/fit/service", 4),
                    ServiceVersion.create(context, "tenshu/sync/service", 2),
                ),
            ),
        )

        val parsed = parser.parse(serializer.serialize(original)) as RealmSchemaEpoch

        assertEquals(RealmSchemaEpoch.SINGLETON_KEY, parsed.epochId.get())
        assertEquals(15, parsed.decodeVersions(parser).coreVersion.get().toInt())
        assertEquals(
            mapOf("tenshu/fit/service" to 4, "tenshu/sync/service" to 2),
            parsed.decodeVersions(parser).toMap(),
            "the whole per-service vector must survive the round-trip",
        )
    }

    @Test
    fun realmSchemaEpoch_isSyncableWithMergeAndIsKeyed() {
        val schema = registry.getSchema(RealmSchemaEpoch.ID)
        assertNotNull(schema, "RealmSchemaEpoch must be registered via syncServiceCommonModule")
        assertTrue(schema.hasTrait(Syncable.ID), "the epoch propagates to the realm's other devices")
        assertTrue(
            schema.hasTrait(DataIdProvider.ID),
            "the epoch is keyed (one row per realm) so a bump upserts rather than appends",
        )

        // Resolved through the SAME resolver `SyncProcessor` consults, so this
        // asserts the merger will actually be dispatched rather than that a
        // schema literal says the right word.
        val syncable = TraitResolverImpl { context }
            .getTrait<Syncable>(RealmSchemaEpoch.ID, Syncable.ID)
        assertNotNull(syncable, "the Syncable trait factory must be registered")
        assertEquals(
            ConflictStrategy.Merge,
            syncable.conflictStrategy,
            "MUST be Merge, not last-writer-wins: LWW would let a device that bumped one " +
                "service erase a peer's bump of another (§12 open question 3)",
        )
    }

    /**
     * The service-version machinery as one service sees it (§4/§8.4): `SyncService`
     * declares its current version, and the DERIVED floor walks back as far as its
     * registered chain reaches. That pair is exactly what the announce reply carries,
     * and it is what makes "update Control" vs "update this app" distinguishable (§6.3).
     *
     * At the release baseline (`core-schema-evolution.md` Appendix A) the service is v1 with
     * no chain, so floor == current == 1. The first bump must register a step, or the floor
     * would sit above stored v1 rows.
     */
    @Test
    fun syncService_reportsFloorOneAndCurrentOne() {
        assertEquals(
            1,
            registry.currentVersionVector(context).toMap()[SyncService.ID],
            "SyncService's declared schema version (release baseline)",
        )
        assertEquals(
            1,
            registry.currentVersionFloors(context).toMap()[SyncService.ID],
            "the floor is DERIVED from the registered chain — with none, it equals the current version",
        )
        assertEquals(
            TENSHU_STORAGE_FORMAT_VERSION,
            registry.currentVersionFloors(context).coreVersion.get().toInt(),
            ":core has no migration chain, so its floor is its current version",
        )
    }
}
