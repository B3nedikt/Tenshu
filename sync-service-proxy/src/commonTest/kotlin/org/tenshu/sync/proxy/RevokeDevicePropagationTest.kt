package org.tenshu.sync.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.createTestContext
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.security.BootstrapSyncCryptoManager
import org.tenshu.core.entity.DataId
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.core.toTString
import org.tenshu.identity.Device
import org.tenshu.identity.IdentityService
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.sync.RevokeDevice
import org.tenshu.sync.common.SyncManager
import org.tenshu.sync.common.SyncRelay
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.sync.proxy.impl.RevokeDeviceImplementation
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * End-to-end regression for the revoke→DeleteBlock→propagate→ingest chain across two
 * proxies sharing a single relay, per `docs/adr/0007-sync-device-management.md` +
 * `docs/adr/0006-sync-tombstones.md`
 *
 * Companion to [RevokeDeviceImplementationTest] (single-proxy, asserts emit) and
 * [SyncProcessorTombstoneTest] (single-proxy, asserts ingest). This one wires the full
 * `SyncScheduler` drain/pull cycle through `MockSyncRelay` so the wire layer added in
 * `docs/adr/0006-sync-tombstones.md` Phase 3 (`PushTombstone` + `GetTombstones`) is exercised
 * end-to-end. Without this test, those two ops + the scheduler additions could regress
 * silently — both companion test files would still pass while cross-device revoke broke.
 */
class RevokeDevicePropagationTest {

    @Test
    fun revoke_propagatesTombstone_andRemovesEntityOnPeer() = runTest {
        val serverRegistry = createCoreTestRegistry().apply {
            registerConfig(syncServiceCommonModule)
            registerConfig(identityServiceCommonModule)
        }
        val relay = MockSyncRelay(createTestContext(serverRegistry))

        val (proxyA, _, componentsA) = setupProxy(relay, backgroundScope)
        val (proxyB, _, _) = setupProxy(relay, backgroundScope)

        // Seed the victim Device on Proxy A. SyncObserver picks it up and the scheduler
        // propagates it to Proxy B via the existing SyncBlock path.
        val victimId = "victim-device"
        val victimDevice = Device.create(
            context = proxyA.tContext,
            deviceId = victimId,
            name = "Old Tablet",
            publicKey = byteArrayOf(0x01),
            isAuthorized = true
        )
        proxyA.executionContext.saveData(victimDevice)
        proxyA.executionContext.database.addToArray(devicesRelationRef(proxyA), victimDevice)
        val victimDataId = DataId.StringDataId.create(proxyA.tContext, Device.ID, victimId)

        // Wait for Proxy B to receive the seed via SyncBlock propagation.
        var seenOnB: Device? = null
        repeat(40) {
            seenOnB = proxyB.database.getData(victimDataId) as? Device
            if (seenOnB != null) return@repeat
            delay(100)
        }
        assertNotNull(seenOnB, "Pre-condition: Proxy B should observe the seeded Device via sync")

        // Revoke on A through the real impl + the SyncObserver-backed TombstoneEmitter
        // from initSyncService. Emit lands in PENDING_TOMBSTONES; the scheduler drains
        // it to the relay via PushTombstone.
        val revokeImpl = RevokeDeviceImplementation(componentsA.tombstoneEmitter)
        revokeImpl.execute<Data>(
            executionContext = proxyA.executionContext,
            operationContext = null,
            implementationFactory = { null },
            caller = null,
            params = mapOf(RevokeDevice.Params.DEVICE_ID to victimId.toTString(proxyA.tContext))
        )

        // Wait for Proxy B's scheduler to pull the tombstone and ingest it via
        // SyncProcessor.processDeleteBlock. The entity disappears from B's local DB.
        var stillOnB: Device? = seenOnB
        repeat(60) {
            stillOnB = proxyB.database.getData(victimDataId) as? Device
            if (stillOnB == null) return@repeat
            delay(100)
        }
        assertNull(
            stillOnB,
            "Proxy B should have deleted the victim Device after ingesting the tombstone"
        )
    }

    private fun setupProxy(
        relay: MockSyncRelay,
        scope: CoroutineScope
    ): Triple<TestProxy, SyncManager, SyncProxyComponents> {
        lateinit var syncManager: SyncManager
        lateinit var crypto: SyncCryptoManager
        val proxy = TestProxy(
            schemaRegistry = createCoreTestRegistry().also {
                it.registerConfig(syncServiceCommonModule)
                it.registerConfig(identityServiceCommonModule)
            },
            additionalComponents = mapOf(SyncRelay.ID to relay),
            coroutineScope = scope
        ) { id, _ ->
            syncProxyImplementationFactory(id, relay, syncManager, crypto)
        }
        crypto = BootstrapSyncCryptoManager()
        syncManager = SyncManagerImpl(
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate(),
            context = proxy.tContext
        )
        syncManager.initialize("shared-realm-phrase")
        val components = proxy.executionContext.initSyncService(
            syncManager = syncManager,
            cryptoManager = crypto,
            coroutineScope = scope,
            pollingIntervalMs = 100,
            platformScheduler = NoOpPlatformScheduler,
        )
        return Triple(proxy, syncManager, components)
    }

    private fun devicesRelationRef(proxy: TestProxy): ArrayDatabaseReference =
        ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = proxy.tContext,
                type = TList.ID,
                relationId = IdentityService.Relations.DEVICES
            ),
            isEntity = true,
            elementSchemaId = Device.ID
        )
}
