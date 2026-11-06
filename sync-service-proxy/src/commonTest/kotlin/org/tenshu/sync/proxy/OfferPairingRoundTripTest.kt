package org.tenshu.sync.proxy

import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.core.TContext
import org.tenshu.core.TenshuException
import org.tenshu.core.entity.DataId
import org.tenshu.core.messageOrNull
import org.tenshu.core.number.toTInteger
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.core.security.stubEd25519Signature
import org.tenshu.core.toTString
import org.tenshu.identity.Device
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.sync.ApprovePairingOffer
import org.tenshu.sync.CancelPairingOffer
import org.tenshu.sync.DenyPairingOffer
import org.tenshu.sync.GetOfferStatus
import org.tenshu.sync.OfferPairing
import org.tenshu.sync.OfferPairingSession
import org.tenshu.sync.OfferStatus
import org.tenshu.sync.PairingOfferApprovalPrompt
import org.tenshu.sync.RespondToPairingOffer
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.common.SyncManager
import org.tenshu.sync.common.SyncRelay
import org.tenshu.sync.proxy.impl.ApprovePairingOfferImplementation
import org.tenshu.sync.proxy.impl.CancelPairingOfferImplementation
import org.tenshu.sync.proxy.impl.DenyPairingOfferImplementation
import org.tenshu.sync.proxy.impl.GetOfferStatusImplementation
import org.tenshu.sync.proxy.impl.OfferPairingImplementation
import org.tenshu.sync.proxy.impl.RespondToPairingOfferImplementation
import org.tenshu.sync.PairingFailedError
import org.tenshu.sync.PairingFailure
import kotlin.test.assertNull
import kotlin.test.assertIs
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import org.tenshu.core.common.util.decodeHlcStamp
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end offer-flow round trip (`sync-pairing-inverted-direction.md` §3, the offer-flow
 * analogue of `ApprovePairingRequestImplementationTest`): the new device (offerer) shows a QR,
 * an existing realm device (responder) scans + approves, and the offerer decrypts + installs.
 * Both devices share ONE symmetric-crypto instance (so ECDH is commutative across them) and ONE
 * relay (the offer store is server-side state).
 */
class OfferPairingRoundTripTest {

    private lateinit var crypto: SymmetricRoundTripCrypto
    private lateinit var relay: SharedOfferRelay

    // Responder = an existing realm device (holds A1 via initialize).
    private lateinit var responderProxy: TestProxy
    private lateinit var responderSM: SyncManagerImpl

    // Offerer = a brand-new device (no realm yet).
    private lateinit var offererProxy: TestProxy
    private lateinit var offererSM: SyncManagerImpl

    private fun proxy() = TestProxy(schemaRegistry = createCoreTestRegistry()).also {
        it.schemaRegistry.registerConfig(org.tenshu.sync.common.syncServiceCommonModule)
        it.schemaRegistry.registerConfig(identityServiceCommonModule)
    }

    @BeforeTest
    fun setup() {
        crypto = SymmetricRoundTripCrypto()
        responderProxy = proxy()
        offererProxy = proxy()
        relay = SharedOfferRelay(responderProxy.tContext)
        responderSM = SyncManagerImpl(crypto, InMemorySecureStorageDelegate(), responderProxy.tContext)
        offererSM = SyncManagerImpl(crypto, InMemorySecureStorageDelegate(), offererProxy.tContext)
        responderSM.initialize("responder recovery phrase")
    }

    private suspend fun offerPairing(deviceName: String): OfferPairingSession =
        OfferPairingImplementation(offererSM, crypto).execute<OfferPairingSession>(
            offererProxy.executionContext, null, { null }, null,
            mapOf(OfferPairing.Params.DEVICE_NAME to deviceName.toTString(offererProxy.tContext)),
        )!!

