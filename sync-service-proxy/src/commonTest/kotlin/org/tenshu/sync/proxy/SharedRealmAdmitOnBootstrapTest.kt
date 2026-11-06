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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SR-3b.4c: on shared-realm creation the bootstrapper admits the user's *other* devices to
 * the new realm at the relay (so their JWTs verify on the shared stream once they auto-join),
 * and does NOT re-admit the creating device (already registered by `createRealm`). Asserted
 * in-process by seeding the personal realm's `IdentityService.Relations.DEVICES` relation and
 * capturing what reaches the relay via [MockSyncRelay.admittedDevices].
 */
class SharedRealmAdmitOnBootstrapTest {

    private val phrase = "personal phrase placeholder words go here now"

    @Test
    fun createSharedRealm_admitsOtherDevices_butNotSelf() = runTest {
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

        // Seed the DEVICES relation: this device + one peer (as the personal realm would
        // hold after the peer paired and its Device entity synced in).
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
        // ⚠ A self-written row claiming another id beside its own key: skipped by the client, never
        // sent — A1 must not sign a delegation that re-keys someone else's id (workstream B).
        proxy.executionContext.database.addToArray(
            devicesRef,
            deviceEntity(proxy, org.tenshu.sync.common.deviceIdForPublicKey(ByteArray(32) { 0x5D }), ByteArray(32) { 0x3C }),
        )

        val realm = SharedRealmBootstrapper(
            executionContext = proxy.executionContext,
            syncManager = syncManager,
            cryptoManager = crypto,
            relay = relay,
            realmRegistry = components.realmRegistry,
        ).createSharedRealm()

        val admitted = relay.admittedDevices()
        assertEquals(1, admitted.size, "only the non-self device is admitted")
        val (ownerId, deviceId, pubkey) = admitted.single()
        assertEquals(realm.ownerId, ownerId, "admitted to the shared realm")
        assertEquals(peerDeviceId, deviceId)
        assertContentEquals(peerPublicKey, pubkey)
        assertTrue(admitted.none { it.second == selfDeviceId }, "the creating device is not re-admitted")
        assertTrue(
            relay.refusedUnderivedIds().isEmpty(),
            "the forged row is skipped by the client, never sent for the relay to refuse",
        )
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
