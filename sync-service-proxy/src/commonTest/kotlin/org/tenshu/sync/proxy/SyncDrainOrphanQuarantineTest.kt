package org.tenshu.sync.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.common.schema.currentVersionVector
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.impl.array.TListDto
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.entity.DataId
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.sync.GetChanges
import org.tenshu.sync.PushBlock
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.SyncService
import org.tenshu.sync.common.DeviceSchemaVersion
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Robustness regression: a pending block whose `ownerId` belongs to a realm this device no
 * longer participates in must be **quarantined** (dropped) by [SyncScheduler.drainPendingBlocks],
 * not retried forever.
 *
 * Real-world trigger (seen on a live deploy): a device that created its own realm, then joined
 * another via pairing (a re-key), keeps its pre-join blocks queued. Those carry the old owner,
 * so the owner-binding-enforcing relay 403s them on every tick — a hot loop that also wedges the
 * rest of the sync cycle (pulls included), so neither device ever sees the other's changes.
 *
 * Blocks are injected straight into the personal queue with a chosen ownerId — deterministic,
 * and independent of the async block-emit path's stamping timing.
 */
class SyncDrainOrphanQuarantineTest {

    private lateinit var testProxy: TestProxy
    private lateinit var scheduler: SyncScheduler
    private lateinit var syncManager: SyncManagerImpl
    private var pushedBlock: SyncBlock? = null

    /**
     * ⚠ A `CoroutineScope` extension, not an `@BeforeTest`, so the [TestProxy] it builds rides
     * the caller's `TestScope`. `initSyncService` below starts a `SyncScheduler` and a 1-second
     * `ScheduleDrainLoop`; built from `@BeforeTest` there is no scope to tie them to, and a loop
     * still ticking after this class finishes throws into the next `runTest` as
     * `UncaughtExceptionsBeforeTest` — blaming an unrelated test (`docs/tech-debt.md` fix-item 5).
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
                pushedBlock = params?.get(PushBlock.Params.BLOCK) as? SyncBlock
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

        // Pre-record this device's DeviceSchemaVersion row so the E4-2 publisher in
        // initSyncService finds it unchanged and emits nothing. ⚠ SYNC_INGEST, or the
        // seed itself would enqueue the blocks it exists to prevent. This test asserts
        // that NOTHING was pushed, so any legitimate startup traffic would read as a
        // quarantine failure; the publisher is covered by its own test.
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

        scheduler = testProxy.executionContext.initSyncService(syncManager, crypto, platformScheduler = NoOpPlatformScheduler).scheduler
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

    /** Inject a block carrying [owner] straight into the personal pending queue. */
    private fun enqueueBlock(owner: String, blockId: String) {
        val block = SyncBlock.create(
            context = testProxy.tContext,
            blockId = blockId,
            ownerId = owner,
            hlc = syncManager.hlcManager.tick(),
            encryptedPayload = byteArrayOf(1, 2, 3),
            targetEntityIdHash = null
        )
        testProxy.executionContext.database.addToArray(pendingBlocksRef(), block)
    }

    @Test
    fun orphanOwnerBlock_isQuarantined_notRetried() = syncTest {
        try {
            val realmOwner = requireNotNull(syncManager.currentOwnerId)
            // A block whose owner is a realm this device no longer belongs to.
            enqueueBlock(owner = "$realmOwner-PREVIOUS-REALM", blockId = "orphan-1")

            scheduler.drainPendingBlocks()
            assertNull(pushedBlock, "an orphaned-owner block must be quarantined, not pushed")

            // Removed, not merely skipped: a second drain still pushes nothing.
            pushedBlock = null
            scheduler.drainPendingBlocks()
            assertNull(pushedBlock, "the quarantined block must be gone from the queue, not retried")
        } finally {
            scheduler.stop()
        }
    }

    @Test
    fun matchingOwnerBlock_stillPushes() = syncTest {
        try {
            val realmOwner = requireNotNull(syncManager.currentOwnerId)
            // Owner matches the current realm → drains normally (guards the quarantine from
            // over-reaching and dropping healthy blocks).
            enqueueBlock(owner = realmOwner, blockId = "healthy-1")

            scheduler.drainPendingBlocks()
            assertEquals(
                realmOwner,
                pushedBlock?.ownerId?.get(),
                "a block whose owner matches the current realm must still be pushed"
            )
        } finally {
            scheduler.stop()
        }
    }
}
