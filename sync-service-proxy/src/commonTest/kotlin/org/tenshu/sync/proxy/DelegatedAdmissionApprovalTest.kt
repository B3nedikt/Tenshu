package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.GenericError
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.TList
import org.tenshu.core.collection.list.toTList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.core.TContext
import org.tenshu.core.entity.DataId
import org.tenshu.core.security.BootstrapSyncCryptoManager
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.core.security.stubEd25519Signature
import org.tenshu.core.toTByteArray
import org.tenshu.core.toTString
import org.tenshu.identity.Device
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.sync.ApprovePairingRequest
import org.tenshu.sync.PairingRequest
import org.tenshu.sync.common.AdmissionCapabilityCert
import org.tenshu.sync.common.AdmissionChainVerdict
import org.tenshu.sync.common.AdmissionChainVerifier
import org.tenshu.sync.SyncBlock
import org.tenshu.sync.common.SyncRelay
import org.tenshu.sync.common.decodeAdmissionCertChain
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.sync.proxy.impl.ApprovePairingRequestImplementation
import org.tenshu.sync.proxy.impl.GetPendingPairingRequestsImplementation
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P3 of `delegated-device-admission-authority.md` (§4.3): the approve path's
 * three admitter shapes and the A33 hand-off to the joiner.
 *
 *  - An **A1-holder** signs the delegation with the master key (no chain on the
 *    wire, byte-identical to pre-P3) and mints the joiner a **depth-1** A33
 *    chain rooted directly at A1, delivered inside the encrypted key-blob.
 *  - A **delegated admitter** (holds A33 + chain, never A1) signs the
 *    delegation with its A33, presents its chain to the relay, and mints the
 *    joiner a **depth+1** chain (Q1 resolved: chained).
 *  - The joiner installs the A33 material via `initializeWithKeys` and it
 *    survives a process restart via the J3 joiner payload.
 *
 * Runs on the stub crypto (`publicKey == privateKey`), like the P1 verifier
 * tests — what's pinned is the wiring: who signs what, what rides the wire vs.
 * the encrypted blob, and the custody round-trip.
 */
class DelegatedAdmissionApprovalTest {

    private lateinit var testProxy: TestProxy
    private lateinit var crypto: AdmissionStubCryptoManager
    private lateinit var relay: RecordingDelegatedRelay
    private lateinit var syncManager: SyncManagerImpl
    private lateinit var impl: ApprovePairingRequestImplementation

    // 32-char phrase → 32-byte stub DerivedKeys, so the v2 key-blob's fixed-width
    // slots line up and the joiner-side unpack round-trips.
    private val hostPhrase = "abcdefghijklmnopqrstuvwxyz012345"
    private val joinerDeviceId = FIXTURE_JOINER_ID
    private val secondJoinerId = fixtureDeviceId("device-x2")

    @BeforeTest
    fun setup() {
        testProxy = TestProxy(schemaRegistry = createCoreTestRegistry()).also {
            it.schemaRegistry.registerConfig(syncServiceCommonModule)
            it.schemaRegistry.registerConfig(identityServiceCommonModule)
        }
        crypto = AdmissionStubCryptoManager()
        relay = RecordingDelegatedRelay(testProxy.tContext)
        syncManager = SyncManagerImpl(
            cryptoManager = crypto,
            storageDelegate = InMemorySecureStorageDelegate(),
            context = testProxy.tContext,
        )
        impl = ApprovePairingRequestImplementation(relay, syncManager, crypto)
    }

    private suspend fun approve(deviceId: String) {
        impl.execute<Data>(
            executionContext = testProxy.executionContext,
            operationContext = null,
            implementationFactory = { null },
            caller = null,
            params = mapOf(ApprovePairingRequest.Params.DEVICE_ID to deviceId.toTString(testProxy.tContext)),
        )
    }

