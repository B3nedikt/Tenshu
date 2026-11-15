package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.common.SyntheticTestData
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.core.toTString
import org.tenshu.sync.ChangesResponse
import org.tenshu.sync.GetChanges
import org.tenshu.sync.GetTombstones
import org.tenshu.sync.PushBlock
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.TombstonesResponse
import org.tenshu.sync.common.RealmContext
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.sync.proxy.RealmRegistry.Companion.NAMESPACE_SEP
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SR-M.1b-drain isolation: each realm's outbound queue drains to *its own*
 * relay. A `tickOnce` pushes the personal realm's block via the (DI) personal
 * relay and the shared realm's block to the shared realm's own
 * [MockSyncRelay] — never the other way round. Push isolation is the
 * leak-relevant half of multi-realm sync (a block must reach only its realm's
 * stream); per-realm pull is the next slice.
 */
class MultiRealmPushDrainTest {

    @Test
    fun tickOnce_drainsEachRealmToItsOwnRelay() = runTest {
        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry(), coroutineScope = this)
        proxy.schemaRegistry.registerConfig(syncServiceCommonModule)

        // Personal relay = the DI PushBlock op; capture what it receives.
        val personalPushed = mutableListOf<SyncBlock>()
        proxy.additionalImplementations[PushBlock.ID] = object : SyncOperationImplementation {
            override fun <T : Data?> execute(
                executionContext: ExecutionContext,
                operationContext: OperationContext?,
                implementationFactory: OperationImplementationFactory,
                caller: Any?,
                params: Map<String, Data>?,
            ): T {
                (params?.get(PushBlock.Params.BLOCK) as? SyncBlock)?.let { personalPushed += it }
                @Suppress("UNCHECKED_CAST")
                return null as T
            }
        }
        proxy.additionalImplementations[GetChanges.ID] = emptyChanges()
        proxy.additionalImplementations[GetTombstones.ID] = emptyTombstones()

        val crypto = proxy.tContext.syncCryptoManager()
        val syncManager = SyncManagerImpl(
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate(),
            context = proxy.tContext,
        )
        syncManager.initialize("personal phrase placeholder words go here now")
        val personalOwner = syncManager.currentOwnerId!!

        val components = proxy.executionContext.initSyncService(
            syncManager,
            crypto,
            // This test steps the scheduler by hand, which is what `tickOnce` is `internal` *for*
            // ("without the polling loop"), so the loops are never started rather than started and
            // then stopped — a loop that never starts has no first tick to race
            // (`SyncHealthReportingTest.fixture`). Two concurrent drains over the unsynchronised
            // `InMemoryObjectStore` are the documented `IllegalStateException: Entry not found`
            // (docs/tech-debt.md § "The full gate is flaky under parallel load"); hygiene here —
            // the forced pair stayed green in both arms (2026-09-03), so the flake is not
            // reproduced, only the shape that could produce it is gone.
            startBackgroundLoops = false,
            platformScheduler = NoOpPlatformScheduler,
        )

        // Register a shared realm with ITS OWN relay.
        val sharedRelay = MockSyncRelay(proxy.tContext)
        val sharedRealm = RealmContext(
            ownerId = "shared-1",
            deviceId = syncManager.currentDeviceId,
            derivedKeys = SyncCryptoManager.DerivedKeys(
                sharedIdentityKey = ByteArray(32) { 0x33 },
                sharedVolumeKey = ByteArray(32) { 0x44 },
            ),
            deviceSigningKeyPair = SyncCryptoManager.KeyPair(ByteArray(32) { 9 }, ByteArray(32) { 9 }),
            masterKeyPair = null,
        )
        components.realmRegistry.register(sharedRealm, sharedRelay)

        // Enqueue a personal block (a saved Syncable entity, async Facet-A emit)…
        val entity = Entity.create<SyntheticTestData>(
            context = proxy.tContext,
            composite = SyntheticTestData.Factory.create(proxy.tContext, name = "personal.txt", value = 1L),
            properties = emptyMap(),
        )
        proxy.executionContext.saveData(entity)
        components.syncObserver.awaitEmitIdle()

        // …and a shared-realm block (a "shared-1::…"-keyed target → shared queue).
        components.syncObserver.emitPropertyBlock(
            targetEntityId = DataId.StringDataId.create(proxy.tContext, "test/entity", "shared-1$NAMESPACE_SEP/x"),
            propertyId = "test/prop",
            value = "shared-payload".toTString(proxy.tContext),
        )

