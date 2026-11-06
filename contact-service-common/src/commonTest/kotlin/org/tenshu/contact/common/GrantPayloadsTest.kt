package org.tenshu.contact.common

import org.tenshu.core.security.BootstrapSyncCryptoManager
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails

/**
 * Grant-payload encode + sealed-box round-trip
 * (`docs/proposals/shared-realm-membership-grant.md` §4, D3).
 *
 * Mirrors the invite-payload tests: JSON survives a round-trip, the sealed blob
 * opens only with the grantee's A13 private key, and the grant-address AAD binds
 * the blob to its mailbox slot. Runs against [BootstrapSyncCryptoManager], whose
 * sealed-box stub enforces the recipient-key + `info`/`aad` match — exactly the
 * failure modes the real AEAD provides.
 */
class GrantPayloadsTest {

    private val crypto = BootstrapSyncCryptoManager()

    // Stub keeps publicKey == privateKey and binds a 32-byte recipient pub.
    private val recipientKey = ByteArray(32) { (it + 7).toByte() }
    private val grantAddress = ByteArray(16) { (it + 3).toByte() }

    private fun samplePayload() = GrantPayload(
        grantId = "grant-1",
        realmId = "owner-realm-1",
        wrappedRealmKey = ByteArray(104) { (it * 3 + 1).toByte() },
        capability = GrantCapability(
            realmId = "owner-realm-1",
            memberAuthorityPublicKey = ByteArray(32) { (it + 9).toByte() },
            epoch = 0L,
            issuedAt = 1_000L,
            signature = ByteArray(64) { (it + 2).toByte() },
        ),
        memberAuthorityPrivateKey = ByteArray(32) { (it + 5).toByte() },
        label = "Holiday photos",
        grantorFingerprint = "fp-alice",
    )

    @Test
    fun jsonRoundTrips() {
        val p = samplePayload()
        val decoded = decodeGrantPayload(p.encode())
        assertEquals(p.grantId, decoded.grantId)
        assertEquals(p.realmId, decoded.realmId)
        assertContentEquals(p.wrappedRealmKey, decoded.wrappedRealmKey)
        assertEquals(p.capability.realmId, decoded.capability.realmId)
        assertEquals(p.capability.epoch, decoded.capability.epoch)
        assertEquals(p.capability.issuedAt, decoded.capability.issuedAt)
        assertContentEquals(p.capability.memberAuthorityPublicKey, decoded.capability.memberAuthorityPublicKey)
        assertContentEquals(p.capability.signature, decoded.capability.signature)
        assertContentEquals(p.memberAuthorityPrivateKey, decoded.memberAuthorityPrivateKey)
        assertEquals(p.label, decoded.label)
        assertEquals(p.grantorFingerprint, decoded.grantorFingerprint)
    }

    @Test
    fun sealUnsealRoundTrips() {
        val p = samplePayload()
        val sealed = sealGrant(crypto, p, recipientKey, grantAddress)
        val opened = unsealGrant(crypto, sealed, recipientKey, grantAddress)
        assertEquals(p.grantId, opened.grantId)
        assertContentEquals(p.wrappedRealmKey, opened.wrappedRealmKey)
        assertContentEquals(p.capability.signature, opened.capability.signature)
        assertContentEquals(p.memberAuthorityPrivateKey, opened.memberAuthorityPrivateKey)
        assertEquals(p.grantorFingerprint, opened.grantorFingerprint)
    }

    @Test
    fun wrongRecipientKey_fails() {
        val sealed = sealGrant(crypto, samplePayload(), recipientKey, grantAddress)
        val wrongKey = ByteArray(32) { (it + 99).toByte() }
        assertFails { unsealGrant(crypto, sealed, wrongKey, grantAddress) }
    }

    @Test
    fun wrongGrantAddress_fails() {
        val sealed = sealGrant(crypto, samplePayload(), recipientKey, grantAddress)
        val wrongAddress = ByteArray(16) { (it + 50).toByte() }
        assertFails { unsealGrant(crypto, sealed, recipientKey, wrongAddress) }
    }
}
