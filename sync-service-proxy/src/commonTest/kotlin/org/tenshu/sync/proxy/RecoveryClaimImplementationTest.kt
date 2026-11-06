package org.tenshu.sync.proxy

import io.ktor.client.engine.mock.respond
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import kotlin.test.assertFalse
import org.tenshu.core.messageOrNull
import org.tenshu.core.InvalidParameter
import org.tenshu.core.GenericError
import org.tenshu.core.TByteArray
import org.tenshu.core.TResult
import org.tenshu.core.TenshuException
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.impl.TByteArrayDto
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.common.security.ownerIdForMasterPublicKey
import org.tenshu.core.common.util.Bip39
import org.tenshu.core.core.TContext
import org.tenshu.core.entity.DataId
import org.tenshu.core.number.toTInteger
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.core.toTString
import org.tenshu.identity.Device
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.sync.DeviceAlreadySetUpError
import org.tenshu.sync.PairingRequest
import org.tenshu.sync.RecoveryClaim
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.common.SyncRelay
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.sync.proxy.impl.RecoveryClaimImplementation
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RecoveryClaimImplementationTest {

    private lateinit var testProxy: TestProxy
    private lateinit var syncManager: SyncManagerImpl
    private lateinit var crypto: RecordingRecoveryCryptoManager
    private lateinit var relay: RecordingRecoveryRelay
    private lateinit var impl: RecoveryClaimImplementation

    /** A valid 12-word BIP-39 mnemonic; RecoveryClaim now decodes the phrase up-front. */
    private val validPhrase: String = Bip39.encode(ByteArray(16) { it.toByte() })

    @BeforeTest
    fun setup() {
        testProxy = TestProxy(schemaRegistry = createCoreTestRegistry()).also {
            it.schemaRegistry.registerConfig(syncServiceCommonModule)
            it.schemaRegistry.registerConfig(identityServiceCommonModule)
        }
        crypto = RecordingRecoveryCryptoManager()
        syncManager = SyncManagerImpl(
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate(),
            context = testProxy.tContext
        )
        relay = RecordingRecoveryRelay(testProxy.tContext)
        impl = RecoveryClaimImplementation(syncManager, crypto, relay)
    }

    private fun paramsFor(
        phrase: String,
        deviceName: String = "Recovered Tablet"
    ): Map<String, Data> = mapOf(
        RecoveryClaim.Params.RECOVERY_PHRASE to phrase.toTString(testProxy.tContext),
        RecoveryClaim.Params.DEVICE_NAME to deviceName.toTString(testProxy.tContext)
    )

    @Test
    fun success_fetchesChallengeAndSubmitsClaimWithCorrectMessage() = runTest {
        val phrase = validPhrase
        relay.nextChallenge =
            byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte())

        impl.execute<Data>(
            executionContext = testProxy.executionContext,
            operationContext = null,
            implementationFactory = OperationImplementationFactory { null },
            caller = null,
            params = paramsFor(phrase)
        )


        // Step 1: relay was asked for a challenge — once, naming no realm (F8).
        val expectedMasterPubKey = crypto.deriveMasterKeyPair(phrase).publicKey
        val expectedOwnerId = ownerIdForMasterPublicKey(expectedMasterPubKey)
        assertEquals(1, relay.challengeCalls)

        // Step 2: claim was submitted with the correct shape — the master PUBLIC KEY, whose hash
        // is the ownerId the relay registers the device under.
        val claim = assertNotNull(relay.lastClaim, "relay.recoveryClaim should have been called")
        assertEquals(expectedMasterPubKey.toList(), claim.masterPublicKey.toList())
        assertEquals(expectedOwnerId, ownerIdForMasterPublicKey(claim.masterPublicKey))
        assertEquals(syncManager.currentDeviceId, claim.newDeviceId)
        val expectedDevicePubKey = syncManager.getDeviceSigningKeyPair().publicKey
        assertContentEquals(expectedDevicePubKey, claim.newDevicePublicKey)
        assertContentEquals(relay.nextChallenge, claim.challenge)

        // Master signature is over the canonical claim message — protocol contract with
        // the relay's MasterDelegationVerifier.recoveryClaimMessage.
        val expectedMessage = org.tenshu.sync.common.recoveryClaimMessage(
            expectedDevicePubKey, syncManager.currentDeviceId, relay.nextChallenge!!,
        )
        val expectedSignature = org.tenshu.core.security.stubEd25519Signature(
            crypto.deriveMasterKeyPair(phrase).privateKey,
            expectedMessage
        )
        assertContentEquals(expectedSignature, claim.masterSignature)

        // Step 3 (docs/adr/0007-sync-device-management.md step 5): the recovering device pushes
        // its own Device entity. `isAuthorized = true` because the device proved
        // possession of the master identity (master self-delegation), distinct from
        // pairing where Device A flips the flag.
        val ownDeviceId = DataId.StringDataId.create(
            context = testProxy.tContext,
            type = Device.ID,
            keyValue = syncManager.currentDeviceId
        )
        val savedDevice = testProxy.database.getData(ownDeviceId) as? Device
        assertNotNull(savedDevice, "Recovered device should have pushed its own Device entity")
        assertEquals(true, savedDevice.isAuthorized.get(), "Recovery is master-self-delegated")
        assertEquals("Recovered Tablet", savedDevice.name.get())
        assertContentEquals(expectedDevicePubKey, savedDevice.publicKey.get())
    }

    @Test
    fun stampsAdmittedAtEpoch_fromRelayFetch_notLocalProvider() = runTest {
        // T-K-02 recovery-race carve-out: a recovering device has no local tombstones
        // yet, so without this OOB fetch its self-pushed Device block would carry
        // `admittedAtEpoch = 0` and be rejected by every realm peer past epoch 0.
        // The relay reports the authoritative count; the impl threads it into the
        // entity construction.
        relay.nextChallenge = byteArrayOf(0x01)
        relay.nextEpoch = 5L

        impl.execute<Data>(
            executionContext = testProxy.executionContext,
            operationContext = null,
            implementationFactory = OperationImplementationFactory { null },
            caller = null,
            params = paramsFor(validPhrase)
        )


        // The relay was asked for the realm's current epoch — same ownerId as the claim.
        val expectedOwnerId = ownerIdForMasterPublicKey(
            crypto.deriveMasterKeyPair(validPhrase).publicKey
        )
        assertEquals(expectedOwnerId, relay.lastEpochFetchOwnerId)

        // The saved Device entity carries the relay-fetched epoch, not the local 0.
        val ownDeviceId = DataId.StringDataId.create(
            context = testProxy.tContext,
            type = Device.ID,
            keyValue = syncManager.currentDeviceId
        )
        val savedDevice = testProxy.database.getData(ownDeviceId) as? Device
        assertNotNull(savedDevice)
        assertEquals(
            5L,
            savedDevice.admittedAtEpoch.get(),
            "Device should carry the relay-fetched realm epoch (5), not the local 0"
        )

        // SyncManager's currentRealmEpoch is also seeded — the merge-time check on
        // *incoming* Device blocks now has a meaningful floor immediately, instead of
        // waiting for tombstones to drain.
        assertEquals(
            5L,
            syncManager.currentRealmEpoch,
            "currentRealmEpoch should be seeded from the relay fetch (closes the " +
                    "second half of the carve-out: receiver-side T-K-02 enforcement)"
        )
    }

    @Test
    fun epochFetchFailure_propagates_andDeviceEntityNotSaved() = runTest {
        // If the relay can't serve the metadata count, we don't ship a stale-epoch
        // Device block silently — the carve-out's whole point is to avoid epoch=0
        // self-pushes when the realm has rotated.
        relay.nextChallenge = byteArrayOf(0x01)
        relay.failNextEpochFetch = true

        assertFailsWith<TenshuException> {
            impl.execute<Data>(
                executionContext = testProxy.executionContext,
                operationContext = null,
                implementationFactory = OperationImplementationFactory { null },
                caller = null,
                params = paramsFor(validPhrase)
            )
        }

        val ownDeviceId = DataId.StringDataId.create(
            context = testProxy.tContext,
            type = Device.ID,
            keyValue = syncManager.currentDeviceId
        )
        kotlin.test.assertNull(
            testProxy.database.getData(ownDeviceId),
            "No Device entity should be saved when the OOB epoch fetch fails"
        )
    }

    @Test
    fun challengeFailure_propagates_andClaimNotSubmitted() = runTest {
        relay.failNextChallenge = true

        assertFailsWith<TenshuException> {
            impl.execute<Data>(
                executionContext = testProxy.executionContext,
                operationContext = null,
                implementationFactory = OperationImplementationFactory { null },
                caller = null,
                params = paramsFor(validPhrase)
            )
        }
        // The claim must NOT have been attempted — short-circuit on challenge failure.
        kotlin.test.assertNull(relay.lastClaim)
    }

    /**
     * The relay's per-IP cap on the challenge (threat model T-RC-08) answers `429` + `Retry-After`.
     * Through the REAL [RestSyncRelay], at the h2 shape (no reason phrase): the op must fail with the
     * rate-limit marker and the wait, so a reader can say "try again shortly" — not a generic error,
     * and never something that reads as a rejected phrase or an identity problem.
     */
    @Test
    fun aRateLimitedChallenge_failsAsRetryable_withTheRelaysWait() = runTest {
        val engine = io.ktor.client.engine.mock.MockEngine {
            respond(
                "Too many recovery challenges from this address",
                io.ktor.http.HttpStatusCode(429, ""),
                io.ktor.http.headersOf(io.ktor.http.HttpHeaders.RetryAfter, "10"),
            )
        }
        val restRelay = RestSyncRelay(
            baseUrl = "https://relay.example",
            httpClient = io.ktor.client.HttpClient(engine) {
                install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
                    json()
                }
            },
            context = testProxy.tContext,
            authTokenProvider = { null },
            ownerIdProvider = { null },
        )
        val rateLimitedImpl = RecoveryClaimImplementation(syncManager, crypto, restRelay)

        val ex = assertFailsWith<TenshuException> {
            rateLimitedImpl.execute<Data>(
                executionContext = testProxy.executionContext,
                operationContext = null,
                implementationFactory = OperationImplementationFactory { null },
                caller = null,
                params = paramsFor(validPhrase)
            )
        }
        val message = assertNotNull(ex.error.messageOrNull())
        assertTrue(RATE_LIMITED_REJECTION_MARKER in message, "the 429 must carry its marker: $message")
        assertTrue("retry after 10s" in message, "the relay's Retry-After must travel with it: $message")
        assertTrue(TResult.Failure.create<Data?>(testProxy.tContext, ex.error).isRelayRateLimited())
        assertFalse(ex.isRelayUnauthorized(), "a 429 is not an identity problem")
    }

    @Test
    fun claimFailure_propagates_andDeviceEntityNotSaved() = runTest {
        relay.nextChallenge = byteArrayOf(1, 2, 3)
        relay.failNextClaim = true

        assertFailsWith<TenshuException> {
            impl.execute<Data>(
                executionContext = testProxy.executionContext,
                operationContext = null,
                implementationFactory = OperationImplementationFactory { null },
                caller = null,
                params = paramsFor(validPhrase)
            )
        }

        // No Device entity saved when admission failed — would otherwise pollute the
        // local DB with a "ghost" device that the relay never accepted.
        val ownDeviceId = DataId.StringDataId.create(
            context = testProxy.tContext,
            type = Device.ID,
            keyValue = syncManager.currentDeviceId
        )
        kotlin.test.assertNull(
            testProxy.database.getData(ownDeviceId),
            "No Device entity should be saved on claim failure"
        )
        // ⚠⚠ Nor is the phrase seated: an enabled manager with a master key in memory is what
        // the scheduler's lazy genesis registers a realm for — a refused (e.g. mistyped) phrase
        // would otherwise become a fresh, empty realm on the relay.
        assertEquals(false, syncManager.isEnabled(), "a refused claim must not seat the phrase")
        kotlin.test.assertNull(syncManager.currentMasterKeyPair)
        kotlin.test.assertNull(syncManager.getRecoveryPhrase())
    }

    @Test
    fun invalidLengthMnemonic_throws_andDoesNotHitRelay() = runTest {
        // Three-word string passes the wordlist check but fails the length check (must
        // be 12/15/18/21/24). Failure should short-circuit before any relay call.
        // Phase 3 of `docs/proposals/core-error-channel.md`: typed throw.
        val ex = assertFailsWith<TenshuException> {
            impl.execute<Data>(
                executionContext = testProxy.executionContext,
                operationContext = null,
                implementationFactory = OperationImplementationFactory { null },
                caller = null,
                params = paramsFor("abandon abandon abandon")
            )
        }
        assertInvalidRecoveryPhrase(ex)
        assertEquals(0, relay.challengeCalls, "No relay challenge should be issued")
    }

    /**
     * The typed contract EC-1 replaced the freetext one with
     * (`core-error-copy-catalogue.md` §5.0): the proxy names the offending *parameter* and a
     * wire-stable *reason*, and writes no sentence for anyone to render.
     *
     * ⚠ All three BIP-39 causes collapse to `BAD_FORMAT` on purpose — this path is defence in
     * depth for callers that are not Control, whose ViewModel decodes BIP-39 itself and raises
     * the precise error with the offending word, locally, before any relay round-trip.
     */
    private fun assertInvalidRecoveryPhrase(ex: TenshuException) {
        val error = ex.error
        assertTrue(error is InvalidParameter, "Expected InvalidParameter, got ${error.schema.id.get()}")
        assertEquals(RecoveryClaim.Params.RECOVERY_PHRASE, error.name.get())
        assertEquals(InvalidParameter.Reasons.BAD_FORMAT, error.reason.get())
    }

    @Test
    fun unknownWordMnemonic_throws_andDoesNotHitRelay() = runTest {
        // Replace one word with a non-wordlist token; the rest of the structure is fine.
        val mutated = validPhrase.split(" ").toMutableList().also {
            it[0] = "notawordatall"
        }.joinToString(" ")

        val ex = assertFailsWith<TenshuException> {
            impl.execute<Data>(
                executionContext = testProxy.executionContext,
                operationContext = null,
                implementationFactory = OperationImplementationFactory { null },
                caller = null,
                params = paramsFor(mutated)
            )
        }
        assertInvalidRecoveryPhrase(ex)
        // ⚠ And it must NOT name the offending word. The old contract asserted that it did, which
        // put a word the user typed into their recovery phrase into `GenericError.message` — a
        // field `TError.messageOrNull()` feeds to the IPC failure logs. Typed errors carry a
        // wire-stable reason code and nothing the user typed.
        assertFalse(
            ex.error.messageOrNull().orEmpty().contains("notawordatall"),
            "The typed error must not carry user-typed phrase content"
        )
        assertEquals(0, relay.challengeCalls)
    }

    @Test
    fun checksumMismatchMnemonic_throws_andDoesNotHitRelay() = runTest {
        // Swap two words from the same valid phrase; the result is still 12 valid
        // wordlist entries but the BIP-39 checksum no longer matches.
        val words = validPhrase.split(" ").toMutableList()
        val tmp = words[0]
        words[0] = words[1]
        words[1] = tmp

        val ex = assertFailsWith<TenshuException> {
            impl.execute<Data>(
                executionContext = testProxy.executionContext,
                operationContext = null,
                implementationFactory = OperationImplementationFactory { null },
                caller = null,
                params = paramsFor(words.joinToString(" "))
            )
        }
        assertInvalidRecoveryPhrase(ex)
        assertEquals(0, relay.challengeCalls)
    }

    @Test
    fun initializesSyncManager_ifNotAlreadyInitialized() = runTest {
        assertEquals(false, syncManager.isEnabled())
        relay.nextChallenge = byteArrayOf(0)

        impl.execute<Data>(
            executionContext = testProxy.executionContext,
            operationContext = null,
            implementationFactory = OperationImplementationFactory { null },
            caller = null,
            params = paramsFor(validPhrase)
        )

        assertEquals(true, syncManager.isEnabled())
    }

    // ---------------------------------------------------------------- already set up (T-RC-09)

    /** A second valid phrase — a different realm from [validPhrase]. */
    private val otherRealmPhrase: String = Bip39.encode(ByteArray(16) { (0x80 + it).toByte() })

    private suspend fun claim(phrase: String) = impl.execute<Data>(
        executionContext = testProxy.executionContext,
        operationContext = null,
        implementationFactory = OperationImplementationFactory { null },
        caller = null,
        params = paramsFor(phrase)
    )

    /**
     * T-RC-09's residual, closed: a device enabled on realm X that recovers realm Y used to keep
     * X's keys (`initialize` is `isEnabled()`-guarded) while the relay admitted it to Y. It is now
     * refused, typed, before any relay call — and X is left exactly as it was.
     */
    @Test
    fun aDeviceEnabledOnAnotherRealm_isRefused_beforeAnyRelayCall_andKeepsItsRealm() = runTest {
        syncManager.initialize(otherRealmPhrase)
        val ownerBefore = syncManager.currentOwnerId
        val keysBefore = assertNotNull(syncManager.getDerivedKeys())
        relay.nextChallenge = byteArrayOf(1)

        val ex = assertFailsWith<TenshuException> { claim(validPhrase) }

        assertTrue(
            ex.error is DeviceAlreadySetUpError,
            "expected DeviceAlreadySetUpError, got ${ex.error.schema.id.get()}",
        )
        assertEquals(0, relay.challengeCalls, "refused before the challenge")
        kotlin.test.assertNull(relay.lastClaim, "and never claimed")
        assertEquals(ownerBefore, syncManager.currentOwnerId)
        assertEquals(ownerBefore, syncManager.residentOwnerId)
        assertEquals(keysBefore, syncManager.getDerivedKeys(), "the first realm's keys are untouched")
        assertEquals(otherRealmPhrase, syncManager.getRecoveryPhrase())
    }

    /**
     * The case `isEnabled()` alone would miss: a LOCKED device is set up (its realm is resident at
     * rest) but has no keys in memory, so the old guard would have seated Y's keys over X's unlock
     * wrapper. The durable marker is what refuses it.
     */
    @Test
    fun aLockedDeviceOnAnotherRealm_isRefused_too() = runTest {
        syncManager.initialize(otherRealmPhrase)
        val ownerBefore = syncManager.currentOwnerId
        syncManager.lock()
        assertFalse(syncManager.isEnabled(), "precondition: locked = not enabled")
        relay.nextChallenge = byteArrayOf(1)

        val ex = assertFailsWith<TenshuException> { claim(validPhrase) }

        assertTrue(ex.error is DeviceAlreadySetUpError)
        assertEquals(0, relay.challengeCalls)
        assertFalse(syncManager.isEnabled(), "nothing was seated")
        assertEquals(ownerBefore, syncManager.residentOwnerId, "the device still belongs to its realm")
    }

    /**
     * ⚠ The one legitimate caller that recovers while set up: the lock screen's "Recover", for a
     * user who forgot the passphrase of the realm this device already belongs to. Same realm →
     * allowed, and it unlocks the device.
     */
    @Test
    fun aLockedDevice_recoveringItsOwnRealm_isAllowed() = runTest {
        syncManager.initialize(validPhrase)
        val owner = syncManager.currentOwnerId
        syncManager.lock()
        relay.nextChallenge = byteArrayOf(1)

        claim(validPhrase)

        assertEquals(1, relay.challengeCalls)
        assertNotNull(relay.lastClaim)
        assertTrue(syncManager.isEnabled(), "recovery re-seated the device's own realm")
        assertEquals(owner, syncManager.currentOwnerId)
    }
}

