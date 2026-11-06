package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.file.ContentId
import org.tenshu.core.file.File
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.file.common.fileServiceCommonModule
import org.tenshu.sync.PushBlock
import org.tenshu.sync.PushTombstone
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.SyncService
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ⭐ CR-2 B4c §2.3, adds before removes (`content-plane-cr2-b4-spec.md`; decided in
 * `content-plane-cr2-quorumless.md` §10.14): a removal of content C is not pushed while a queued,
 * unacknowledged block adds C.
 *
 * The planner is pinned on its own first, then through `initSyncService`, the real observer (its
 * pre-save and pre-delete hooks), the real drains and [FreeingSyncRelay], which records arrival order.
 */
class AddsBeforeRemovesTest {

    // ── The planner ──────────────────────────────────────────────────────────────────────────────

    private fun entry(id: String, holder: String?, adds: Set<String> = emptySet(), cleared: Set<String> = emptySet()) =
        AddsBeforeRemoves.Entry(id, holder, adds, cleared)

    /** Pushes everything the plan allows, accepting all but [failing], and returns the push order. */
    private fun AddsBeforeRemoves.drain(failing: Set<String> = emptySet()): List<String> {
        val order = mutableListOf<String>()
        while (true) {
            val next = pushable().ifEmpty { listOfNotNull(breakCycle()) }
            if (next.isEmpty()) return order
            for (id in next) {
                if (!isRemaining(id)) continue
                order += id
                if (id in failing) attempted(id) else done(id)
            }
        }
    }

    @Test
    fun plain_queueOrderWhenNothingClears() {
        val plan = AddsBeforeRemoves(listOf(entry("1", "a", setOf("c")), entry("2", "b"), entry("3", null)))
        assertEquals(listOf("1", "2", "3"), plan.drain())
    }

    @Test
    fun aBlockThatClearsC_goesAfterALaterBlockOfAnotherHolderThatAddsC() {
        val plan = AddsBeforeRemoves(listOf(entry("clear", "x", cleared = setOf("c")), entry("add", "y", adds = setOf("c"))))
        assertEquals(listOf("add", "clear"), plan.drain())
    }

    @Test
    fun theSameHolderNeverReorders() {
        val plan = AddsBeforeRemoves(
            listOf(
                entry("x1", "x", cleared = setOf("c")),
                entry("x2", "x", adds = setOf("d")),
                entry("y", "y", adds = setOf("c")),
            )
        )
        val order = plan.drain()
        assertEquals(listOf("y", "x1", "x2"), order, "x2 waits behind x1, which waits for y")
    }

    @Test
    fun aClearBehindAnAddThatFailed_isHeldToALaterTick() {
        val plan = AddsBeforeRemoves(listOf(entry("clear", "x", cleared = setOf("c")), entry("add", "y", adds = setOf("c"))))
        assertEquals(listOf("add"), plan.drain(failing = setOf("add")), "the clear is not pushed at all")
        assertTrue(plan.isRemaining("clear"))
    }

    @Test
    fun anAddEarlierThanItsClear_imposesNoWait() {
        // The rule looks only at LATER adds: an earlier one is already ahead in queue order.
        val plan = AddsBeforeRemoves(
            listOf(
                entry("x", "x", adds = setOf("d"), cleared = setOf("c")),
                entry("y", "y", adds = setOf("c"), cleared = setOf("d")),
            )
        )
        assertEquals(listOf("y", "x"), plan.drain())
    }

    @Test
    fun aPureCycleIsBroken_atItsEarliestBlock() {
        // x1 waits for y (it adds what x1 clears), y waits for x2 (likewise), and x2 waits for x1 (same
        // holder, earlier): no block reaches a failed one, so the earliest is released.
        val plan = AddsBeforeRemoves(
            listOf(
                entry("x1", "x", cleared = setOf("c")),
                entry("y", "y", adds = setOf("c"), cleared = setOf("d")),
                entry("x2", "x", adds = setOf("d")),
            )
        )
        assertEquals(listOf("x1", "x2", "y"), plan.drain())
        assertTrue(plan.remaining().isEmpty())
    }

