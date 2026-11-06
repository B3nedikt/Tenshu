package org.tenshu.sync.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.GenericError
import org.tenshu.core.TResult
import org.tenshu.core.TenshuException
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toTList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.core.TContext
import org.tenshu.core.entity.DataId
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.core.toTByteArray
import org.tenshu.core.toTString
import org.tenshu.identity.Device
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.sync.ApprovePairingRequest
import org.tenshu.sync.PairingRequest
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.common.SyncRelay
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.sync.proxy.impl.ApprovePairingRequestImplementation
import org.tenshu.sync.proxy.impl.GetPendingPairingRequestsImplementation
import org.tenshu.sync.proxy.security.PairingRendezvous
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import org.tenshu.core.common.util.toStamp
import org.tenshu.core.common.util.decodeHlcStamp
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApprovePairingRequestImplementationTest {

    private lateinit var testProxy: TestProxy
    private lateinit var syncManager: SyncManagerImpl
    private lateinit var crypto: RecordingApproveCryptoManager
    private lateinit var relay: RecordingApproveRelay
    private lateinit var impl: ApprovePairingRequestImplementation

    /**
     * ⚠ A `CoroutineScope` extension rather than an `@BeforeTest`, so the [TestProxy] rides the
     * caller's `TestScope` and `runTest` cancels the loops this fixture's `initSyncService` starts.
     * `@AfterTest` is not an option here: this is commonTest, where it must return `Unit` and
     * `runBlocking` does not exist (`docs/tech-debt.md` fix-item 5).
     */
    private fun CoroutineScope.setup() {
        testProxy = TestProxy(
            schemaRegistry = createCoreTestRegistry(),
            coroutineScope = this,
        ).also {
            it.schemaRegistry.registerConfig(syncServiceCommonModule)
            it.schemaRegistry.registerConfig(identityServiceCommonModule)
        }
        crypto = RecordingApproveCryptoManager()
        syncManager = SyncManagerImpl(
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate(),
            context = testProxy.tContext
        )
        relay = RecordingApproveRelay(testProxy.tContext)
        impl = ApprovePairingRequestImplementation(relay, syncManager, crypto)
    }

    /**
     * `runTest`, with [setup] already run. Every test in this class needs the fixture, so the
     * call belongs here once rather than as the first line of each body.
     *
     * ⚠⚠ It cannot be an `@BeforeTest`, and that is the whole point: [setup] takes a
     * `CoroutineScope` receiver so the [TestProxy] rides the **`TestScope`**, and `@BeforeTest`
     * runs outside `runTest` where no such scope exists. Nor can the teardown move to
     * `@AfterTest` — this is `commonTest`, where `@AfterTest` returns `Unit` and cannot suspend,
     * and `runBlocking` does not exist. Letting `runTest` own the proxy is what cancels the
     * `SyncScheduler` and 1-second `ScheduleDrainLoop` that `initSyncService` starts
     * (`docs/tech-debt.md` fix-item 5). Same shape as `:maps-shared-logic`'s `vmTest`.
     */
    private fun syncTest(body: suspend TestScope.() -> Unit): TestResult = runTest {
        setup()
        body()
    }

    /**
     * The host UI's poll. Approve admits only a request this session has shown a SAS for (threat
     * model T-P-14), so every approve below is preceded by the poll that precedes it in the app.
     */
    private suspend fun showRequestToUser() {
        GetPendingPairingRequestsImplementation(relay, syncManager, crypto).execute<Data>(
            executionContext = testProxy.executionContext,
            operationContext = null,
            implementationFactory = { null },
            caller = null,
            params = null,
        )
    }

    /** Saves a freshly-paired (unauthorized) Device entity locally — the state Device A
     *  observes when Device B has just registered itself per device-mgmt §2.4 step 5. */
    private fun seedUnauthorizedDevice(deviceId: String): DataId {
        val device = Device.create(
            context = testProxy.tContext,
            deviceId = deviceId,
            name = "Tablet",
            publicKey = byteArrayOf(0x01),
            isAuthorized = false
        )
        testProxy.executionContext.saveData(device)
        return DataId.StringDataId.create(testProxy.tContext, Device.ID, deviceId)
    }

    @Test
    fun success_flipsLocalDeviceIsAuthorized_andClearsState() = syncTest {
        val deviceId = FIXTURE_JOINER_ID
        // A host always has an owner before it can pair (initialize first); a pairing
        // context now requires one (StartPairing gates on it).
        syncManager.initialize("recovery-phrase")
        syncManager.generatePairingContext()
        val secret = syncManager.activePairingContext!!.secret
        relay.pairingRequests.add(mockRequest(deviceId, secret))
        showRequestToUser()
        val deviceDataId = seedUnauthorizedDevice(deviceId)

        impl.execute<Data>(
            executionContext = testProxy.executionContext,
            operationContext = null,
            implementationFactory = { null },
            caller = null,
            params = mapOf(ApprovePairingRequest.Params.DEVICE_ID to deviceId.toTString(testProxy.tContext))
        )

        assertNull(syncManager.activePairingContext, "Pairing context should be cleared")
        assertNotNull(relay.lastResponse, "Relay should have received response")
        // F7: the response mailbox is addressed by the secret's hash, never the secret.
        assertEquals(PairingRendezvous.joinSecretHash(secret), relay.lastResponse?.first)
        assertTrue(relay.lastResponse?.first != secret, "the raw pairing secret must not reach the relay")

        // The load-bearing assertion of the migration: the local Device entity now has
        // isAuthorized=true. The SyncObserver picks this up and produces a SyncBlock that
        // propagates to other realm devices — the sync flow replaces the relay's
        // /identity/authorize-device endpoint per device-mgmt §2.4 step 7.
        val updated = testProxy.database.getData(deviceDataId) as? Device
        assertNotNull(updated, "Device entity should still exist after approve")
        assertEquals(true, updated.isAuthorized.get())

        // Master delegation submitted with the expected shape. Per sync-relay-api.md §3.3
        // the canonical message is (newDevicePublicKey || newDeviceId || epoch big-endian).
        val delegation = assertNotNull(
            relay.lastDelegation,
            "Relay should have received the master-delegation extension"
        )
        assertEquals(deviceId, delegation.newDeviceId)
        assertEquals(0L, delegation.epoch)
        // Signature byte-equals the stub-Ed25519 of (msg, masterPrivateKey). Pinning
        // here guards against client/server drift on the canonical encoding.
        val expectedMessage = org.tenshu.sync.common.pairingDelegationMessage(
            delegation.newDevicePublicKey, delegation.newDeviceId, 0L,
        )
        val expectedSignature = org.tenshu.core.security.stubEd25519Signature(
            syncManager.currentMasterKeyPair!!.privateKey,
            expectedMessage
        )
        assertContentEquals(expectedSignature, delegation.masterDelegation)
    }

    @Test
    fun approval_carriesAnAdmissionStampFromThisDevicesOwnClock() = syncTest {
        val deviceId = FIXTURE_JOINER_ID
        syncManager.initialize("recovery-phrase")
        syncManager.generatePairingContext()
        val secret = syncManager.activePairingContext!!.secret
        relay.pairingRequests.add(mockRequest(deviceId, secret))
        showRequestToUser()
        seedUnauthorizedDevice(deviceId)
        val beforeApproval = syncManager.hlcManager.current().toStamp()

        impl.execute<Data>(
            executionContext = testProxy.executionContext,
            operationContext = null,
            implementationFactory = { null },
            caller = null,
            params = mapOf(ApprovePairingRequest.Params.DEVICE_ID to deviceId.toTString(testProxy.tContext))
        )

        // The relay compares this stamp against the Device tombstone that would revoke the same
        // device (DeviceRegistry.revokeIfNotStale). An approval that sends nothing leaves the
        // ordering guard with nothing to compare, so a stale "retire this device" queued hours
        // earlier still wins — the defect this whole change exists to close.
        val raw = assertNotNull(relay.lastAdmittedAtHlc, "Approval must send an admission stamp")
        val stamp = assertNotNull(decodeHlcStamp(raw), "Admission stamp must decode: $raw")
        assertEquals(syncManager.hlcManager.deviceId, stamp.deviceId)
        // Strictly greater, not >=: admissionStamp() ticks rather than reading current(), so the
        // stamp dominates everything this device has already observed and cannot tie with a
        // tombstone it has seen. A regression to current() fails here.
        assertTrue(
            stamp > beforeApproval,
            "Admission stamp $stamp must advance past the pre-approval clock $beforeApproval"
        )
    }

    @Test
    fun noActiveContext_throwsTenshuException() = syncTest {
        // Phase 3 of `docs/proposals/core-error-channel.md`: impls throw typed
        // `TenshuException`; the catch-all at LocalTaskExecutor unwraps to
        // `TResult.Failure(error)` at the dispatch boundary.
        val ex = assertFailsWith<TenshuException> {
            impl.execute<Data>(
                executionContext = testProxy.executionContext,
                operationContext = null,
                implementationFactory = { null },
                caller = null,
                params = mapOf(
                    ApprovePairingRequest.Params.DEVICE_ID to "any".toTString(testProxy.tContext)
                )
            )
        }
        assertTrue(
            (ex.error as GenericError).message.get().contains("No active pairing context")
        )
    }

    @Test
    fun requestNotFound_throwsTenshuException() = syncTest {
        syncManager.initialize("recovery-phrase")
        syncManager.generatePairingContext()
        val ex = assertFailsWith<TenshuException> {
            impl.execute<Data>(
                executionContext = testProxy.executionContext,
                operationContext = null,
                implementationFactory = { null },
                caller = null,
                params = mapOf(
                    ApprovePairingRequest.Params.DEVICE_ID to "unknown".toTString(testProxy.tContext)
                )
            )
        }
        assertTrue(
            (ex.error as GenericError).message.get().contains("Pairing request not found")
        )
    }

    @Test
    fun joinerWithoutMasterKeyOrPhrase_throwsUserFacingError_andPostsNothing() = syncTest {
        // A phrase-less joiner: `initializeWithKeys` sets ownerId + derivedKeys (so it can
        // poll + compute the SAS) but NEVER the master key, and no recovery phrase is stored.
        // Approving a new device needs A1, so it must fail — with the user-facing P0 message,
        // and without posting anything to the relay (delegated-device-admission-authority.md).
        syncManager.initializeWithKeys(
            SyncCryptoManager.DerivedKeys(ByteArray(32) { 1 }, ByteArray(32) { 2 }),
            ownerId = "host-owner",
        )
        syncManager.generatePairingContext()
        val secret = syncManager.activePairingContext!!.secret
        val deviceId = FIXTURE_JOINER_ID
        relay.pairingRequests.add(mockRequest(deviceId, secret))
        showRequestToUser()
        seedUnauthorizedDevice(deviceId)

        val ex = assertFailsWith<TenshuException> {
            impl.execute<Data>(
                executionContext = testProxy.executionContext,
                operationContext = null,
                implementationFactory = { null },
                caller = null,
                params = mapOf(ApprovePairingRequest.Params.DEVICE_ID to deviceId.toTString(testProxy.tContext))
            )
        }
        assertTrue(
            (ex.error as GenericError).message.get().contains("can't add new devices"),
            "must surface the user-facing 'can't add new devices' message, not the internal one",
        )
        assertNull(relay.lastResponse, "a joiner that can't admit must post no pairing response")
    }

    // (Removed) `uninitializedSyncManager_throwsTenshuException`: its scenario — an active
    // pairing context on a manager with no keys/owner — is now unreachable. Pairing
    // requires a configured owner at creation (generatePairingContext gates on it), so you
    // can no longer reach approve's "not initialized" guard via a context built without a
    // key. The no-owner contract is pinned by PairingRequiresOwnerTest instead.

    @Test
    fun noLocalEntity_succeeds_byCreatingFromPairingRequest() = syncTest {
        // The common case on a clean pair: Device B has POSTed the pairing request to the
        // relay (carrying deviceId/deviceName/publicKey) but cannot have self-pushed its
        // own Device entity yet — that step happens in `GetPairingStatus`, which runs only
        // AFTER this approve POSTs the pairing response. Pre-fix, the approve failed here
        // because it tried to look up a local entity that physically couldn't exist; the
        // newly-paired device then never appeared on Device A's dashboard. The fix:
        // construct the entity from the PairingRequest payload, with isAuthorized=true.
        val deviceId = FIXTURE_JOINER_ID
        syncManager.initialize("recovery-phrase")
        syncManager.generatePairingContext()
        val secret = syncManager.activePairingContext!!.secret
        relay.pairingRequests.add(mockRequest(deviceId, secret))
        showRequestToUser()
        // Intentionally no seedUnauthorizedDevice() — Device A hasn't seen the entity yet.

        impl.execute<Data>(
            executionContext = testProxy.executionContext,
            operationContext = null,
            implementationFactory = { null },
            caller = null,
            params = mapOf(ApprovePairingRequest.Params.DEVICE_ID to deviceId.toTString(testProxy.tContext))
        )

        assertNull(syncManager.activePairingContext, "Pairing context should be cleared")

        val createdDataId = DataId.StringDataId.create(testProxy.tContext, Device.ID, deviceId)
        val created = testProxy.database.getData(createdDataId) as? Device
        assertNotNull(created, "Device entity should have been created from PairingRequest")
        assertEquals(true, created.isAuthorized.get(), "Created entity is authoritatively admitted")
        assertEquals("Tablet", created.name.get(), "Name comes from PairingRequest")
        // Device entity holds the joiner's signing key (the value the relay
        // registered + that JWT/block-signature verification resolves to),
        // not the ephemeral X25519 pairing key (mockRequest:publicKey = 0x01).
        assertContentEquals(ByteArray(32) { 0x02 }, created.publicKey.get())
    }

    @Test
    fun relayForgotRealm_reBootstrapsAndRetriesResponse() = syncTest {
        // The relay lost this realm (in-memory restart / redeploy without durable storage):
        // the first pairing-response POST fails ("Realm not found" / 401). The approver
        // holds the master identity, so it must re-bootstrap the realm genesis and retry —
        // otherwise the encrypted key never reaches the mailbox the joiner polls, and the
        // joiner times out on the SAS screen (the reported web-pairing bug).
        val deviceId = FIXTURE_JOINER_ID
        syncManager.initialize("recovery-phrase")
        syncManager.generatePairingContext()
        val secret = syncManager.activePairingContext!!.secret
        relay.pairingRequests.add(mockRequest(deviceId, secret))
        showRequestToUser()
        seedUnauthorizedDevice(deviceId)
        // First response POST fails; it succeeds only after a re-bootstrap (createRealm).
        relay.failResponseUntilReBootstrap = true

        impl.execute<Data>(
            executionContext = testProxy.executionContext,
            operationContext = null,
            implementationFactory = { null },
            caller = null,
            params = mapOf(ApprovePairingRequest.Params.DEVICE_ID to deviceId.toTString(testProxy.tContext))
        )

        assertEquals(1, relay.createRealmCalls, "must re-assert the forgotten realm genesis exactly once")
        assertNotNull(relay.lastResponse, "the retried response POST must deliver the encrypted key")
        assertEquals(PairingRendezvous.joinSecretHash(secret), relay.lastResponse?.first)
        assertNull(syncManager.activePairingContext, "success still clears the pairing context")
    }

    @Test
    fun relayKeptTheRealmButForgotThisDevice_selfAdmits_ratherThanTellingTheUserToRePair() = syncTest {
        // ⚠⚠ The half the old self-heal missed. `reassertGenesis` alone repairs only a relay that
        // lost the WHOLE realm; when the realm survives and just this device's registration is
        // gone, createRealm answers 409 — a Failure on purpose — the retry is refused identically,
        // and the approve surfaces "this device is no longer recognised … pair this device again"
        // for a state a self-admit fixes in one call.
        val deviceId = FIXTURE_JOINER_ID
        syncManager.initialize("recovery-phrase")
        syncManager.generatePairingContext()
        val secret = syncManager.activePairingContext!!.secret
        relay.pairingRequests.add(mockRequest(deviceId, secret))
        showRequestToUser()
        seedUnauthorizedDevice(deviceId)
        relay.failResponseUntilReBootstrap = true
        relay.realmExistsButDeviceForgotten = true

        impl.execute<Data>(
            executionContext = testProxy.executionContext,
            operationContext = null,
            implementationFactory = { null },
            caller = null,
            params = mapOf(ApprovePairingRequest.Params.DEVICE_ID to deviceId.toTString(testProxy.tContext))
        )

        assertEquals(1, relay.createRealmCalls, "genesis is still attempted first")
        assertEquals(
            1,
            relay.admitDeviceCalls,
            "the 409 must fall through to a self-admit — that is the repair genesis cannot do",
        )
        assertNotNull(
            relay.lastResponse,
            "and the retried response POST must then deliver the key, not fail with 'pair again'",
        )
        assertEquals(PairingRendezvous.joinSecretHash(secret), relay.lastResponse?.first)
    }

    @Test
    fun relayResponsePermanentlyFails_throwsInsteadOfSilentSuccess() = syncTest {
        // Even after a re-bootstrap the response POST keeps failing (relay genuinely
        // unreachable). The approve must surface the error rather than dismiss the dialog
        // as if it worked — the old code discarded the POST result, so the host looked
        // successful while the joiner never received anything.
        val deviceId = FIXTURE_JOINER_ID
        syncManager.initialize("recovery-phrase")
        syncManager.generatePairingContext()
        val secret = syncManager.activePairingContext!!.secret
        relay.pairingRequests.add(mockRequest(deviceId, secret))
        showRequestToUser()
        seedUnauthorizedDevice(deviceId)
        relay.alwaysFailResponse = true

        assertFailsWith<TenshuException> {
            impl.execute<Data>(
                executionContext = testProxy.executionContext,
                operationContext = null,
                implementationFactory = { null },
                caller = null,
                params = mapOf(ApprovePairingRequest.Params.DEVICE_ID to deviceId.toTString(testProxy.tContext))
            )
        }
        // It still attempted the self-heal once before giving up.
        assertEquals(1, relay.createRealmCalls)
    }

    private suspend fun approve(deviceId: String) {
        impl.execute<Data>(
            executionContext = testProxy.executionContext,
            operationContext = null,
            implementationFactory = { null },
            caller = null,
            params = mapOf(ApprovePairingRequest.Params.DEVICE_ID to deviceId.toTString(testProxy.tContext))
        )
    }

    @Test
    fun firstPostLandsButItsReplyIsLost_andTheJoinerAcksBeforeTheRetry_theApproveSucceeds() = syncTest {
        // F7 retires an acked hash, so the retry of a post that DID land — reply lost, joiner
        // already fetched and acked — is refused "already consumed". That is delivery, not
        // failure: the joiner is paired, and the host must still write its Device row rather than
        // report an error and skip it.
        val deviceId = FIXTURE_JOINER_ID
        syncManager.initialize("recovery-phrase")
        syncManager.generatePairingContext()
        val secret = syncManager.activePairingContext!!.secret
        relay.pairingRequests.add(mockRequest(deviceId, secret))
        showRequestToUser()
        val deviceDataId = seedUnauthorizedDevice(deviceId)
        relay.replyLostThenConsumed = true

        approve(deviceId)

        assertEquals(2, relay.responsePosts, "the lost reply is retried once")
        assertNotNull(relay.lastResponse, "the first post delivered the key")
        assertEquals(true, (testProxy.database.getData(deviceDataId) as? Device)?.isAuthorized?.get())
        assertNull(syncManager.activePairingContext, "success clears the pairing context")
    }

    @Test
    fun aFirstPostAnsweredConsumed_isNotReadAsDelivered() = syncTest {
        // "Consumed" on a FIRST post means an earlier approve delivered different admission
        // material; recording this attempt's would stamp a capability the joiner never received.
        val deviceId = FIXTURE_JOINER_ID
        syncManager.initialize("recovery-phrase")
        syncManager.generatePairingContext()
        val secret = syncManager.activePairingContext!!.secret
        relay.pairingRequests.add(mockRequest(deviceId, secret))
        showRequestToUser()
        seedUnauthorizedDevice(deviceId)
        relay.alwaysConsumed = true

        assertFailsWith<TenshuException> { approve(deviceId) }
        assertEquals(1, relay.responsePosts, "a consumed first post is not retried")
        assertEquals(0, relay.createRealmCalls, "nor does it trigger the realm self-heal")
    }

    @Test
    fun stampsCurrentRealmEpoch_inDelegationAndFlippedEntity() = syncTest {
        // After two prior Device revocations, syncManager.currentRealmEpoch should be 2;
        // the approve flow must stamp that into both the master-delegation message AND
        // the local Device entity's `admittedAtEpoch`. Until this test, we relied only
        // on the genesis-zero default.
        val deviceId = FIXTURE_JOINER_ID
        syncManager.initialize("recovery-phrase")
        syncManager.generatePairingContext()
        val secret = syncManager.activePairingContext!!.secret
        relay.pairingRequests.add(mockRequest(deviceId, secret))
        showRequestToUser()
        seedUnauthorizedDevice(deviceId)

        // Inject the realm-epoch provider (in production this is wired by
        // `initSyncService` from `TombstoneRegistry.tombstoneCountForType(Device.ID)`).
        syncManager.setRealmEpochProvider { 2L }

        impl.execute<Data>(
            executionContext = testProxy.executionContext,
            operationContext = null,
            implementationFactory = { null },
            caller = null,
            params = mapOf(ApprovePairingRequest.Params.DEVICE_ID to deviceId.toTString(testProxy.tContext))
        )

        // Delegation message carries the right epoch (big-endian 2 = 0,0,0,0,0,0,0,2).
        val delegation = assertNotNull(relay.lastDelegation)
        assertEquals(2L, delegation.epoch)

        // Flipped local entity carries the same epoch — receivers will use this for
        // the T-K-02 check once the relay-side enforcement ships.
        val updatedDataId = DataId.StringDataId.create(
            testProxy.tContext, Device.ID, deviceId
        )
        val updated = testProxy.database.getData(updatedDataId) as Device
        assertEquals(2L, updated.admittedAtEpoch.get())
    }

    // publicKey 0x01 is the ephemeral X25519 key; signingPublicKey 0x02 is distinct — the
    // joiner's persistent Ed25519 device signing key, the value the master delegation signs
    // and the relay registers.
    private fun mockRequest(deviceId: String, secret: String): SyncRelay.RelayPairingRequest =
        sealedRelayRequest(crypto, deviceId, secret)
}

