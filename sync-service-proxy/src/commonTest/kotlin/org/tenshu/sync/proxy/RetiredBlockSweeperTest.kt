package org.tenshu.sync.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.db.InMemoryObjectStore
import org.tenshu.core.common.proxy.db.ObjectStore
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.getDataIdHash
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.SyncService
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertNull

/**
 * G3 (`core-local-store-orphan-gc.md`): collect the block rows that leaked **before** G1 closed
 * the tap, without ever deleting one that is still queued.
 *
 * The dangerous direction is over-deletion — a queued block is an **unsent write** — so the
 * refusal paths are tested as carefully as the happy one.
 */
class RetiredBlockSweeperTest {

    private lateinit var testProxy: TestProxy
    private lateinit var syncManager: SyncManagerImpl
    private lateinit var store: EnumerableStore

    /**
     * An in-memory store that can also enumerate by type — the one capability
     * `SqliteObjectStore` has in production and `InMemoryObjectStore` does not. Injected into
     * [TestProxy] so the sweep reads exactly the rows the proxy wrote.
     */
    private class EnumerableStore(
        private val delegate: InMemoryObjectStore = InMemoryObjectStore(),
    ) : ObjectStore by delegate {
        private val hashes = LinkedHashSet<Long>()

        /** Flip to model a store that cannot enumerate (today JS) and must be refused. */
        var enumerationSupported: Boolean = true

        /** Every row read through [getObject], in order. */
        val reads = mutableListOf<Long>()

        /** Whether the proxy's DatabaseLock is held now (set by [setup]). */
        var lockHeld: () -> Boolean = { true }

        /** Rows read through [getObject] WITHOUT the DatabaseLock. */
        val unlockedReads = HashSet<Long>()

        /** Rows whose read throws. */
        val unreadable = HashSet<Long>()

        /** Rows a decode-through-`getObject` would drop; the keep-set must not depend on them. */
        val unknownToTheIndex = HashSet<Long>()

        /** Fence the store the moment a page scan returns (see [Fenced]). */
        var fenceAfterScan = false

        /**
         * Models a fenced `SharedReplicaStore` handle: every read answers absent and every write
         * throws (`requireServing` → `ReplicaReplaced`). The fence never lifts in production; a
         * test clears it only to inspect what the pass left behind.
         */
        var fenced = false

        override fun getObject(dataIdHash: Long): ObjectStore.DbEntry? {
            reads += dataIdHash
            if (!lockHeld()) unlockedReads += dataIdHash
            if (fenced) return null
            if (dataIdHash in unreadable) throw IllegalStateException("made-up unreadable row")
            return if (dataIdHash in unknownToTheIndex) null else delegate.getObject(dataIdHash)
        }

        override fun storeObject(dbEntry: ObjectStore.DbEntry) {
            if (fenced) throw Fenced()
            hashes += dbEntry.dataIdHash
            delegate.storeObject(dbEntry)
        }

        // ⚠ `by delegate` routes the cursor's default methods to the DELEGATE's storeObject, past the
        // fence above; production's default goes through the fenced store's own `storeObject`.
        override fun storeRetiredBlockSweepCursor(value: Long) {
            if (fenced) throw Fenced()
            delegate.storeRetiredBlockSweepCursor(value)
        }

        override fun removeObject(dataIdHash: Long) {
            if (fenced) throw Fenced()
            hashes -= dataIdHash
            delegate.removeObject(dataIdHash)
        }

        /** Scans requested — 0 proves a completed sweep never asks the store again. */
        var scans: Int = 0

        /** The `afterHash` of the most recent scan, so a test can assert the cursor advanced. */
        var lastAfterHash: Long = Long.MIN_VALUE

        /**
         * Models the store that actually needed the fix: the only index is the hash, so a type
         * filter costs a read per row and both budgets bite. `SqliteObjectStore`'s type index
         * makes `examineLimit` moot there — see [ObjectStore.scanRowsOfType].
         */
        override fun scanRowsOfType(
            typeId: String,
            afterHash: Long,
            matchLimit: Int,
            examineLimit: Int,
        ): ObjectStore.TypeScanPage? =
            scan(typeId, afterHash, matchLimit, examineLimit).also { if (fenceAfterScan) fenced = true }

        private fun scan(
            typeId: String,
            afterHash: Long,
            matchLimit: Int,
            examineLimit: Int,
        ): ObjectStore.TypeScanPage? {
            if (!enumerationSupported) return null
            scans++
            lastAfterHash = afterHash
            val out = mutableListOf<ObjectStore.DbEntry>()
            var reached = afterHash
            var examined = 0
            for (hash in hashes.sorted().filter { it > afterHash }) {
                if (examined == examineLimit) {
                    return ObjectStore.TypeScanPage(out, reached, exhausted = false)
                }
                val entry = delegate.getObject(hash)
                examined++
                reached = hash
                if (entry == null || entry.type != typeId) continue
                out += entry
                if (out.size == matchLimit) {
                    return ObjectStore.TypeScanPage(out, reached, exhausted = false)
                }
            }
            return ObjectStore.TypeScanPage(out, reached, exhausted = true)
        }
    }

