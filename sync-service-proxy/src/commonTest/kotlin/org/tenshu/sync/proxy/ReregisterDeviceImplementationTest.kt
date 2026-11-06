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
import org.tenshu.sync.PairingRequest
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.common.SyncRelay
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.sync.proxy.impl.ReregisterDeviceImplementation
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.tenshu.core.common.util.decodeHlcStamp
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for `ReregisterDevice` — re-asserting a device's relay registration after the
 * relay forgot it (e.g. an in-memory relay restart). Owner path = re-bootstrap via
 * `/sync/realm`, with `/sync/admit-device` fallback when the realm record already
 * exists; joiner path = fail loudly (no master identity to self-reregister).
 */
class ReregisterDeviceImplementationTest {

    private lateinit var testProxy: TestProxy
    private lateinit var syncManager: SyncManagerImpl
    private lateinit var crypto: StubCryptoManager
    private lateinit var relay: ReregisterRecordingRelay
    private lateinit var impl: ReregisterDeviceImplementation

    @BeforeTest
    fun setup() {
        testProxy = TestProxy(schemaRegistry = createCoreTestRegistry()).also {
            it.schemaRegistry.registerConfig(syncServiceCommonModule)
        }
        crypto = StubCryptoManager()
        syncManager = SyncManagerImpl(
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate(),
            context = testProxy.tContext
        )
        relay = ReregisterRecordingRelay(testProxy.tContext)
        impl = ReregisterDeviceImplementation(syncManager, crypto, relay)
    }

    private suspend fun run() = impl.execute<Data>(
        executionContext = testProxy.executionContext,
        operationContext = null,
        implementationFactory = OperationImplementationFactory { null },
        caller = null,
        params = null
    )

    @Test
    fun owner_reBootstrapsRealm_viaCreateRealm() = runTest {
        syncManager.initialize("owner phrase")
        val masterPub = crypto.deriveMasterKeyPair("owner phrase").publicKey
        val expectedOwnerId = ownerIdForMasterPublicKey(masterPub)

        run()

        val call = assertNotNull(relay.lastCreateRealm, "createRealm should be called")
        assertEquals(expectedOwnerId, call.ownerId)
        assertContentEquals(masterPub, call.masterPublicKey)
        assertEquals(syncManager.currentDeviceId, call.firstDeviceId)
        assertContentEquals(
            syncManager.getDeviceSigningKeyPair().publicKey,
            call.firstDevicePublicKey
        )
        assertTrue(
            crypto.verifyEd25519(masterPub, call.bootstrapMessage(), call.masterSelfSignature),
            "master self-signature must verify against the master public key"
        )
        assertNull(relay.lastAdmit, "admitDevice should not be called when createRealm succeeds")
        // Re-registration after a relay wipe is the exact window the stale tombstone exploited:
        // this device re-admits itself, and the relay needs an admission instant to compare a
        // queued "retire this device" against. No stamp here means the guard is inert precisely
        // where the incident happened.
        val stamp = assertNotNull(
            decodeHlcStamp(relay.lastCreateRealmHlc),
            "re-bootstrap must carry an admission stamp"
        )
        assertEquals(syncManager.hlcManager.deviceId, stamp.deviceId)
    }

    @Test
    fun owner_fallsBackToAdmitDevice_whenRealmAlreadyExists() = runTest {
        syncManager.initialize("owner phrase")
        relay.failCreateRealm = true // simulate 409 — another device recreated the realm first

        run()

        val admit = assertNotNull(relay.lastAdmit, "admitDevice should be the fallback")
        assertEquals(ownerIdForMasterPublicKey(crypto.deriveMasterKeyPair("owner phrase").publicKey), admit.ownerId)
        assertEquals(syncManager.currentDeviceId, admit.newDeviceId)
        assertContentEquals(syncManager.getDeviceSigningKeyPair().publicKey, admit.newDevicePublicKey)
        val stamp = assertNotNull(
            decodeHlcStamp(relay.lastAdmitHlc),
            "the admitDevice fallback must carry an admission stamp too"
        )
        assertEquals(syncManager.hlcManager.deviceId, stamp.deviceId)
    }

    @Test
    fun bothRelayCallsFail_propagatesFailure() = runTest {
        syncManager.initialize("owner phrase")
        relay.failCreateRealm = true
        relay.failAdmit = true

        assertFailsWith<TenshuException> { run() }
    }

    @Test
    fun joinerWithoutMasterKey_failsLoudly_withNoRelayCalls() = runTest {
        // Joiner path: derivedKeys + ownerId, but no master key and no recovery phrase.
        syncManager.initializeWithKeys(
            keys = crypto.deriveKeys("joiner volume"),
            ownerId = "owner-xyz",
            realmEncryptionKeyPair = null,
        )
        assertNull(syncManager.currentMasterKeyPair)

        assertFailsWith<TenshuException> { run() }
        assertNull(relay.lastCreateRealm, "a joiner must not attempt realm bootstrap")
        assertNull(relay.lastAdmit, "a joiner must not attempt admit")
    }
}

// Public == private so the same bytes sign + verify; mirrors the stub used elsewhere.
private class StubCryptoManager : SyncCryptoManager {
    override fun deriveKeys(recoveryPhrase: String): SyncCryptoManager.DerivedKeys {
        val bytes = recoveryPhrase.encodeToByteArray()
        return SyncCryptoManager.DerivedKeys(bytes, bytes.reversedArray())
    }

