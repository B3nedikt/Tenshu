package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.NotFoundError
import org.tenshu.core.TResult
import org.tenshu.core.TenshuException
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.core.TContext
import org.tenshu.core.entity.DataId
import org.tenshu.core.security.BootstrapSyncCryptoManager
import org.tenshu.core.toTString
import org.tenshu.identity.Device
import org.tenshu.identity.IdentityService
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.sync.ReadmitDevice
import org.tenshu.sync.common.SyncRelay
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.sync.proxy.impl.ReadmitDeviceImplementation
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `ReadmitDevice` — an A1-holder restores a **peer's** relay registration from its own roster,
 * the exit a stranded joiner cannot reach itself (`docs/tech-debt.md` → *"A joiner that loses its
 * relay registration is wedged permanently"*).
 */
class ReadmitDeviceImplementationTest {

    private lateinit var testProxy: TestProxy
    private lateinit var crypto: BootstrapSyncCryptoManager
    private lateinit var relay: RecordingAdmitRelay
    private lateinit var syncManager: SyncManagerImpl

    private val masterPhrase = "abcdefghijklmnopqrstuvwxyz012345"
    private val peerPublicKey = ByteArray(32) { (0x11 + it).toByte() }
    /** Workstream B: the stranded peer's id is its key's. */
    private val strandedPeer = org.tenshu.sync.common.deviceIdForPublicKey(peerPublicKey)

    @BeforeTest
    fun setup() {
        testProxy = TestProxy(schemaRegistry = createCoreTestRegistry()).also {
            it.schemaRegistry.registerConfig(syncServiceCommonModule)
            it.schemaRegistry.registerConfig(identityServiceCommonModule)
        }
        crypto = BootstrapSyncCryptoManager()
        relay = RecordingAdmitRelay(testProxy.tContext)
        syncManager = SyncManagerImpl(crypto, InMemorySecureStorageDelegate(), testProxy.tContext)
    }

    private fun impl() = ReadmitDeviceImplementation(syncManager, crypto, relay)

    private fun seedDevice(deviceId: String, publicKey: ByteArray = peerPublicKey) {
        val device = Device.create(
            context = testProxy.tContext,
            deviceId = deviceId,
            name = deviceId,
            publicKey = publicKey,
            isAuthorized = true,
        )
        testProxy.executionContext.saveData(device)
        testProxy.executionContext.database.addToArray(
            ArrayDatabaseReference(
                arrayId = DataId.RelationDataId.create(
                    context = testProxy.tContext,
                    type = TList.ID,
                    relationId = IdentityService.Relations.DEVICES
                ),
                isEntity = true,
                elementSchemaId = Device.ID
            ),
            device,
        )
    }

    private suspend fun readmit(deviceId: String) = impl().execute<Data>(
        executionContext = testProxy.executionContext,
        operationContext = null,
        implementationFactory = { null },
        caller = null,
        params = mapOf(ReadmitDevice.Params.DEVICE_ID to deviceId.toTString(testProxy.tContext)),
    )

    @Test
    fun a1Holder_readmitsAPeer_fromItsRosterAlone() = runTest {
        // The point of the op: nothing is needed FROM the stranded device. Its id and public key
        // both come off the synced `Device` row the A1-holder already has.
        syncManager.initialize(masterPhrase)
        syncManager.setRealmEpochProvider { 7L }
        seedDevice(strandedPeer)

        readmit(strandedPeer)

        val admitted = relay.admissions.single()
        assertEquals(strandedPeer, admitted.newDeviceId)
        assertContentEquals(
            peerPublicKey,
            admitted.newDevicePublicKey,
            "the public key must come from the peer's row, not be invented",
        )
        assertEquals(
            7L,
            admitted.epoch,
            "admitted at the CURRENT epoch — a revocation raises it precisely so a later " +
                "re-admission outranks the revocation",
        )
        assertTrue(admitted.masterDelegation.isNotEmpty(), "delegation must be master-signed")
    }

    @Test
    fun joiner_withoutTheMasterIdentity_cannotReadmitAPeer() = runTest {
        // A joiner can rescue neither itself nor anyone else — the delegation is master-signed.
        // Saying so beats letting the relay answer 401 and making the user decode it.
        seedDevice(strandedPeer)

        assertFailsWith<TenshuException> { readmit(strandedPeer) }
        assertTrue(relay.admissions.isEmpty(), "nothing may reach the relay without A1")
    }

    @Test
    fun ownDeviceId_isRefused_soTheGenesisRepairIsNotSkipped() = runTest {
        // ⚠ Self belongs to `ReregisterDevice`, which re-asserts genesis FIRST — the step that
        // repairs a relay that lost the whole realm rather than just this one device. Routing
        // self through here would silently skip it and half-fix an amnesiac relay.
        syncManager.initialize(masterPhrase)
        seedDevice(syncManager.currentDeviceId)

        assertFailsWith<TenshuException> { readmit(syncManager.currentDeviceId) }
        assertTrue(relay.admissions.isEmpty())
    }

    @Test
    fun aRevokedDevice_hasNoRow_soItCannotBeReadmitted() = runTest {
        // The safety property, and the reason this op needs no scary confirmation: revocation
        // DELETES the `Device` row, so the public key this operation must sign over is gone. A
        // deliberately removed device can only come back through a real re-pairing.
        syncManager.initialize(masterPhrase)
        // No seedDevice — this is the post-revocation state.

        val ex = assertFailsWith<TenshuException> { readmit("revoked-device") }
        assertEquals(NotFoundError.ID, ex.error.schema.id.get())
        assertTrue(relay.admissions.isEmpty())
    }

    /**
     * ⚠⚠ B1: any realm device can write a `Device` row, so the key A1 is asked to sign over is
     * attacker-chosen. Before the delegation was tagged, a key of the right length and content made
     * A1's delegation signature double as a cert signature. Only a 32-byte key is ever signed over.
     */
    @Test
    fun aRowWhoseKeyIsNotAnEd25519Key_isRefused_andNothingIsSigned() = runTest {
        syncManager.initialize(masterPhrase)
        seedDevice("forged-row", publicKey = ByteArray(77) { 3 })

        assertFailsWith<TenshuException> { readmit("forged-row") }
        assertTrue(relay.admissions.isEmpty(), "A1 must not sign a delegation over a non-Ed25519 key")
    }

    /**
     * Workstream B: a row pairing one device's id with another's (real, 32-byte) key — any realm
     * device can write one. A1 must not vouch for the pair; the relay would refuse it anyway.
     */
    @Test
    fun aRowWhoseIdIsNotItsKeys_isRefused_andNothingIsSigned() = runTest {
        syncManager.initialize(masterPhrase)
        val victimsId = org.tenshu.sync.common.deviceIdForPublicKey(ByteArray(32) { 0x55 })
        seedDevice(victimsId, publicKey = peerPublicKey)

        assertFailsWith<TenshuException> { readmit(victimsId) }
        assertTrue(relay.admissions.isEmpty(), "A1 must not sign a delegation over a squatted id")
    }
}

private class RecordingAdmitRelay(private val context: TContext) : SyncRelay {
    data class Admission(
        val ownerId: String,
        val newDeviceId: String,
        val newDevicePublicKey: ByteArray,
        val masterDelegation: ByteArray,
        val epoch: Long,
    )

    val admissions = mutableListOf<Admission>()

    override suspend fun admitDevice(
        ownerId: String,
        newDeviceId: String,
        newDevicePublicKey: ByteArray,
        masterDelegation: ByteArray,
        epoch: Long,
        admittedAtHlc: String?,
    ): TResult<Data?> {
        admissions.add(
            Admission(ownerId, newDeviceId, newDevicePublicKey, masterDelegation, epoch),
        )
        return TResult.Success.create(context, null)
    }

    // `SyncRelay`'s remaining members; this fake exists only for the admit path.
    override suspend fun pushBlock(block: org.tenshu.sync.SyncBlock, edges: List<org.tenshu.core.common.content.ContentEdge>): TResult<Data?> = error("not used")
    override suspend fun getChanges(
        sinceTimestamp: Long,
        tip: org.tenshu.sync.SignedHlcTip,
    ): TResult<org.tenshu.sync.ChangesResponse> = error("not used")

    override suspend fun pushTombstone(block: org.tenshu.sync.DeleteBlock, cascade: List<String>): TResult<Data?> = error("not used")
    override suspend fun getTombstones(sinceTimestamp: Long): TResult<org.tenshu.sync.TombstonesResponse> =
        error("not used")

    override suspend fun fetchEntityBlocks(entityWireId: String): TResult<TList<org.tenshu.sync.SyncBlock>> =
        error("not used")

    override suspend fun postPairingRequest(
        ownerId: String,
        pairingSecretHash: String,
        deviceId: String,
        sealedDeviceName: ByteArray,
        publicKey: ByteArray,
        signingPublicKey: ByteArray,
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
        masterSignature: ByteArray,
    ): TResult<Data?> = error("not used")

    override suspend fun fetchRealmEpoch(ownerId: String): TResult<org.tenshu.core.number.TInteger> =
        error("not used")
}
