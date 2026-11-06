package org.tenshu.sync.proxy

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
import org.tenshu.core.common.delegate.SecureStorageDelegate
import org.tenshu.core.common.impl.TByteArrayDto
import org.tenshu.core.common.proxy.executor.OperationImplementationFactory
import org.tenshu.core.core.TContext
import org.tenshu.core.entity.DataId
import org.tenshu.core.number.toTInteger
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.core.toTByteArray
import org.tenshu.core.toTString
import org.tenshu.identity.Device
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.sync.GetPairingStatus
import org.tenshu.sync.PairingRequest
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.common.SyncRelay
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.sync.proxy.impl.GetPairingStatusImplementation
import org.tenshu.sync.proxy.security.PairingRendezvous
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GetPairingStatusImplementationTest {

    private lateinit var testProxy: TestProxy
    private lateinit var syncManager: SyncManagerImpl
    private lateinit var crypto: ScriptedCryptoManager
    private lateinit var relay: ScriptedPairingResponseRelay
    private lateinit var impl: GetPairingStatusImplementation
    private lateinit var storage: SecureStorageDelegate

    @BeforeTest
    fun setup() {
        testProxy = TestProxy(schemaRegistry = createCoreTestRegistry()).also {
            it.schemaRegistry.registerConfig(syncServiceCommonModule)
            // Required so the saved Device entity has its DataIdProvider trait registered
            // (added in device-mgmt Phase 1).
            it.schemaRegistry.registerConfig(identityServiceCommonModule)
        }
        crypto = ScriptedCryptoManager()
        storage = InMemorySecureStorageDelegate()
        syncManager = SyncManagerImpl(
            cryptoManager = crypto,
            storageDelegate = storage,
            context = testProxy.tContext
        )
        relay = ScriptedPairingResponseRelay(testProxy.tContext)
        impl = GetPairingStatusImplementation(relay, syncManager, crypto)
    }

    @Test
    fun pendingResponse_returnsFalseAndDoesNotInitialize() = runTest {
        val secret = "secret-pending"
        val myKeyPair = SyncCryptoManager.KeyPair(
            publicKey = byteArrayOf(1, 2, 3),
            privateKey = byteArrayOf(4, 5, 6)
        )
        syncManager.startHandshake(secret, myKeyPair, hostOwnerId = "host-owner")
        // relay.responses is empty → still polling

        val result = impl.execute<TBoolean>(
            executionContext = testProxy.executionContext,
            operationContext = null,
            implementationFactory = OperationImplementationFactory { null },
            caller = null,
            params = mapOf(
                GetPairingStatus.Params.PAIRING_SECRET to secret.toTString(testProxy.tContext),
                GetPairingStatus.Params.OTHER_DEVICE_PUBLIC_KEY to
                        byteArrayOf(7, 7, 7).toTByteArray(testProxy.tContext)
            )
        )

        val flag = assertNotNull(result, "Success must carry a TBoolean")
        assertEquals(false, flag.get(), "Should report not-yet-paired")

        // Critical: the polling caller must be able to retry — handshake stays put.
        assertNotNull(syncManager.getHandshake(secret), "Handshake should still be retained")
        assertFalse(syncManager.isEnabled(), "SyncManager must not initialize on pending response")

        // storage-entitlement-sync.md §3.3: the joiner-bootstrap latch must NOT be set
        // until pairing actually completes (no master key served yet ⇒ no funding proof).
        assertFalse(
            syncManager.joinedFundedRealm,
            "joinedFundedRealm must not be set while the pairing response is still pending",
        )

        // No ack on the pending case — there's nothing to consume.
        assertEquals(emptyList(), relay.ackedSecrets)
    }

    @Test
    fun relayPollFailure_propagatesAsThrow_andRetainsHandshake() = runTest {
        // A relay poll that *fails* (network / CORS / 5xx → TResult.Failure) must be
        // distinguishable from "the approval hasn't arrived yet" (404 → Success(null)).
        // Pre-fix both folded into `return false`, so a transport error looked identical to
        // a pending handshake and the requester polled on forever — on the web, rendering
        // the SAS until the 5-minute budget elapsed with no surfaced cause. It must now
        // propagate so the poll loop can surface it.
        val secret = "secret-relay-down"
        syncManager.startHandshake(
            secret,
            SyncCryptoManager.KeyPair(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6)),
            hostOwnerId = "host-owner",
        )
        relay.forceResponseFailure = true

        assertFailsWith<TenshuException> {
            impl.execute<TBoolean>(
                executionContext = testProxy.executionContext,
                operationContext = null,
                implementationFactory = OperationImplementationFactory { null },
                caller = null,
                params = mapOf(
                    GetPairingStatus.Params.PAIRING_SECRET to secret.toTString(testProxy.tContext),
                    GetPairingStatus.Params.OTHER_DEVICE_PUBLIC_KEY to
                            byteArrayOf(7, 7, 7).toTByteArray(testProxy.tContext),
                ),
            )
        }

        // The failure is thrown before any local state changes, so a later poll (once the
        // blip clears) can still complete the same handshake.
        assertNotNull(
            syncManager.getHandshake(secret),
            "handshake must survive a transient relay failure so the poll can retry",
        )
        assertFalse(syncManager.isEnabled(), "a failed poll must not initialise SyncManager")
    }

    @Test
    fun completedResponse_decryptsAndInitializesAndClearsHandshake() = runTest {
        val secret = "secret-complete"
        val myKeyPair = SyncCryptoManager.KeyPair(
            publicKey = byteArrayOf(1, 2, 3),
            privateKey = byteArrayOf(4, 5, 6)
        )
        val otherPublicKey = byteArrayOf(7, 7, 7)
        val deliveredCiphertext = byteArrayOf(0x10, 0x20, 0x30)

        syncManager.startHandshake(secret, myKeyPair, hostOwnerId = "host-owner")
        // The pairing entry point (InitiatePairing) sets the device name; we simulate it
        // here so the Device entity that GetPairingStatus pushes carries the right name.
        syncManager.setCurrentDeviceName("Recovered Tablet")
        relay.respondWith(secret, deliveredCiphertext)
        crypto.programDecryption(deliveredCiphertext, ByteArray(32) { 0xAA.toByte() } + ByteArray(32) { 0xBB.toByte() })

        val result = impl.execute<TBoolean>(
            executionContext = testProxy.executionContext,
            operationContext = null,
            implementationFactory = OperationImplementationFactory { null },
            caller = null,
            params = mapOf(
                GetPairingStatus.Params.PAIRING_SECRET to secret.toTString(testProxy.tContext),
                GetPairingStatus.Params.OTHER_DEVICE_PUBLIC_KEY to
                        otherPublicKey.toTByteArray(testProxy.tContext)
            )
        )

        val flag = assertNotNull(result, "Success must carry a TBoolean")
        assertEquals(true, flag.get(), "Should report paired")

        // Handshake cleaned up so subsequent polls don't re-trigger.
        assertNull(syncManager.getHandshake(secret), "Handshake should be cleared after success")

        // SyncManager initialized with the two keys of the decrypted 64-byte key blob.
        val derived = assertNotNull(syncManager.getDerivedKeys())
        assertContentEquals(ByteArray(32) { 0xAA.toByte() }, derived.sharedIdentityKey)
        assertContentEquals(ByteArray(32) { 0xBB.toByte() }, derived.sharedVolumeKey)

        // Crypto used the correct key material.
        assertContentEquals(myKeyPair.privateKey, crypto.lastSharedSecretPrivateKey)
        assertContentEquals(otherPublicKey, crypto.lastSharedSecretOtherPublicKey)

        // Device B adopts the host realm's ownerId from the handshake context so its
        // subsequent JWTs verify against the relay's `(ownerId, deviceId)` registration.
        assertEquals("host-owner", syncManager.currentOwnerId)

        // storage-entitlement-sync.md §3.3: reaching completion means the relay served
        // the host's pairing response (only done for a registered, owner-funded realm),
        // so the joiner latches `joinedFundedRealm` — this opens the personal-realm
        // storage sync gate so its bootstrap pull (which delivers the entitlement) runs
        // even before its own device-local subscription is known. It must NOT have run
        // realm genesis, so `realmRelayRegistered` stays false (it flips later, after
        // the first successful pull).
        assertTrue(
            syncManager.joinedFundedRealm,
            "completing pairing must latch joinedFundedRealm (opens the joiner bootstrap gate)",
        )
        assertFalse(
            syncManager.realmRelayRegistered,
            "a joiner must not run realm genesis at pairing — realmRelayRegistered stays false",
        )

        // Ack the relay so the pairing-secret state is freed (sync-relay-api.md §3.3).
        // GET is peek-only; without this explicit ack the relay would hold the entry
        // until TTL.
        // F7: the ack addresses the mailbox by the secret's hash, never the secret.
        assertEquals(listOf(PairingRendezvous.joinSecretHash(secret)), relay.ackedSecrets)

        // Per docs/adr/0007-sync-device-management.md step 5, the new device pushes its own
        // Device entity into the synced DB. This is what eliminates the
        // "Invisible Self" bug from §1 — the device sees itself in `GetDevices`
        // immediately, before Device A's authoritative block has had a chance
        // to round-trip. `isAuthorized = true` because this branch is reached
        // only after the relay has served the master key Device A signed off
        // on; the dashboard's `dev.isAuthorized.get()` filter therefore lets
        // the joiner's own entry through without waiting for the next
        // `/sync/changes` poll.
        val ownDeviceId = DataId.StringDataId.create(
            context = testProxy.tContext,
            type = Device.ID,
            keyValue = syncManager.currentDeviceId
        )
        val savedDevice = testProxy.database.getData(ownDeviceId) as? Device
        assertNotNull(savedDevice, "Device B should have pushed its own Device entity")
        assertEquals(
            true,
            savedDevice.isAuthorized.get(),
            "Self-push happens after the master key has been decrypted from a " +
                "relay-served pairing response — Device A's master delegation is " +
                "already on file at that point, so the joiner is authorised."
        )
        assertEquals("Recovered Tablet", savedDevice.name.get())
        assertContentEquals(
            syncManager.getDeviceSigningKeyPair().publicKey,
            savedDevice.publicKey.get()
        )
    }

    @Test
    fun completingPairingForDifferentOwner_discardsSupersededOwnRealm() = runTest {
        // Genesis-then-join: this device already belongs to its OWN realm
        // ("own-owner-P") and still carries that realm's leftover recovery phrase +
        // relay-registered latch. Joining the desktop's realm must purge them so a
        // later restart / unlock can't silently revert the device onto its orphaned
        // realm (where it would push/pull the wrong owner's stream).
        syncManager.initializeWithKeys(
            SyncCryptoManager.DerivedKeys(ByteArray(32) { 1 }, ByteArray(32) { 2 }),
            ownerId = "own-owner-P",
        )
        storage.storeSecret("sync_recovery_phrase", "leftover own-genesis phrase")
        syncManager.realmRelayRegistered = true

        val secret = "secret-switch"
        val deliveredCiphertext = byteArrayOf(0x10, 0x20, 0x30)
        syncManager.startHandshake(
            secret,
            SyncCryptoManager.KeyPair(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6)),
            hostOwnerId = "host-owner",
        )
        syncManager.setCurrentDeviceName("Phone")
        relay.respondWith(secret, deliveredCiphertext)
        crypto.programDecryption(deliveredCiphertext, ByteArray(32) { 0xAA.toByte() } + ByteArray(32) { 0xBB.toByte() })

        impl.execute<TBoolean>(
            executionContext = testProxy.executionContext,
            operationContext = null,
            implementationFactory = OperationImplementationFactory { null },
            caller = null,
            params = mapOf(
                GetPairingStatus.Params.PAIRING_SECRET to secret.toTString(testProxy.tContext),
                GetPairingStatus.Params.OTHER_DEVICE_PUBLIC_KEY to
                    byteArrayOf(7, 7, 7).toTByteArray(testProxy.tContext),
            ),
        )

        assertEquals("host-owner", syncManager.currentOwnerId, "adopts the joined realm")
        assertNull(
            storage.getSecret("sync_recovery_phrase"),
            "superseded own-genesis phrase must be purged so a restart can't revert to it",
        )
        assertFalse(
            syncManager.realmRelayRegistered,
            "own-realm relay latch reset; the joined realm re-flips it on its first pull",
        )
    }

    @Test
    fun completingPairingForSameOwner_keepsExistingState() = runTest {
        // Re-pairing to the SAME realm (owner unchanged) must NOT discard local state —
        // the guard is owner-switch-only, so a benign re-pair keeps the device's
        // credential/cursor intact.
        syncManager.initializeWithKeys(
            SyncCryptoManager.DerivedKeys(ByteArray(32) { 1 }, ByteArray(32) { 2 }),
            ownerId = "host-owner",
        )
        storage.storeSecret("sync_recovery_phrase", "sentinel-should-survive")

        val secret = "secret-repair"
        val deliveredCiphertext = byteArrayOf(0x10, 0x20, 0x30)
        syncManager.startHandshake(
            secret,
            SyncCryptoManager.KeyPair(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6)),
            hostOwnerId = "host-owner",
        )
        syncManager.setCurrentDeviceName("Phone")
        relay.respondWith(secret, deliveredCiphertext)
        crypto.programDecryption(deliveredCiphertext, ByteArray(32) { 0xAA.toByte() } + ByteArray(32) { 0xBB.toByte() })

        impl.execute<TBoolean>(
            executionContext = testProxy.executionContext,
            operationContext = null,
            implementationFactory = OperationImplementationFactory { null },
            caller = null,
            params = mapOf(
                GetPairingStatus.Params.PAIRING_SECRET to secret.toTString(testProxy.tContext),
                GetPairingStatus.Params.OTHER_DEVICE_PUBLIC_KEY to
                    byteArrayOf(7, 7, 7).toTByteArray(testProxy.tContext),
            ),
        )

        assertEquals("host-owner", syncManager.currentOwnerId)
        assertNotNull(
            storage.getSecret("sync_recovery_phrase"),
            "re-pairing to the same realm must not discard state (no owner switch)",
        )
    }

    @Test
    fun missingHandshake_throws() = runTest {
        // No startHandshake() — polling without first calling InitiatePairing is a logic error.
        assertFailsWith<IllegalStateException> {
            impl.execute<TBoolean>(
                executionContext = testProxy.executionContext,
                operationContext = null,
                implementationFactory = OperationImplementationFactory { null },
                caller = null,
                params = mapOf(
                    GetPairingStatus.Params.PAIRING_SECRET to "no-such-secret".toTString(testProxy.tContext),
                    GetPairingStatus.Params.OTHER_DEVICE_PUBLIC_KEY to
                            byteArrayOf(7, 7, 7).toTByteArray(testProxy.tContext)
                )
            )
        }
    }
}

