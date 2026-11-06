package org.tenshu.sync.proxy

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.GenericError
import org.tenshu.core.TBoolean
import org.tenshu.core.TResult
import org.tenshu.core.TenshuException
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.impl.TByteArrayDto
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.core.TContext
import org.tenshu.core.entity.DataId
import org.tenshu.core.number.toTInteger
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.core.toTByteArray
import org.tenshu.core.toTString
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.sync.AwaitPairing
import org.tenshu.sync.PairingRequest
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.common.SyncRelay
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.sync.proxy.impl.AwaitPairingImplementation
import org.tenshu.sync.proxy.security.PairingRendezvous
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * §7.5 Phase L2. `AwaitPairing` loops the shared `PairingCompletion` (so the
 * completed-path crypto is covered by `GetPairingStatusImplementationTest` —
 * not re-asserted here) and tears down the in-proxy handshake (asset A8) when
 * cancelled. These tests cover exactly those two: completes when the key is
 * present, and a cancel mid-wait clears the handshake.
 */
class AwaitPairingImplementationTest {

    private lateinit var testProxy: TestProxy
    private lateinit var syncManager: SyncManagerImpl
    private lateinit var crypto: MiniCrypto
    private lateinit var relay: GatedPairingRelay
    private lateinit var impl: AwaitPairingImplementation

    @BeforeTest
    fun setup() {
        testProxy = TestProxy(schemaRegistry = createCoreTestRegistry()).also {
            it.schemaRegistry.registerConfig(syncServiceCommonModule)
            it.schemaRegistry.registerConfig(identityServiceCommonModule)
        }
        crypto = MiniCrypto()
        syncManager = SyncManagerImpl(
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate(),
            context = testProxy.tContext
        )
        relay = GatedPairingRelay(testProxy.tContext)
        impl = AwaitPairingImplementation(relay, syncManager, crypto)
    }

    private fun params(secret: String, otherPk: ByteArray) = mapOf(
        AwaitPairing.Params.PAIRING_SECRET to secret.toTString(testProxy.tContext),
        AwaitPairing.Params.OTHER_DEVICE_PUBLIC_KEY to otherPk.toTByteArray(testProxy.tContext)
    )

    @Test
    fun keyPresent_completesWithTrueAndClearsHandshake() = runTest {
        val secret = "secret-await-complete"
        val myKeyPair = SyncCryptoManager.KeyPair(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))
        val otherPk = byteArrayOf(7, 7, 7)
        val ciphertext = byteArrayOf(0x10, 0x20)
        syncManager.startHandshake(secret, myKeyPair, hostOwnerId = "host-owner")
        syncManager.setCurrentDeviceName("Tablet")
        relay.respondWith(secret, ciphertext)
        crypto.programDecryption(ciphertext, ByteArray(32) { 0xAA.toByte() } + ByteArray(32) { 0xBB.toByte() })

        val result = impl.execute<TBoolean>(
            executionContext = testProxy.executionContext,
            operationContext = null,
            implementationFactory = OperationImplementationFactory { null },
            caller = null,
            params = params(secret, otherPk)
        )

