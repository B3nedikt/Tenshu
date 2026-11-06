package org.tenshu.sync.proxy

import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createCoreTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.common.proxy.WriteOrigin
import org.tenshu.core.common.security.ownerIdForMasterPublicKey
import org.tenshu.core.entity.DataId
import org.tenshu.core.security.BootstrapSyncCryptoManager
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.core.security.stubEd25519Signature
import org.tenshu.identity.AdmissionCertGrant
import org.tenshu.identity.AdmissionKeyAnnouncement
import org.tenshu.identity.Device
import org.tenshu.identity.common.identityServiceCommonModule
import org.tenshu.sync.common.AdmissionCapabilityCert
import org.tenshu.sync.common.AdmissionChainVerdict
import org.tenshu.sync.common.AdmissionChainVerifier
import org.tenshu.sync.common.admissionKeyAnnouncementMessage
import org.tenshu.sync.common.decodeAdmissionCertChain
import org.tenshu.sync.common.encodeAdmissionCertChain
import org.tenshu.sync.common.syncServiceCommonModule
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P4 of `delegated-device-admission-authority.md` (§8): the migration/re-root
 * choreography over synced rows — a pre-P3 joiner self-mints + announces, an
 * A1-holder verifies the announcement (against the Device row's A4 key) and
 * grants a depth-1 chain, the joiner installs it and becomes an admitter, and
 * a deep chain flattens the same way (Q2). No private key ever on a row.
 */
class AdmissionMigrationObserverTest {

    private lateinit var testProxy: TestProxy
    private lateinit var crypto: MigrationStubCryptoManager

    private val masterPhrase = "abcdefghijklmnopqrstuvwxyz012345"
    private lateinit var masterKey: SyncCryptoManager.KeyPair
    private lateinit var realmOwnerId: String

    @BeforeTest
    fun setup() {
        testProxy = TestProxy(schemaRegistry = createCoreTestRegistry()).also {
            it.schemaRegistry.registerConfig(syncServiceCommonModule)
            it.schemaRegistry.registerConfig(identityServiceCommonModule)
        }
        crypto = MigrationStubCryptoManager()
        masterKey = crypto.deriveMasterKeyPair(masterPhrase)
        realmOwnerId = ownerIdForMasterPublicKey(masterKey.publicKey)
    }

    /** A joiner-shape SyncManagerImpl: derivedKeys + A13, never A1. */
    private fun joinerManager(
        storage: InMemorySecureStorageDelegate = InMemorySecureStorageDelegate(),
        admissionKeyPair: SyncCryptoManager.KeyPair? = null,
        admissionCertChain: List<AdmissionCapabilityCert> = emptyList(),
    ): SyncManagerImpl = SyncManagerImpl(crypto, storage, testProxy.tContext).apply {
        initializeWithKeys(
            SyncCryptoManager.DerivedKeys(ByteArray(32) { 1 }, ByteArray(32) { 2 }),
            ownerId = realmOwnerId,
            realmEncryptionKeyPair = SyncCryptoManager.KeyPair(ByteArray(32) { 3 }, ByteArray(32) { 4 }),
            admissionKeyPair = admissionKeyPair,
            admissionCertChain = admissionCertChain,
        )
    }

    private fun observer(syncManager: SyncManagerImpl) =
        AdmissionMigrationObserver(testProxy.executionContext, syncManager, crypto)

    /** Any save event — the observer's tick; content is irrelevant to self-announce. */
    private fun tick(obs: AdmissionMigrationObserver, syncManager: SyncManagerImpl) {
        obs.onDataSaved(
            Device.create(testProxy.tContext, syncManager.currentDeviceId, "Self", byteArrayOf(1)),
            WriteOrigin.SYNC_INGEST,
        )
    }

    private fun readAnnouncement(deviceId: String): AdmissionKeyAnnouncement? =
        testProxy.database.getData(
            DataId.StringDataId.create(testProxy.tContext, AdmissionKeyAnnouncement.ID, deviceId),
        ) as? AdmissionKeyAnnouncement

    private fun readGrant(deviceId: String): AdmissionCertGrant? =
        testProxy.database.getData(
            DataId.StringDataId.create(testProxy.tContext, AdmissionCertGrant.ID, deviceId),
        ) as? AdmissionCertGrant

    private fun seedDeviceRow(deviceId: String, a4PublicKey: ByteArray) {
        testProxy.executionContext.saveData(
            Device.create(testProxy.tContext, deviceId, "Joiner", a4PublicKey),
        )
    }

    private fun saveAnnouncement(deviceId: String, a33Pub: ByteArray, signer: ByteArray) {
        testProxy.executionContext.saveData(
            AdmissionKeyAnnouncement.create(
                context = testProxy.tContext,
                deviceId = deviceId,
                publicKey = a33Pub,
                signature = stubEd25519Signature(signer, admissionKeyAnnouncementMessage(deviceId, a33Pub)),
            )
        )
    }

    private fun mintGrantRow(deviceId: String, a33Pub: ByteArray, master: SyncCryptoManager.KeyPair = masterKey, owner: String = realmOwnerId) {
        val cert = AdmissionCapabilityCert(
            ownerId = owner,
            deviceA33PublicKey = a33Pub,
            deviceId = deviceId,
            mintedAtEpoch = 0L,
            signature = stubEd25519Signature(
                master.privateKey,
                AdmissionCapabilityCert.message(owner, a33Pub, deviceId, 0L),
            ),
        )
        testProxy.executionContext.saveData(
            AdmissionCertGrant.create(
                context = testProxy.tContext,
                deviceId = deviceId,
                masterPublicKey = master.publicKey,
                certChain = encodeAdmissionCertChain(listOf(cert)),
            )
        )
    }

    // ---- duty 1: self-announce ----

    @Test
    fun preP3Joiner_selfMintsAndAnnounces_andCustodySurvivesRestart() {
        val storage = InMemorySecureStorageDelegate()
        val joiner = joinerManager(storage)
        tick(observer(joiner), joiner)

        // Local: keypair minted + persisted; still a non-admitter (empty chain).
        val keyPair = assertNotNull(joiner.getAdmissionKeyPair(), "joiner must self-mint an A33 keypair")
        assertTrue(joiner.getAdmissionCertChain().isEmpty(), "no chain until the grant lands")

        // Synced: announcement row carries the public key, A4-signed.
        val announcement = assertNotNull(readAnnouncement(joiner.currentDeviceId))
        assertContentEquals(keyPair.publicKey, announcement.publicKey.get())
        assertTrue(
            crypto.verifyEd25519(
                joiner.getDeviceSigningKeyPair().publicKey,
                admissionKeyAnnouncementMessage(joiner.currentDeviceId, keyPair.publicKey),
                announcement.signature.get(),
            ),
            "announcement must verify against the device's A4 key",
        )

        // Restart over the same storage: the J3 payload rehydrates the pending keypair.
        val restarted = SyncManagerImpl(crypto, storage, testProxy.tContext)
        assertContentEquals(keyPair.privateKey, restarted.getAdmissionKeyPair()?.privateKey)
    }

    @Test
    fun a1Holder_neverAnnounces() {
        val host = SyncManagerImpl(crypto, InMemorySecureStorageDelegate(), testProxy.tContext)
        host.initialize(masterPhrase)
        tick(observer(host), host)
        assertNull(readAnnouncement(host.currentDeviceId))
        assertNull(host.getAdmissionKeyPair())
    }

    @Test
    fun depth1Admitter_doesNotAnnounce() {
        val a33 = SyncCryptoManager.KeyPair(ByteArray(32) { 9 }, ByteArray(32) { 9 })
        val cert = AdmissionCapabilityCert(
            realmOwnerId, a33.publicKey, "device-flat", 0L,
            stubEd25519Signature(masterKey.privateKey, AdmissionCapabilityCert.message(realmOwnerId, a33.publicKey, "device-flat", 0L)),
        )
        val joiner = joinerManager(admissionKeyPair = a33, admissionCertChain = listOf(cert))
        tick(observer(joiner), joiner)
        assertNull(readAnnouncement(joiner.currentDeviceId), "a flat chain has nothing to migrate or re-root")
    }

    // ---- duty 2: owner mint ----

    @Test
    fun owner_mintsDepth1Grant_forVerifiedAnnouncement() {
        val owner = SyncManagerImpl(crypto, InMemorySecureStorageDelegate(), testProxy.tContext)
        owner.initialize(masterPhrase)
        val obs = observer(owner)

        val a4 = ByteArray(32) { 40 }
        val a33Pub = ByteArray(32) { 50 }
        val y = org.tenshu.sync.common.deviceIdForPublicKey(a4) // workstream B: the id is the key's
        seedDeviceRow(y, a4)
        saveAnnouncement(y, a33Pub, signer = a4)

        obs.onDataSaved(readAnnouncement(y)!!, WriteOrigin.SYNC_INGEST)

        val grant = assertNotNull(readGrant(y), "a verified announcement earns a grant")
        assertContentEquals(owner.currentMasterKeyPair!!.publicKey, grant.masterPublicKey.get())
        val chain = decodeAdmissionCertChain(grant.certChain.get())
        assertEquals(1, chain.size, "owner-minted grants are always depth-1 (the Q2 re-root shape)")
        assertContentEquals(a33Pub, chain[0].deviceA33PublicKey)
        assertEquals(
            AdmissionChainVerdict.Admit,
            AdmissionChainVerifier.verifyChain(
                owner.currentMasterKeyPair!!.publicKey, owner.currentOwnerId!!, chain,
                emptyList(), crypto::verifyEd25519,
            ),
        )
    }

    @Test
    fun forgedAnnouncement_isNotMinted() {
        val owner = SyncManagerImpl(crypto, InMemorySecureStorageDelegate(), testProxy.tContext)
        owner.initialize(masterPhrase)

        // Announcement signed by an attacker key, not device-y's registered A4 —
        // minting would bind the attacker's A33 to device-y's identity (T-DA-06).
        seedDeviceRow("device-y", a4PublicKey = ByteArray(32) { 40 })
        saveAnnouncement("device-y", ByteArray(32) { 50 }, signer = ByteArray(32) { 66 })

        observer(owner).onDataSaved(readAnnouncement("device-y")!!, WriteOrigin.SYNC_INGEST)
        assertNull(readGrant("device-y"), "a mis-signed announcement must not be granted")
    }

    /**
     * Workstream B: an admitted device writes a `Device` row pairing a VICTIM's id with its OWN A4
     * (rows are synced and unchecked on merge) and announces under it with a valid A4 signature.
     * A1 must not mint a cert naming the victim's id for the attacker's A33.
     */
    @Test
    fun aRowPairingAnotherDevicesIdWithTheAnnouncersKey_isNotMinted() {
        val owner = SyncManagerImpl(crypto, InMemorySecureStorageDelegate(), testProxy.tContext)
        owner.initialize(masterPhrase)

        val attackersA4 = ByteArray(32) { 40 }
        val victimsId = org.tenshu.sync.common.deviceIdForPublicKey(ByteArray(32) { 41 })
        seedDeviceRow(victimsId, attackersA4)
        saveAnnouncement(victimsId, ByteArray(32) { 50 }, signer = attackersA4)

        observer(owner).onDataSaved(readAnnouncement(victimsId)!!, WriteOrigin.SYNC_INGEST)
        assertNull(readGrant(victimsId), "A1 must not mint a cert under an id that is not the key's")
    }

    // ---- duty 3: install ----

    @Test
    fun announcingJoiner_installsItsGrant_andBecomesAdmitter() {
        val storage = InMemorySecureStorageDelegate()
        val joiner = joinerManager(storage)
        val obs = observer(joiner)
        tick(obs, joiner) // self-mint + announce
        val keyPair = joiner.getAdmissionKeyPair()!!

        mintGrantRow(joiner.currentDeviceId, keyPair.publicKey)
        obs.onDataSaved(readGrant(joiner.currentDeviceId)!!, WriteOrigin.SYNC_INGEST)

        assertEquals(1, joiner.getAdmissionCertChain().size, "grant installed — the device is now an admitter")
        // And it survives a restart (J3 persistence).
        val restarted = SyncManagerImpl(crypto, storage, testProxy.tContext)
        assertEquals(1, restarted.getAdmissionCertChain().size)
    }

    @Test
    fun grantRootedAtForeignMaster_isRejected() {
        val joiner = joinerManager()
        val obs = observer(joiner)
        tick(obs, joiner)
        val keyPair = joiner.getAdmissionKeyPair()!!

        // Chain verifies against SOME master — but not the one this realm's ownerId
        // is derived from. Installing it would root the device's authority elsewhere.
        val foreignMaster = crypto.deriveMasterKeyPair("some-other-realm-phrase-entirely")
        mintGrantRow(
            joiner.currentDeviceId, keyPair.publicKey,
            master = foreignMaster, owner = ownerIdForMasterPublicKey(foreignMaster.publicKey),
        )
        obs.onDataSaved(readGrant(joiner.currentDeviceId)!!, WriteOrigin.SYNC_INGEST)

        assertTrue(joiner.getAdmissionCertChain().isEmpty(), "a foreign-rooted grant must not install")
    }

    @Test
    fun deepChain_reRootsToDepth1() {
        // Device X holds a valid depth-2 chain (admitted by a delegate, P3 shape).
        val xA33 = SyncCryptoManager.KeyPair(ByteArray(32) { 70 }, ByteArray(32) { 70 })
        val parentA33 = ByteArray(32) { 80 }
        val deep = listOf(
            AdmissionCapabilityCert(
                realmOwnerId, xA33.publicKey, "device-x", 0L,
                stubEd25519Signature(parentA33, AdmissionCapabilityCert.message(realmOwnerId, xA33.publicKey, "device-x", 0L)),
            ),
            AdmissionCapabilityCert(
                realmOwnerId, parentA33, "device-w", 0L,
                stubEd25519Signature(masterKey.privateKey, AdmissionCapabilityCert.message(realmOwnerId, parentA33, "device-w", 0L)),
            ),
        )
        val device = joinerManager(admissionKeyPair = xA33, admissionCertChain = deep)
        val obs = observer(device)

        // A deep chain announces (the re-root request) — with its EXISTING key.
        tick(obs, device)
        val announcement = assertNotNull(readAnnouncement(device.currentDeviceId))
        assertContentEquals(xA33.publicKey, announcement.publicKey.get())

        // The owner's depth-1 grant arrives → adopted because it's flatter.
        mintGrantRow(device.currentDeviceId, xA33.publicKey)
        obs.onDataSaved(readGrant(device.currentDeviceId)!!, WriteOrigin.SYNC_INGEST)
        assertEquals(1, device.getAdmissionCertChain().size, "re-root flattens the chain")
    }
}

/** [BootstrapSyncCryptoManager] with real-width, fresh-per-mint A33 keys (see P3 test). */
private class MigrationStubCryptoManager : SyncCryptoManager by BootstrapSyncCryptoManager() {
    private var counter = 0
    override fun generateAdmissionKeyPair(): SyncCryptoManager.KeyPair {
        val seed = ByteArray(32) { (it + 100 + counter).toByte() }
        counter++
        return SyncCryptoManager.KeyPair(publicKey = seed, privateKey = seed)
    }
}