    // The ECDH shared secret the host will encrypt the key-blob under — captured
    // at staging time because approve's stopPairing() clears the context AND zeroes
    // the ephemeral private key in place. The stub calculateSharedSecret returns its
    // privateKey argument by reference, so we must copy the bytes before the wipe.
    private var stagedSharedSecret: ByteArray = byteArrayOf()

    private suspend fun stagePairingRequest(deviceId: String): String {
        syncManager.generatePairingContext()
        val context = syncManager.activePairingContext!!
        stagedSharedSecret = crypto.calculateSharedSecret(context.privateKey, byteArrayOf(0x01)).copyOf()
        relay.pairingRequests.add(mockRequest(deviceId, context.secret))
        // The host UI's poll: approve admits only a request this session has shown a SAS for
        // (threat model T-P-14).
        GetPendingPairingRequestsImplementation(relay, syncManager, crypto).execute<Data>(
            executionContext = testProxy.executionContext,
            operationContext = null,
            implementationFactory = { null },
            caller = null,
            params = null,
        )
        return context.secret
    }

    /** Decrypt + unpack the blob the approve path posted, as the joiner would. */
    private fun unpackPostedBlob(): PairingKeyBlob.Unpacked {
        val posted = assertNotNull(relay.lastEncryptedMasterKey, "approve must post a response")
        return assertNotNull(PairingKeyBlob.unpack(crypto.decrypt(posted, stagedSharedSecret)))
    }

    @Test
    fun a1Holder_signsWithMaster_noChainOnWire_andMintsDepth1ChainInBlob() = runTest {
        syncManager.initialize(hostPhrase)
        stagePairingRequest(joinerDeviceId)

        approve(joinerDeviceId)

        // Wire: no chain presented — the delegation is A1-signed, as before P3.
        assertNull(relay.lastAdmissionCertChain, "an A1-holder admits without a chain")
        val masterPub = syncManager.currentMasterKeyPair!!.publicKey
        assertContentEquals(
            stubEd25519Signature(masterPub, relay.lastDelegationMessage()),
            assertNotNull(relay.lastMasterDelegation),
            "delegation must be signed by the master key",
        )

        // Blob: joiner receives a fresh A33 keypair + a depth-1 chain rooted at A1.
        val unpacked = unpackPostedBlob()
        val a33 = assertNotNull(unpacked.admissionKeyPair, "joiner must receive its A33 keypair")
        assertEquals(1, unpacked.admissionCertChain.size)
        val cert = unpacked.admissionCertChain[0]
        assertEquals(joinerDeviceId, cert.deviceId)
        assertContentEquals(a33.publicKey, cert.deviceA33PublicKey)
        assertEquals(
            AdmissionChainVerdict.Admit,
            AdmissionChainVerifier.verifyChain(
                masterPub, syncManager.currentOwnerId!!, unpacked.admissionCertChain,
                emptyList(), crypto::verifyEd25519,
            ),
            "the minted chain must verify to the realm master",
        )
    }

    /**
     * Make this host device X: a joiner previously admitted by the owner — derivedKeys, A13 and an
     * owner-minted depth-1 A33 chain, never A1. Returns X's A33 keypair.
     */
    private fun initAsDelegatedAdmitter(): SyncCryptoManager.KeyPair {
        val master = crypto.deriveMasterKeyPair(hostPhrase)
        val ownerId = org.tenshu.core.common.security.ownerIdForMasterPublicKey(master.publicKey)
        val xA33 = SyncCryptoManager.KeyPair(
            publicKey = ByteArray(32) { (it + 70).toByte() },
            privateKey = ByteArray(32) { (it + 70).toByte() },
        )
        val xCert = AdmissionCapabilityCert(
            ownerId = ownerId,
            deviceA33PublicKey = xA33.publicKey,
            deviceId = "device-x",
            mintedAtEpoch = 0L,
            signature = crypto.signEd25519(
                master.privateKey,
                AdmissionCapabilityCert.message(ownerId, xA33.publicKey, "device-x", 0L),
            ),
        )
        syncManager.initializeWithKeys(
            SyncCryptoManager.DerivedKeys(ByteArray(32) { 1 }, ByteArray(32) { 2 }),
            ownerId = ownerId,
            realmEncryptionKeyPair = SyncCryptoManager.KeyPair(ByteArray(32) { 3 }, ByteArray(32) { 4 }),
            admissionKeyPair = xA33,
            admissionCertChain = listOf(xCert),
        )
        return xA33
    }