    override fun encrypt(payload: ByteArray, key: ByteArray): ByteArray = payload
    override fun decrypt(encryptedPayload: ByteArray, key: ByteArray): ByteArray = encryptedPayload

    override fun generateKeyPair(): SyncCryptoManager.KeyPair {
        val pub = ByteArray(32) { (0x42 + it).toByte() }
        return SyncCryptoManager.KeyPair(publicKey = pub, privateKey = pub)
    }

    override fun calculateSharedSecret(privateKey: ByteArray, otherPublicKey: ByteArray): ByteArray =
        privateKey + otherPublicKey

    override fun deriveMasterKeyPair(recoveryPhrase: String): SyncCryptoManager.KeyPair {
        val seed = org.tenshu.core.security.stubEd25519Signature(
            "tenshu-master-key-stub".encodeToByteArray(),
            recoveryPhrase.encodeToByteArray()
        )
        return SyncCryptoManager.KeyPair(seed, seed)
    }

    override fun signEd25519(privateKey: ByteArray, message: ByteArray): ByteArray =
        org.tenshu.core.security.stubEd25519Signature(privateKey, message)

    override fun verifyEd25519(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean =
        signature.contentEquals(org.tenshu.core.security.stubEd25519Signature(publicKey, message))
}

// Records createRealm + admitDevice; failure of each is independently switchable.
private class ReregisterRecordingRelay(private val context: TContext) : SyncRelay {

    data class CreateRealmCall(
        val ownerId: String,
        val masterPublicKey: ByteArray,
        val firstDevicePublicKey: ByteArray,
        val firstDeviceId: String,
        val masterSelfSignature: ByteArray,
    ) {
        // Canonical bootstrap message the relay would verify the signature against.
        fun bootstrapMessage(): ByteArray =
            org.tenshu.sync.common.realmBootstrapMessage(ownerId, masterPublicKey, firstDevicePublicKey, firstDeviceId)
    }

    data class AdmitCall(
        val ownerId: String,
        val newDeviceId: String,
        val newDevicePublicKey: ByteArray,
        val masterDelegation: ByteArray,
        val epoch: Long,
    )

    var lastCreateRealm: CreateRealmCall? = null
    var lastAdmit: AdmitCall? = null
    var lastCreateRealmHlc: String? = null
    var lastAdmitHlc: String? = null
    var failCreateRealm = false
    var failAdmit = false

    override suspend fun createRealm(
        ownerId: String,
        masterPublicKey: ByteArray,
        firstDevicePublicKey: ByteArray,
        firstDeviceId: String,
        masterSelfSignature: ByteArray,
        admittedAtHlc: String?,
    ): TResult<Data?> {
        lastCreateRealmHlc = admittedAtHlc
        lastCreateRealm = CreateRealmCall(
            ownerId, masterPublicKey, firstDevicePublicKey, firstDeviceId, masterSelfSignature
        )
        return if (failCreateRealm) {
            TResult.Failure.create(context, GenericError.create(context, "Realm creation failed: 409"))
        } else {
            TResult.Success.create(context, null)
        }
    }

    override suspend fun admitDevice(
        ownerId: String,
        newDeviceId: String,
        newDevicePublicKey: ByteArray,
        masterDelegation: ByteArray,
        epoch: Long,
        admittedAtHlc: String?,
    ): TResult<Data?> {
        lastAdmitHlc = admittedAtHlc
        lastAdmit = AdmitCall(ownerId, newDeviceId, newDevicePublicKey, masterDelegation, epoch)
        return if (failAdmit) {
            TResult.Failure.create(context, GenericError.create(context, "admit failed"))
        } else {
            TResult.Success.create(context, null)
        }
    }

    // Unused by this test.
    override suspend fun pushBlock(block: SyncBlock, edges: List<org.tenshu.core.common.content.ContentEdge>): TResult<Data?> = error("not used")
    override suspend fun getChanges(
        sinceTimestamp: Long,
        tip: org.tenshu.sync.SignedHlcTip
    ): TResult<org.tenshu.sync.ChangesResponse> = error("not used")

    override suspend fun pushTombstone(block: org.tenshu.sync.DeleteBlock, cascade: List<String>): TResult<Data?> =
        error("not used")

    override suspend fun getTombstones(sinceTimestamp: Long): TResult<org.tenshu.sync.TombstonesResponse> =
        error("not used")

    override suspend fun fetchEntityBlocks(entityWireId: String): TResult<TList<SyncBlock>> =
        error("not used")

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

    override suspend fun ackPairingResponse(pairingSecret: String): TResult<Data?> = error("not used")
    override suspend fun getPairingResponse(pairingSecret: String): TResult<Data?> = error("not used")

    override suspend fun recoveryChallenge(): TResult<org.tenshu.core.TByteArray> =
        error("not used")

    override suspend fun recoveryClaim(
        masterPublicKey: ByteArray,
        newDevicePublicKey: ByteArray,
        newDeviceId: String,
        challenge: ByteArray,
        masterSignature: ByteArray
    ): TResult<Data?> = error("not used")

    override suspend fun fetchRealmEpoch(ownerId: String): TResult<org.tenshu.core.number.TInteger> =
        error("not used")
}
