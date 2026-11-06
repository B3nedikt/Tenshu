package app.tenshu.contacts.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The D3 badge derivation, including the Q2b rule. These assertions pin the
 * demote-on-mixed-keyring behaviour, so it cannot be "simplified" into a
 * `≥1 identity at VERIFIED+` rule.
 */
class ContactTrustTest {

    private fun identity(
        fingerprint: String,
        verification: Verification,
    ) = ContactIdentity(
        fingerprint = fingerprint,
        selfAssertedName = "claimed",
        verification = verification,
    )

    @Test
    fun `no keyring is not connected`() {
        assertEquals(TrustState.NotConnected, trustOf(emptyList()))
    }

    @Test
    fun `an all-unverified keyring is unverified`() {
        val keyring = listOf(
            identity("aaaa", Verification.UNVERIFIED),
            identity("bbbb", Verification.UNVERIFIED),
        )
        assertEquals(TrustState.Unverified, trustOf(keyring))
    }

    @Test
    fun `an all-verified keyring is verified`() {
        val keyring = listOf(
            identity("aaaa", Verification.VERIFIED),
            identity("bbbb", Verification.ATTESTED),
        )
        assertEquals(TrustState.Verified, trustOf(keyring))
    }

    @Test
    fun `a verified key beside an unverified one demotes the badge`() {
        // ⚠ Q2b. TenshuIdentity is keyed by fingerprint, so a rotated key is a NEW row
        // beside the old one. A `≥1 VERIFIED` rule would render a checkmark here — for
        // a key the contact no longer uses, which is the exact case verification catches.
        val keyring = listOf(
            identity("old-verified", Verification.VERIFIED),
            identity("new-unverified", Verification.UNVERIFIED),
        )
        assertEquals(TrustState.NeedsReverification, trustOf(keyring))
    }

    @Test
    fun `the demotion does not depend on keyring order`() {
        val newFirst = listOf(
            identity("new-unverified", Verification.UNVERIFIED),
            identity("old-verified", Verification.VERIFIED),
        )
        assertEquals(TrustState.NeedsReverification, trustOf(newFirst))
    }

    @Test
    fun `attested beside unverified also demotes`() {
        val keyring = listOf(
            identity("attested", Verification.ATTESTED),
            identity("fresh", Verification.UNVERIFIED),
        )
        assertEquals(TrustState.NeedsReverification, trustOf(keyring))
    }

    @Test
    fun `a contact with no key is still a contact`() {
        // D5: manual entry / import is not a third path — it is a Person with no key.
        val contact = Contact(personId = "p1", displayName = "Dad")
        assertFalse(contact.isConnected)
        assertEquals(TrustState.NotConnected, contact.trust)
    }

    @Test
    fun `search matches the petname and the claimed names`() {
        val contact = Contact(
            personId = "p1",
            displayName = "Sara Lindqvist",
            identities = listOf(identity("aaaa", Verification.UNVERIFIED).copy(selfAssertedName = "slq")),
        )
        assertTrue(contact.matches("sara"))
        assertTrue(contact.matches("LINDQ"))
        assertTrue(contact.matches("slq"))
        assertTrue(contact.matches("   "), "a blank query matches everything")
        assertFalse(contact.matches("mira"))
    }

    @Test
    fun `fingerprints render in the pinned display format`() {
        // contact-invitation-handshake.md §5.3: leading 15 hex chars, XXXXX-YYYYY-ZZZZZ.
        assertEquals(
            "3AB19-C7F2E-08D64",
            displayFingerprint("3ab19c7f2e08d641b5c0aa93e77f10d2846bc5e1"),
        )
    }
}