    /**
     * ⚠ A `CoroutineScope` extension, not an `@BeforeTest`: `TombstoneRegistry.<init>` reaches
     * `initSyncService`, whose loops need a scope the test owns (`docs/tech-debt.md` fix-item 5).
     */
    private fun CoroutineScope.setup() {
        store = EnumerableStore()
        val lock = HeldDepthLock()
        store.lockHeld = { lock.depth > 0 }
        testProxy = TestProxy(
            schemaRegistry = createSyntheticTestRegistry(),
            objectStore = store,
            coroutineScope = this,
            lock = lock,
        )
        testProxy.schemaRegistry.registerConfig(org.tenshu.sync.common.syncServiceCommonModule)
        syncManager = SyncManagerImpl(
            cryptoManager = testProxy.tContext.syncCryptoManager(),
            storageDelegate = InMemorySecureStorageDelegate(),
            context = testProxy.tContext,
        )
        syncManager.initialize("mock phrase")
    }

    /**
     * `runTest`, with [setup] already run. Every test in this class needs the fixture, so the
     * call belongs here once rather than as the first line of each body.
     *
     * ⚠⚠ It cannot be an `@BeforeTest`, and that is the whole point: [setup] takes a
     * `CoroutineScope` receiver so the [TestProxy] rides the **`TestScope`**, and `@BeforeTest`
     * runs outside `runTest` where no such scope exists. Nor can the teardown move to
     * `@AfterTest` — this is `commonTest`, where `@AfterTest` returns `Unit` and cannot suspend,
     * and `runBlocking` does not exist. Letting `runTest` own the proxy is what cancels the
     * `SyncScheduler` and 1-second `ScheduleDrainLoop` that `initSyncService` starts
     * (`docs/tech-debt.md` fix-item 5). Same shape as `:maps-shared-logic`'s `vmTest`.
     */
    private fun syncTest(body: suspend TestScope.() -> Unit): TestResult = runTest {
        setup()
        body()
    }

    private fun pendingBlocksRef() = ArrayDatabaseReference(
        arrayId = DataId.RelationDataId.create(
            context = testProxy.tContext,
            type = SyncBlock.ID,
            relationId = SyncService.Collections.PENDING_BLOCKS
        ),
        isEntity = false,
        elementSchemaId = SyncBlock.ID
    )

    /** A block written to the store but NOT queued — i.e. exactly a pre-G1 orphan. */
    private fun orphanBlock(blockId: String): SyncBlock {
        val block = SyncBlock.create(
            context = testProxy.tContext,
            blockId = blockId,
            ownerId = requireNotNull(syncManager.currentOwnerId),
            hlc = syncManager.hlcManager.tick(),
            encryptedPayload = byteArrayOf(7, 7, 7),
            targetEntityIdHash = null,
        )
        testProxy.executionContext.saveData(block)
        return block
    }