    private suspend fun respond(uri: String): PairingOfferApprovalPrompt =
        RespondToPairingOfferImplementation(responderSM, crypto, relay).execute<PairingOfferApprovalPrompt>(
            responderProxy.executionContext, null, { null }, null,
            mapOf(RespondToPairingOffer.Params.SCANNED_URI to uri.toTString(responderProxy.tContext)),
        )!!

    private suspend fun approve(hash: String) =
        ApprovePairingOfferImplementation(relay, responderSM, crypto).execute<Data>(
            responderProxy.executionContext, null, { null }, null,
            mapOf(ApprovePairingOffer.Params.PAIRING_SECRET_HASH to hash.toTString(responderProxy.tContext)),
        )

    private suspend fun deny(hash: String) =
        DenyPairingOfferImplementation(relay, responderSM, crypto).execute<Data>(
            responderProxy.executionContext, null, { null }, null,
            mapOf(DenyPairingOffer.Params.PAIRING_SECRET_HASH to hash.toTString(responderProxy.tContext)),
        )

    private suspend fun offerStatus(hash: String): OfferStatus =
        GetOfferStatusImplementation(relay, offererSM, crypto).execute<OfferStatus>(
            offererProxy.executionContext, null, { null }, null,
            mapOf(GetOfferStatus.Params.PAIRING_SECRET_HASH to hash.toTString(offererProxy.tContext)),
        )!!

    @Test
    fun fullApproveRoundTrip_installsKeys_matchesSas_authorsBothDeviceRows() = runTest {
        // 1. Offerer shows a QR.
        val session = offerPairing("Ben's Laptop")
        val uri = session.offerUri.get()
        val hash = session.pairingSecretHash.get()
        assertTrue(uri.startsWith("tenshu://offer?"), "offerer must produce an offer URI: $uri")

        // 2. Offerer poll before the responder acts → pending.
        assertEquals(OfferStatus.PENDING, offerStatus(hash).state.get())

        // 3. Responder scans + sees the approval prompt (name + SAS).
        val prompt = respond(uri)
        assertEquals(hash, prompt.pairingSecretHash.get(), "both sides key off the same secret hash")
        assertEquals("Ben's Laptop", prompt.deviceName.get())
        val responderSas = prompt.sas.get()
        assertTrue(responderSas.length == 6, "SAS is a 6-digit string")

        // 4. Responder approves.
        approve(hash)

        // The responder authored the offerer's authorised Device row.
        val offererDeviceId = offererSM.currentDeviceId
        val authoredOnResponder = responderProxy.database.getData(
            DataId.StringDataId.create(responderProxy.tContext, Device.ID, offererDeviceId),
        ) as? Device
        assertNotNull(authoredOnResponder, "responder authors the offerer's Device row")
        assertEquals(true, authoredOnResponder.isAuthorized.get())

        // 5. Offerer polls → approved, installs keys, adopts the realm.
        val status = offerStatus(hash)
        assertEquals(OfferStatus.APPROVED, status.state.get())
        assertEquals(responderSas, status.sas.get(), "the SAS must match on both screens")

        assertEquals(
            responderSM.currentOwnerId,
            offererSM.currentOwnerId,
            "the offerer adopts the responder realm's ownerId (else its JWTs 401)",
        )
        assertContentEquals(
            responderSM.getDerivedKeys()!!.sharedIdentityKey,
            offererSM.getDerivedKeys()!!.sharedIdentityKey,
            "the offerer installs the realm's derived keys",
        )
        assertTrue(offererSM.joinedFundedRealm, "storage sync gate opened for the joiner")

        // The offerer self-published its own authorised Device row.
        val selfRow = offererProxy.database.getData(
            DataId.StringDataId.create(offererProxy.tContext, Device.ID, offererDeviceId),
        ) as? Device
        assertNotNull(selfRow, "offerer self-publishes its Device row")
        assertEquals(true, selfRow.isAuthorized.get())

        // The offer session is torn down (privB zeroed + dropped).
        assertEquals(null, offererSM.getOfferSession(hash))

        // The responder (the approving side) stamps the admission. This flow is how a device the
        // relay has forgotten gets re-admitted, so it is the likeliest route for a
        // re-admission to race a retirement that has been queued on some other device for hours.
        val stamp = assertNotNull(
            decodeHlcStamp(relay.lastOfferHlc),
            "an approved offer response must carry an admission stamp",
        )
        assertEquals(responderSM.hlcManager.deviceId, stamp.deviceId)
    }

