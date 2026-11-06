package org.tenshu.sync.common

import org.tenshu.core.security.BootstrapSyncCryptoManager
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F4: a membership **capability** signature must never double as a membership **revocation**.
 *
 * ⚠⚠ Both records are signed by the *same* key — the realm master (A1) — and both were bare
 * concatenations of the *same* four fields in the same order:
 *
 * ```
 * capability = realmId ‖ memberAuthorityPub ‖ epoch(8) ‖ issuedAt(8)
 * revocation = realmId ‖ memberAuthorityPub ‖ epoch(8) ‖ revokedAt(8)
 * ```
 *
 * With `epoch` pinned at 0 on both sides (`SharedRealmGrantManager.CAP_EPOCH`/`REVOKE_EPOCH`) the
 * two differ only in what the last 8 bytes are *called*. So a capability with `issuedAt = T` is
 * byte-identical to a revocation with `revokedAt = T`, and its master signature verifies as both.
 *
 * ⚠⚠ That is an eviction primitive for anyone holding a capability, and the sharpest holder is the
 * **relay**: it receives one in the body of every `POST /sync/realm-member/admit`, and under House
 * Rule #1 it is supposed to be able to *verify* a master signature and never to mint one. Replaying
 * a capability at `/sync/realm-member/revoke` lets it evict any member of any realm using only
 * material it legitimately holds. A member can likewise replay their own capability to self-evict.
 *
 * ⚠ Not cross-member, though: `MemberAuthorityKey` carries the A26 **private** key, so it syncs
 * across one member's own devices, never to another member. Measured before the fix — production
 * held 0 `member_revocation` and 0 `member_admission` rows, so this was latent, never exercised.
 *
 * Unlike the admission twin (§7d) this needs no collision construction and no attacker-chosen
 * field: the shapes are already exactly equal.
 */
class MembershipMessageConfusionTest {

    private val crypto = BootstrapSyncCryptoManager()
    private val masterKey = ByteArray(32) { (it + 1).toByte() }
    private val memberAuthority = ByteArray(32) { (it + 50).toByte() }
    private val realmId = "owner-realm-1"

    /** The two shapes must be distinguishable as bytes, whatever the field values. */
    @Test
    fun theCapabilityAndRevocationMessagesCannotCollide() {
        val t = 1_000L
        val capabilityBytes = MembershipCapability.message(realmId, memberAuthority, epoch = 0L, issuedAt = t)
        val revocationBytes = MembershipRevocation.message(realmId, memberAuthority, epoch = 0L, revokedAt = t)

        assertFalse(
            capabilityBytes.contentEquals(revocationBytes),
            "a capability signature is a valid revocation signature — one member can evict another",
        )
    }

    /**
     * End to end, as the relay actually checks it: mint a legitimate capability, re-label its
     * signed fields as a revocation, and confirm neither the relay's `/revoke` authorisation check
     * nor the verifier accepts it.
     */
    @Test
    fun aCapabilitySignatureCannotBeReplayedAsARevocation() {
        val issuedAt = 1_000L
        val capabilitySignature = crypto.signEd25519(
            masterKey,
            MembershipCapability.message(realmId, memberAuthority, epoch = 0L, issuedAt = issuedAt),
        )
        val capability = MembershipCapability(realmId, memberAuthority, 0L, issuedAt, capabilitySignature)

        // The replay: same realm, same authority, same epoch, and `revokedAt` set to `issuedAt`.
        val forged = MembershipRevocation(
            realmId = realmId,
            memberAuthorityPublicKey = memberAuthority,
            epoch = 0L,
            revokedAt = issuedAt,
            signature = capabilitySignature,
        )

        // 1. The relay's own authorisation check on POST /sync/realm-member/revoke.
        assertFalse(
            crypto.verifyEd25519(masterKey, forged.signedMessage(), forged.signature),
            "the relay would accept this forged revocation as master-signed",
        )

        // 2. And it must not actually evict the member it was minted for.
        assertFalse(
            MembershipChainVerifier.isRevoked(masterKey, capability, listOf(forged), crypto::verifyEd25519),
            "a member's own capability evicted them",
        )

        // Control: the genuine capability still admits, so the fix isn't just breaking everything.
        assertTrue(MembershipChainVerifier.verifyCapability(masterKey, capability, crypto::verifyEd25519))
    }
}
