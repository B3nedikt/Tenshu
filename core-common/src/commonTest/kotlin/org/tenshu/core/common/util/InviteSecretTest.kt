package org.tenshu.core.common.util

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Invite-link triple + encodings (`contact-invitation-handshake.md` §3):
 * round-trip both forms, unknown-version hard error, entropy sanity.
 */
class InviteSecretTest {

    private fun payload() = InviteLinkPayload.mint(nextSecureRandomBytes(32))

    @Test
    fun messagingLinkRoundTrips() {
        val original = payload()
        val link = original.toMessagingLink()

        assertTrue(link.startsWith("https://tenshu.app/i#v1."), "unexpected link shape: $link")
        // 64-byte payload → 86 base64url chars, no padding (§3).
        assertEquals(86, link.substringAfter("#v1.").length)

        val parsed = InviteLinkPayload.parse(link)
        assertContentEquals(original.inviteId, parsed.inviteId)
        assertContentEquals(original.inviteSecret, parsed.inviteSecret)
        assertContentEquals(original.inviterPublicKey, parsed.inviterPublicKey)
    }

    @Test
    fun qrUriRoundTrips() {
        val original = payload()
        val uri = original.toQrUri()

        assertTrue(uri.startsWith("tenshu-invite:v1?"), "unexpected QR shape: $uri")

        val parsed = InviteLinkPayload.parse(uri)
        assertContentEquals(original.inviteId, parsed.inviteId)
        assertContentEquals(original.inviteSecret, parsed.inviteSecret)
        assertContentEquals(original.inviterPublicKey, parsed.inviterPublicKey)
    }

    @Test
    fun unknownVersionIsAHardError() {
        // A v2 link minted by a newer app must fail loudly ("update your
        // app"), not parse garbage — both encodings.
        val v2Link = payload().toMessagingLink().replace("#v1.", "#v2.")
        assertFailsWith<UnsupportedInviteVersionException> { InviteLinkPayload.parse(v2Link) }

        val v2Qr = payload().toQrUri().replace(":v1?", ":v2?")
        assertFailsWith<UnsupportedInviteVersionException> { InviteLinkPayload.parse(v2Qr) }
    }

    @Test
    fun malformedInputIsAFormatError() {
        assertFailsWith<InviteLinkFormatException> { InviteLinkPayload.parse("https://example.com/x") }
        assertFailsWith<InviteLinkFormatException> { InviteLinkPayload.parse("https://tenshu.app/i#v1.!!!") }
        assertFailsWith<InviteLinkFormatException> { InviteLinkPayload.parse("tenshu-invite:v1?i=ABC") }
        // Truncated base64url payload (right alphabet, wrong length).
        assertFailsWith<InviteLinkFormatException> {
            InviteLinkPayload.parse("https://tenshu.app/i#v1.AAAA")
        }
    }

    @Test
    fun mintedValuesDoNotCollide() {
        // Entropy sanity: 32 generations, no repeated id or secret.
        val ids = mutableSetOf<String>()
        val secrets = mutableSetOf<String>()
        repeat(32) {
            val minted = payload()
            assertTrue(ids.add(minted.inviteId.toHexString()), "inviteId collision")
            assertTrue(secrets.add(minted.inviteSecret.toHexString()), "inviteSecret collision")
        }
    }

    @Test
    fun secretHashIsSha256Hex() {
        val minted = payload()
        assertEquals(sha256(minted.inviteSecret).toHexString(), minted.secretHashHex())
        assertEquals(64, minted.secretHashHex().length)
        assertEquals(32, minted.inviteIdHex.length)
    }

    @Test
    fun base32RoundTripsArbitraryLengths() {
        // RFC 4648 base32 (unpadded) over the sizes the QR form uses + edges.
        listOf(0, 1, 2, 5, 16, 31, 32).forEach { size ->
            val bytes = nextSecureRandomBytes(size)
            assertContentEquals(bytes, decodeBase32(encodeBase32(bytes)), "size $size")
        }
        // Decoding is case-insensitive (QR alphanumeric mode upper-cases).
        val bytes = nextSecureRandomBytes(16)
        assertContentEquals(bytes, decodeBase32(encodeBase32(bytes).lowercase()))
    }
}
