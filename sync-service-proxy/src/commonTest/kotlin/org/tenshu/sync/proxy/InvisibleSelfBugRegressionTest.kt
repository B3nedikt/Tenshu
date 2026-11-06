package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.security.BootstrapSyncCryptoManager
import org.tenshu.core.entity.DataId
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.core.toTByteArray
import org.tenshu.core.toTString
import org.tenshu.identity.Device
import org.tenshu.identity.IdentityService
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.identity.proxy.initIdentityService
import org.tenshu.sync.CreateRealm
import org.tenshu.sync.GetPairingStatus
import org.tenshu.sync.RecoveryClaim
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.sync.proxy.impl.CreateRealmImplementation
import org.tenshu.sync.proxy.impl.GetPairingStatusImplementation
import org.tenshu.sync.proxy.impl.RecoveryClaimImplementation
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Regression test for the "Invisible Self" bug from `docs/adr/0007-sync-device-management.md`:
 *
 * > Logic bugs. It produces the "Invisible Self" bug — a device can't see itself in the
 * > device list until another device approves it, because the source of truth is server-
 * > side and the server has no notion of "self".
 *
 * The fix (per device-mgmt §2.4 step 5 + §2.3) is that the new device pushes its own
 * `Device` entity into the synced DB at the end of admission. This test asserts that
 * **all three admission paths** — first-device bootstrap, recovery, and pairing — leave
 * the device immediately visible in the local `IdentityService.Relations.DEVICES`
 * relation array (the same path `GetDevices` reads).
 */
class InvisibleSelfBugRegressionTest {

    private lateinit var testProxy: TestProxy
    private lateinit var syncManager: SyncManagerImpl
    private lateinit var crypto: SyncCryptoManager
    private lateinit var relay: MockSyncRelay

    @BeforeTest
    fun setup() {
        testProxy = TestProxy(schemaRegistry = createCoreTestRegistry()).also {
            it.schemaRegistry.registerConfig(syncServiceCommonModule)
            it.schemaRegistry.registerConfig(identityServiceCommonModule)
        }
        crypto = BootstrapSyncCryptoManager()
        syncManager = SyncManagerImpl(
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate(),
            context = testProxy.tContext
        )
        // Mirror production wiring: the platform proxy modules call
        // `initIdentityService` to register the DataObserver that maintains the
        // DEVICES relation on every Device save. Without this, the operation impls
        // `saveData(device)` lands in the DB but never in the relation, so
        // `getDevices` returns nothing.
        testProxy.executionContext.initIdentityService()
        relay = MockSyncRelay(testProxy.tContext)
    }

    private fun devicesRelationRef(): ArrayDatabaseReference {
        // Same reference shape resolveServiceData builds inside GetDevicesImplementation —
        // we read directly to skip the operation-pipeline materialisation and assert the
        // store-level invariant.
        return ArrayDatabaseReference(
            arrayId = DataId.RelationDataId.create(
                context = testProxy.tContext,
                type = TList.ID,
                relationId = IdentityService.Relations.DEVICES
            ),
            isEntity = true,
            elementSchemaId = Device.ID
        )
    }

    /** Asserts the current device's entity is present in the local DEVICES relation. */
    private fun assertDeviceIsVisible(expectedAuthorized: Boolean, expectedName: String) {
        val devices = testProxy.executionContext.getArrayData(devicesRelationRef())
            ?.toList()
            ?: error("DEVICES relation should resolve")

        // The relation reads back Entity wrappers (isEntity=true on the reference). We
        // resolve the underlying Device fields via Entity.getFieldValue — the same path
        // the GetDevices client extension takes when materialising results.
        val self = devices
            .filterIsInstance<org.tenshu.core.entity.Entity<*>>()
            .find {
                it.getFieldValue<org.tenshu.core.TString>(Device.Fields.DEVICE_ID).get() ==
                        syncManager.currentDeviceId
            }

        assertNotNull(
            self,
            "Newly-admitted device must see its own Device entity in the relation array " +
                    "(the 'Invisible Self' bug regression)"
        )
        assertEquals(
            expectedAuthorized,
            self.getFieldValue<org.tenshu.core.TBoolean>(Device.Fields.IS_AUTHORIZED).get()
        )
        assertEquals(
            expectedName,
            self.getFieldValue<org.tenshu.core.TString>(Device.Fields.NAME).get()
        )
    }

