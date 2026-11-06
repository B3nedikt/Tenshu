package org.tenshu.sync.proxy

import org.tenshu.sync.proxy.security.PairingRendezvous
import org.tenshu.sync.proxy.security.deriveJoinPairingSas

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.GenericError
import org.tenshu.core.TByteArray
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.TenshuException
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.core.TContext
import org.tenshu.core.entity.DataId
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.core.toTByteArray
import org.tenshu.core.toTString
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.sync.InitiatePairing
import org.tenshu.sync.PairingRequest
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.common.SyncRelay
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.sync.proxy.impl.InitiatePairingImplementation
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InitiatePairingImplementationTest {

    private lateinit var testProxy: TestProxy
    private lateinit var syncManager: SyncManagerImpl
    private lateinit var crypto: RecordingCryptoManager
    private lateinit var relay: RecordingRelay
    private lateinit var impl: InitiatePairingImplementation

    @BeforeTest
    fun setup() {
        testProxy = TestProxy(schemaRegistry = createCoreTestRegistry()).also {
            it.schemaRegistry.registerConfig(syncServiceCommonModule)
            it.schemaRegistry.registerConfig(identityServiceCommonModule)
        }
        crypto = RecordingCryptoManager()
        syncManager = SyncManagerImpl(
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate(),
            context = testProxy.tContext
        )
        relay = RecordingRelay(testProxy.tContext)
        impl = InitiatePairingImplementation(relay, syncManager, crypto)
    }

    /**
     * ⚠⚠ The guard for the live "matching SAS, unopenable blob" failure
     * (`docs/tech-debt.md` → *"The joiner's decrypt fails its MAC check"*).
     *
     * Nothing binds the host's encrypted blob to the keypair it was encrypted for: the relay's
     * mailbox is `Map<pairingSecret, blob>` removed **only on ack**, the joiner's handshake is
     * *also* keyed by the secret alone, and the SAS the UI shows is a snapshot from the FIRST
     * initiate. So if a second initiate under one secret re-minted the keypair — a double-tapped
     * Continue, a back-and-retry — the joiner would go on displaying a SAS the host agrees with
     * while holding a private key that cannot open the blob the host posted. That is exactly the
     * observed signature, including the identical failure on every retry.
     *
     * The host side has been idempotent-per-session for this reason since `generatePairingContext`
     * ("a non-null context reliably means a handshake the joiner may be mid-way through; reuse
     * it"). This is the joiner's half of that same rule.
     */
    @Test
    fun aSecondInitiateUnderOneSecret_reusesTheKeypairTheHostEncryptedTo() = runTest {
        val secret = "secret-double-initiate"

        val first = assertNotNull(
            impl.execute<org.tenshu.sync.PairingInitiation>(
                executionContext = testProxy.executionContext,
                operationContext = null,
                implementationFactory = OperationImplementationFactory { null },
                caller = null,
                params = paramsFor(secret = secret, owner = "user-1", deviceName = "Tablet"),
            )
        )
        val afterFirst = assertNotNull(syncManager.getHandshake(secret))

        // The user taps Continue a second time / backs out and retries, under the SAME secret.
        val second = assertNotNull(
            impl.execute<org.tenshu.sync.PairingInitiation>(
                executionContext = testProxy.executionContext,
                operationContext = null,
                implementationFactory = OperationImplementationFactory { null },
                caller = null,
                params = paramsFor(secret = secret, owner = "user-1", deviceName = "Tablet"),
            )
        )
        val afterSecond = assertNotNull(syncManager.getHandshake(secret))

        assertContentEquals(
            afterFirst.keyPair.privateKey,
            afterSecond.keyPair.privateKey,
            "a second initiate must NOT re-mint the ephemeral key: the host may already have " +
                "encrypted the master blob to the first one, and the relay only drops that blob " +
                "on a successful ack",
        )
        assertContentEquals(
            first.publicKey.get(),
            second.publicKey.get(),
            "the request the host sees must carry the same public key both times",
        )
        assertEquals(
            first.sas.get(),
            second.sas.get(),
            "the SAS on screen is a snapshot of the first initiate — if the key moved under it, " +
                "the user would confirm a code that no longer matches the key in use",
        )
    }

    @Test
    fun success_storesHandshakeAndPostsToRelayAndReturnsInitiation() = runTest {
        val secret = "secret-1"

        val result = impl.execute<org.tenshu.sync.PairingInitiation>(
            executionContext = testProxy.executionContext,
            operationContext = null,
            implementationFactory = OperationImplementationFactory { null },
            caller = null,
            params = paramsFor(secret = secret, owner = "user-1", deviceName = "Tablet")
        )

        val generated =
            assertNotNull(crypto.lastGeneratedKeyPair, "Crypto should generate a key pair")
        val initiation = assertNotNull(result, "Success must carry initiation data")
        assertContentEquals(generated.publicKey, initiation.publicKey.get())
        // SAS is a 6-digit zero-padded decimal — exact value depends on
        // the stub crypto's shared-secret derivation; the contract tested
        // here is shape only. PairingSasTest covers correctness.
        val sas = initiation.sas.get()
        assertEquals(6, sas.length, "SAS should be 6 chars: '$sas'")
        assertTrue(sas.all { it.isDigit() }, "SAS should be all digits: '$sas'")

        // Handshake state retained for the polling step.
        val stored = assertNotNull(syncManager.getHandshake(secret))
        assertContentEquals(generated.publicKey, stored.keyPair.publicKey)
        assertContentEquals(generated.privateKey, stored.keyPair.privateKey)
        // Host realm's ownerId is plumbed into the handshake context so
        // GetPairingStatus can adopt it as Device B's currentOwnerId.
        assertEquals("user-1", stored.hostOwnerId)

        // The relay received exactly one matching pairing request.
        assertEquals(1, relay.postedRequests.size)
        val posted = relay.postedRequests.single()
        assertEquals("user-1", posted.ownerId)
        // ⚠⚠ F7: the relay is sent the secret's domain-separated HASH and the name SEALED —
        // neither the secret nor the name in clear.
        assertEquals(PairingRendezvous.joinSecretHash(secret), posted.pairingSecretHash)
        assertTrue(posted.pairingSecretHash != secret, "the raw secret must not be posted")
        assertEquals(syncManager.currentDeviceId, posted.deviceId)
        // (This crypto stub's `encrypt` is the identity, so ciphertext opacity is asserted with real
        // crypto in `PairingRendezvousTest` and the relay spy in `PairingSystemTest`.) The host, holding the secret, opens it to exactly the name the SAS covers.
        val postedName = PairingRendezvous.openJoinDeviceName(
            crypto, secret, posted.deviceId, posted.publicKey, posted.signingPublicKey, posted.sealedDeviceName,
        )
        assertEquals("Tablet", postedName)
        assertTrue(
            runCatching {
                PairingRendezvous.openJoinDeviceName(
                    crypto, secret, "another-device", posted.publicKey, posted.signingPublicKey, posted.sealedDeviceName,
                )
            }.isFailure,
            "the sealed name is bound to the posting deviceId",
        )
        assertTrue(
            runCatching {
                PairingRendezvous.openJoinDeviceName(
                    crypto, secret, posted.deviceId, posted.publicKey, ByteArray(32) { 9 }, posted.sealedDeviceName,
                )
            }.isFailure,
            "the sealed name is bound to the posted signing key",
        )
        // ⚠⚠ SAS v2 (threat model T-P-14): the code on this screen is over exactly the identity
        // this device posted — its signing key, id and name — so the host, deriving from what
        // it receives, matches only if the relay changed none of them.
        assertEquals(
            deriveJoinPairingSas(
                sharedSecret = crypto.calculateSharedSecret(generated.privateKey, HOST_PUBLIC_KEY),
                hostPublicKey = HOST_PUBLIC_KEY,
                joinerPublicKey = generated.publicKey,
                joinerSigningPublicKey = posted.signingPublicKey,
                joinerDeviceId = posted.deviceId,
                joinerDeviceName = postedName,
            ),
            sas,
            "the joiner's code must be the v2 code over the identity it posted",
        )
        assertContentEquals(generated.publicKey, posted.publicKey)
        // The signing pubkey must come from `getDeviceSigningKeyPair()`, not
        // `generateKeyPair()` — registering the X25519 ECDH key would break
        // every later JWT-authed call from this device.
        assertContentEquals(
            byteArrayOf(0xDD.toByte(), 0xDD.toByte()),
            posted.signingPublicKey
        )
    }

    @Test
    fun anOverlongMultiByteName_isTruncatedOnce_andTheSasTheSealAndTheStoredNameAgree() = runTest {
        // A long emoji-heavy platform name used to reach the relay as an oversized sealed blob and
        // come back as an opaque 400. It is now fitted to the relay's cap BEFORE anything uses it,
        // so the code on screen, the sealed copy and the stored name are the same string.
        val secret = "secret-long-name"
        val requested = "🎉".repeat(400) // 1600 UTF-8 bytes

        val initiation = assertNotNull(
            impl.execute<org.tenshu.sync.PairingInitiation>(
                executionContext = testProxy.executionContext,
                operationContext = null,
                implementationFactory = OperationImplementationFactory { null },
                caller = null,
                params = paramsFor(secret = secret, deviceName = requested),
            ),
        )

        val posted = relay.postedRequests.single()
        val postedName = PairingRendezvous.openJoinDeviceName(
            crypto, secret, posted.deviceId, posted.publicKey, posted.signingPublicKey, posted.sealedDeviceName,
        )
        assertTrue(postedName.isNotEmpty() && requested.startsWith(postedName), "a whole-code-point prefix")
        assertTrue(postedName.encodeToByteArray().size < requested.encodeToByteArray().size, "it was cut")
        assertEquals(
            PairingRendezvous.fitJoinDeviceName(secret, posted.deviceId, posted.publicKey, posted.signingPublicKey, requested),
            postedName,
        )
        assertEquals(postedName, syncManager.currentDeviceName, "the stored name is the one sealed")
        val generated = assertNotNull(crypto.lastGeneratedKeyPair)
        assertEquals(
            deriveJoinPairingSas(
                sharedSecret = crypto.calculateSharedSecret(generated.privateKey, HOST_PUBLIC_KEY),
                hostPublicKey = HOST_PUBLIC_KEY,
                joinerPublicKey = generated.publicKey,
                joinerSigningPublicKey = posted.signingPublicKey,
                joinerDeviceId = posted.deviceId,
                joinerDeviceName = postedName,
            ),
            initiation.sas.get(),
            "the SAS covers the truncated name, which is what the host unseals",
        )
    }

    @Test
    fun relayFailure_clearsHandshakeAndReturnsFailure() = runTest {
        val secret = "secret-relay-fail"
        relay.failNextPostPairingRequest = true

        assertFailsWith<TenshuException> {
            impl.execute<org.tenshu.sync.PairingInitiation>(
                executionContext = testProxy.executionContext,
                operationContext = null,
                implementationFactory = OperationImplementationFactory { null },
                caller = null,
                params = paramsFor(secret = secret)
            )
        }
        assertNull(
            syncManager.getHandshake(secret),
            "Handshake should be cleaned up on relay failure"
        )
    }

    private fun paramsFor(
        secret: String,
        owner: String = "user-1",
        deviceName: String = "Tablet"
    ): Map<String, Data> = mapOf(
        InitiatePairing.Params.OWNER_ID to owner.toTString(testProxy.tContext),
        InitiatePairing.Params.PAIRING_SECRET to secret.toTString(testProxy.tContext),
        InitiatePairing.Params.DEVICE_NAME to deviceName.toTString(testProxy.tContext),
        InitiatePairing.Params.OTHER_DEVICE_PUBLIC_KEY to
                HOST_PUBLIC_KEY.toTByteArray(testProxy.tContext),
    )

    private companion object {
        val HOST_PUBLIC_KEY = byteArrayOf(0x10, 0x20, 0x30, 0x40)
    }
}