        assertEquals(true, assertNotNull(result).get(), "paired")
        assertNull(syncManager.getHandshake(secret), "handshake cleared on completion")
        assertTrue(syncManager.isEnabled(), "SyncManager initialised with the master key")
        // F7: the ack names the mailbox by the secret's hash.
        assertEquals(listOf(PairingRendezvous.joinSecretHash(secret)), relay.ackedSecrets)
    }

    @Test
    fun transientRelayFailures_areToleratedThenCompletes() = runTest {
        // A couple of failed polls mid-wait (< the consecutive-failure threshold) must not
        // abort the wait — the user is actively approving on their other device. Once the
        // relay recovers and the key is present, the wait still completes.
        val secret = "secret-await-flaky"
        val myKeyPair = SyncCryptoManager.KeyPair(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))
        val ciphertext = byteArrayOf(0x10, 0x20)
        syncManager.startHandshake(secret, myKeyPair, hostOwnerId = "host-owner")
        syncManager.setCurrentDeviceName("Tablet")
        crypto.programDecryption(ciphertext, ByteArray(32) { 0xAA.toByte() } + ByteArray(32) { 0xBB.toByte() })
        relay.failuresBeforeSuccess = 2 // < MAX_CONSECUTIVE_FAILURES (3)
        relay.respondWith(secret, ciphertext)

        val result = impl.execute<TBoolean>(
            executionContext = testProxy.executionContext,
            operationContext = null,
            implementationFactory = OperationImplementationFactory { null },
            caller = null,
            params = params(secret, byteArrayOf(7, 7, 7))
        )

        assertEquals(true, assertNotNull(result).get(), "paired after riding out transient failures")
        assertNull(syncManager.getHandshake(secret), "handshake cleared on completion")
    }

    @Test
    fun persistentRelayFailure_surfacesInsteadOfTimingOut() = runTest {
        // A relay that stays unreachable across the threshold must surface the error — the
        // whole point of the fix: no more silent "poll forever until the 5-minute timeout".
        val secret = "secret-await-down"
        val myKeyPair = SyncCryptoManager.KeyPair(byteArrayOf(1), byteArrayOf(4))
        syncManager.startHandshake(secret, myKeyPair, hostOwnerId = "host-owner")
        relay.alwaysFail = true

        assertFailsWith<TenshuException> {
            impl.execute<TBoolean>(
                executionContext = testProxy.executionContext,
                operationContext = null,
                implementationFactory = OperationImplementationFactory { null },
                caller = null,
                params = params(secret, byteArrayOf(9))
            )
        }
    }

    @Test
    fun cancel_burnsTheInProxyHandshake() = runTest {
        val secret = "secret-await-cancel"
        val myKeyPair = SyncCryptoManager.KeyPair(byteArrayOf(1), byteArrayOf(4))
        syncManager.startHandshake(secret, myKeyPair, hostOwnerId = "host-owner")
        // No response programmed → tryComplete suspends inside getPairingResponse,
        // which the gate holds open until we cancel.
        val entered = CompletableDeferred<Unit>()
        relay.gate(entered = entered, release = CompletableDeferred()) // release never completes

        val job = launch {
            runCatching {
                impl.execute<TBoolean>(
                    executionContext = testProxy.executionContext,
                    operationContext = null,
                    implementationFactory = OperationImplementationFactory { null },
                    caller = null,
                    params = params(secret, byteArrayOf(9))
                )
            }
        }

        entered.await() // the op is now parked in relay.getPairingResponse
        assertNotNull(syncManager.getHandshake(secret), "handshake present while waiting")

        job.cancelAndJoin()

        // §7.5 / threat-model A8: cancelling the wait tears down the in-proxy
        // ephemeral handshake — no separate CancelPairing needed.
        assertNull(
            syncManager.getHandshake(secret),
            "cancel must burn the in-proxy handshake (asset A8 deterministic teardown)"
        )
    }
}

/** Minimal SyncCryptoManager — only what PairingCompletion's success path uses. */
private class MiniCrypto : SyncCryptoManager {
    private val decryptions = mutableMapOf<List<Byte>, ByteArray>()
    fun programDecryption(ciphertext: ByteArray, plaintext: ByteArray) {
        decryptions[ciphertext.toList()] = plaintext
    }

    override fun deriveKeys(recoveryPhrase: String): SyncCryptoManager.DerivedKeys =
        error("not used")

    override fun encrypt(payload: ByteArray, key: ByteArray): ByteArray = error("not used")
    override fun decrypt(encryptedPayload: ByteArray, key: ByteArray): ByteArray =
        decryptions[encryptedPayload.toList()] ?: error("no decrypt programmed")

    override fun generateKeyPair(): SyncCryptoManager.KeyPair =
        SyncCryptoManager.KeyPair(byteArrayOf(0x42), byteArrayOf(0x42))

    override fun calculateSharedSecret(privateKey: ByteArray, otherPublicKey: ByteArray): ByteArray =
        privateKey + otherPublicKey

    override fun signEd25519(privateKey: ByteArray, message: ByteArray): ByteArray =
        org.tenshu.core.security.stubEd25519Signature(privateKey, message)

