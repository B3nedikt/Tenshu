package org.tenshu.sync.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.impl.array.TListDto
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.entity.Entity
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.sync.GetChanges
import org.tenshu.sync.PushBlock
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncMetadataOperationTest {

    private lateinit var testProxy: TestProxy
    private lateinit var scheduler: SyncScheduler
    private lateinit var syncObserver: SyncObserver
    private lateinit var syncManager: SyncManagerImpl
    private var pushedBlock: SyncBlock? = null
    private var pushedOwnerId: String? = null
    private var pushedPayloadSize: Long? = null

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

        // Mock PushBlock operation to capture outgoing blocks
        testProxy.additionalImplementations[PushBlock.ID] = object : SyncOperationImplementation {
            override fun <T : Data?> execute(
                executionContext: ExecutionContext,
                operationContext: OperationContext?,
                implementationFactory: OperationImplementationFactory,
                caller: Any?,
                params: Map<String, Data>?
            ): T {
                val block = params?.get(PushBlock.Params.BLOCK) as? SyncBlock
                pushedBlock = block
                // ⚠ Read what we assert on HERE, while the block is still live.
                // Since orphan-GC G1 the drain deletes a pushed block's row as it dequeues it,
                // and these blocks are lazily backed — so a field read *after* the drain hits a
                // row that no longer exists ("Entry not found"). It surfaced as flake rather
                // than a hard failure only because an already-resolved field survives, which
                // depends on cache state. A real consumer (`RestSyncRelay.pushBlock`)
                // serialises during the call, exactly like this.
                pushedOwnerId = block?.ownerId?.get()
                pushedPayloadSize = block?.encryptedPayload?.size
                @Suppress("UNCHECKED_CAST")
                return null as T
            }
        }

        // Mock GetChanges to avoid background errors
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

        val components = testProxy.executionContext.initSyncService(syncManager, crypto, platformScheduler = NoOpPlatformScheduler)
        scheduler = components.scheduler
        syncObserver = components.syncObserver
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

    @Test
    fun testSavingSyncableEntityGeneratesBlock() = syncTest {
        try {
            val entity = Entity.create<SyntheticTestData>(
                context = testProxy.tContext,
                composite = SyntheticTestData.Factory.create(
                    context = testProxy.tContext,
                    name = "sync.txt",
                    value = 100L
                ),
                properties = emptyMap()
            )

            testProxy.executionContext.saveData(entity)

            // Block generation is async now (Facet A): wait for the emit
            // consumer to queue the block to pendingBlocks before draining.
            syncObserver.awaitEmitIdle()

            // Manually trigger drain since scheduler might be delayed
            scheduler.drainPendingBlocks()

            assertTrue(pushedBlock != null, "A SyncBlock should have been pushed")
            // After `initialize`, ownerId is deterministically derived from the master
            // public key (see `ownerIdForMasterPublicKey`); pushed blocks must carry
            // exactly that value so JWTs verify against the relay's KnownDevicesStore
            // entry.
            assertEquals(syncManager.currentOwnerId, pushedOwnerId)
            assertTrue((pushedPayloadSize ?: 0L) > 0L)
        } finally {
            scheduler.stop()
        }
    }
}
