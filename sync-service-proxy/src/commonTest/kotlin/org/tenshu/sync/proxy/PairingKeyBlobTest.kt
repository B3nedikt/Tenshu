package org.tenshu.sync.proxy

import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.sync.common.AdmissionCapabilityCert
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `PairingKeyBlob` pack/unpack contract across all four length-dispatched
 * shapes — v2 (A33 + cert chain, `delegated-device-admission-authority.md`
 * §4.2), the 4-key and 2-key SR-3b.4 shapes, and the legacy single-key blob —
 * plus the degrade-not-crash path for a corrupt v2 tail.
 */
class PairingKeyBlobTest {

    private val derivedKeys = SyncCryptoManager.DerivedKeys(
        sharedIdentityKey = ByteArray(32) { 0x11 },
        sharedVolumeKey = ByteArray(32) { 0x22 },
    )
    private val a13 = SyncCryptoManager.KeyPair(
        publicKey = ByteArray(32) { 0x33 },
        privateKey = ByteArray(32) { 0x44 },
    )
    private val a33 = SyncCryptoManager.KeyPair(
        publicKey = ByteArray(32) { 0x55 },
        privateKey = ByteArray(32) { 0x66 },
    )
    private val certChain = listOf(
        AdmissionCapabilityCert(
            ownerId = "owner-1",
            deviceA33PublicKey = a33.publicKey,
            deviceId = "device-y",
            mintedAtEpoch = 2L,
            signature = ByteArray(64) { 0x77 },
        ),
    )

    @Test
    fun v2Blob_roundTripsAdmissionKeyPairAndChain() {
        val unpacked = assertNotNull(
            PairingKeyBlob.unpack(PairingKeyBlob.pack(derivedKeys, a13, a33, certChain)),
        )

        assertContentEquals(derivedKeys.sharedIdentityKey, unpacked.derivedKeys.sharedIdentityKey)
        assertContentEquals(derivedKeys.sharedVolumeKey, unpacked.derivedKeys.sharedVolumeKey)
        assertContentEquals(a13.privateKey, assertNotNull(unpacked.realmEncryptionKeyPair).privateKey)

        val admission = assertNotNull(unpacked.admissionKeyPair, "joiner receives its A33 keypair")
        assertContentEquals(a33.publicKey, admission.publicKey)
        assertContentEquals(a33.privateKey, admission.privateKey)

        assertEquals(1, unpacked.admissionCertChain.size)
        val cert = unpacked.admissionCertChain[0]
        assertEquals("owner-1", cert.ownerId)
        assertEquals("device-y", cert.deviceId)
        assertEquals(2L, cert.mintedAtEpoch)
        assertContentEquals(certChain[0].signature, cert.signature)
    }

    @Test
    fun fourKeyBlob_hasNoAdmissionAuthority() {
        val blob = PairingKeyBlob.pack(derivedKeys, a13)
        assertEquals(128, blob.size)

        val unpacked = assertNotNull(PairingKeyBlob.unpack(blob))
        assertNotNull(unpacked.realmEncryptionKeyPair)
        assertNull(unpacked.admissionKeyPair)
        assertTrue(unpacked.admissionCertChain.isEmpty())
    }

    @Test
    fun admissionWithoutCertChain_dropsToFourKeyShape() {
        // An A33 keypair with no chain can't prove anything — the packer ships
        // the plain 4-key blob and the joiner stays a non-admitter.
        assertEquals(128, PairingKeyBlob.pack(derivedKeys, a13, a33, emptyList()).size)
    }

    @Test
    fun admissionWithoutA13_dropsToTwoKeyShape() {
        // A host that can't supply A13 (locked) can't mint either; the shorter
        // legacy shape wins regardless of what admission params are passed.
        assertEquals(64, PairingKeyBlob.pack(derivedKeys, null, a33, certChain).size)
    }

    @Test
    fun twoKeyBlob_parsesKeysOnly() {
        val unpacked = assertNotNull(PairingKeyBlob.unpack(PairingKeyBlob.pack(derivedKeys, null)))
        assertContentEquals(derivedKeys.sharedVolumeKey, unpacked.derivedKeys.sharedVolumeKey)
        assertNull(unpacked.realmEncryptionKeyPair)
        assertNull(unpacked.admissionKeyPair)
    }

    @Test
    fun aBlobOfNoKnownShape_isRefused() {
        // 32 bytes was the pre-SR-3b.4 single-key blob; it is no longer read as anything.
        assertNull(PairingKeyBlob.unpack(ByteArray(32) { 0x0A }))
        assertNull(PairingKeyBlob.unpack(ByteArray(100)))
        assertNull(PairingKeyBlob.unpack(ByteArray(0)))
    }

    @Test
    fun corruptV2Tail_degradesToFourKeyRead() {
        val blob = PairingKeyBlob.pack(derivedKeys, a13, a33, certChain)
        val corrupt = blob.copyOfRange(0, blob.size - 3) // truncate inside the cert chain

        val unpacked = assertNotNull(PairingKeyBlob.unpack(corrupt))
        // Keys + A13 survive; the joiner just isn't an admitter.
        assertContentEquals(derivedKeys.sharedIdentityKey, unpacked.derivedKeys.sharedIdentityKey)
        assertContentEquals(a13.privateKey, assertNotNull(unpacked.realmEncryptionKeyPair).privateKey)
        assertNull(unpacked.admissionKeyPair)
        assertTrue(unpacked.admissionCertChain.isEmpty())
    }
}
