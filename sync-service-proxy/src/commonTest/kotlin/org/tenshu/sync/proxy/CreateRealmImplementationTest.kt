package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.GenericError
import org.tenshu.core.TResult
import org.tenshu.core.TenshuException
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.security.ownerIdForMasterPublicKey
import org.tenshu.core.core.TContext
import org.tenshu.core.entity.DataId
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.core.toTString
import org.tenshu.identity.Device
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.sync.CreateRealm
import org.tenshu.sync.PairingRequest
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.common.SyncRelay
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.sync.proxy.impl.CreateRealmImplementation
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CreateRealmImplementationTest {

    private lateinit var testProxy: TestProxy
    private lateinit var syncManager: SyncManagerImpl
    private lateinit var crypto: RecordingRealmCryptoManager
    private lateinit var relay: RecordingRealmRelay
    private lateinit var impl: CreateRealmImplementation

    @BeforeTest
    fun setup() {
        testProxy = TestProxy(schemaRegistry = createCoreTestRegistry()).also {
            it.schemaRegistry.registerConfig(syncServiceCommonModule)
            it.schemaRegistry.registerConfig(identityServiceCommonModule)
        }
        crypto = RecordingRealmCryptoManager()
        syncManager = SyncManagerImpl(
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate(),
            context = testProxy.tContext
        )
        relay = RecordingRealmRelay(testProxy.tContext)
        impl = CreateRealmImplementation(syncManager, crypto, relay)
    }

    private fun paramsFor(
        phrase: String,
        deviceName: String = "First Device"
    ): Map<String, Data> = mapOf(
        CreateRealm.Params.RECOVERY_PHRASE to phrase.toTString(testProxy.tContext),
        CreateRealm.Params.DEVICE_NAME to deviceName.toTString(testProxy.tContext)
    )

    @Test
    fun success_postsRealmWithDeterministicOwnerIdAndMasterSignature() = runTest {
        val phrase = "deterministic phrase"
        val expectedMasterPubKey = crypto.deriveMasterKeyPair(phrase).publicKey
        val expectedOwnerId = ownerIdForMasterPublicKey(expectedMasterPubKey)

        impl.execute<Data>(
            executionContext = testProxy.executionContext,
            operationContext = null,
            implementationFactory = OperationImplementationFactory { null },
            caller = null,
            params = paramsFor(phrase)
        )

        val posted =
            assertNotNull(relay.lastCreateRealm, "relay.createRealm should have been called")

        // ownerId is deterministically derived from the master public key.
        assertEquals(expectedOwnerId, posted.ownerId)
        assertContentEquals(expectedMasterPubKey, posted.masterPublicKey)

        // First device public key matches what SyncManager exposes (asset A4).
        val devicePubKey = syncManager.getDeviceSigningKeyPair().publicKey
        assertContentEquals(devicePubKey, posted.firstDevicePublicKey)
        assertEquals(syncManager.currentDeviceId, posted.firstDeviceId)

        // The signature is over the canonical bootstrap message — protocol contract with
        // the relay's MasterDelegationVerifier.realmBootstrapMessage.
        val expectedMessage = org.tenshu.sync.common.realmBootstrapMessage(
            expectedOwnerId, expectedMasterPubKey, devicePubKey, syncManager.currentDeviceId,
        )
        val expectedSignature = org.tenshu.core.security.stubEd25519Signature(
            crypto.deriveMasterKeyPair(phrase).privateKey,
            expectedMessage
        )
        assertContentEquals(expectedSignature, posted.masterSelfSignature)

        // Per docs/adr/0007-sync-device-management.md first-device step 4, the bootstrapping
        // device pushes its own Device entity. Same self-admitted trust model as
        // recovery: the device holds the master identity, so it admits itself
        // (`isAuthorized = true`) — no other device exists yet to delegate from.
        val ownDeviceId = DataId.StringDataId.create(
            context = testProxy.tContext,
            type = Device.ID,
            keyValue = syncManager.currentDeviceId
        )
        val savedDevice = testProxy.database.getData(ownDeviceId) as? Device
        assertNotNull(savedDevice, "First device should have pushed its own Device entity")
        assertEquals(true, savedDevice.isAuthorized.get(), "Bootstrap is master-self-delegated")
        assertEquals("First Device", savedDevice.name.get())
        assertContentEquals(devicePubKey, savedDevice.publicKey.get())
    }

    @Test
    fun relayFailure_propagates_butDeviceEntityStillSavedLocally() = runTest {
        relay.failNextCall = true

        assertFailsWith<TenshuException> {
            impl.execute<Data>(
                executionContext = testProxy.executionContext,
                operationContext = null,
                implementationFactory = OperationImplementationFactory { null },
                caller = null,
                params = paramsFor("phrase")
            )
        }

        // The local Device push is unconditional — the local realm is deterministically
        // consistent with the master key derivation (ownerId is derived from the master
        // pubkey), so the Device entity is locally correct regardless of relay state.
        // A retry of `createRealm` will eventually succeed against the relay (or 409 if
        // a previous attempt registered, which is semantically equivalent). Without
        // this unconditional push, an offline first-launch leaves the user with a sync
        // dashboard that shows zero devices and silently masks that local state is fine.
        val ownDeviceId = DataId.StringDataId.create(
            context = testProxy.tContext,
            type = Device.ID,
            keyValue = syncManager.currentDeviceId
        )
        val savedDevice = testProxy.database.getData(ownDeviceId) as? Device
        assertNotNull(
            savedDevice,
            "Device entity should be locally saved even when the relay call fails"
        )
        assertEquals(true, savedDevice.isAuthorized.get())
    }

    @Test
    fun initializesSyncManager_ifNotAlreadyInitialized() = runTest {
        assertEquals(false, syncManager.isEnabled())

        impl.execute<Data>(
            executionContext = testProxy.executionContext,
            operationContext = null,
            implementationFactory = OperationImplementationFactory { null },
            caller = null,
            params = paramsFor("setup-phrase")
        )

        assertEquals(true, syncManager.isEnabled())
    }
}