    override fun verifyEd25519(
        publicKey: ByteArray,
        message: ByteArray,
        signature: ByteArray
    ): Boolean = true

    override fun deriveMasterKeyPair(recoveryPhrase: String): SyncCryptoManager.KeyPair =
        error("not used")
}

/**
 * SyncRelay whose `getPairingResponse` either returns a preloaded response
 * (success path) or, when gated, signals `entered` and suspends on `release`
 * (so a test can deterministically cancel the op mid-wait).
 */
private class GatedPairingRelay(private val context: TContext) : SyncRelay {
    private val responses = mutableMapOf<String, ByteArray>()
    private var enteredSignal: CompletableDeferred<Unit>? = null
    private var releaseSignal: CompletableDeferred<Unit>? = null

    /** Fail this many initial `getPairingResponse` calls, then behave normally. */
    var failuresBeforeSuccess = 0

    /** Every `getPairingResponse` returns a `TResult.Failure`. */
    var alwaysFail = false

    /** Keyed as the relay keys it after F7: by the secret's hash. */
    fun respondWith(secret: String, encryptedMasterKey: ByteArray) {
        responses[PairingRendezvous.joinSecretHash(secret)] = encryptedMasterKey
    }

    fun gate(entered: CompletableDeferred<Unit>, release: CompletableDeferred<Unit>) {
        enteredSignal = entered
        releaseSignal = release
    }

    val ackedSecrets = mutableListOf<String>()

    override suspend fun getPairingResponse(pairingSecretHash: String): TResult<Data?> {
        enteredSignal?.complete(Unit)
        releaseSignal?.await()
        if (alwaysFail || failuresBeforeSuccess > 0) {
            if (failuresBeforeSuccess > 0) failuresBeforeSuccess--
            return TResult.Failure.create(context, GenericError.create(context, "relay unreachable"))
        }
        val encrypted = responses[pairingSecretHash]
        return TResult.Success.create(context, encrypted?.let { TByteArrayDto(it) })
    }

    override suspend fun ackPairingResponse(pairingSecretHash: String): TResult<Data?> {
        ackedSecrets.add(pairingSecretHash)
        responses.remove(pairingSecretHash)
        return TResult.Success.create(context, null)
    }

    override suspend fun fetchRealmEpoch(
        ownerId: String
    ): TResult<org.tenshu.core.number.TInteger> =
        TResult.Success.create(context, 0L.toTInteger(context))

    override suspend fun pushBlock(block: SyncBlock, edges: List<org.tenshu.core.common.content.ContentEdge>): TResult<Data?> = error("not used")
    override suspend fun getChanges(
        sinceTimestamp: Long,
        tip: org.tenshu.sync.SignedHlcTip
    ): TResult<org.tenshu.sync.ChangesResponse> = error("not used")

    override suspend fun pushTombstone(block: org.tenshu.sync.DeleteBlock, cascade: List<String>): TResult<Data?> =
        error("not used")

    override suspend fun getTombstones(
        sinceTimestamp: Long
    ): TResult<org.tenshu.sync.TombstonesResponse> = error("not used")

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
        pairingSecretHash: String,
        encryptedMasterKey: ByteArray,
        newDevicePublicKey: ByteArray,
        newDeviceId: String,
        masterDelegation: ByteArray,
        epoch: Long,
        admissionCertChain: ByteArray?,
        admittedAtHlc: String?,
    ): TResult<Data?> = error("not used")

    override suspend fun createRealm(
        ownerId: String,
        masterPublicKey: ByteArray,
        firstDevicePublicKey: ByteArray,
        firstDeviceId: String,
        masterSelfSignature: ByteArray,
        admittedAtHlc: String?,
    ): TResult<Data?> = error("not used")

    override suspend fun recoveryChallenge(): TResult<org.tenshu.core.TByteArray> = error("not used")

    override suspend fun recoveryClaim(
        masterPublicKey: ByteArray,
        newDevicePublicKey: ByteArray,
        newDeviceId: String,
        challenge: ByteArray,
        masterSignature: ByteArray
    ): TResult<Data?> = error("not used")
}