    private fun savedJoinerRow(deviceId: String = joinerDeviceId): Device? = testProxy.database.getData(
        DataId.StringDataId.create(testProxy.tContext, Device.ID, deviceId),
    ) as? Device

    @Test
    fun delegatedAdmitter_aLostReply_isRetried_andConsumedOnTheRetryIsDelivered() = runTest {
        // The retry is not tied to the A1 re-bootstrap: an A33-only admitter skipping it would
        // surface a lost reply as a failure although the joiner was paired, and never write its
        // Device row. The retry is a plain re-post of identical material.
        initAsDelegatedAdmitter()
        stagePairingRequest(joinerDeviceId)
        relay.script.addAll(listOf(Outcome.LOST, Outcome.CONSUMED))

        approve(joinerDeviceId)

        assertEquals(2, relay.postedBlobs.size, "the lost reply is retried once")
        assertEquals(0, relay.createRealmCalls, "no genesis re-bootstrap: an A33-only admitter holds no A1")
        val row = assertNotNull(savedJoinerRow(), "the Device row is written once delivery is known")
        assertContentEquals(unpackPostedBlob().admissionKeyPair!!.publicKey, row.admissionPublicKey?.get())
    }

    @Test
    fun aSecondApprove_afterBothRepliesOfTheFirstWereLost_recordsTheCapabilityTheJoinerHolds() = runTest {
        // The double-failure edge: approve 1 lands, both its replies are lost, and the joiner
        // fetches + acks. Approve 2's first post fails for another reason and its retry reads
        // "consumed" as delivered. An approve minting FRESH material would make approve 2 record
        // an A33 capability the joiner never received — and a revocation naming it would miss the
        // one the joiner holds. One mint per pin: every post carries the material the joiner acked.
        syncManager.initialize(hostPhrase)
        stagePairingRequest(joinerDeviceId)
        relay.script.addAll(listOf(Outcome.LOST, Outcome.LOST, Outcome.FAILED, Outcome.CONSUMED))

        assertFailsWith<org.tenshu.core.TenshuException> { approve(joinerDeviceId) }
        assertNull(savedJoinerRow(), "approve #1 knows of no delivery")
        approve(joinerDeviceId)

        assertEquals(4, relay.postedBlobs.size)
        relay.postedBlobs.forEach {
            assertContentEquals(relay.postedBlobs.first(), it, "every post for one pin is one admission")
        }
        assertEquals(1, crypto.admissionMints, "the joiner's A33 capability is minted once")
        val joinerHolds = assertNotNull(PairingKeyBlob.unpack(crypto.decrypt(relay.postedBlobs.first(), stagedSharedSecret)))
        assertContentEquals(
            joinerHolds.admissionKeyPair!!.publicKey,
            assertNotNull(savedJoinerRow()).admissionPublicKey?.get(),
            "the row names the capability the joiner acked",
        )
    }

    @Test
    fun consumedAfterAnotherDevicesPost_isNotReadAsDelivered() = runTest {
        // Two joiners under one session secret (T-P-14 lets several requests share a hash). X's
        // post lands with its replies lost and X acks; approving Y then sees "consumed" on its
        // retry — but that ack was X's, so Y must not be recorded as admitted.
        syncManager.initialize(hostPhrase)
        stagePairingRequest(secondJoinerId)
        stagePairingRequest(joinerDeviceId)
        relay.script.addAll(listOf(Outcome.LOST, Outcome.LOST, Outcome.FAILED, Outcome.CONSUMED))

        assertFailsWith<org.tenshu.core.TenshuException> { approve(secondJoinerId) }
        assertFailsWith<org.tenshu.core.TenshuException> { approve(joinerDeviceId) }
        assertNull(savedJoinerRow(), "Y never received anything")
    }