private class RecordingApproveCryptoManager : SyncCryptoManager {
    override fun deriveKeys(recoveryPhrase: String): SyncCryptoManager.DerivedKeys {
        val bytes = recoveryPhrase.encodeToByteArray()
        return SyncCryptoManager.DerivedKeys(bytes, bytes.reversedArray())
    }

    override fun encrypt(payload: ByteArray, key: ByteArray): ByteArray = payload
    override fun decrypt(encryptedPayload: ByteArray, key: ByteArray): ByteArray = encryptedPayload
    override fun generateKeyPair(): SyncCryptoManager.KeyPair =
        SyncCryptoManager.KeyPair(ByteArray(32) { 0x01 }, ByteArray(32) { 0x02 })

    override fun calculateSharedSecret(
        privateKey: ByteArray,
        otherPublicKey: ByteArray
    ): ByteArray =
        privateKey + otherPublicKey

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

    // SR-3b.4: the approve path now derives A13 to hand the joiner via the pairing
    // key-blob. A deterministic stub keypair keeps these approve-flow assertions green.
    override fun deriveRealmEncryptionKeyPair(recoveryPhrase: String): SyncCryptoManager.KeyPair {
        val seed = org.tenshu.core.security.stubEd25519Signature(
            "tenshu-realm-enc-stub".encodeToByteArray(),
            recoveryPhrase.encodeToByteArray()
        )
        return SyncCryptoManager.KeyPair(seed, seed)
    }
}