    @Test
    fun scannedAnnounce_putsTheSasOnTheOffererScreenBEFORETheResponderApproves() = runTest {
        // ⭐ The payoff of the whole track. Before A2 the offerer could not derive the SAS until
        // the approve POST carried the responder's ephemeral key, so the comparison was
        // necessarily post-approval — the user had to approve first and compare from memory
        // afterwards. Now both screens hold the code while the decision is still open.
        val session = offerPairing("Ben's Laptop")
        val uri = session.offerUri.get()
        val hash = session.pairingSecretHash.get()

        val prompt = respond(uri)

        // The decision has NOT been made — no approve() yet — and the offerer already has the SAS.
        val scanned = offerStatus(hash)
        assertEquals(OfferStatus.SCANNED, scanned.state.get())
        assertEquals(
            prompt.sas.get(),
            scanned.sas.get(),
            "both screens must show the SAME code, or the comparison this exists for is theatre",
        )

        // …and the terminal decision still completes normally afterwards.
        approve(hash)
        val approved = offerStatus(hash)
        assertEquals(OfferStatus.APPROVED, approved.state.get())
        assertEquals(prompt.sas.get(), approved.sas.get())
    }

    @Test
    fun anApprovalFromADifferentDeviceThanTheOneThatScanned_isRejectedBeforeAnythingIsInstalled() =
        runTest {
            // ⚠⚠ THE key-equality lock (§2.3), and the reason A2 is worth shipping rather than just
            // A3's nicer screen. Once an announce is pinned, a terminal `approved` carrying a
            // DIFFERENT publicKeyA means a third party posted it — so the sealed payload must not
            // be opened. This is T-P-07 moving from detect-after-install to reject-before-install.
            val session = offerPairing("Ben's Laptop")
            val uri = session.offerUri.get()
            val hash = session.pairingSecretHash.get()

            respond(uri)
            assertEquals(OfferStatus.SCANNED, offerStatus(hash).state.get(), "precondition: pinned")

            // A third party posts the terminal response under a key that is not the announced one.
            relay.forceOffer(
                hash,
                SyncRelay.OfferResponseFetch.Found(
                    ownerId = "attacker-owner",
                    status = "approved",
                    publicKeyA = ByteArray(32) { 0x5a },
                    payload = byteArrayOf(1, 2, 3),
                ),
            )

            assertFailsWith<TenshuException> { offerStatus(hash) }
            assertNull(
                offererSM.currentOwnerId,
                "a rejected approval must install NOTHING — no realm binding, no keys",
            )
        }

    @Test
    fun denyRoundTrip_offererSeesDenied_andInstallsNothing() = runTest {
        val session = offerPairing("Ben's Laptop")
        val hash = session.pairingSecretHash.get()
        respond(session.offerUri.get())

        deny(hash)

        val status = offerStatus(hash)
        assertEquals(OfferStatus.DENIED, status.state.get())
        assertEquals(null, offererSM.currentOwnerId, "a denied offer installs no realm")
        assertEquals(null, offererSM.getOfferSession(hash), "the offer session is dropped on denial")
    }