private class RecordingCryptoManager : SyncCryptoManager {
    /** Records the last X25519 pairing keypair handed out by `generateKeyPair()`. */
    var lastGeneratedKeyPair: SyncCryptoManager.KeyPair? = null
        private set

    override fun deriveKeys(recoveryPhrase: String): SyncCryptoManager.DerivedKeys {
        val bytes = recoveryPhrase.encodeToByteArray()
        return SyncCryptoManager.DerivedKeys(
            sharedIdentityKey = bytes,
            sharedVolumeKey = bytes.reversedArray()
        )
    }

    override fun encrypt(payload: ByteArray, key: ByteArray): ByteArray = payload
    override fun decrypt(encryptedPayload: ByteArray, key: ByteArray): ByteArray = encryptedPayload

    override fun generateKeyPair(): SyncCryptoManager.KeyPair {
        // Distinguishable per call so tests can assert which key pair was generated/stored.
        val tag = ((lastGeneratedKeyPair?.publicKey?.firstOrNull()?.toInt() ?: 0) + 1).toByte()
        return SyncCryptoManager.KeyPair(
            publicKey = byteArrayOf(tag, 0xAA.toByte()),
            privateKey = byteArrayOf(tag, 0xBB.toByte())
        ).also { lastGeneratedKeyPair = it }
    }