private class RecordingApproveRelay(private val context: TContext) : SyncRelay {
    val pairingRequests = mutableListOf<SyncRelay.RelayPairingRequest>()
    var lastResponse: Pair<String, ByteArray>? = null

    // Self-heal simulation knobs (relay-forgot-realm case).
    var createRealmCalls = 0
        private set
    private var reBootstrapped = false

    /** First response POST fails; subsequent ones succeed once [createRealm] has run. */
    var failResponseUntilReBootstrap = false

    /** Every response POST fails (relay genuinely unreachable). */
    var alwaysFailResponse = false

    /** The first response POST lands but its reply is lost; every later one is refused consumed. */
    var replyLostThenConsumed = false

    /** Every response POST is refused "already consumed" (F7). */
    var alwaysConsumed = false

    var responsePosts = 0
        private set

    private fun consumed(): TResult<Data?> = TResult.Failure.create(
        context,
        GenericError.create(context, "Response failed: $PAIRING_RESPONSE_CONSUMED_MARKER: 409"),
    )

    /**
     * The relay still HAS the realm but has forgotten this device: `createRealm` answers 409, and
     * only a self-admit repairs it. The commoner amnesia shape, and the one genesis alone misses.
     */
    var realmExistsButDeviceForgotten = false
    var admitDeviceCalls = 0
        private set

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
        SyncRelay.PairingRequestsFetch.Found(pairingRequests.toList())