    @Test
    fun aCycleThatReachesAFailedAdd_isNotBroken() {
        // The same cycle, but x1's clear also waits on a later add that FAILS: releasing x1 would free
        // content that add still needs, so nothing in the cycle moves this tick.
        val plan = AddsBeforeRemoves(
            listOf(
                entry("x1", "x", cleared = setOf("c")),
                entry("y", "y", adds = setOf("c"), cleared = setOf("d")),
                entry("x2", "x", adds = setOf("d")),
                entry("f", "z", adds = setOf("c")),
            )
        )
        assertEquals(listOf("f"), plan.drain(failing = setOf("f")))
        assertEquals(listOf("x1", "y", "x2", "f"), plan.remaining())
    }

    @Test
    fun addsAnyOf_answersForTheRemovalDrains() {
        val plan = AddsBeforeRemoves(listOf(entry("1", "a", adds = setOf("c"))))
        assertTrue(plan.addsAnyOf(setOf("c")))
        plan.done("1")
        assertTrue(!plan.addsAnyOf(setOf("c")))
    }

    // ── Through the drains ───────────────────────────────────────────────────────────────────────

    private class Rig(
        val proxy: TestProxy,
        val relay: FreeingSyncRelay,
        val components: SyncProxyComponents,
        val syncManager: SyncManagerImpl,
    ) {
        val ec get() = proxy.executionContext

        fun pendingBlocks(): List<SyncBlock> = ec.database.getArrayData(
            ArrayDatabaseReference(
                arrayId = DataId.RelationDataId.create(proxy.tContext, SyncBlock.ID, SyncService.Collections.PENDING_BLOCKS),
                isEntity = false,
                elementSchemaId = SyncBlock.ID,
            )
        )?.toList()?.filterIsInstance<SyncBlock>().orEmpty()

        fun blockNaming(contentId: String): String = pendingBlocks().last { block ->
            PendingBlockEdgeStore(ec).edgesOf(block.blockId.get()).any { it.contentId == contentId }
        }.blockId.get()

        fun wireName(dataId: DataId): String = WireIdResolver(syncManager) { null }.of(dataId)
    }

    private fun rig(): Rig {
        val proxy = TestProxy(
            schemaRegistry = createSyntheticTestRegistry().apply {
                registerConfig(syncServiceCommonModule)
                registerConfig(fileServiceCommonModule)
            },
        )
        val crypto = proxy.tContext.syncCryptoManager()
        val syncManager = SyncManagerImpl(
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate(),
            context = proxy.tContext,
        ).apply { initialize("mock phrase") }
        val relay = FreeingSyncRelay(proxy.tContext)
        proxy.additionalImplementations[PushBlock.ID] =
            requireNotNull(syncProxyImplementationFactory(PushBlock.ID, relay, syncManager, crypto))
        proxy.additionalImplementations[PushTombstone.ID] =
            requireNotNull(syncProxyImplementationFactory(PushTombstone.ID, relay, syncManager, crypto))
        val components = proxy.executionContext.initSyncService(syncManager, crypto, startBackgroundLoops = false, platformScheduler = NoOpPlatformScheduler)
        return Rig(proxy, relay, components, syncManager)
    }

    private suspend fun Rig.save(path: String, contentId: String): DataId {
        val composite = File.create(proxy.tContext, path)
        ec.saveData(Entity.create<File>(context = proxy.tContext, composite = composite) { set(File.ContentId, contentId) })
        components.syncObserver.awaitEmitIdle()
        return assertNotNull(ec.getDataId(composite))
    }

