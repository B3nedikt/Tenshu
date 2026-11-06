package org.tenshu.sync.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.impl.array.TListDto
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.common.schema.currentVersionVector
import org.tenshu.core.entity.DataId
import org.tenshu.core.id
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.sync.GetChanges
import org.tenshu.sync.PushBlock
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.SyncService
import org.tenshu.sync.common.DeviceSchemaVersion
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * G1 of `core-local-store-orphan-gc.md`: **a block that is finished must leave no row behind.**
 *
 * `Database.addToArray` calls `saveData` on the member, so queueing a block persists it;
 * `removeFromArray` only rewrites the list's refs. Without the paired delete every block this
 * device has ever pushed stays in the store forever — measured at 77,800 rows of which 101 were
 * referenced, 129 MB of a 145 MB store, growing ~1.8/sec.
 *
 * These tests pin both halves: the row goes when the block is done, and it **stays** when it is
 * not.
 */
class RetiredBlockDisposalTest {

    private lateinit var testProxy: TestProxy
    private lateinit var scheduler: SyncScheduler
    private lateinit var syncManager: SyncManagerImpl
    private var pushShouldFail = false

    /**
     * ⚠ A `CoroutineScope` extension, not an `@BeforeTest`, so the [TestProxy] it builds rides
     * the caller's `TestScope`. Built from `@BeforeTest` there is no scope to tie the proxy's
     * machinery (emit fan-out, session scope) to, and work still running after this class
     * finishes throws into the next `runTest` as `UncaughtExceptionsBeforeTest` — blaming an
     * unrelated test (`docs/tech-debt.md` fix-item 5). The `SyncScheduler` / `ScheduleDrainLoop`
     * loops themselves are never started here (see `initSyncService` below).
     */
    private fun CoroutineScope.setup() {
        testProxy = TestProxy(schemaRegistry = createSyntheticTestRegistry(), coroutineScope = this)
        testProxy.schemaRegistry.registerConfig(syncServiceCommonModule)

        testProxy.additionalImplementations[PushBlock.ID] = object : SyncOperationImplementation {
            override fun <T : Data?> execute(
                executionContext: ExecutionContext,
                operationContext: OperationContext?,
                implementationFactory: OperationImplementationFactory,
                caller: Any?,
                params: Map<String, Data>?
            ): T {
                if (pushShouldFail) error("relay unreachable")
                @Suppress("UNCHECKED_CAST")
                return null as T
            }
        }
        testProxy.additionalImplementations[org.tenshu.sync.PushTombstone.ID] =
            object : SyncOperationImplementation {
                override fun <T : Data?> execute(
                    executionContext: ExecutionContext,
                    operationContext: OperationContext?,
                    implementationFactory: OperationImplementationFactory,
                    caller: Any?,
                    params: Map<String, Data>?
                ): T {
                    @Suppress("UNCHECKED_CAST")
                    return null as T
                }
            }
        testProxy.additionalImplementations[GetChanges.ID] = object : SyncOperationImplementation {
            override fun <T : Data?> execute(
                executionContext: ExecutionContext,
                operationContext: OperationContext?,
                implementationFactory: OperationImplementationFactory,
                caller: Any?,
                params: Map<String, Data>?
            ): T {
                @Suppress("UNCHECKED_CAST")
                return TListDto<SyncBlock>(emptyList()) as T
            }
        }

        val crypto = testProxy.tContext.syncCryptoManager()
        syncManager = SyncManagerImpl(
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate(),
            context = testProxy.tContext
        )
        syncManager.initialize("mock phrase")

        // Seed the device's own schema-version row via SYNC_INGEST so the E4-2 publisher emits
        // nothing — otherwise startup traffic would add blocks this test does not control.
        syncManager.currentRealmContext()?.deviceId?.takeIf { it.isNotEmpty() }?.let { deviceId ->
            testProxy.executionContext.saveData(
                DeviceSchemaVersion.create(
                    context = testProxy.tContext,
                    deviceId = deviceId,
                    versions = testProxy.schemaRegistry.currentVersionVector(testProxy.tContext),
                ),
                WriteOrigin.SYNC_INGEST,
            )
        }

        scheduler = testProxy.executionContext.initSyncService(
            syncManager,
            crypto,
            // Every test steps `drainPendingBlocks` / `drainPendingTombstones` by hand, so the
            // loops are never started rather than started and then stopped — a loop that never
            // starts has no first tick to race (`SyncHealthReportingTest.fixture`).
            //
            // ⚠ A loop running through the body (stopped only in teardown) races the hand-stepping:
            // its next tick drains the block the test just queued, and the precondition `sanity:
            // queueing must have persisted the block` then reads the row it deleted.
            startBackgroundLoops = false,
            platformScheduler = NoOpPlatformScheduler,
        ).scheduler
    }