    private fun queuedBlock(blockId: String): SyncBlock {
        val block = SyncBlock.create(
            context = testProxy.tContext,
            blockId = blockId,
            ownerId = requireNotNull(syncManager.currentOwnerId),
            hlc = syncManager.hlcManager.tick(),
            encryptedPayload = byteArrayOf(9, 9, 9),
            targetEntityIdHash = null,
        )
        testProxy.executionContext.database.addToArray(pendingBlocksRef(), block)
        return block
    }

    private fun rowOf(block: SyncBlock) =
        testProxy.executionContext.getDataId(block)
            ?.let { testProxy.database.getData(it) }

    private fun sweeper(
        registry: RealmRegistry? = RealmRegistry(syncManager),
        objectStore: ObjectStore? = null,
        examine: Int = RetiredBlockSweeper.EXAMINE_PAGE,
    ) = RetiredBlockSweeper(
        executionContext = testProxy.executionContext,
        realmRegistry = registry,
        objectStore = objectStore,
        examineLimit = examine,
    )

    /**
     * A row of some other type, at an explicitly LOW hash so it always sorts before any real
     * `DataId`-derived one — which is what lets a budgeted pass be stopped before it reaches the
     * orphan under test.
     */
    private fun filler(i: Int) = store.storeObject(
        ObjectStore.DbEntry(
            type = "tenshu/test/filler",
            dataIdHash = Long.MIN_VALUE + 100 + i,
            fields = listOf(ObjectStore.DbValue.StringValue("filler-" + i)),
            properties = emptyMap(),
        )
    )

    private fun relationRef(relationId: String) = ArrayDatabaseReference(
        arrayId = DataId.RelationDataId.create(
            context = testProxy.tContext,
            type = SyncBlock.ID,
            relationId = relationId
        ),
        isEntity = false,
        elementSchemaId = SyncBlock.ID
    )

    private fun blockInRelation(blockId: String, relationId: String): SyncBlock {
        val block = SyncBlock.create(
            context = testProxy.tContext,
            blockId = blockId,
            ownerId = requireNotNull(syncManager.currentOwnerId),
            hlc = syncManager.hlcManager.tick(),
            encryptedPayload = byteArrayOf(5, 5, 5),
            targetEntityIdHash = null,
        )
        testProxy.executionContext.database.addToArray(relationRef(relationId), block)
        return block
    }

    /** What the fenced double throws on a write, as `SharedReplicaStore`'s `ReplicaReplaced` does. */
    private class Fenced : IllegalStateException("replica replaced")

    /** The proxy's real reentrant lock, counting how deep the current holder is. */
    private class HeldDepthLock : org.tenshu.core.common.proxy.db.DatabaseLock {
        private val inner = org.tenshu.core.common.TestDatabaseLock()
        var depth = 0
        override fun <R> withLock(block: () -> R): R = inner.withLock {
            depth++
            try { block() } finally { depth-- }
        }
    }

    @Test
    fun anUnreadableKeepSetOnTheLastPageLeavesTheCursorAndTheNextPassSweepsIt() = syncTest {
        // ⚠⚠ Pins the order: the cursor (here SWEEP_COMPLETE, the page is the last) is written only
        // after the keep-set is read, so a pass the unreadable queue ended does not skip its page
        // forever.
        queuedBlock("queued-p2")
        val orphan = orphanBlock("orphan-p2")
        val queue = pendingBlocksRef().arrayId.getDataIdHash()
        store.unreadable += queue
        val before = store.loadRetiredBlockSweepCursor()

        assertFailsWith<IllegalStateException>("unknown is not empty: the pass ends") {
            sweeper(objectStore = store).sweepOnce()
        }
        assertEquals(before, store.loadRetiredBlockSweepCursor(), "⚠⚠ neither advanced nor marked complete")
        assertNotNull(rowOf(orphan), "and nothing was deleted")

        store.unreadable -= queue
        assertNotNull(sweeper(objectStore = store).sweepOnce())
        assertNull(rowOf(orphan), "the next pass sweeps the same page")
        assertEquals(ObjectStore.SWEEP_COMPLETE, store.loadRetiredBlockSweepCursor())
    }

