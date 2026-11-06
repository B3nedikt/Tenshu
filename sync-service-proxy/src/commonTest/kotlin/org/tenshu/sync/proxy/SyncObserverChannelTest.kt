package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.collection.list.toList
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.SyncService
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Facet A (ADR 0015): outbound `SyncBlock` generation is
 * decoupled from the `saveData` thread. `SyncObserver.onDataSaved` only enqueues;
 * a single consumer coroutine on the session scope does the serialize + AEAD +
 * HLC tick + `pendingBlocks` enqueue.
 *
 * Pins the decoupling: right after `saveData` the block is **not** yet in
 * `pendingBlocks` (under the StandardTestDispatcher the consumer hasn't run), and
 * it appears only after `awaitEmitIdle()` drains the channel. Reverting
 * `onDataSaved` to do generation inline would make the first assert see 1.
 *
 * Constructs `SyncObserver` directly (no `initSyncService`) so the scheduler loop
 * doesn't race the assertions.
 */
class SyncObserverChannelTest {

    @Test
    fun onDataSaved_decouplesBlockGeneration_drainedByAwaitEmitIdle() = runTest {
        val proxy = TestProxy(
            schemaRegistry = createSyntheticTestRegistry(),
            coroutineScope = this
        )
        proxy.schemaRegistry.registerConfig(syncServiceCommonModule)

        val crypto = proxy.tContext.syncCryptoManager()
        val syncManager = SyncManagerImpl(
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate(),
            context = proxy.tContext
        )
        syncManager.initialize("mock phrase")

        val observer = SyncObserver(proxy.executionContext, syncManager, crypto)
        proxy.executionContext.addDataObserver(observer)

        val pendingRef = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = proxy.tContext,
                type = SyncBlock.ID,
                relationId = SyncService.Collections.PENDING_BLOCKS
            ),
            isEntity = false,
            elementSchemaId = SyncBlock.ID
        )
        fun pendingCount() =
            proxy.executionContext.database.getArrayData(pendingRef)?.toList()?.size ?: 0

        val entity = Entity.create<SyntheticTestData>(
            context = proxy.tContext,
            composite = SyntheticTestData.Factory.create(proxy.tContext, "f.txt", 1L),
            properties = emptyMap()
        )
        proxy.executionContext.saveData(entity)

        // Decoupled: nothing generated on the saveData thread; the consumer (on
        // the session scope / StandardTestDispatcher) hasn't been advanced yet.
        assertEquals(0, pendingCount(), "onDataSaved must not generate the block inline")

        observer.awaitEmitIdle()
        assertEquals(1, pendingCount(), "consumer queues the block after emit-idle")
    }

    /**
     * Locked-window hold + re-emit: a Syncable save that lands while the realm is locked
     * (`!syncManager.isEnabled()`, no keys) must NOT be silently dropped (which stranded the
     * entity local forever) — it's held and replayed by [SyncObserver.reemitPendingLockedWork]
     * once the realm is enabled. This is the fix for "a file uploaded during the startup/lock
     * window never syncs".
     */
    @Test
    fun saveWhileLocked_isHeld_thenReemittedOnEnable() = runTest {
        val proxy = TestProxy(
            schemaRegistry = createSyntheticTestRegistry(),
            coroutineScope = this
        )
        proxy.schemaRegistry.registerConfig(syncServiceCommonModule)

        val crypto = proxy.tContext.syncCryptoManager()
        // NOT initialized → isEnabled() == false (the locked / pre-autoload window).
        val syncManager = SyncManagerImpl(
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate(),
            context = proxy.tContext
        )

        val observer = SyncObserver(proxy.executionContext, syncManager, crypto)
        proxy.executionContext.addDataObserver(observer)

        val pendingRef = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = proxy.tContext,
                type = SyncBlock.ID,
                relationId = SyncService.Collections.PENDING_BLOCKS
            ),
            isEntity = false,
            elementSchemaId = SyncBlock.ID
        )
        fun pendingCount() =
            proxy.executionContext.database.getArrayData(pendingRef)?.toList()?.size ?: 0

        val entity = Entity.create<SyntheticTestData>(
            context = proxy.tContext,
            composite = SyntheticTestData.Factory.create(proxy.tContext, "locked.txt", 2L),
            properties = emptyMap()
        )
        proxy.executionContext.saveData(entity)
        observer.awaitEmitIdle()
        assertEquals(
            0,
            pendingCount(),
            "a save while the realm is locked is held, not emitted (and not dropped)"
        )

        // Realm unlocks → keys available. The scheduler's enable edge calls this in production.
        syncManager.initialize("mock phrase")
        observer.reemitPendingLockedWork()
        observer.awaitEmitIdle()
        assertEquals(
            1,
            pendingCount(),
            "the held save is replayed and emitted once the realm is enabled"
        )
    }
}