    /**
     * `runTest`, with [setup] already run. Every test in this class needs the fixture, so the
     * call belongs here once rather than as the first line of each body.
     *
     * ⚠⚠ It cannot be an `@BeforeTest`, and that is the whole point: [setup] takes a
     * `CoroutineScope` receiver so the [TestProxy] rides the **`TestScope`**, and `@BeforeTest`
     * runs outside `runTest` where no such scope exists. Nor can the teardown move to
     * `@AfterTest` — this is `commonTest`, where `@AfterTest` returns `Unit` and cannot suspend,
     * and `runBlocking` does not exist. Letting `runTest` own the proxy is what cancels whatever
     * `initSyncService` launches on the session scope (`docs/tech-debt.md` fix-item 5); the
     * polling loops are not among it, since [setup] never starts them. Same shape as
     * `:maps-shared-logic`'s `vmTest`.
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

    private fun enqueueBlock(owner: String, blockId: String): SyncBlock {
        val block = SyncBlock.create(
            context = testProxy.tContext,
            blockId = blockId,
            ownerId = owner,
            hlc = syncManager.hlcManager.tick(),
            encryptedPayload = byteArrayOf(1, 2, 3),
            targetEntityIdHash = null
        )
        testProxy.executionContext.database.addToArray(pendingBlocksRef(), block)
        return block
    }

    private fun blockRow(block: SyncBlock): Data? {
        val dataId = testProxy.executionContext.getDataId(block) ?: return null
        return testProxy.database.getData(dataId)
    }

    /** The block's parent-owned `hlc` child row — addressed by a key derived from the block. */
    private fun hlcChildRow(block: SyncBlock): Data? {
        val parent = testProxy.executionContext.getDataId(block) as? DataId.EntityDataId
            ?: return null
        return testProxy.database.getData(
            DataId.EntityFieldDataId.create(
                context = testProxy.tContext,
                type = block.hlc.id,
                parentEntity = parent,
                fieldRef = SyncBlock.Fields.HLC,
            )
        )
    }

    @Test
    fun aPushedBlockLeavesNoRow() = syncTest {
        val owner = requireNotNull(syncManager.currentOwnerId)
        val block = enqueueBlock(owner = owner, blockId = "pushed-1")

        assertNotNull(blockRow(block), "sanity: queueing must have persisted the block")
        assertNotNull(hlcChildRow(block), "sanity: the hlc child must have its own row")

        scheduler.drainPendingBlocks()

        assertNull(
            blockRow(block),
            "a successfully pushed block must not outlive its dequeue — this row is the " +
                "99.87% of sync/block rows that were measured as unreachable garbage"
        )
        assertNull(
            hlcChildRow(block),
            "the block's parent-owned hlc child must go with it — one HLC row per block is " +
                "36% of all rows in a real store"
        )
    }

    @Test
    fun aQuarantinedBlockLeavesNoRow() = syncTest {
        val owner = requireNotNull(syncManager.currentOwnerId)
        // Orphaned owner → quarantined rather than retried. Also terminal, also collectable.
        val block = enqueueBlock(owner = "$owner-PREVIOUS-REALM", blockId = "orphan-1")
        assertNotNull(blockRow(block), "sanity: queueing must have persisted the block")

        scheduler.drainPendingBlocks()

        assertNull(blockRow(block), "a quarantined block must not outlive its dequeue")
        assertNull(hlcChildRow(block), "its hlc child must go too")
    }

    /**
     * ⚠⚠ Pushing a tombstone must not disarm the registry. The obvious-looking symmetry — "blocks
     * get collected on push, tombstones should too" — is safe only while the registry's durable
     * record ([org.tenshu.sync.common.PersistedTombstone]) is independent of the queued block row.
     *
     * This drives the real drain and then rebuilds a registry from disk, so a disposal that
     * reaches the registry's state fails here rather than in the field.
     */
    @Test
    fun aPushedTombstoneKeepsItsRowForTheRegistry() = syncTest {
        val owner = requireNotNull(syncManager.currentOwnerId)
        val registry = TombstoneRegistry(
            executionContext = testProxy.executionContext,
            wireIdFor = WireIdResolver(syncManager)::ofOrNull,
            localEventHlc = { syncManager.hlcManager.tick() }
        )
        val tombstone = org.tenshu.sync.DeleteBlock.create(
            context = testProxy.tContext,
            blockId = "tomb-1",
            ownerId = owner,
            hlc = syncManager.hlcManager.tick(),
            targetEntityIdHash = "entity-hash-1",
            targetEntityType = "tenshu/test/thing",
        )
        // Exactly what SyncObserver.emit does: one instance into the registry AND the queue.
        val queue = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = testProxy.tContext,
                type = org.tenshu.sync.DeleteBlock.ID,
                relationId = SyncService.Collections.PENDING_TOMBSTONES
            ),
            isEntity = false,
            elementSchemaId = org.tenshu.sync.DeleteBlock.ID
        )
        registry.record(tombstone)
        testProxy.executionContext.database.addToArray(queue, tombstone)

        scheduler.drainPendingTombstones()

        // ⚠ Guard the guard: without this the test passes vacuously whenever the push fails
        // and the tombstone is simply never dequeued. An empty queue proves the drain really
        // took the branch that a delete would have been added to.
        assertNull(
            testProxy.executionContext.database.getArrayData(queue)
                ?.toList()
                ?.firstOrNull(),
            "sanity: the tombstone must actually have been pushed and dequeued"
        )

        // A registry built fresh from disk must still see it — i.e. the row survived the push.
        val rebuilt = TombstoneRegistry(
            executionContext = testProxy.executionContext,
            wireIdFor = WireIdResolver(syncManager)::ofOrNull,
            localEventHlc = { syncManager.hlcManager.tick() }
        )
        assertNotNull(
            rebuilt.tombstoneHlcFor("entity-hash-1"),
            "a pushed tombstone's row is still referenced by the registry — deleting it " +
                "would disarm resurrection protection after the next restart"
        )
    }

    @Test
    fun aFailedPushKeepsTheBlockAndItsRow() = syncTest {
        pushShouldFail = true
        val owner = requireNotNull(syncManager.currentOwnerId)
        val block = enqueueBlock(owner = owner, blockId = "retry-me")

        scheduler.drainPendingBlocks()

        // THE OTHER HALF OF THE INVARIANT. The delete is tied to the dequeue, so a block
        // that stays queued must stay stored — deleting on a failed push would destroy an
        // unsent write.
        assertNotNull(
            blockRow(block),
            "a block that failed to push is still queued, so its row must survive"
        )
        assertNotNull(hlcChildRow(block), "and so must its hlc child")
    }
}