// Stub-level crypto manager. Same shape as the one in CreateRealmImplementationTest.
private class RecordingRecoveryCryptoManager : SyncCryptoManager {
    override fun deriveKeys(recoveryPhrase: String): SyncCryptoManager.DerivedKeys {
        val bytes = recoveryPhrase.encodeToByteArray()
        return SyncCryptoManager.DerivedKeys(bytes, bytes.reversedArray())
    }

    override fun encrypt(payload: ByteArray, key: ByteArray): ByteArray = payload
    override fun decrypt(encryptedPayload: ByteArray, key: ByteArray): ByteArray = encryptedPayload

    override fun generateKeyPair(): SyncCryptoManager.KeyPair {
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

private class RecordingRecoveryRelay(private val context: TContext) : SyncRelay {

    data class ClaimCall(
        val masterPublicKey: ByteArray,
        val newDevicePublicKey: ByteArray,
        val newDeviceId: String,
        val challenge: ByteArray,
        val masterSignature: ByteArray
    )

    var challengeCalls: Int = 0
    var nextChallenge: ByteArray? = null
    var failNextChallenge: Boolean = false

    var lastClaim: ClaimCall? = null
    var failNextClaim: Boolean = false

    override suspend fun recoveryChallenge(): TResult<TByteArray> {
        challengeCalls++
        return if (failNextChallenge) {
            TResult.Failure.create(context, GenericError.create(context, "challenge fetch failed"))
        } else {
            TResult.Success.create(context, TByteArrayDto(nextChallenge ?: byteArrayOf()))
        }
    }

    override suspend fun recoveryClaim(
        masterPublicKey: ByteArray,
        newDevicePublicKey: ByteArray,
        newDeviceId: String,
        challenge: ByteArray,
        masterSignature: ByteArray
    ): TResult<Data?> {
        lastClaim = ClaimCall(
            masterPublicKey,
            newDevicePublicKey,
            newDeviceId,
            challenge,
            masterSignature
        )
        return if (failNextClaim) {
            TResult.Failure.create(context, GenericError.create(context, "claim failed"))
        } else {
            TResult.Success.create(context, null)
        }
    }

    // Methods below are not exercised by RecoveryClaimImplementationTest.
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

    override suspend fun createRealm(
        ownerId: String,
        masterPublicKey: ByteArray,
        firstDevicePublicKey: ByteArray,
        firstDeviceId: String,
        masterSelfSignature: ByteArray,
        admittedAtHlc: String?,
    ): TResult<Data?> = error("not used")

    var lastEpochFetchOwnerId: String? = null
    var nextEpoch: Long = 0L
    var failNextEpochFetch: Boolean = false

    override suspend fun fetchRealmEpoch(
        ownerId: String
    ): TResult<org.tenshu.core.number.TInteger> {
        lastEpochFetchOwnerId = ownerId
        return if (failNextEpochFetch) {
            TResult.Failure.create(
                context,
                GenericError.create(context, "epoch fetch failed")
            )
        } else {
            TResult.Success.create(context, nextEpoch.toTInteger(context))
        }
    }
}