    @Test
    fun relayForgotThisDevice_responderReBootstraps_andTheRoundTripStillCompletes() = runTest {
        // The relay lost its device registry (data wipe / restore from an older backup / a
        // different relay host). It therefore refuses the RESPONDER — the device doing the adding —
        // at `knownDevices.getPublicKey(...)` with a 401, which is a permanent dead end without a
        // self-heal: `RealmRegistrar`'s once-ever latch means the responder never re-bootstraps on
        // its own, so every Approve tap 401s forever. The responder holds A1, so it must re-assert the genesis and retry once.
        val session = offerPairing("Ben's Laptop")
        val hash = session.pairingSecretHash.get()
        respond(session.offerUri.get())
        relay.refuseResponderUntilReBootstrap = true

        approve(hash)

        assertEquals(1, relay.createRealmCalls, "must re-assert the forgotten realm genesis exactly once")
        // The retry actually delivered: the offerer's poll finds the approval and installs keys.
        assertEquals(OfferStatus.APPROVED, offerStatus(hash).state.get())
        assertEquals(
            responderSM.currentOwnerId,
            offererSM.currentOwnerId,
            "the offerer adopts the realm, i.e. the mailbox really was written",
        )
    }

    @Test
    fun relayKeepsRefusingTheResponder_failsWithAnErrorAboutThisDevice() = runTest {
        // The repair ran and the relay still refuses this device (or it could not run at all). The
        // approve must fail — never a silent success — and the message must say that THIS device is
        // the unrecognised one. The raw relay text ("Unknown responder device") must not reach the
        // pairing screen verbatim, where it reads as a problem with the device being added.
        val session = offerPairing("Ben's Laptop")
        val hash = session.pairingSecretHash.get()
        respond(session.offerUri.get())
        relay.alwaysRefuseResponder = true

        val failure = assertFailsWith<TenshuException> { approve(hash) }

        assertEquals(1, relay.createRealmCalls, "it still attempts the self-heal once before giving up")
        // The same guarantee, now typed: the failure names THIS device as the unrecognised one
        // via a wire-stable reason rather than an English sentence
        // (`core-error-copy-catalogue.md` EC-4). The copy itself moved to the apps.
        val error = failure.error
        assertIs<PairingFailedError>(error, "the approve must fail with a typed pairing failure")
        assertEquals(
            PairingFailure.AdmitterUnrecognised,
            error.reason,
            "the error must name THIS device as the unrecognised one",
        )
        // ⚠ Stronger than the old wording check: the typed error carries no message field at all,
        // so raw relay/HTTP text ("401", "Unknown responder device") cannot reach a screen by
        // construction rather than by a substring assertion.
        assertNull(
            error.messageOrNull(),
            "a typed pairing failure must carry no freetext for a screen to render",
        )
        // Nothing was delivered, so the offerer must not be in a half-joined state.
        // ⚠ SCANNED, not PENDING, correctly: `respond()` announces before the relay starts
        // refusing, and the responder genuinely did scan. What matters is that a refused approve
        // installs NOTHING — asserted directly below, which is the property this test holds.
        assertEquals(OfferStatus.SCANNED, offerStatus(hash).state.get())
        assertNull(
            offererSM.currentOwnerId,
            "a refused approve must leave the offerer unbound — no realm, no keys",
        )
    }

    @Test
    fun cancel_dropsTheOfferSession() = runTest {
        val session = offerPairing("Ben's Laptop")
        val hash = session.pairingSecretHash.get()
        assertNotNull(offererSM.getOfferSession(hash))

        CancelPairingOfferImplementation(offererSM).execute<Data>(
            offererProxy.executionContext, null, { null }, null,
            mapOf(CancelPairingOffer.Params.PAIRING_SECRET_HASH to hash.toTString(offererProxy.tContext)),
        )
        assertEquals(null, offererSM.getOfferSession(hash))
    }
}

/**
 * Symmetric ECDH stub shared by both devices: `publicKey == privateKey == [uniqueTag]`, and
 * `calculateSharedSecret` combines the two tags order-independently so `calc(privB, pubA) ==
 * calc(privA, pubB)` across instances (the property real X25519 has). encrypt/decrypt are
 * identity (confidentiality is unit-tested elsewhere; this test pins the protocol wiring).
 */