    /**
     * Distinct from `generateKeyPair()` so tests can keep their X25519 vs
     * Ed25519 assertions separate. The default impl on `SyncCryptoManager`
     * delegates to `generateKeyPair()`, which would advance
     * [lastGeneratedKeyPair] — overriding here returns a fixed, recognisable
     * marker without that side effect.
     */
    override fun generateDeviceSigningKeyPair(): SyncCryptoManager.KeyPair =
        SyncCryptoManager.KeyPair(
            publicKey = byteArrayOf(0xDD.toByte(), 0xDD.toByte()),
            privateKey = byteArrayOf(0xEE.toByte(), 0xEE.toByte())
        )

    override fun calculateSharedSecret(
        privateKey: ByteArray,
        otherPublicKey: ByteArray
    ): ByteArray = privateKey + otherPublicKey

    override fun signEd25519(privateKey: ByteArray, message: ByteArray): ByteArray =
        org.tenshu.core.security.stubEd25519Signature(privateKey, message)

    override fun verifyEd25519(
        publicKey: ByteArray,
        message: ByteArray,
        signature: ByteArray
    ): Boolean =
        signature.contentEquals(org.tenshu.core.security.stubEd25519Signature(publicKey, message))

    override fun deriveMasterKeyPair(recoveryPhrase: String): SyncCryptoManager.KeyPair {
        val seed = org.tenshu.core.security.stubEd25519Signature(
            "tenshu-master-key-stub".encodeToByteArray(),
            recoveryPhrase.encodeToByteArray()
        )
        return SyncCryptoManager.KeyPair(seed, seed)
    }
}

