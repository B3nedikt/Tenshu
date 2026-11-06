package org.tenshu.sync.proxy

import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.proxy.db.InMemoryObjectStore
import org.tenshu.core.common.proxy.db.ObjectStore
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.entity.getDataIdHash
import org.tenshu.core.time.HybridLogicalClock
import org.tenshu.sync.DeleteBlock
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TombstoneRegistryTest {

    private lateinit var testProxy: TestProxy
    private lateinit var store: CountingStore

    /** Counts row reads so a test can assert what construction does NOT do. */
    private class CountingStore(
        private val delegate: InMemoryObjectStore = InMemoryObjectStore(),
    ) : ObjectStore by delegate {
        var reads: Int = 0

        override fun getObject(dataIdHash: Long): ObjectStore.DbEntry? {
            reads++
            return delegate.getObject(dataIdHash)
        }
    }

    @BeforeTest
    fun setup() {
        store = CountingStore()
        testProxy = TestProxy(
            schemaRegistry = createSyntheticTestRegistry(),
            objectStore = store,
        )
        testProxy.schemaRegistry.registerConfig(syncServiceCommonModule)
    }

    private fun makeBlock(
        idHash: String,
        physicalTime: Long,
        logicalCounter: Long = 0L,
        deviceId: String = "device-a",
        type: String = SyntheticTestData.ID,
        blockId: String = "block-${physicalTime}-${logicalCounter}"
    ) = DeleteBlock.create(
        context = testProxy.tContext,
        blockId = blockId,
        ownerId = "user-1",
        hlc = HybridLogicalClock.create(
            context = testProxy.tContext,
            physicalTime = physicalTime,
            logicalCounter = logicalCounter,
            deviceId = deviceId
        ),
        targetEntityIdHash = idHash,
        targetEntityType = type
    )

    @Test
    fun recordAndLookup() {
        val registry = TombstoneRegistry(testProxy.executionContext, wireIdFor = localTestNaming)
        val block = makeBlock(idHash = "hash-1", physicalTime = 100L)
        registry.record(block)

        val hlc = registry.tombstoneHlcFor("hash-1")
        assertNotNull(hlc)
        assertEquals(100L, hlc.physicalTime.get())
    }

    @Test
    fun lookup_unknownHash_returnsNull() {
        val registry = TombstoneRegistry(testProxy.executionContext, wireIdFor = localTestNaming)
        assertNull(registry.tombstoneHlcFor("never-seen"))
    }

    @Test
    fun record_keepsHighestHlc() {
        val registry = TombstoneRegistry(testProxy.executionContext, wireIdFor = localTestNaming)
        registry.record(makeBlock(idHash = "h", physicalTime = 100L))
        registry.record(makeBlock(idHash = "h", physicalTime = 200L))
        // Older HLC arriving after a newer one should not regress the registry.
        registry.record(makeBlock(idHash = "h", physicalTime = 150L))

        assertEquals(200L, registry.tombstoneHlcFor("h")!!.physicalTime.get())
    }

    @Test
    fun persistsAcrossRestart() {
        val first = TombstoneRegistry(testProxy.executionContext, wireIdFor = localTestNaming)
        first.record(makeBlock(idHash = "persist-me", physicalTime = 42L))

        // "Restart": fresh registry against the same Database should rehydrate.
        val second = TombstoneRegistry(testProxy.executionContext, wireIdFor = localTestNaming)
        val hlc = second.tombstoneHlcFor("persist-me")

        assertNotNull(hlc, "Tombstone should survive a registry restart against the same DB")
        assertEquals(42L, hlc.physicalTime.get())
    }

    @Test
    fun tombstoneCountForType_tracksDistinctEntities() {
        val registry = TombstoneRegistry(testProxy.executionContext, wireIdFor = localTestNaming)
        // Two distinct Device-typed tombstones — counts as 2 for "device".
        registry.record(makeBlock("d1", 100L, type = "device"))
        registry.record(makeBlock("d2", 110L, type = "device"))
        // A higher-HLC re-record for an already-seen entity is a refresh, not a new
        // revocation — counter must NOT double-count.
        registry.record(makeBlock("d1", 200L, type = "device"))
        // A different type contributes to its own bucket only.
        registry.record(makeBlock("f1", 120L, type = "file"))

        assertEquals(2, registry.tombstoneCountForType("device"))
        assertEquals(1, registry.tombstoneCountForType("file"))
        assertEquals(0, registry.tombstoneCountForType("notebook"))
    }

    @Test
    fun tombstoneCountForType_persistsAcrossRestart() {
        val first = TombstoneRegistry(testProxy.executionContext, wireIdFor = localTestNaming)
        first.record(makeBlock("d1", 100L, type = "device"))
        first.record(makeBlock("d2", 110L, type = "device"))

        // Fresh registry rebuilds counts from the persisted store on init — load-bearing
        // because `SyncManager.currentRealmEpoch` is read before any new tombstones flow
        // in after restart.
        val second = TombstoneRegistry(testProxy.executionContext, wireIdFor = localTestNaming)
        assertEquals(2, second.tombstoneCountForType("device"))
    }

    @Test
    fun resolveDataId_populatedBySaveSideEffect() {
        val registry = TombstoneRegistry(testProxy.executionContext, wireIdFor = localTestNaming)

        val data = Entity.create<SyntheticTestData>(
            context = testProxy.tContext,
            composite = SyntheticTestData.Factory.create(testProxy.tContext, "alpha", 1L),
            properties = emptyMap()
        )
        testProxy.executionContext.saveData(data)

        val dataId = testProxy.executionContext.getDataId(data)!!
        val resolved = registry.resolveDataId(dataId.getDataIdHash().toString())

        assertNotNull(resolved, "DataObserver should populate the side-cache on save")
        assertEquals(dataId.getDataIdHash(), resolved.getDataIdHash())
    }

    @Test
    fun resolveDataId_persistsAcrossRestart() {
        // An entity's metadata syncs in (its create is applied) in one session...
        val first = TombstoneRegistry(testProxy.executionContext, wireIdFor = localTestNaming)
        val data = Entity.create<SyntheticTestData>(
            context = testProxy.tContext,
            composite = SyntheticTestData.Factory.create(testProxy.tContext, "beta", 2L),
            properties = emptyMap()
        )
        testProxy.executionContext.saveData(data)
        val dataId = testProxy.executionContext.getDataId(data)!!
        val idHash = dataId.getDataIdHash().toString()

        // "Restart": a fresh registry starts with an empty in-memory map. Before the fix the
        // mapping was lost here and resolveDataId returned null, so on-demand file-content
        // (FileContentManifestApplier) could never resolve the target and silently no-op'd.
        val second = TombstoneRegistry(testProxy.executionContext, wireIdFor = localTestNaming)
        val resolved = second.resolveDataId(idHash)

        assertNotNull(
            resolved,
            "idHash → DataId mapping must survive a restart so synced bytes still resolve"
        )
        assertEquals(dataId.getDataIdHash(), resolved.getDataIdHash())
    }

    @Test
    fun rememberDataId_seedsMissingMapping() {
        // An entity that exists but whose `idHash → DataId` mapping was never written via
        // onDataSaved — the hydration gap: an entity loaded from the store at startup is
        // not re-saved, so the save observer never fires for it and the reverse index has
        // no entry. (Reproduced here by deriving the DataId without saving.)
        val data = Entity.create<SyntheticTestData>(
            context = testProxy.tContext,
            composite = SyntheticTestData.Factory.create(testProxy.tContext, "delta", 4L),
            properties = emptyMap()
        )
        val dataId = testProxy.executionContext.getDataId(data)!!
        val idHash = dataId.getDataIdHash().toString()

        val registry = TombstoneRegistry(testProxy.executionContext, wireIdFor = localTestNaming)
        assertNull(
            registry.resolveDataId(idHash),
            "precondition: no mapping until something writes it"
        )

        // A reader that already holds the DataId repairs the index on demand.
        registry.rememberDataId(dataId)
        val resolved = registry.resolveDataId(idHash)

        assertNotNull(resolved, "rememberDataId must seed a missing mapping")
        assertEquals(dataId.getDataIdHash(), resolved.getDataIdHash())
    }

    @Test
    fun rememberDataId_persistsAcrossRestart() {
        val data = Entity.create<SyntheticTestData>(
            context = testProxy.tContext,
            composite = SyntheticTestData.Factory.create(testProxy.tContext, "epsilon", 5L),
            properties = emptyMap()
        )
        val dataId = testProxy.executionContext.getDataId(data)!!
        val idHash = dataId.getDataIdHash().toString()

        TombstoneRegistry(testProxy.executionContext, wireIdFor = localTestNaming).rememberDataId(dataId)

        // "Restart": a fresh registry starts with an empty in-memory map; the durable record
        // rememberDataId wrote must still resolve the mapping — so a hydrated entity healed
        // on one open stays healed across the next launch.
        val second = TombstoneRegistry(testProxy.executionContext, wireIdFor = localTestNaming)
        val resolved = second.resolveDataId(idHash)

        assertNotNull(resolved, "rememberDataId must persist the mapping across restart")
        assertEquals(dataId.getDataIdHash(), resolved.getDataIdHash())
    }

    @Test
    fun resolveDataId_afterDelete_returnsNull() {
        val registry = TombstoneRegistry(testProxy.executionContext, wireIdFor = localTestNaming)
        val data = Entity.create<SyntheticTestData>(
            context = testProxy.tContext,
            composite = SyntheticTestData.Factory.create(testProxy.tContext, "gamma", 3L),
            properties = emptyMap()
        )
        testProxy.executionContext.saveData(data)
        val dataId = testProxy.executionContext.getDataId(data)!!
        val idHash = dataId.getDataIdHash().toString()

        testProxy.executionContext.deleteData(dataId)

        // Durable record is removed on delete — a fresh registry must not resurrect the mapping.
        val second = TombstoneRegistry(testProxy.executionContext, wireIdFor = localTestNaming)
        assertNull(second.resolveDataId(idHash))
    }

    @Test
    fun constructionDoesNoWorkProportionalToTheTombstonePopulation() {
        // ⚠⚠ THE REGRESSION. `TombstoneRegistry` is constructed inside `initSyncService`, which
        // mints the IPC session — so anything it reads blocks every client's first read.
        //
        // It once resolved a whole array of every tombstone in its constructor. Measured on a
        // real iPhone 2026-08-04: 10,523 members => 6,316 ms, ~96% of a 6.5 s launch, and growing
        // with the tombstone population. The registry's read is a point lookup, so the population
        // never had to be resident at all.
        val seeding = TombstoneRegistry(testProxy.executionContext, wireIdFor = localTestNaming)
        repeat(200) { seeding.record(makeBlock(idHash = "e$it", physicalTime = 100L + it)) }

        store.reads = 0
        TombstoneRegistry(testProxy.executionContext, wireIdFor = localTestNaming) // "restart"

        assertEquals(
            0, store.reads,
            "construction must read NOTHING — it is on the session-mint path, and any per-tombstone " +
                "cost there scales with a population that only grows"
        )
    }

    @Test
    fun aLookupStillCostsOneReadAndCachesIt() {
        // The other half: startup does no work because the cost moved to the point lookup, which
        // must stay O(1) — and must not re-read what it already resolved.
        val seeding = TombstoneRegistry(testProxy.executionContext, wireIdFor = localTestNaming)
        repeat(50) { seeding.record(makeBlock(idHash = "e$it", physicalTime = 100L + it)) }

        val restarted = TombstoneRegistry(testProxy.executionContext, wireIdFor = localTestNaming)
        store.reads = 0
        assertNotNull(restarted.tombstoneHlcFor("e7"), "the tombstone survives the restart")
        val first = store.reads
        assertTrue(first in 1..4, "a lookup is a point read, not a scan (was $first)")

        store.reads = 0
        assertNotNull(restarted.tombstoneHlcFor("e7"))
        assertEquals(0, store.reads, "a resolved tombstone is cached, not re-read")
    }
}