        components.scheduler.tickOnce()

        // Personal relay drained the personal realm's blocks — ALL of them stamped
        // with the personal owner, and crucially NONE the shared realm's (a saved
        // Syncable entity may emit >1 block: entity + Syncable property).
        assertTrue(personalPushed.isNotEmpty(), "personal queue drains to the personal relay")
        assertTrue(
            personalPushed.all { it.ownerId.get() == personalOwner },
            "the shared block must not reach the personal relay (no cross-realm leak)",
        )

        // Shared relay got exactly the shared realm's one block.
        val sharedReceived = sharedRelay.pushedBlocks()
        assertEquals(1, sharedReceived.size, "shared queue drains to the shared relay")
        assertEquals("shared-1", sharedReceived.single().ownerId.get())
        assertTrue(
            sharedReceived.none { it.ownerId.get() == personalOwner },
            "the personal block must not reach the shared relay",
        )
    }

    /**
     * A shared realm unregistered mid-drain — "Forget this device" (§7i.5) — pushes nothing more
     * under the identity it was registered with.
     */
    @Test
    fun aSharedRealmUnregisteredMidDrain_pushesNoMore() = runTest {
        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry(), coroutineScope = this)
        proxy.schemaRegistry.registerConfig(syncServiceCommonModule)
        proxy.additionalImplementations[GetChanges.ID] = emptyChanges()
        proxy.additionalImplementations[GetTombstones.ID] = emptyTombstones()
        val crypto = proxy.tContext.syncCryptoManager()
        val syncManager = SyncManagerImpl(crypto, InMemorySecureStorageDelegate(), proxy.tContext)
        syncManager.initialize("personal phrase placeholder words go here now")
        val components = proxy.executionContext.initSyncService(
            syncManager, crypto, startBackgroundLoops = false, platformScheduler = NoOpPlatformScheduler,
        )
        val delegate = MockSyncRelay(proxy.tContext)
        var pushes = 0
        val forgettingRelay = object : org.tenshu.sync.common.SyncRelay by delegate {
            override suspend fun pushBlock(
                block: SyncBlock,
                edges: List<org.tenshu.core.common.content.ContentEdge>,
            ): org.tenshu.core.TResult<Data?> {
                pushes++
                components.realmRegistry.unregisterAll() // the forget lands during the first push
                return delegate.pushBlock(block, edges)
            }
        }
        components.realmRegistry.register(
            RealmContext(
                ownerId = "shared-1",
                deviceId = syncManager.currentDeviceId,
                derivedKeys = SyncCryptoManager.DerivedKeys(ByteArray(32) { 0x33 }, ByteArray(32) { 0x44 }),
                deviceSigningKeyPair = SyncCryptoManager.KeyPair(ByteArray(32) { 9 }, ByteArray(32) { 9 }),
                masterKeyPair = null,
            ),
            forgettingRelay,
        )
        repeat(2) { n ->
            components.syncObserver.emitPropertyBlock(
                targetEntityId = DataId.StringDataId.create(proxy.tContext, "test/entity", "shared-1$NAMESPACE_SEP/x$n"),
                propertyId = "test/prop",
                value = "shared-payload-$n".toTString(proxy.tContext),
            )
        }

        components.scheduler.tickOnce()

        assertEquals(1, pushes, "the second queued block must not be pushed once its realm is forgotten")
    }

    private fun emptyChanges() = object : SyncOperationImplementation {
        override fun <T : Data?> execute(
            executionContext: ExecutionContext,
            operationContext: OperationContext?,
            implementationFactory: OperationImplementationFactory,
            caller: Any?,
            params: Map<String, Data>?,
        ): T {
            @Suppress("UNCHECKED_CAST")
            return ChangesResponse.create(
                executionContext.context, emptyList(), emptyList(), 0L
            ) as T
        }
    }

    private fun emptyTombstones() = object : SyncOperationImplementation {
        override fun <T : Data?> execute(
            executionContext: ExecutionContext,
            operationContext: OperationContext?,
            implementationFactory: OperationImplementationFactory,
            caller: Any?,
            params: Map<String, Data>?,
        ): T {
            @Suppress("UNCHECKED_CAST")
            return TombstonesResponse.create(
                executionContext.context, emptyList(), 0L
            ) as T
        }
    }
}