private class RecordingRelay(private val context: TContext) : SyncRelay {

    data class PostedPairingRequest(
        val ownerId: String,
        val pairingSecretHash: String,
        val deviceId: String,
        val sealedDeviceName: ByteArray,
        val publicKey: ByteArray,
        val signingPublicKey: ByteArray
    )

    val postedRequests = mutableListOf<PostedPairingRequest>()
    var failNextPostPairingRequest: Boolean = false

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
    ): TResult<Data?> {
        if (failNextPostPairingRequest) {
            failNextPostPairingRequest = false
            return TResult.Failure.create(
                context,
                GenericError.create(context, "relay boom")
            )
        }
        postedRequests.add(
            PostedPairingRequest(
                ownerId = ownerId,
                pairingSecretHash = pairingSecretHash,
                deviceId = deviceId,
                sealedDeviceName = sealedDeviceName,
                publicKey = publicKey,
                signingPublicKey = signingPublicKey
            )
        )
        return TResult.Success.create(context, null)
    }

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

    override suspend fun createRealm(
        ownerId: String,
        masterPublicKey: ByteArray,
        firstDevicePublicKey: ByteArray,
        firstDeviceId: String,
        masterSelfSignature: ByteArray,
        admittedAtHlc: String?,
    ): TResult<Data?> = error("not used")

    override suspend fun recoveryChallenge(): TResult<TByteArray> = error("not used")

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
