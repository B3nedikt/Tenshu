package org.tenshu.core.common.security

import org.tenshu.core.common.util.nextSecureRandomBytes
import org.tenshu.core.security.AadMismatchException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * Generalised sealed box (`contact-invitation-handshake.md` §5.1) against the
 * real BouncyCastle backends: round-trip, recipient binding, info/AAD
 * binding, and byte-compatibility with the historical `wrapKeyForIdentity`
 * blob layout (which is now the `info = "tenshu/key-wrap/v1", aad = empty`
 * instance of the same primitive).
 */
class SealToIdentityTest {

    private val crypto = DefaultSyncCryptoManager(
        ed25519 = BouncyCastleEd25519Backend(),
        x25519 = BouncyCastleX25519Backend(),
        aead = BouncyCastleAeadBackend(),
        kdf = BouncyCastleKdfBackend(),
    )

    private val recipient = crypto.generateKeyPair()
    private val info = "tenshu/invite/response/v1".encodeToByteArray()
    private val inviteId = nextSecureRandomBytes(16)

    @Test
    fun sealUnsealRoundTrips() {
        val plaintext = "payload-with-some-structure".encodeToByteArray()
        val sealed = crypto.sealToIdentity(plaintext, recipient.publicKey, info, inviteId)
        val opened = crypto.unsealFromIdentity(sealed, recipient.privateKey, info, inviteId)
        assertContentEquals(plaintext, opened)
    }

    @Test
    fun twoSealsOfTheSamePlaintextDiffer() {
        // Fresh ephemeral keypair + nonce per seal.
        val plaintext = nextSecureRandomBytes(48)
        val a = crypto.sealToIdentity(plaintext, recipient.publicKey, info, inviteId)
        val b = crypto.sealToIdentity(plaintext, recipient.publicKey, info, inviteId)
        assertFalse(a.contentEquals(b))
        assertContentEquals(
            crypto.unsealFromIdentity(a, recipient.privateKey, info, inviteId),
            crypto.unsealFromIdentity(b, recipient.privateKey, info, inviteId),
        )
    }

    @Test
    fun wrongRecipientKeyFails() {
        val sealed = crypto.sealToIdentity(nextSecureRandomBytes(32), recipient.publicKey, info, inviteId)
        val other = crypto.generateKeyPair()
        // Wrong private key ⇒ different ECDH secret ⇒ AEAD tag failure.
        assertFails { crypto.unsealFromIdentity(sealed, other.privateKey, info, inviteId) }
    }

    @Test
    fun aadMismatchFails() {
        // The inviteId is bound into the AEAD AAD — a relay splicing a sealed
        // response from invite X onto invite Y must fail at unseal.
        val sealed = crypto.sealToIdentity(nextSecureRandomBytes(32), recipient.publicKey, info, inviteId)
        val otherInviteId = nextSecureRandomBytes(16)
        assertFailsWith<AadMismatchException> {
            crypto.unsealFromIdentity(sealed, recipient.privateKey, info, otherInviteId)
        }
    }

    @Test
    fun infoMismatchFails() {
        // Domain separation: a response blob can never open as an ack blob.
        val sealed = crypto.sealToIdentity(nextSecureRandomBytes(32), recipient.publicKey, info, inviteId)
        val ackInfo = "tenshu/invite/ack/v1".encodeToByteArray()
        assertFails { crypto.unsealFromIdentity(sealed, recipient.privateKey, ackInfo, inviteId) }
    }

    @Test
    fun keyWrapBlobsAreCrossCompatible() {
        // wrapKeyForIdentity ≡ sealToIdentity(info = "tenshu/key-wrap/v1",
        // aad = empty): blobs from either constructor open with the other, so
        // pre-existing wrapped A12 keys stay readable.
        val keyWrapInfo = "tenshu/key-wrap/v1".encodeToByteArray()
        val key = nextSecureRandomBytes(32)

        val wrapped = crypto.wrapKeyForIdentity(key, recipient.publicKey)
        assertContentEquals(
            key,
            crypto.unsealFromIdentity(wrapped, recipient.privateKey, keyWrapInfo),
        )

        val sealed = crypto.sealToIdentity(key, recipient.publicKey, keyWrapInfo)
        assertContentEquals(
            key,
            crypto.unwrapKeyForIdentity(sealed, recipient.privateKey),
        )
    }
}
