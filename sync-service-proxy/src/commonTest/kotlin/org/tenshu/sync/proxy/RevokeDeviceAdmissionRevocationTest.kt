package org.tenshu.sync.proxy

import kotlinx.coroutines.test.runTest
import org.tenshu.core.Data
import org.tenshu.core.TResult
import org.tenshu.core.collection.list.TList
import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.proxy.ArrayDatabaseReference
import org.tenshu.core.core.TContext
import org.tenshu.core.entity.DataId
import org.tenshu.core.security.BootstrapSyncCryptoManager
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.core.security.stubEd25519Signature
import org.tenshu.core.toTString
import org.tenshu.identity.AdmissionKeyAnnouncement
import org.tenshu.identity.Device
import org.tenshu.identity.IdentityService
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.sync.RevokeDevice
import org.tenshu.sync.common.AdmissionRevocation
import org.tenshu.sync.common.SyncRelay
import org.tenshu.sync.common.syncServiceCommonModule
import org.tenshu.sync.proxy.impl.RevokeDeviceImplementation
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P5 of `delegated-device-admission-authority.md` (§5): removing a device also
 * strips its own admission capability on the relay. ⚠⚠ Its own keys only — the
 * devices it admitted are untouched (`admission-revocation-names-the-capability.md`
 * §7j.10, retiring Q3's subtree strip): a device is a member or removed, never a
 * member that cannot admit. A delegated (A1-less) remover skips the strip but the
 * removal itself still works.
 */
class RevokeDeviceAdmissionRevocationTest {

    private lateinit var testProxy: TestProxy
    private lateinit var crypto: BootstrapSyncCryptoManager
    private lateinit var relay: RecordingRevokeRelay
    private lateinit var syncManager: SyncManagerImpl

    private val masterPhrase = "abcdefghijklmnopqrstuvwxyz012345"

    @BeforeTest
    fun setup() {
        testProxy = TestProxy(schemaRegistry = createCoreTestRegistry()).also {
            it.schemaRegistry.registerConfig(syncServiceCommonModule)
            it.schemaRegistry.registerConfig(identityServiceCommonModule)
        }
        crypto = BootstrapSyncCryptoManager()
        relay = RecordingRevokeRelay(testProxy.tContext)
        syncManager = SyncManagerImpl(crypto, InMemorySecureStorageDelegate(), testProxy.tContext)
    }

    private fun impl() = RevokeDeviceImplementation(
        tombstoneEmitter = null,
        syncManager = syncManager,
        cryptoManager = crypto,
        syncRelay = relay,
        nowMs = { 4_000L },
    )

    private fun devicesRelationRef() = ArrayDatabaseReference(
        arrayId = DataId.RelationDataId.create(
            context = testProxy.tContext,
            type = TList.ID,
            relationId = IdentityService.Relations.DEVICES
        ),
        isEntity = true,
        elementSchemaId = Device.ID
    )

    /** A distinct, deterministic A33 key per device — every paired device has one captured (F0). */
    private fun keyFor(deviceId: String) = ByteArray(32) { (deviceId.hashCode() + it).toByte() }

    private fun seedDevice(
        deviceId: String,
        admittedBy: String = "",
        admissionPublicKey: ByteArray = keyFor(deviceId),
    ) {
        val device = Device.create(
            context = testProxy.tContext,
            deviceId = deviceId,
            name = deviceId,
            publicKey = byteArrayOf(0x01),
            isAuthorized = true,
            admittedByDeviceId = admittedBy,
            admissionPublicKey = admissionPublicKey,
        )
        testProxy.executionContext.saveData(device)
        testProxy.executionContext.database.addToArray(devicesRelationRef(), device)
    }

    private suspend fun revoke(deviceId: String) {
        impl().execute<Data>(
            executionContext = testProxy.executionContext,
            operationContext = null,
            implementationFactory = { null },
            caller = null,
            params = mapOf(RevokeDevice.Params.DEVICE_ID to deviceId.toTString(testProxy.tContext)),
        )
    }

    /** host → X → C1 → C2, plus sibling S also admitted by the host. */
    private fun seedLineage() {
        seedDevice("host-device")
        seedDevice("device-x", admittedBy = "host-device")
        seedDevice("child-1", admittedBy = "device-x")
        seedDevice("child-2", admittedBy = "child-1")
        seedDevice("sibling", admittedBy = "host-device")
    }

    @Test
    fun revoking_raisesTheRealmEpochPastTheRevocation_soARe_admissionIsNotBornRevoked() = runTest {
        // Born as THE production defect (2026-08-18): under the since-retired epoch rule a device
        // re-admitted at the revoked epoch was superseded at the instant its cert was signed.
        // ⚠ F5b removed that rule from admission, but the raise still matters: the next
        // admission stamps `Device.admittedAtEpoch` from the same counter, and T-K-02's merge
        // check drops a Device row stamped below a peer's epoch (§7e.2, F5c).
        syncManager.initialize(masterPhrase)
        // A tombstone count that does NOT move when the same device is revoked again — exactly the
        // production case, and the reason the epoch cannot be trusted to advance on its own.
        syncManager.setRealmEpochProvider { 2L }
        seedDevice("host-device")
        seedDevice("stale-device", admittedBy = "host-device")

        revoke("stale-device")

        val revocation = relay.revocations.single { it.deviceId == "stale-device" }
        assertEquals(2L, revocation.epoch, "revoked at the epoch that was current")
        assertTrue(
            syncManager.currentRealmEpoch > revocation.epoch,
            "the realm epoch must move PAST the revocation, else a device admitted next " +
                "(PairingAdmission reads the same currentRealmEpoch) is stamped below the tombstone: " +
                "epoch=${syncManager.currentRealmEpoch}, revocation=${revocation.epoch}",
        )
    }

    /**
     * ⚠⚠ §7j.10 (2026-11-11), inverting `a1Holder_revokesTheWholeAdmissionSubtree_notSiblings`:
     * removing X revokes X's key and nothing else — not C1/C2, which X admitted. The user kept them
     * (or will remove them by id from the removal dialog); a revocation here would leave them
     * members that cannot admit, the state the maintainer ruled out.
     */
    @Test
    fun a1Holder_revokesOnlyTheRemovedDevice_neverTheDevicesItAdmitted() = runTest {
        syncManager.initialize(masterPhrase)
        syncManager.setRealmEpochProvider { 7L }
        seedLineage()

        revoke("device-x")

        assertEquals(
            listOf("device-x"),
            relay.revocations.map { it.deviceId },
            "the removed device's own key — never its admittees",
        )
        // The kept admittees are untouched members.
        for (kept in listOf("child-1", "child-2", "sibling")) {
            assertNotNull(
                testProxy.database.getData(DataId.StringDataId.create(testProxy.tContext, Device.ID, kept)),
                "$kept stays a member",
            )
        }
        // Each revocation is master-signed over the canonical bytes at the current
        // epoch — the exact shape the P2 relay endpoint verifies before appending.
        val masterKey = syncManager.currentMasterKeyPair!!
        for (r in relay.revocations) {
            assertEquals(7L, r.epoch)
            assertEquals(4_000L, r.revokedAt)
            assertContentEquals(
                stubEd25519Signature(
                    masterKey.privateKey,
                    AdmissionRevocation.message(
                        syncManager.currentOwnerId!!, r.deviceId, 7L, 4_000L, keyFor(r.deviceId),
                    ),
                ),
                r.signature,
            )
        }
        // The removal itself happened as before.
        assertNull(
            testProxy.database.getData(
                DataId.StringDataId.create(testProxy.tContext, Device.ID, "device-x"),
            ),
        )
    }

    @Test
    fun leafDevice_revokesOnlyItself() = runTest {
        syncManager.initialize(masterPhrase)
        seedLineage()

        revoke("child-2")

        assertEquals(listOf("child-2"), relay.revocations.map { it.deviceId })
    }

    /**
     * The removal dialog's unticked admittee (§7j.4): the VM removes it by its own `RevokeDevice`
     * call after the admitter's — so it is FULLY removed (row gone, own key revoked), not merely
     * stripped.
     */
    @Test
    fun anUntickedAdmittee_isRemovedByItsOwnCall_withItsOwnKeyRevoked() = runTest {
        syncManager.initialize(masterPhrase)
        seedLineage()

        revoke("device-x")
        revoke("child-1")

        assertEquals(listOf("device-x", "child-1"), relay.revocations.map { it.deviceId })
        assertContentEquals(keyFor("child-1"), relay.revocations[1].deviceA33PublicKey)
        assertNull(testProxy.database.getData(DataId.StringDataId.create(testProxy.tContext, Device.ID, "child-1")))
        assertNotNull(
            testProxy.database.getData(DataId.StringDataId.create(testProxy.tContext, Device.ID, "child-2")),
            "child-2 (kept) stays",
        )
    }

    @Test
    fun delegatedRemover_skipsTheStrip_butRemovalStillWorks() = runTest {
        // An A1-less joiner (P3 delegated admitter or plain joiner) can remove a
        // device but cannot master-sign revocations — the strip waits for an
        // A1-holder rather than blocking the removal.
        syncManager.initializeWithKeys(
            SyncCryptoManager.DerivedKeys(ByteArray(32) { 1 }, ByteArray(32) { 2 }),
            ownerId = "host-owner",
        )
        seedLineage()

        revoke("device-x")

        assertTrue(relay.revocations.isEmpty(), "no A1 → no master-signed revocations")
        assertNull(
            testProxy.database.getData(
                DataId.StringDataId.create(testProxy.tContext, Device.ID, "device-x"),
            ),
            "the removal itself must not be blocked",
        )
    }


    /**
     * F1′ end-to-end on the authoring side: the revoker must **name the captured key** so the
     * revocation is capability-scoped.
     *
     * ⚠⚠ The `Device` row (F0) is the first source — R1 measured the synced
     * `AdmissionKeyAnnouncement` unresolvable at revoke time on real hardware
     * (`a33KeyResolvable=false`), which is the entire reason the key is captured at admission.
     */
    @Test
    fun revokingADeviceWithACapturedKey_namesTheCapability() = runTest {
        val key = ByteArray(32) { (it + 21).toByte() }
        syncManager.initialize(masterPhrase)
        seedDevice("device-keyed", admissionPublicKey = key)

        revoke("device-keyed")

        val posted = relay.revocations.single { it.deviceId == "device-keyed" }
        assertContentEquals(
            key,
            posted.deviceA33PublicKey,
            "the revocation must name the capability the admission minted",
        )
    }

    /**
     * F5b: an A1-rooted device (the realm's first) has no captured key on its row — nobody
     * admitted it. Its key comes from its own `AdmissionKeyAnnouncement`, which is how its cert
     * was minted in the first place (P4). Before F5b this fell back to a keyless epoch revocation.
     */
    @Test
    fun revokingADeviceWithoutACapturedKey_namesItsAnnouncedKey() = runTest {
        val announced = ByteArray(32) { (it + 77).toByte() }
        syncManager.initialize(masterPhrase)
        seedDevice("device-root", admissionPublicKey = ByteArray(0))
        testProxy.executionContext.saveData(
            AdmissionKeyAnnouncement.create(
                context = testProxy.tContext,
                deviceId = "device-root",
                publicKey = announced,
                signature = ByteArray(64),
            ),
        )

        revoke("device-root")

        assertContentEquals(
            announced,
            relay.revocations.single { it.deviceId == "device-root" }.deviceA33PublicKey,
        )
    }

    /**
     * ⚠⚠ F5b: with no key anywhere, the relay strip is **skipped** — never sent keyless. A keyless
     * revocation has no meaning any more (the verifier has no epoch rule) and the relay 400s it,
     * so sending one would only turn a known gap into a failure that hides it. The removal itself
     * — the tombstone, the security-critical step — still happens.
     */
    @Test
    fun revokingADeviceWithNoKnownKey_sendsNoKeylessRevocation_butStillRemoves() = runTest {
        syncManager.initialize(masterPhrase)
        seedDevice("device-keyless", admissionPublicKey = ByteArray(0))

        revoke("device-keyless")

        assertTrue(
            relay.revocations.none { it.deviceId == "device-keyless" },
            "a keyless revocation was posted: ${relay.revocations}",
        )
        assertNull(
            testProxy.database.getData(
                DataId.StringDataId.create(testProxy.tContext, Device.ID, "device-keyless"),
            ),
            "the removal must not depend on the admission strip",
        )
    }
}

/** Minimal relay recording `revokeAdmission` calls; everything else unused. */
private class RecordingRevokeRelay(private val context: TContext) : SyncRelay {
    data class Revocation(
        val ownerId: String,
        val deviceId: String,
        val epoch: Long,
        val revokedAt: Long,
        val signature: ByteArray,
        /** F1′ — the capability key the revocation names. */
        val deviceA33PublicKey: ByteArray,
    )

    val revocations = mutableListOf<Revocation>()

    override suspend fun revokeAdmission(
        ownerId: String,
        deviceId: String,
        epoch: Long,
        revokedAt: Long,
        signature: ByteArray,
        deviceA33PublicKey: ByteArray,
    ): TResult<Data?> {
        revocations.add(
            Revocation(ownerId, deviceId, epoch, revokedAt, signature, deviceA33PublicKey),
        )
        return TResult.Success.create(context, null)
    }

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