    @Test
    fun delegatedAdmitter_signsWithA33_presentsChain_andMintsDepth2Chain() = runTest {
        // Realm master exists elsewhere (the owner); device X never holds it.
        val master = crypto.deriveMasterKeyPair(hostPhrase)
        val ownerId = org.tenshu.core.common.security.ownerIdForMasterPublicKey(master.publicKey)

        // Device X: a joiner previously admitted by the owner — holds derivedKeys,
        // A13, and an owner-minted depth-1 A33 chain.
        val xA33 = SyncCryptoManager.KeyPair(
            publicKey = ByteArray(32) { (it + 70).toByte() },
            privateKey = ByteArray(32) { (it + 70).toByte() },
        )
        val xCert = AdmissionCapabilityCert(
            ownerId = ownerId,
            deviceA33PublicKey = xA33.publicKey,
            deviceId = "device-x",
            mintedAtEpoch = 0L,
            signature = crypto.signEd25519(
                master.privateKey,
                AdmissionCapabilityCert.message(ownerId, xA33.publicKey, "device-x", 0L),
            ),
        )
        syncManager.initializeWithKeys(
            SyncCryptoManager.DerivedKeys(ByteArray(32) { 1 }, ByteArray(32) { 2 }),
            ownerId = ownerId,
            realmEncryptionKeyPair = SyncCryptoManager.KeyPair(ByteArray(32) { 3 }, ByteArray(32) { 4 }),
            admissionKeyPair = xA33,
            admissionCertChain = listOf(xCert),
        )
        stagePairingRequest(joinerDeviceId)

        approve(joinerDeviceId)

        // Wire: X's own chain rides along, and the delegation is signed by X's A33.
        val presented = decodeAdmissionCertChain(
            assertNotNull(relay.lastAdmissionCertChain, "a delegated admitter must present its chain"),
        )
        assertEquals(1, presented.size)
        assertEquals("device-x", presented[0].deviceId)
        assertContentEquals(
            stubEd25519Signature(xA33.publicKey, relay.lastDelegationMessage()),
            assertNotNull(relay.lastMasterDelegation),
            "delegation must be signed by X's A33, not a master key",
        )

        // Blob: the joiner's chain is X's chain plus one link (Q1: chained), and it
        // verifies all the way to the realm master X never held.
        val unpacked = unpackPostedBlob()
        assertEquals(2, unpacked.admissionCertChain.size)
        assertEquals(joinerDeviceId, unpacked.admissionCertChain[0].deviceId)
        assertEquals("device-x", unpacked.admissionCertChain[1].deviceId)
        assertEquals(
            AdmissionChainVerdict.Admit,
            AdmissionChainVerifier.verifyChain(
                master.publicKey, ownerId, unpacked.admissionCertChain,
                emptyList(), crypto::verifyEd25519,
            ),
        )

        // Delegated admission IS device-vouched — the row records the admitter
        // (§5 lineage, what the P5 subtree revoke walks).
        val saved = testProxy.database.getData(
            DataId.StringDataId.create(testProxy.tContext, Device.ID, joinerDeviceId),
        ) as Device
        assertEquals(syncManager.currentDeviceId, saved.admittedByDeviceId?.get())
    }

    @Test
    fun admittedDevice_carriesLineage_onlyWhenVouchedByA33() = runTest {
        // A1-admitted: the capability is rooted at the realm master, not at any
        // device — no lineage parent (removing the owner DEVICE must not strip
        // A1-rooted capabilities).
        syncManager.initialize(hostPhrase)
        stagePairingRequest(joinerDeviceId)
        approve(joinerDeviceId)
        val ownerAdmitted = testProxy.database.getData(
            DataId.StringDataId.create(testProxy.tContext, Device.ID, joinerDeviceId),
        ) as Device
        assertEquals("", ownerAdmitted.admittedByDeviceId?.get(), "A1-rooted admission has no device parent")
    }

