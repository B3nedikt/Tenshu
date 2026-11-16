package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.collection.list.TList
import org.tenshu.core.entity.DataId
import org.tenshu.core.entity.Entity
import org.tenshu.core.security.syncCryptoManager
import org.tenshu.identity.Device
import org.tenshu.identity.IdentityService
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.sync.ChangesResponse
import org.tenshu.sync.GetChanges
import org.tenshu.sync.GetTombstones
import org.tenshu.sync.TombstonesResponse
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.core.Data
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.core.common.proxy.OperationContext
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.proxy.executor.SyncOperationImplementation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * D10 (`shared-realm-read-only-members.md`; threat model T-GR-05): creating a shared realm registers
 * the creating device under a **fresh per-realm key and realm-local id**, and admits **none** of the
 * user's other devices from here — admitting them used to register their personal A4 keys under the
 * share, so every id inside it was a personal device id. Each other device admits its own per-realm
 * key when it joins (`SharedRealmJoinObserver`, pinned by `SharedRealmDeviceIdentityTest`).
 */
class SharedRealmAdmitOnBootstrapTest {

    private val phrase = "personal phrase placeholder words go here now"

    @Test
    fun createSharedRealm_registersAPerRealmDevice_andAdmitsNoPersonalKeys() = runTest {
        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry(), coroutineScope = this)
        proxy.schemaRegistry.registerConfig(syncServiceCommonModule)
        proxy.schemaRegistry.registerConfig(identityServiceCommonModule)
        proxy.additionalImplementations[GetChanges.ID] = emptyChanges()
        proxy.additionalImplementations[GetTombstones.ID] = emptyTombstones()

        val crypto = proxy.tContext.syncCryptoManager()
        val syncManager = SyncManagerImpl(crypto, InMemorySecureStorageDelegate(), proxy.tContext)
        syncManager.initialize(phrase)
        val selfDeviceId = syncManager.currentDeviceId

        val components = proxy.executionContext.initSyncService(
            syncManager,
            crypto,
            // Nothing here steps or waits on the loops, so they are never started — a loop that
            // never starts has no first tick to race (`SyncHealthReportingTest.fixture`).
            startBackgroundLoops = false,
            platformScheduler = NoOpPlatformScheduler,
        )
        val relay = MockSyncRelay(proxy.tContext)

        // Seed the DEVICES relation with this device and a peer, as the personal realm would hold.
        val peerPublicKey = ByteArray(32) { 0x7A }
        val peerDeviceId = org.tenshu.sync.common.deviceIdForPublicKey(peerPublicKey)
        val devicesRef = ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = proxy.tContext,
                type = TList.ID,
                relationId = IdentityService.Relations.DEVICES,
            ),
            isEntity = true,
            elementSchemaId = Device.ID,
        )
        proxy.executionContext.database.addToArray(devicesRef, deviceEntity(proxy, selfDeviceId, ByteArray(32) { 1 }))
        proxy.executionContext.database.addToArray(devicesRef, deviceEntity(proxy, peerDeviceId, peerPublicKey))

        val realm = SharedRealmBootstrapper(
            executionContext = proxy.executionContext,
            syncManager = syncManager,
            cryptoManager = crypto,
            relay = relay,
            realmRegistry = components.realmRegistry,
        ).createSharedRealm()

        val (createdOwner, firstDeviceId, firstDeviceKey) = relay.createdRealmFirstDevices().single()
        assertEquals(realm.ownerId, createdOwner)
        assertTrue(firstDeviceId != selfDeviceId, "⚠⚠ the share's first device must not be the personal device id")
        assertFalse(
            firstDeviceKey.contentEquals(syncManager.getDeviceSigningKeyPair().publicKey),
            "nor the personal A4",
        )
        assertEquals(org.tenshu.sync.common.deviceIdForPublicKey(firstDeviceKey), firstDeviceId, "a realm-local id derived from the per-realm key")
        assertEquals(firstDeviceId, realm.deviceId, "the registered context carries the realm-local id")
        assertTrue(relay.admittedDevices().isEmpty(), "no other device is admitted from here — not under its personal key, not at all")
        assertTrue(relay.admittedDevices().none { it.second == peerDeviceId })
    }

    private fun deviceEntity(proxy: TestProxy, deviceId: String, publicKey: ByteArray): Entity<Device> =
        Entity.create(
            context = proxy.tContext,
            composite = Device.create(
                context = proxy.tContext,
                deviceId = deviceId,
                name = deviceId,
                publicKey = publicKey,
            ),
            properties = emptyMap(),
        )

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