    @Test
    fun aFenceBetweenTheScanAndTheKeepSetReadDeletesNothingAndLeavesTheCursor() = syncTest {
        // ⚠⚠ What stands in for the retired `DurableRow.Unreadable` guard (`ios-shared-store-sqlite.md`
        // (405)). A fenced `SharedReplicaStore` reads every row absent, so a fence landing after the
        // scan makes the keep-set EMPTY and every scanned block a candidate — the queued one too.
        // Nothing may be deleted: each candidate's re-read through `database.getData` reads absent
        // and is skipped, and the cursor write throws, so the pass ends with the cursor in place.
        val queued = queuedBlock("queued-fenced")
        val orphan = orphanBlock("orphan-fenced")
        val before = store.loadRetiredBlockSweepCursor()
        store.fenceAfterScan = true

        assertFailsWith<Fenced>("the cursor write on a fenced store throws") {
            sweeper(objectStore = store).sweepOnce()
        }

        store.fenceAfterScan = false
        store.fenced = false
        assertNotNull(rowOf(queued), "⚠⚠ the queued block survives an empty keep-set")
        assertNotNull(rowOf(orphan), "and so does the orphan: a fenced pass deletes nothing")
        assertEquals(before, store.loadRetiredBlockSweepCursor(), "the cursor did not move")
    }

    @Test
    fun theKeepSetIsReadUnderTheDatabaseLock() = syncTest {
        // ⚠⚠ The page scan runs unlocked, and `addToArray` writes a block row
        // before its queue row — only a locked keep-set read is sure to see the append.
        queuedBlock("queued-locked")
        orphanBlock("orphan-locked")
        val queue = pendingBlocksRef().arrayId.getDataIdHash()
        store.reads.clear()
        assertNotNull(sweeper(objectStore = store).sweepOnce())
        assertTrue(queue in store.reads, "the keep-set was read")
        assertTrue(queue !in store.unlockedReads, "⚠⚠ every keep-set read held the DatabaseLock")
    }

    @Test
    fun aBlockHeldOnlyByThePENDING_TOMBSTONESArrayIsNotSwept() = syncTest {
        // The sibling relation, kept for the same reason: every relation that can hold a block ref
        // is a keep-set source. Enumerating only the ones that obviously do is what caused this.
        val held = blockInRelation("held-by-pending", SyncService.Collections.PENDING_TOMBSTONES)

        assertNotNull(sweeper(objectStore = store).sweepOnce())

        assertNotNull(rowOf(held), "a block referenced by PENDING_TOMBSTONES must survive")
    }

    @Test
    fun sweepsAnOrphanButNotAQueuedBlock() = syncTest {
        val orphan = orphanBlock("orphan-1")
        val queued = queuedBlock("queued-1")
        assertNotNull(rowOf(orphan), "sanity: the orphan was persisted")
        assertNotNull(rowOf(queued), "sanity: the queued block was persisted")

        val pass = assertNotNull(sweeper(objectStore = store).sweepOnce())

        assertEquals(1, pass.deleted, "exactly the unreferenced block is collected")
        assertNull(rowOf(orphan), "the orphan's row must be gone")
        assertNotNull(
            rowOf(queued),
            "a QUEUED block must survive — deleting it would destroy an unsent write"
        )
    }

    @Test
    fun refusesWhenTheStoreCannotEnumerate() = syncTest {
        val orphan = orphanBlock("orphan-2")
        store.enumerationSupported = false

        assertNull(
            sweeper(objectStore = store).sweepOnce(),
            "a store that cannot enumerate is refused outright, as SchemaCompactor does"
        )
        assertNotNull(rowOf(orphan), "and nothing is deleted")
    }

    @Test
    fun refusesWithoutARealmRegistry() = syncTest {
        val orphan = orphanBlock("orphan-3")
        assertNull(
            sweeper(registry = null, objectStore = store).sweepOnce(),
            "without the registry the shared-realm queues are unknowable, so no block can be " +
                "proven unreferenced — refuse rather than guess"
        )
        assertNotNull(rowOf(orphan), "and nothing is deleted")
    }