    @Test
    fun firstDevice_visibleAfterCreateRealm() = runTest {
        val impl = CreateRealmImplementation(syncManager, crypto, relay)

        impl.execute<Data>(
            executionContext = testProxy.executionContext,
            operationContext = null,
            implementationFactory = OperationImplementationFactory { null },
            caller = null,
            params = mapOf(
                CreateRealm.Params.RECOVERY_PHRASE to "first-phrase".toTString(testProxy.tContext),
                CreateRealm.Params.DEVICE_NAME to "Founding Device".toTString(testProxy.tContext)
            )
        )

        // First-device bootstrap is master-self-signed → device admits itself.
        assertDeviceIsVisible(expectedAuthorized = true, expectedName = "Founding Device")
    }

    @Test
    fun recoveringDevice_visibleAfterRecoveryClaim() = runTest {
        val impl = RecoveryClaimImplementation(syncManager, crypto, relay)

        impl.execute<Data>(
            executionContext = testProxy.executionContext,
            operationContext = null,
            implementationFactory = OperationImplementationFactory { null },
            caller = null,
            params = mapOf(
                RecoveryClaim.Params.RECOVERY_PHRASE to org.tenshu.core.common.util.Bip39
                    .encode(ByteArray(16) { it.toByte() })
                    .toTString(testProxy.tContext),
                RecoveryClaim.Params.DEVICE_NAME to "Restored Phone".toTString(testProxy.tContext)
            )
        )

        // Recovery is master-self-delegated (challenge response) → device admits itself.
        assertDeviceIsVisible(expectedAuthorized = true, expectedName = "Restored Phone")
    }

    @Test
    fun newlyPairedDevice_visibleAfterPairingHandshakeCompletes() = runTest {
        // Simulate the pre-state of GetPairingStatus on Device B: SyncManager initialised
        // (master keys derived from the just-received recovery code… but in pairing the
        // master key arrives encrypted via MockSyncRelay; here we initialize directly to
        // model the post-decrypt state), handshake context registered, device name set.
        syncManager.initialize("paired-phrase")
        val myKeyPair = crypto.generateKeyPair()
        val secret = "pair-secret"
        syncManager.startHandshake(secret, myKeyPair, hostOwnerId = syncManager.currentOwnerId!!)
        syncManager.setCurrentDeviceName("Paired Tablet")

        // Pre-load the encrypted master key into the mock relay's response slot so
        // GetPairingStatus's poll succeeds.
        relay.postPairingResponse(
            pairingSecretHash = org.tenshu.sync.proxy.security.PairingRendezvous.joinSecretHash(secret),
            encryptedMasterKey = ByteArray(64) { (0x10 + it).toByte() }, // the 64-byte key-blob shape
            newDevicePublicKey = ByteArray(32) { 0x01 },
            newDeviceId = org.tenshu.sync.common.deviceIdForPublicKey(ByteArray(32) { 0x01 }),
            masterDelegation = byteArrayOf(0x02),
            epoch = 0L
        )

        val impl = GetPairingStatusImplementation(relay, syncManager, crypto)
        impl.execute<Data>(
            executionContext = testProxy.executionContext,
            operationContext = null,
            implementationFactory = OperationImplementationFactory { null },
            caller = null,
            params = mapOf(
                GetPairingStatus.Params.PAIRING_SECRET to secret.toTString(testProxy.tContext),
                GetPairingStatus.Params.OTHER_DEVICE_PUBLIC_KEY to
                        byteArrayOf(0x07, 0x07).toTByteArray(testProxy.tContext)
            )
        )

        // Pairing-admitted device self-pushes with `isAuthorized = true` — by
        // the time `GetPairingStatus` reaches the save, the relay has already
        // verified Device A's master delegation and served the master key, so
        // the joiner is authoritatively admitted. The "Invisible Self" fix is
        // that the device sees itself in the list IMMEDIATELY, and the
        // dashboard's `isAuthorized` filter no longer hides it for the
        // round-trip window before A's authoritative block lands.
        assertDeviceIsVisible(expectedAuthorized = true, expectedName = "Paired Tablet")
    }
}
