package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.entity.Entity
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The task-scoped lane when no realm is there to seal in: the journal is the only durable copy and
 * stays for the replay (`ios-shared-store-sqlite.md` B.20.15), and a lane whose checkpoint finds no
 * realm spills to the journal alone (B.22).
 */
class SyncLaneNoRealmTest {

    private fun entity(proxy: TestProxy, name: String) = Entity.create<SyntheticTestData>(
        context = proxy.tContext,
        composite = SyntheticTestData.Factory.create(proxy.tContext, name, 1L),
        properties = emptyMap(),
    )

    private fun process(store: org.tenshu.core.common.proxy.db.ObjectStore, scope: kotlinx.coroutines.CoroutineScope, schemas: org.tenshu.core.common.proxy.SchemaRegistry? = null): Pair<TestProxy, SyncManagerImpl> {
        val proxy = TestProxy(schemaRegistry = schemas ?: createSyntheticTestRegistry().also { it.registerConfig(syncServiceCommonModule) }, coroutineScope = scope, objectStore = store)
        val syncManager = SyncManagerImpl(cryptoManager = proxy.tContext.syncCryptoManager(), storageDelegate = InMemorySecureStorageDelegate(), context = proxy.tContext)
        syncManager.initialize("mock phrase")
        return proxy to syncManager
    }

    private fun pendingCount(proxy: TestProxy): Int {
        val ref = org.tenshu.core.common.proxy.ArrayDatabaseReference(
            arrayId = org.tenshu.core.entity.DataId.RelationDataId.create(context = proxy.tContext, type = org.tenshu.sync.SyncBlock.ID, relationId = org.tenshu.sync.SyncService.Collections.PENDING_BLOCKS),
            isEntity = false,
            elementSchemaId = org.tenshu.sync.SyncBlock.ID,
        )
        return proxy.executionContext.database.getArrayData(ref)?.toList()?.size ?: 0
    }

    private fun laneJournalCount(proxy: TestProxy): Int {
        val ref = org.tenshu.core.common.proxy.ArrayDatabaseReference(
            arrayId = org.tenshu.core.entity.DataId.RelationDataId.create(context = proxy.tContext, type = org.tenshu.sync.SyncDelta.ID, relationId = org.tenshu.sync.SyncService.Collections.PENDING_LANE_DELTAS),
            isEntity = false,
            elementSchemaId = org.tenshu.sync.SyncDelta.ID,
        )
        return proxy.executionContext.database.getArrayData(ref)?.toList()?.size ?: 0
    }

    @Test
    fun aLaneTaggedSaveOnAnUnopenedLaneWithNoRealmQueuesNothingAndJournalsNothing() = runTest {
        val (proxy, manager) = process(org.tenshu.core.common.proxy.db.InMemoryObjectStore(), this)
        val observer = SyncObserver(proxy.executionContext, manager, proxy.tContext.syncCryptoManager())
        proxy.executionContext.addDataObserver(observer)

        // Enqueued while enabled, with an id nobody opened (every L2 op's taskId reaches here)…
        proxy.executionContext.saveData(entity(proxy, "laned.txt"), WriteOrigin.USER, syncLaneId = "never-opened")
        // …and a sibling locks the realm before the consumer takes it.
        manager.lock()
        observer.awaitEmitIdle()

        assertEquals(0, pendingCount(proxy), "no realm: nothing was queued")
        assertEquals(0, laneJournalCount(proxy), "and the unopened lane journals nothing")
    }

    @Test
    fun aLaneCheckpointWithNoRealmKeepsTheJournal() = runTest {
        val (proxy, manager) = process(org.tenshu.core.common.proxy.db.InMemoryObjectStore(), this)
        val observer = SyncObserver(proxy.executionContext, manager, proxy.tContext.syncCryptoManager())
        proxy.executionContext.addDataObserver(observer)

        observer.openSyncLane("lane")
        proxy.executionContext.saveData(entity(proxy, "staged.txt"), WriteOrigin.USER, syncLaneId = "lane")
        observer.awaitEmitIdle()
        assertEquals(1, laneJournalCount(proxy), "journaled")

        manager.lock()
        observer.checkpointSyncLane("lane")
        assertEquals(0, pendingCount(proxy), "no realm to seal in")
        assertEquals(1, laneJournalCount(proxy), "⚠⚠ so the journal, the save's only durable copy, stays for the replay")
    }

    // ── B.22 (B.20.16 (404)): the no-realm lane is capped ──

    @Test
    fun aLaneWhoseCheckpointFindsNoRealmSpillsToTheJournalAloneAndLogsOnce() = runTest {
        val (proxy, manager) = process(org.tenshu.core.common.proxy.db.InMemoryObjectStore(), this)
        // Enabled, with wire names, but no realm context when a batch is sealed: a sibling locked
        // the shared realm between this process's staging and its checkpoint.
        var realmGone = false
        val racing = object : org.tenshu.sync.common.SyncManager by manager {
            override fun currentRealmContext() = if (realmGone) null else manager.currentRealmContext()
        }
        val observer = SyncObserver(
            proxy.executionContext, racing, proxy.tContext.syncCryptoManager(),
            wireIdResolver = WireIdResolver(manager),
        )
        val logged = ArrayList<String>()
        observer.laneLog = { logged += it }
        proxy.executionContext.addDataObserver(observer)

        observer.openSyncLane("lane")
        observer.openSyncLane("other") // another task's lane, open throughout
        realmGone = true
        // More saves than one batch holds: the cap's checkpoint, and every one after it, finds no realm.
        val saves = 600 // LANE_DELTA_CAP (500) + 100
        repeat(saves) { proxy.executionContext.saveData(entity(proxy, "spill-$it.txt"), WriteOrigin.USER, syncLaneId = "lane") }
        observer.checkpointSyncLane("lane")
        observer.checkpointSyncLane("lane")
        observer.awaitEmitIdle()

        assertEquals(0, pendingCount(proxy), "no realm: nothing sealed")
        assertEquals(saves, laneJournalCount(proxy), "⚠⚠ every delta is in the durable journal, its only copy")
        assertEquals(0, observer.laneMemoryOf("lane"), "⚠⚠ and none of them in the lane's memory any more")
        assertEquals(1, logged.size, "logged once for the lane, not at every checkpoint: $logged")

        // The realm is back: the spilled lane's next checkpoint seals the whole journal.
        realmGone = false
        observer.checkpointSyncLane("lane")
        observer.awaitEmitIdle()
        assertEquals(0, laneJournalCount(proxy), "the journal is sealed and removed")
        assertTrue(pendingCount(proxy) > 0, "into queued blocks")

        // ⚠ The replay drops lane STATE only. Both lanes are still open, fresh and
        // un-spilled: their next saves are staged in memory again, not sent one block each.
        val pendingBefore = pendingCount(proxy)
        proxy.executionContext.saveData(entity(proxy, "after-lane.txt"), WriteOrigin.USER, syncLaneId = "lane")
        proxy.executionContext.saveData(entity(proxy, "after-other.txt"), WriteOrigin.USER, syncLaneId = "other")
        observer.awaitEmitIdle()
        assertEquals(2, observer.laneMemoryOf("lane"), "the spilled lane is un-spilled: its delta staged (and its journal append) in memory")
        assertEquals(2, observer.laneMemoryOf("other"), "⚠ the other task's lane survived the replay")
        assertEquals(pendingBefore, pendingCount(proxy), "no per-save blocks")
    }
}