private class SymmetricRoundTripCrypto : SyncCryptoManager {
    private var counter = 0

    override fun generateKeyPair(): SyncCryptoManager.KeyPair {
        // Distinct public/private arrays (as real X25519 keys are) so on-teardown zeroing of
        // the private key can't alias the public key a fake relay is still holding.
        val tag = (++counter).toByte()
        return SyncCryptoManager.KeyPair(publicKey = ByteArray(32) { tag }, privateKey = ByteArray(32) { tag })
    }

    override fun calculateSharedSecret(privateKey: ByteArray, otherPublicKey: ByteArray): ByteArray {
        val a = privateKey.firstOrNull() ?: 0
        val b = otherPublicKey.firstOrNull() ?: 0
        return if (a <= b) byteArrayOf(a, b) else byteArrayOf(b, a)
    }

    override fun encrypt(payload: ByteArray, key: ByteArray): ByteArray = payload
    override fun decrypt(encryptedPayload: ByteArray, key: ByteArray): ByteArray = encryptedPayload

    override fun deriveKeys(recoveryPhrase: String): SyncCryptoManager.DerivedKeys {
        // 32-byte keys — PairingKeyBlob packs/unpacks fixed-width 32-byte keys, so a
        // variable-length stub would mis-slice on the offerer's unpack.
        return SyncCryptoManager.DerivedKeys(
            sharedIdentityKey = stubEd25519Signature("identity".encodeToByteArray(), recoveryPhrase.encodeToByteArray()),
            sharedVolumeKey = stubEd25519Signature("volume".encodeToByteArray(), recoveryPhrase.encodeToByteArray()),
        )
    }

    override fun deriveMasterKeyPair(recoveryPhrase: String): SyncCryptoManager.KeyPair {
        val seed = stubEd25519Signature("master".encodeToByteArray(), recoveryPhrase.encodeToByteArray())
        return SyncCryptoManager.KeyPair(seed, seed)
    }

    override fun deriveRealmEncryptionKeyPair(recoveryPhrase: String): SyncCryptoManager.KeyPair {
        val seed = stubEd25519Signature("a13".encodeToByteArray(), recoveryPhrase.encodeToByteArray())
        return SyncCryptoManager.KeyPair(seed, seed)
    }

    override fun signEd25519(privateKey: ByteArray, message: ByteArray): ByteArray =
        stubEd25519Signature(privateKey, message)

    override fun verifyEd25519(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean =
        signature.contentEquals(stubEd25519Signature(publicKey, message))
}

/** In-memory offer store + minimal sync surface shared by the offerer + responder. */
private class SharedOfferRelay(private val context: TContext) : SyncRelay {
    private val offers = mutableMapOf<String, SyncRelay.OfferResponseFetch.Found>()

    /**
     * Plant a terminal response directly, bypassing [postOfferResponse]. Models a third party
     * posting the approve — the case the key-equality lock exists to reject.
     */
    fun forceOffer(pairingSecretHash: String, found: SyncRelay.OfferResponseFetch.Found) {
        offers[pairingSecretHash] = found
    }

    /**
     * Relay-forgot-this-device simulation. The real relay refuses the offer-response at
     * `knownDevices.getPublicKey(ownerId, responderDeviceId)` with `401 Unauthorized — Unknown
     * responder device`, so the fake answers with that exact message: the production code keys its
     * actionable error on [org.tenshu.sync.proxy.isRelayUnauthorized], which reads the status text.
     */
    var refuseResponderUntilReBootstrap = false
    var alwaysRefuseResponder = false
    var createRealmCalls = 0
        private set
    private var reBootstrapped = false