    @Test
    fun refusesWithoutAStore() = syncTest {
        val orphan = orphanBlock("orphan-4")
        assertNull(sweeper(objectStore = null).sweepOnce(), "no store ⇒ no sweep")
        assertNotNull(rowOf(orphan), "and nothing is deleted")
    }

    @Test
    fun aBudgetedPassThatFoundNothingDoesNotEndTheSweep() = syncTest {
        // ⚠⚠ THE REGRESSION, from the caller's side.
        //
        // The old sweep derived `done` from `page.size < PAGE`. Under an examine budget that
        // inference is false: a pass can legitimately return zero rows and still have most of the
        // store ahead of it. Latching `finished` there would strand every orphan above the cursor
        // — permanently now, since the cursor is durable.
        repeat(40) { filler(it) }
        val orphan = orphanBlock("orphan-beyond-the-budget")

        val sweeper = sweeper(objectStore = store, examine = 3)
        val first = assertNotNull(sweeper.sweepOnce())

        assertEquals(false, first.done, "a pass that stopped on its budget is not exhaustion")
        assertNotNull(rowOf(orphan), "and it has not reached the orphan yet")

        var guard = 0
        while (sweeper.sweepOnce()?.done == false) {
            check(guard++ < 500) { "the sweep never terminated" }
        }
        assertNull(rowOf(orphan), "the orphan beyond the first budget is still collected")
    }

    @Test
    fun aCompletedSweepIsNeverRescannedByALaterProcess() = syncTest {
        // ⚠⚠ Why the cursor is durable. With `finished` only in memory every process would re-run
        // the scan — and on a store with no type index that scan reads EVERY row (on an iPhone,
        // ~100k blob reads at each launch under the route-1 cross-process flock) for a population
        // that was already swept.
        orphanBlock("orphan-once")
        val first = sweeper(objectStore = store)
        var guard = 0
        while (first.sweepOnce()?.done == false) {
            check(guard++ < 500) { "the sweep never terminated" }
        }

        // A brand-new sweeper over the SAME store models the next app launch.
        store.scans = 0
        val next = sweeper(objectStore = store)

        assertNull(next.sweepOnce(), "a swept device must not scan again")
        assertEquals(0, store.scans, "not even one page — the store is never asked")
    }

    @Test
    fun resumesFromWhereTheScanReachedNotFromTheLastMatch() = syncTest {
        // ⚠ A cursor that advanced only over MATCHES would re-examine the whole non-matching
        // tail on every later pass — which is what made a nearly-extinct type so expensive.
        repeat(30) { filler(it) }
        orphanBlock("orphan-low")

        val sweeper = sweeper(objectStore = store, examine = 5)
        sweeper.sweepOnce()
        val afterFirst = store.lastAfterHash
        sweeper.sweepOnce()

        assertTrue(
            store.lastAfterHash > afterFirst,
            "the second pass must start above the first pass's reach ($afterFirst), not back at " +
                "the last matching block"
        )
    }

    @Test
    fun terminatesAndStopsRescanning() = syncTest {
        orphanBlock("orphan-5")
        val sweeper = sweeper(objectStore = store)

        val first = assertNotNull(sweeper.sweepOnce())
        assertEquals(true, first.done, "the scan ran off the end of the hash space")

        assertNull(
            sweeper.sweepOnce(),
            "a finished sweep must not re-walk the store on every subsequent tick"
        )
    }

    @Test
    fun theKeepSetIsTheQueuesListedMembersReadFromTheDurableCopy() = syncTest {
        // The keep-set is the queue row's LISTED members, so a queued
        // block a member decode would drop is still kept.
        val queued = queuedBlock("b-sibling")
        val hash = testProxy.executionContext.getDataId(queued)!!.getDataIdHash()
        store.unknownToTheIndex += hash
        sweeper(objectStore = store).sweepOnce()
        assertTrue(pendingBlocksRef().arrayId.getDataIdHash() in store.reads, "the queue row is read")
        store.unknownToTheIndex -= hash
        assertNotNull(rowOf(queued), "the queued block is never deleted")
    }
}