// Stub-level crypto manager that exercises deriveMasterKeyPair + signEd25519. Mirrors the
// pattern used elsewhere — public == private so the same key is used for sign and verify.
private class RecordingRealmCryptoManager : SyncCryptoManager {

    override fun deriveKeys(recoveryPhrase: String): SyncCryptoManager.DerivedKeys {
        val bytes = recoveryPhrase.encodeToByteArray()
        return SyncCryptoManager.DerivedKeys(bytes, bytes.reversedArray())
    }

    override fun encrypt(payload: ByteArray, key: ByteArray): ByteArray = payload
    override fun decrypt(encryptedPayload: ByteArray, key: ByteArray): ByteArray = encryptedPayload

    override fun generateKeyPair(): SyncCryptoManager.KeyPair {
        // Distinct stable bytes so device-pubkey != master-pubkey in assertions.
        val pub = byteArrayOf(0x42, 0x43, 0x44)
        return SyncCryptoManager.KeyPair(publicKey = pub, privateKey = pub)
    }

    override fun calculateSharedSecret(
        privateKey: ByteArray,
        otherPublicKey: ByteArray
    ): ByteArray = privateKey + otherPublicKey

    override fun deriveMasterKeyPair(recoveryPhrase: String): SyncCryptoManager.KeyPair {
        val seed = org.tenshu.core.security.stubEd25519Signature(
            "tenshu-master-key-stub".encodeToByteArray(),
            recoveryPhrase.encodeToByteArray()
        )
        return SyncCryptoManager.KeyPair(seed, seed)
    }

    override fun signEd25519(privateKey: ByteArray, message: ByteArray): ByteArray =
        org.tenshu.core.security.stubEd25519Signature(privateKey, message)

    override fun verifyEd25519(
        publicKey: ByteArray,
        message: ByteArray,
        signature: ByteArray
    ): Boolean =
        signature.contentEquals(org.tenshu.core.security.stubEd25519Signature(publicKey, message))
}

// Records calls to createRealm; other SyncRelay methods are not exercised here.
private class RecordingRealmRelay(private val context: TContext) : SyncRelay {

    data class CreateRealmCall(
        val ownerId: String,
        val masterPublicKey: ByteArray,
        val firstDevicePublicKey: ByteArray,
        val firstDeviceId: String,
        val masterSelfSignature: ByteArray
    )

    var lastCreateRealm: CreateRealmCall? = null
    var failNextCall: Boolean = false

    override suspend fun createRealm(
        ownerId: String,
        masterPublicKey: ByteArray,
        firstDevicePublicKey: ByteArray,
        firstDeviceId: String,
        masterSelfSignature: ByteArray,
        admittedAtHlc: String?,
    ): TResult<Data?> {
        lastCreateRealm = CreateRealmCall(
            ownerId,
            masterPublicKey,
            firstDevicePublicKey,
            firstDeviceId,
            masterSelfSignature
        )
        return if (failNextCall) {
            TResult.Failure.create(context, GenericError.create(context, "simulated relay failure"))
        } else {
            TResult.Success.create(context, null)
        }
    }

    // Methods below are not exercised by CreateRealmImplementationTest.
    override suspend fun pushBlock(block: SyncBlock, edges: List<org.tenshu.core.common.content.ContentEdge>): TResult<Data?> = error("not used")
    override suspend fun getChanges(
        sinceTimestamp: Long,
        tip: org.tenshu.sync.SignedHlcTip
    ): TResult<org.tenshu.sync.ChangesResponse> = error("not used")

    override suspend fun pushTombstone(block: org.tenshu.sync.DeleteBlock, cascade: List<String>): TResult<Data?> =
        error("not used")

    override suspend fun getTombstones(sinceTimestamp: Long): TResult<org.tenshu.sync.TombstonesResponse> =
        error("not used")

    override suspend fun fetchEntityBlocks(entityWireId: String): TResult<TList<SyncBlock>> = error("not used")

    override suspend fun postPairingRequest(
        ownerId: String,
        pairingSecretHash: String,
        deviceId: String,
        sealedDeviceName: ByteArray,
        publicKey: ByteArray,
        signingPublicKey: ByteArray
    ): TResult<Data?> = error("not used")

    override suspend fun getPairingRequests(ownerId: String): SyncRelay.PairingRequestsFetch =
        error("not used")

    override suspend fun postPairingResponse(
        pairingSecret: String,
        encryptedMasterKey: ByteArray,
        newDevicePublicKey: ByteArray,
        newDeviceId: String,
        masterDelegation: ByteArray,
        epoch: Long,
        admissionCertChain: ByteArray?,
        admittedAtHlc: String?,
    ): TResult<Data?> = error("not used")

    override suspend fun ackPairingResponse(pairingSecret: String): TResult<Data?> =
        error("not used")

    override suspend fun getPairingResponse(pairingSecret: String): TResult<Data?> =
        error("not used")

    override suspend fun recoveryChallenge(): TResult<org.tenshu.core.TByteArray> = error("not used")

    override suspend fun recoveryClaim(
        masterPublicKey: ByteArray,
        newDevicePublicKey: ByteArray,
        newDeviceId: String,
        challenge: ByteArray,
        masterSignature: ByteArray
    ): TResult<Data?> = error("not used")

    override suspend fun fetchRealmEpoch(
        ownerId: String
    ): TResult<org.tenshu.core.number.TInteger> = error("not used")
}