    @Test
    fun joinerInstallsA33_andItSurvivesRestart_viaJ3Payload() = runTest {
        // Host approves; take the posted blob and complete joiner-side by hand
        // (decrypt → unpack → initializeWithKeys — the PairingCompletion steps).
        syncManager.initialize(hostPhrase)
        stagePairingRequest(joinerDeviceId)
        approve(joinerDeviceId)
        val unpacked = unpackPostedBlob()

        val joinerStorage = InMemorySecureStorageDelegate()
        val joiner = SyncManagerImpl(crypto, joinerStorage, testProxy.tContext)
        joiner.initializeWithKeys(
            unpacked.derivedKeys,
            ownerId = syncManager.currentOwnerId!!,
            realmEncryptionKeyPair = unpacked.realmEncryptionKeyPair,
            admissionKeyPair = unpacked.admissionKeyPair,
            admissionCertChain = unpacked.admissionCertChain,
        )
        assertContentEquals(unpacked.admissionKeyPair!!.privateKey, joiner.getAdmissionKeyPair()?.privateKey)
        assertEquals(1, joiner.getAdmissionCertChain().size)

        // New process over the SAME storage: the J3 joiner payload rehydrates the
        // A33 material, so the device stays an admitter across restarts.
        val restarted = SyncManagerImpl(crypto, joinerStorage, testProxy.tContext)
        assertContentEquals(
            unpacked.admissionKeyPair!!.privateKey,
            restarted.getAdmissionKeyPair()?.privateKey,
            "A33 must survive a restart via the persisted joiner payload",
        )
        assertEquals(joinerDeviceId, restarted.getAdmissionCertChain().singleOrNull()?.deviceId)
        assertContentEquals(
            unpacked.realmEncryptionKeyPair!!.privateKey,
            restarted.getRealmEncryptionKeyPair()?.privateKey,
            "A13 still rides the payload alongside the A33 material",
        )
    }

    private fun mockRequest(deviceId: String, secret: String): SyncRelay.RelayPairingRequest =
        sealedRelayRequest(
            crypto, deviceId, secret,
            signingPublicKey = if (deviceId == secondJoinerId) fixtureDeviceKey("device-x2") else FIXTURE_JOINER_SIGNING_KEY,
        )
}

/**
 * [BootstrapSyncCryptoManager] with a real-width A33 generator: the base stub's
 * `generateKeyPair` returns a fixed 5-byte "dummy" (fine for the X25519
 * ephemeral whose bytes nothing slices), but the A33 keypair rides the
 * fixed-width v2 key-blob, so it must be 32 bytes — and fresh-ish per mint.
 */
private class AdmissionStubCryptoManager : SyncCryptoManager by BootstrapSyncCryptoManager() {
    private var counter = 0
    val admissionMints: Int get() = counter
    override fun generateAdmissionKeyPair(): SyncCryptoManager.KeyPair {
        val seed = ByteArray(32) { (it + 100 + counter).toByte() }
        counter++
        return SyncCryptoManager.KeyPair(publicKey = seed, privateKey = seed)
    }
}

/** Scripted answers to `postPairingResponse`; an empty script answers success. */
private enum class Outcome {
    /** Stored at the relay, but the reply never reached the host. */
    LOST,
    /** Never stored (transport failure before the relay). */
    FAILED,
    /** `409 pairing-response-consumed`: the joiner already acked this hash. */
    CONSUMED,
}

