package org.tenshu.sync.proxy

import org.tenshu.core.common.TestProxy
import org.tenshu.core.common.createSyntheticTestRegistry
import org.tenshu.core.common.delegate.InMemorySecureStorageDelegate
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.core.security.syncCryptoManager
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * SR-3b.4 option B: a device's A13 (realm encryption sub-key) is resolvable via
 * `SyncManager.getRealmEncryptionKeyPair()` — derived from the phrase on a host,
 * delivered via the pairing key-blob on a joiner — and the joiner's A13 survives a
 * process restart (re-hydrated from the persisted joiner payload). Plus the
 * `PairingKeyBlob` pack/unpack contract the pairing handshake relies on.
 */
class RealmEncryptionKeyDeliveryTest {

    private val phrase = "personal phrase placeholder words go here now"

    @Test
    fun host_resolvesA13ByDerivingFromPhrase() {
        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        val crypto = proxy.tContext.syncCryptoManager()
        val sm = SyncManagerImpl(crypto, InMemorySecureStorageDelegate(), proxy.tContext)
        sm.initialize(phrase)

        val a13 = sm.getRealmEncryptionKeyPair()
        assertNotNull(a13, "host derives A13 from the phrase")
        assertContentEquals(
            crypto.deriveRealmEncryptionKeyPair(phrase).privateKey,
            a13.privateKey,
            "host's A13 is the phrase-derived realm encryption key",
        )
    }

    @Test
    fun joiner_resolvesA13FromPairing_andSurvivesRestart() {
        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        val crypto = proxy.tContext.syncCryptoManager()
        val storage = InMemorySecureStorageDelegate()

        val a13 = crypto.deriveRealmEncryptionKeyPair("the realm's recovery phrase")
        val keys = SyncCryptoManager.DerivedKeys(
            sharedIdentityKey = ByteArray(32) { 0x11 },
            sharedVolumeKey = ByteArray(32) { 0x22 },
        )

        // Joiner receives keys + A13 via pairing.
        val joiner = SyncManagerImpl(crypto, storage, proxy.tContext)
        joiner.initializeWithKeys(keys, ownerId = "host-realm", realmEncryptionKeyPair = a13)
        assertContentEquals(
            a13.privateKey,
            joiner.getRealmEncryptionKeyPair()?.privateKey,
            "joiner holds the A13 delivered via pairing",
        )

        // New process: a fresh SyncManagerImpl over the SAME storage re-hydrates the
        // joiner payload (J2 format) and recovers A13.
        val restarted = SyncManagerImpl(crypto, storage, proxy.tContext)
        assertContentEquals(
            a13.privateKey,
            restarted.getRealmEncryptionKeyPair()?.privateKey,
            "A13 survives a restart via the persisted joiner payload",
        )
        assertEquals("host-realm", restarted.currentOwnerId)
    }

    @Test
    fun joiner_withoutA13_resolvesNull() {
        val proxy = TestProxy(schemaRegistry = createSyntheticTestRegistry())
        val crypto = proxy.tContext.syncCryptoManager()
        val joiner = SyncManagerImpl(crypto, InMemorySecureStorageDelegate(), proxy.tContext)
        // Legacy-shaped pairing: keys but no A13 (host couldn't supply it).
        joiner.initializeWithKeys(
            SyncCryptoManager.DerivedKeys(ByteArray(32), ByteArray(32)),
            ownerId = "host-realm",
            realmEncryptionKeyPair = null,
        )
        assertNull(joiner.getRealmEncryptionKeyPair(), "no phrase + no delivered A13 ⇒ null")
    }

    @Test
    fun pairingKeyBlob_roundTrips() {
        val keys = SyncCryptoManager.DerivedKeys(
            sharedIdentityKey = ByteArray(32) { it.toByte() },
            sharedVolumeKey = ByteArray(32) { (it + 64).toByte() },
        )
        val a13 = SyncCryptoManager.KeyPair(
            publicKey = ByteArray(32) { (it + 128).toByte() },
            privateKey = ByteArray(32) { (it + 192).toByte() },
        )

        // With A13 (128 bytes).
        val packed = PairingKeyBlob.pack(keys, a13)
        assertEquals(128, packed.size)
        val unpacked = assertNotNull(PairingKeyBlob.unpack(packed))
        assertContentEquals(keys.sharedIdentityKey, unpacked.derivedKeys.sharedIdentityKey)
        assertContentEquals(keys.sharedVolumeKey, unpacked.derivedKeys.sharedVolumeKey)
        assertContentEquals(a13.publicKey, unpacked.realmEncryptionKeyPair?.publicKey)
        assertContentEquals(a13.privateKey, unpacked.realmEncryptionKeyPair?.privateKey)

        // Without A13 (64 bytes) — correct volume key, no A13.
        val packedNoA13 = PairingKeyBlob.pack(keys, null)
        assertEquals(64, packedNoA13.size)
        val unpackedNoA13 = assertNotNull(PairingKeyBlob.unpack(packedNoA13))
        assertContentEquals(keys.sharedVolumeKey, unpackedNoA13.derivedKeys.sharedVolumeKey)
        assertNull(unpackedNoA13.realmEncryptionKeyPair)

        // A single-key blob (32 bytes) is no known shape: refused.
        assertNull(PairingKeyBlob.unpack(keys.sharedIdentityKey))
    }
}
