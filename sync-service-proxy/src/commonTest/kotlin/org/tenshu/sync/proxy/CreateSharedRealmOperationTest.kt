package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.TString
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.sync.ChangesResponse
import org.tenshu.sync.CreateSharedRealm
import org.tenshu.sync.GetChanges
import org.tenshu.sync.GetTombstones
import org.tenshu.sync.TombstonesResponse
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.identity.common.identityServiceCommonModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SR-3b.3: the `CreateSharedRealm` SDK op. Proves (a) the factory only exposes the
 * op when a `RealmRegistry` is wired, and (b) invoking it creates + registers a
 * shared realm (the bootstrapper's behaviour, reached through the op + execution
 * path) and returns the new realm's ownerId.
 */
class CreateSharedRealmOperationTest {

    private val phrase = "personal phrase placeholder words go here now"

    @Test
    fun factory_exposesCreateSharedRealm_onlyWhenRealmRegistryWired() {
        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        proxy.schemaRegistry.registerConfig(syncServiceCommonModule)
        val crypto = proxy.tContext.syncCryptoManager()
        val syncManager = SyncManagerImpl(crypto, InMemorySecureStorageDelegate(), proxy.tContext)
        val relay = MockSyncRelay(proxy.tContext)

        assertNull(
            syncProxyImplementationFactory(CreateSharedRealm.ID, relay, syncManager, crypto),
            "CreateSharedRealm is unavailable without a RealmRegistry",
        )
        assertNotNull(
            syncProxyImplementationFactory(
                CreateSharedRealm.ID, relay, syncManager, crypto,
                realmRegistry = RealmRegistry(syncManager),
            ),
            "CreateSharedRealm is available once a RealmRegistry is wired",
        )
    }

    @Test
    fun op_createsAndRegistersSharedRealm() = runTest {
        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry(), coroutineScope = this)
        proxy.schemaRegistry.registerConfig(syncServiceCommonModule)
        proxy.schemaRegistry.registerConfig(identityServiceCommonModule)
        proxy.additionalImplementations[GetChanges.ID] = emptyChanges()
        proxy.additionalImplementations[GetTombstones.ID] = emptyTombstones()

        val crypto = proxy.tContext.syncCryptoManager()
        val syncManager = SyncManagerImpl(crypto, InMemorySecureStorageDelegate(), proxy.tContext)
        syncManager.initialize(phrase)
        val personalOwner = syncManager.currentOwnerId!!

        val components = proxy.executionContext.initSyncService(
            syncManager,
            crypto,
            // Nothing here steps or waits on the loops, so they are never started — a loop that
            // never starts has no first tick to race (`SyncHealthReportingTest.fixture`).
            startBackgroundLoops = false,
            platformScheduler = NoOpPlatformScheduler,
        )
        val relay = MockSyncRelay(proxy.tContext)

        // Register the op through the production factory path (realmRegistry threaded).
        proxy.additionalImplementations[CreateSharedRealm.ID] = syncProxyImplementationFactory(
            CreateSharedRealm.ID, relay, syncManager, crypto,
            realmRegistry = components.realmRegistry,
        )!!

        val result = proxy.executionContext.executeAsync<Data>(
            operationId = CreateSharedRealm.ID,
            caller = null,
            params = emptyMap(),
        )

        val ownerId = (result as? TString)?.get()
        assertNotNull(ownerId, "op returns the new shared realm's ownerId")
        assertTrue(ownerId != personalOwner, "shared realm has a distinct ownerId")
        assertTrue(relay.createdRealms().contains(ownerId), "the realm was bootstrapped at the relay")
        assertTrue(
            components.realmRegistry.sharedRealms().any { it.ownerId == ownerId },
            "the shared realm is registered",
        )
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
            return ChangesResponse.create(executionContext.context, emptyList(), emptyList(), 0L) as T
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
            return TombstonesResponse.create(executionContext.context, emptyList(), 0L) as T
        }
    }
}