    @Test
    fun aMovesTombstone_waitsForTheNewPathsBlockToBeAccepted() = runTest {
        val r = rig()
        val old = r.save("/old.txt", "cid-m")
        r.components.scheduler.drainNow()
        // The move: the new path's row names the same content; the old row goes, with a tombstone.
        r.save("/new.txt", "cid-m")
        val newBlock = r.blockNaming("cid-m")
        r.ec.deleteData(old)
        assertNotNull(r.components.syncObserver.emit(old, File.ID), "precondition: a tombstone is queued")
        val oldWire = r.wireName(old)
        assertEquals(setOf("cid-m"), RemovedContentsStore(r.ec).contentsOf(oldWire), "the pre-delete hook recorded it")
        r.relay.failingBlocks += newBlock

        r.components.scheduler.drainNow()

        assertTrue("tombstone:$oldWire" !in r.relay.arrivals, "held while the add is unacknowledged")

        r.relay.failingBlocks.clear()
        r.components.scheduler.drainNow()

        val arrivals = r.relay.arrivals
        assertTrue(arrivals.indexOf("block:$newBlock") in 0 until arrivals.indexOf("tombstone:$oldWire"), "$arrivals")
        assertTrue(RemovedContentsStore(r.ec).contentsOf(oldWire).isEmpty(), "consumed with the tombstone")
    }

    @Test
    fun aRemovalBehindARefusedAdd_staysHeld() = runTest {
        // The add is refused as freed and cannot re-upload here (no bytes, so it would retire — use a
        // transport failure instead): "unacknowledged" means not yet answered 200, whatever the reason.
        val r = rig()
        val old = r.save("/old.txt", "cid-m")
        r.components.scheduler.drainNow()
        r.save("/new.txt", "cid-m")
        r.relay.failingBlocks += r.blockNaming("cid-m")
        r.ec.deleteData(old)
        r.components.syncObserver.emit(old, File.ID)

        repeat(3) { r.components.scheduler.drainNow() }

        assertTrue(r.relay.arrivals.none { it.startsWith("tombstone:") }, "${r.relay.arrivals}")
    }

    @Test
    fun aBlockThatClearsC_isPushedAfterALaterBlockOfAnotherHolderThatAddsC() = runTest {
        val r = rig()
        r.save("/x.txt", "cid-c")
        r.components.scheduler.drainNow()
        // X moves on to other content (clearing c), then Y picks c up.
        r.save("/x.txt", "cid-other")
        val clearing = r.blockNaming("cid-other")
        assertEquals(setOf("cid-c"), PendingBlockEdgeStore(r.ec).clearedOf(clearing), "the pre-save hook recorded the clear")
        r.save("/y.txt", "cid-c")
        val adding = r.blockNaming("cid-c")
        r.relay.arrivals.clear()

        r.components.scheduler.drainNow()

        val arrivals = r.relay.arrivals
        assertTrue(arrivals.indexOf("block:$adding") < arrivals.indexOf("block:$clearing"), "$arrivals")
    }

    @Test
    fun theSameHoldersBlocksKeepTheirOrder_whenOneIsDeferred() = runTest {
        val r = rig()
        r.save("/x.txt", "cid-c")
        r.components.scheduler.drainNow()
        r.save("/x.txt", "cid-other")
        val clearing = r.blockNaming("cid-other")
        r.save("/x.txt", "cid-third")
        val after = r.blockNaming("cid-third")
        r.save("/y.txt", "cid-c")
        val adding = r.blockNaming("cid-c")
        r.relay.arrivals.clear()

        r.components.scheduler.drainNow()

        assertEquals(listOf("block:$adding", "block:$clearing", "block:$after"), r.relay.arrivals.filter {
            it in setOf("block:$adding", "block:$clearing", "block:$after")
        })
    }

    @Test
    fun anIngestDeleteRecordsNothing() = runTest {
        // A peer's tombstone applied here owes no removal push, so nothing would ever consume an entry.
        val r = rig()
        val row = r.save("/a.txt", "cid-a")
        r.ec.deleteData(row, origin = org.tenshu.core.common.proxy.WriteOrigin.SYNC_INGEST)
        assertNull(r.ec.database.getData(row))
        assertTrue(RemovedContentsStore(r.ec).contentsOf(r.wireName(row)).isEmpty())
    }
}