    data class PairingResponseDelegation(
        val newDevicePublicKey: ByteArray,
        val newDeviceId: String,
        val masterDelegation: ByteArray,
        val epoch: Long
    )

    var lastDelegation: PairingResponseDelegation? = null

    /** The admission stamp the client sent, so a test can prove one was sent at all. */
    var lastAdmittedAtHlc: String? = null

    override suspend fun postPairingResponse(
        pairingSecretHash: String,
        encryptedMasterKey: ByteArray,
        newDevicePublicKey: ByteArray,
        newDeviceId: String,
        masterDelegation: ByteArray,
        epoch: Long,
        admissionCertChain: ByteArray?,
        admittedAtHlc: String?,
    ): TResult<Data?> {
        responsePosts++
        if (alwaysConsumed) return consumed()
        if (replyLostThenConsumed) {
            if (responsePosts > 1) return consumed()
            lastResponse = pairingSecretHash to encryptedMasterKey
            return TResult.Failure.create(context, GenericError.create(context, "Network error"))
        }
        if (alwaysFailResponse || (failResponseUntilReBootstrap && !reBootstrapped)) {
            return TResult.Failure.create(
                context,
                GenericError.create(context, "Realm not found"),
            )
        }
        lastResponse = pairingSecretHash to encryptedMasterKey
        lastAdmittedAtHlc = admittedAtHlc
        lastDelegation = PairingResponseDelegation(
            newDevicePublicKey,
            newDeviceId,
            masterDelegation,
            epoch
        )
        return TResult.Success.create(context, null)
    }