/**
 * SyncCryptoManager that lets tests script the decrypt() output by ciphertext, and
 * captures the inputs to calculateSharedSecret() so we can assert the operation passed
 * the correct key material.
 */
private class ScriptedCryptoManager : SyncCryptoManager {
    private val programmedDecryptions = mutableMapOf<List<Byte>, ByteArray>()
    var lastSharedSecretPrivateKey: ByteArray? = null
        private set
    var lastSharedSecretOtherPublicKey: ByteArray? = null
        private set

    fun programDecryption(ciphertext: ByteArray, plaintext: ByteArray) {
        programmedDecryptions[ciphertext.toList()] = plaintext
    }

    override fun deriveKeys(recoveryPhrase: String): SyncCryptoManager.DerivedKeys =
        error("not used")

    override fun encrypt(payload: ByteArray, key: ByteArray): ByteArray = error("not used")

    override fun decrypt(encryptedPayload: ByteArray, key: ByteArray): ByteArray =
        programmedDecryptions[encryptedPayload.toList()]
            ?: error("No decrypt programmed for ciphertext ${encryptedPayload.toList()}")

    // Used by SyncManager.getDeviceSigningKeyPair when GetPairingStatus pushes the
    // new device's own Device entity (docs/adr/0007-sync-device-management.md step 5).
    override fun generateKeyPair(): SyncCryptoManager.KeyPair {
        val pub = byteArrayOf(0x42, 0x43, 0x44)
        return SyncCryptoManager.KeyPair(pub, pub)
    }