    private fun unauthorized(): TResult<Data?> = TResult.Failure.create(
        context,
        org.tenshu.core.GenericError.create(
            context,
            // Shaped by the constant the production client attaches, never a hand-typed status line:
            // over HTTP/2 the relay sends no reason phrase, so "401 Unauthorized" is a string the
            // real wire does not produce (see UNAUTHORIZED_REJECTION_MARKER).
            "Offer-response failed: $UNAUTHORIZED_REJECTION_MARKER: 401 — Unknown responder device",
        ),
    )

    /** The admission stamp the approving host sent with its offer response. */
    var lastOfferHlc: String? = null

    override suspend fun postOfferResponse(
        ownerId: String,
        responderDeviceId: String,
        pairingSecretHash: String,
        status: String,
        publicKeyA: ByteArray,
        payload: ByteArray?,
        hostSignature: ByteArray,
        offererDeviceId: String?,
        offererSigningKey: ByteArray?,
        masterDelegation: ByteArray?,
        epoch: Long?,
        admissionCertChain: ByteArray?,
        admittedAtHlc: String?,
    ): TResult<Data?> {
        if (alwaysRefuseResponder || (refuseResponderUntilReBootstrap && !reBootstrapped)) {
            return unauthorized()
        }
        lastOfferHlc = admittedAtHlc
        offers[pairingSecretHash] =
            SyncRelay.OfferResponseFetch.Found(ownerId, status, publicKeyA, payload)
        return TResult.Success.create(context, null)
    }

    override suspend fun getOfferResponse(pairingSecretHash: String): SyncRelay.OfferResponseFetch =
        offers[pairingSecretHash] ?: SyncRelay.OfferResponseFetch.NotFound

    override suspend fun fetchRealmEpoch(ownerId: String): TResult<org.tenshu.core.number.TInteger> =
        TResult.Success.create(context, 0L.toTInteger(context))

    override suspend fun pushBlock(block: SyncBlock, edges: List<org.tenshu.core.common.content.ContentEdge>): TResult<Data?> =
        TResult.Success.create(context, null)

    // Unused by the offer round trip.
    override suspend fun getChanges(sinceTimestamp: Long, tip: org.tenshu.sync.SignedHlcTip) = error("not used")
    override suspend fun pushTombstone(block: org.tenshu.sync.DeleteBlock, cascade: List<String>): TResult<Data?> = error("not used")
    override suspend fun getTombstones(sinceTimestamp: Long) = error("not used")
    override suspend fun fetchEntityBlocks(entityWireId: String): TResult<TList<SyncBlock>> = error("not used")
    override suspend fun postPairingRequest(ownerId: String, pairingSecretHash: String, deviceId: String, sealedDeviceName: ByteArray, publicKey: ByteArray, signingPublicKey: ByteArray): TResult<Data?> = error("not used")
    override suspend fun getPairingRequests(ownerId: String): SyncRelay.PairingRequestsFetch = error("not used")
    override suspend fun postPairingResponse(pairingSecret: String, encryptedMasterKey: ByteArray, newDevicePublicKey: ByteArray, newDeviceId: String, masterDelegation: ByteArray, epoch: Long, admissionCertChain: ByteArray?, admittedAtHlc: String?): TResult<Data?> = error("not used")
    override suspend fun ackPairingResponse(pairingSecret: String): TResult<Data?> = error("not used")
    override suspend fun getPairingResponse(pairingSecret: String): TResult<Data?> = error("not used")
    override suspend fun createRealm(ownerId: String, masterPublicKey: ByteArray, firstDevicePublicKey: ByteArray, firstDeviceId: String, masterSelfSignature: ByteArray, admittedAtHlc: String?): TResult<Data?> {
        createRealmCalls++
        reBootstrapped = true
        return TResult.Success.create(context, null)
    }
    override suspend fun recoveryChallenge() = error("not used")
    override suspend fun recoveryClaim(masterPublicKey: ByteArray, newDevicePublicKey: ByteArray, newDeviceId: String, challenge: ByteArray, masterSignature: ByteArray) = error("not used")
}