    override suspend fun ackPairingResponse(pairingSecretHash: String): TResult<Data?> =
        error("not used")

    override suspend fun getPairingResponse(pairingSecretHash: String): TResult<Data?> =
        error("not used")

    override suspend fun createRealm(
        ownerId: String,
        masterPublicKey: ByteArray,
        firstDevicePublicKey: ByteArray,
        firstDeviceId: String,
        masterSelfSignature: ByteArray,
        admittedAtHlc: String?,
    ): TResult<Data?> {
        createRealmCalls++
        if (realmExistsButDeviceForgotten) {
            // 409 — and `reassertGenesis` surfaces it as a Failure on purpose.
            return TResult.Failure.create(
                context,
                GenericError.create(context, "409 Conflict — Realm already exists"),
            )
        }
        reBootstrapped = true
        return TResult.Success.create(context, null)
    }

    override suspend fun admitDevice(
        ownerId: String,
        newDeviceId: String,
        newDevicePublicKey: ByteArray,
        masterDelegation: ByteArray,
        epoch: Long,
        admittedAtHlc: String?,
    ): TResult<Data?> {
        admitDeviceCalls++
        // The self-admit is what actually restores this device, so it is what unblocks the retry.
        reBootstrapped = true
        return TResult.Success.create(context, null)
    }

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