/** Minimal recording relay for the delegated-admission approve tests. */
private class RecordingDelegatedRelay(private val context: TContext) : SyncRelay {
    val pairingRequests = mutableListOf<SyncRelay.RelayPairingRequest>()
    val script = ArrayDeque<Outcome>()
    /** Every blob a post carried, stored or not, in order. */
    val postedBlobs = mutableListOf<ByteArray>()
    var createRealmCalls = 0
        private set
    var lastEncryptedMasterKey: ByteArray? = null
    var lastMasterDelegation: ByteArray? = null
    var lastAdmissionCertChain: ByteArray? = null
    private var lastNewDevicePublicKey: ByteArray? = null
    private var lastNewDeviceId: String? = null
    private var lastEpoch: Long = 0L

    /** The canonical delegation message the last POST's signature is over. */
    fun lastDelegationMessage(): ByteArray =
        org.tenshu.sync.common.pairingDelegationMessage(lastNewDevicePublicKey!!, lastNewDeviceId!!, lastEpoch)

    override suspend fun getPairingRequests(ownerId: String): SyncRelay.PairingRequestsFetch =
        SyncRelay.PairingRequestsFetch.Found(pairingRequests.toList())

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
        postedBlobs += encryptedMasterKey
        val outcome = script.removeFirstOrNull()
        when (outcome) {
            Outcome.FAILED -> return TResult.Failure.create(context, GenericError.create(context, "Network error"))
            Outcome.CONSUMED -> return TResult.Failure.create(
                context,
                GenericError.create(context, "Response failed: $PAIRING_RESPONSE_CONSUMED_MARKER: 409"),
            )
            Outcome.LOST, null -> Unit
        }
        lastEncryptedMasterKey = encryptedMasterKey
        lastMasterDelegation = masterDelegation
        lastAdmissionCertChain = admissionCertChain
        lastNewDevicePublicKey = newDevicePublicKey
        lastNewDeviceId = newDeviceId
        lastEpoch = epoch
        if (outcome == Outcome.LOST) {
            return TResult.Failure.create(context, GenericError.create(context, "Network error (reply lost)"))
        }
        return TResult.Success.create(context, null)
    }

    override suspend fun createRealm(
        ownerId: String,
        masterPublicKey: ByteArray,
        firstDevicePublicKey: ByteArray,
        firstDeviceId: String,
        masterSelfSignature: ByteArray,
        admittedAtHlc: String?,
    ): TResult<Data?> {
        createRealmCalls++
        return TResult.Success.create(context, null)
    }

    override suspend fun pushBlock(block: SyncBlock, edges: List<org.tenshu.core.common.content.ContentEdge>): TResult<Data?> = error("not used")
    override suspend fun getChanges(
        sinceTimestamp: Long,
        tip: org.tenshu.sync.SignedHlcTip,
    ): TResult<org.tenshu.sync.ChangesResponse> = error("not used")

    override suspend fun pushTombstone(block: org.tenshu.sync.DeleteBlock, cascade: List<String>): TResult<Data?> = error("not used")
    override suspend fun getTombstones(sinceTimestamp: Long): TResult<org.tenshu.sync.TombstonesResponse> =
        error("not used")

    override suspend fun fetchEntityBlocks(entityWireId: String): TResult<TList<SyncBlock>> = error("not used")
    override suspend fun postPairingRequest(
        ownerId: String,
        pairingSecretHash: String,
        deviceId: String,
        sealedDeviceName: ByteArray,
        publicKey: ByteArray,
        signingPublicKey: ByteArray,
    ): TResult<Data?> = error("not used")

    override suspend fun ackPairingResponse(pairingSecret: String): TResult<Data?> = error("not used")
    override suspend fun getPairingResponse(pairingSecret: String): TResult<Data?> = error("not used")
    override suspend fun recoveryChallenge(): TResult<org.tenshu.core.TByteArray> = error("not used")
    override suspend fun recoveryClaim(
        masterPublicKey: ByteArray,
        newDevicePublicKey: ByteArray,
        newDeviceId: String,
        challenge: ByteArray,
        masterSignature: ByteArray,
    ): TResult<Data?> = error("not used")

    override suspend fun fetchRealmEpoch(ownerId: String): TResult<org.tenshu.core.number.TInteger> =
        error("not used")
}
