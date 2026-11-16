package org.tenshu.sync.proxy

import org.tenshu.contact.common.AcceptancePayload
import org.tenshu.core.security.BootstrapSyncCryptoManager
import org.tenshu.sync.common.MembershipAcceptance
import org.tenshu.sync.common.MembershipRecordVerifier
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RO D7 — the owner writes a member's `Member` row only for an acceptance that is genuinely the one it waits
 * for: right realm, right member, the A26 key it granted, from the contact it granted to, and signed by that A26.
 */
class AcceptanceVerificationTest {

    private val crypto = BootstrapSyncCryptoManager()
    private val authority = crypto.generateDeviceSigningKeyPair()
    private val other = crypto.generateDeviceSigningKeyPair()
    private val realmId = "shared-realm-1"
    private val memberId = MembershipRecordVerifier.memberIdOf(authority.publicKey)
    private val grantee = "grantee-fingerprint"

    private fun acceptance(
        realm: String = realmId,
        authorityPub: ByteArray = authority.publicKey,
        signer: ByteArray = authority.privateKey,
        contactFingerprint: String = grantee,
        displayName: String = "Bob",
    ) = AcceptancePayload(
        realmId = realm,
        memberAuthorityPublicKey = authorityPub,
        displayName = displayName,
        contactFingerprint = contactFingerprint,
        acceptedAt = 1_000L,
        signature = crypto.signEd25519(signer, MembershipAcceptance.message(realm, authorityPub, displayName, contactFingerprint, 1_000L)),
    )

    private fun genuine(a: AcceptancePayload) =
        isGenuineAcceptance(a, realmId, memberId, authority.publicKey, grantee, crypto::verifyEd25519)

    @Test
    fun theAwaitedAcceptance_isGenuine() = assertTrue(genuine(acceptance()))

    /** ⚠⚠ Only the member's own A26 can accept: a well-formed acceptance signed by any other key is refused. */
    @Test
    fun anAcceptanceSignedByAnotherKey_isRefused() = assertFalse(genuine(acceptance(signer = other.privateKey)))

    @Test
    fun anAcceptanceForAnotherAuthority_isRefused() =
        assertFalse(genuine(acceptance(authorityPub = other.publicKey, signer = other.privateKey)))

    @Test
    fun anAcceptanceForAnotherRealm_isRefused() = assertFalse(genuine(acceptance(realm = "another-realm")))

    @Test
    fun anAcceptanceFromAnotherContact_isRefused() = assertFalse(genuine(acceptance(contactFingerprint = "someone-else")))

    /** The display name is signed: an altered one does not verify. */
    @Test
    fun aTamperedDisplayName_isRefused() {
        val signed = acceptance(displayName = "Bob")
        assertFalse(genuine(signed.copy(displayName = "Alice")))
    }
}
