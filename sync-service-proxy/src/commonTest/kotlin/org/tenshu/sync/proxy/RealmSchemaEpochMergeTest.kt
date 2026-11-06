package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.parser.BinarySerializer
import org.tenshu.core.common.schema.ServiceVersion
import org.tenshu.core.common.schema.VersionVector
import org.tenshu.core.common.schema.toMap
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.sync.common.RealmSchemaEpoch
import org.tenshu.sync.common.decodeVersions
import org.tenshu.sync.common.SemanticMergerRegistry
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [RealmSchemaEpochMerger] — the §12-open-question-3 max-merge.
 *
 * MAX over a version vector is a join-semilattice, so the merge must be
 * **order-independent**: the tests below run the same pair of one-sided bumps in
 * BOTH orders and require an identical result. That property is what makes
 * §7.2's uncoordinated "bump on first write" safe.
 */
class RealmSchemaEpochMergeTest {

    private lateinit var testProxy: TestProxy
    private val merger = RealmSchemaEpochMerger()

    @BeforeTest
    fun setup() {
        testProxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        testProxy.schemaRegistry.registerConfig(syncServiceCommonModule)
    }

    private fun epoch(core: Int, vararg services: Pair<String, Int>): RealmSchemaEpoch =
        RealmSchemaEpoch.create(
            context = testProxy.tContext,
            versions = VersionVector.create(
                context = testProxy.tContext,
                coreVersion = core,
                services = services.map { (id, version) ->
                    ServiceVersion.create(testProxy.tContext, id, version)
                },
            ),
        )

    private fun merge(a: RealmSchemaEpoch, b: RealmSchemaEpoch): RealmSchemaEpoch =
        merger.merge(testProxy.executionContext, a, b) as RealmSchemaEpoch

    @Test
    fun disjointBumps_union_inBothOrders() {
        // Device A bumped only file-service; device B only fit. Neither vector
        // dominates the other — LWW would erase one of them.
        val a = epoch(15, "tenshu/file/service" to 3)
        val b = epoch(15, "tenshu/fit/service" to 4)

        val expected = mapOf("tenshu/file/service" to 3, "tenshu/fit/service" to 4)
        assertEquals(expected, merge(a, b).decodeVersions(testProxy.tContext).toMap(), "A then B")
        assertEquals(expected, merge(b, a).decodeVersions(testProxy.tContext).toMap(), "B then A — must be identical")
    }

    @Test
    fun overlappingEntries_takeTheMax_inBothOrders() {
        val a = epoch(15, "tenshu/file/service" to 3, "tenshu/fit/service" to 2)
        val b = epoch(15, "tenshu/file/service" to 1, "tenshu/fit/service" to 4)

        val expected = mapOf("tenshu/file/service" to 3, "tenshu/fit/service" to 4)
        assertEquals(expected, merge(a, b).decodeVersions(testProxy.tContext).toMap(), "A then B")
        assertEquals(expected, merge(b, a).decodeVersions(testProxy.tContext).toMap(), "B then A — must be identical")
    }

    @Test
    fun coreVersion_onlyEverRises() {
        val a = epoch(15, "tenshu/file/service" to 1)
        val b = epoch(12, "tenshu/file/service" to 1)

        assertEquals(15, merge(a, b).decodeVersions(testProxy.tContext).coreVersion.get().toInt(), "A then B")
        assertEquals(15, merge(b, a).decodeVersions(testProxy.tContext).coreVersion.get().toInt(), "B then A")
    }

    @Test
    fun mergeIsIdempotent_andKeepsTheSingletonKey() {
        val a = epoch(15, "tenshu/file/service" to 3)
        val merged = merge(a, a)

        assertEquals(mapOf("tenshu/file/service" to 3), merged.decodeVersions(testProxy.tContext).toMap())
        assertEquals(15, merged.decodeVersions(testProxy.tContext).coreVersion.get().toInt())
        assertEquals(
            RealmSchemaEpoch.SINGLETON_KEY,
            merged.epochId.get(),
            "the merged row must stay the realm's single epoch row",
        )
    }

    @Test
    fun mergedRow_survivesTheWire() {
        // The wire-registration gap: an in-process merge result proves nothing
        // about whether the merged row can actually reach the realm's peers.
        val merged = merge(
            epoch(15, "tenshu/file/service" to 3),
            epoch(15, "tenshu/fit/service" to 4),
        )

        val parsed = testProxy.parser.parse(BinarySerializer().serialize(merged)) as RealmSchemaEpoch

        assertEquals(
            mapOf("tenshu/file/service" to 3, "tenshu/fit/service" to 4),
            parsed.decodeVersions(testProxy.tContext).toMap(),
        )
        assertEquals(15, parsed.decodeVersions(testProxy.tContext).coreVersion.get().toInt())
        assertEquals(RealmSchemaEpoch.SINGLETON_KEY, parsed.epochId.get())
    }

    /**
     * The production registration guard. `initSyncService` — the one seam every
     * platform wiring funnels through — must register this merger, or the epoch
     * silently degrades to last-writer-wins (the repo's standing
     * "merger registered in tests only" gotcha).
     */
    @Test
    fun mergerIsRegisteredByInitSyncService() = runTest {
        val proxy = TestProxy(
            schemaRegistry = createSyntheticTestRegistry().also {
                it.registerConfig(syncServiceCommonModule)
            },
            coroutineScope = this,
        )
        val registry = SemanticMergerRegistry()
        val crypto = proxy.tContext.syncCryptoManager()
        val syncManager = SyncManagerImpl(
            context = proxy.tContext,
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate(),
        )
        syncManager.initialize("mock phrase")

        proxy.executionContext.initSyncService(syncManager, crypto, registry, platformScheduler = NoOpPlatformScheduler)

        assertEquals(
            RealmSchemaEpoch.ID,
            registry.getMerger(RealmSchemaEpoch.ID)?.typeId,
            "initSyncService must register RealmSchemaEpochMerger into the production registry",
        )
    }
}