    override fun calculateSharedSecret(
        privateKey: ByteArray,
        otherPublicKey: ByteArray
    ): ByteArray {
        lastSharedSecretPrivateKey = privateKey
        lastSharedSecretOtherPublicKey = otherPublicKey
        return privateKey + otherPublicKey
    }

    override fun signEd25519(privateKey: ByteArray, message: ByteArray): ByteArray =
        org.tenshu.core.security.stubEd25519Signature(privateKey, message)

    override fun verifyEd25519(
        publicKey: ByteArray,
        message: ByteArray,
        signature: ByteArray
    ): Boolean =
        signature.contentEquals(org.tenshu.core.security.stubEd25519Signature(publicKey, message))

    override fun deriveMasterKeyPair(recoveryPhrase: String): SyncCryptoManager.KeyPair =
        error("not used")
}

/**
 * SyncRelay that exposes only `getPairingResponse` and lets tests preload responses
 * for a pairing secret — stored, as the relay stores them after F7, under the secret's hash.
 * Other relay methods are not exercised by GetPairingStatus.
 */
private class ScriptedPairingResponseRelay(private val context: TContext) : SyncRelay {
    private val responses = mutableMapOf<String, ByteArray>()

    /** When set, `getPairingResponse` returns a `TResult.Failure` (simulated transport error). */
    var forceResponseFailure = false

    fun respondWith(pairingSecret: String, encryptedMasterKey: ByteArray) {
        responses[PairingRendezvous.joinSecretHash(pairingSecret)] = encryptedMasterKey
    }

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

    val ackedSecrets: MutableList<String> = mutableListOf()

    override suspend fun ackPairingResponse(pairingSecretHash: String): TResult<Data?> {
        ackedSecrets.add(pairingSecretHash)
        responses.remove(pairingSecretHash)
        return TResult.Success.create(context, null)
    }

    override suspend fun getPairingResponse(pairingSecretHash: String): TResult<Data?> {
        if (forceResponseFailure) {
            return TResult.Failure.create(
                context,
                GenericError.create(context, "relay unreachable"),
            )
        }
        val encrypted = responses[pairingSecretHash]
        return TResult.Success.create<Data?>(
            context,
            value = encrypted?.let { TByteArrayDto(it) }
        )
    }

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

    var fetchedEpoch: Long = 0L
    var fetchedEpochOwnerId: String? = null

    override suspend fun fetchRealmEpoch(
        ownerId: String
    ): TResult<org.tenshu.core.number.TInteger> {
        fetchedEpochOwnerId = ownerId
        return TResult.Success.create(context, fetchedEpoch.toTInteger(context))
    }
}
