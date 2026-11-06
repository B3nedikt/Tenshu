package org.tenshu.sync.common

import org.tenshu.core.security.BootstrapSyncCryptoManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies the Q30 membership capability chain
 * (`docs/proposals/shared-realm-membership-grant.md` §4, D1/D6).
 *
 * Runs against [BootstrapSyncCryptoManager] — the stub keeps `publicKey ==
 * privateKey`, so each "keypair" here is a single distinct seed used as both
 * halves; what's exercised is the chain *logic* (who signs what, realm binding,
 * revocation epochs). Real Ed25519 is exercised by the GR-3 live-relay system
 * test, the way the rest of this layer is split.
 */
class MembershipChainVerifierTest {

    private val crypto = BootstrapSyncCryptoManager()

    private val masterKey = ByteArray(32) { (it + 1).toByte() }        // realm master (A1)
    private val memberAuthority = ByteArray(32) { (it + 50).toByte() } // member authority (A26)
    private val deviceKey = ByteArray(32) { (it + 100).toByte() }      // per-realm device key
    private val wrongKey = ByteArray(32) { (it + 200).toByte() }
    private val realmId = "owner-realm-1"

    private fun capability(
        epoch: Long = 0L,
        signer: ByteArray = masterKey,
        authority: ByteArray = memberAuthority,
    ): MembershipCapability {
        val sig = crypto.signEd25519(
            signer,
            MembershipCapability.message(realmId, authority, epoch, issuedAt = 1_000L),
        )
        return MembershipCapability(realmId, authority, epoch, issuedAt = 1_000L, signature = sig)
    }

    private fun binding(
        signer: ByteArray = memberAuthority,
        realm: String = realmId,
    ): MemberDeviceBinding {
        val sig = crypto.signEd25519(
            signer,
            MemberDeviceBinding.message(realm, "device-x", deviceKey, boundAt = 2_000L),
        )
        return MemberDeviceBinding(realm, "device-x", deviceKey, boundAt = 2_000L, signature = sig)
    }

    private fun revocation(
        epoch: Long,
        signer: ByteArray = masterKey,
        authority: ByteArray = memberAuthority,
    ): MembershipRevocation {
        val sig = crypto.signEd25519(
            signer,
            MembershipRevocation.message(realmId, authority, epoch, revokedAt = 3_000L),
        )
        return MembershipRevocation(realmId, authority, epoch, revokedAt = 3_000L, signature = sig)
    }

    @Test
    fun validChain_admits() {
        assertEquals(
            MembershipAdmission.Admit,
            MembershipChainVerifier.verifyAdmission(masterKey, capability(), binding(), emptyList(), crypto::verifyEd25519),
        )
    }

    @Test
    fun capabilitySignedByWrongKey_rejected() {
        val cap = capability(signer = wrongKey)
        assertFalse(MembershipChainVerifier.verifyCapability(masterKey, cap, crypto::verifyEd25519))
        assertEquals(
            MembershipAdmission.RejectBadCapability,
            MembershipChainVerifier.verifyAdmission(masterKey, cap, binding(), emptyList(), crypto::verifyEd25519),
        )
    }

    @Test
    fun bindingSignedByWrongAuthority_rejected() {
        val cap = capability()
        val b = binding(signer = wrongKey)
        assertFalse(MembershipChainVerifier.verifyDeviceBinding(cap, b, crypto::verifyEd25519))
        assertEquals(
            MembershipAdmission.RejectBadBinding,
            MembershipChainVerifier.verifyAdmission(masterKey, cap, b, emptyList(), crypto::verifyEd25519),
        )
    }

    @Test
    fun bindingForDifferentRealm_rejected() {
        // Correctly signed by the member authority, but for another realm — the
        // realm-equality guard rejects it before the signature even matters.
        assertFalse(MembershipChainVerifier.verifyDeviceBinding(capability(), binding(realm = "other-realm"), crypto::verifyEd25519))
    }

    @Test
    fun revocationNamingTheAuthority_revokes() {
        val cap = capability(epoch = 5L)
        assertTrue(MembershipChainVerifier.isRevoked(masterKey, cap, listOf(revocation(epoch = 5L)), crypto::verifyEd25519))
        assertEquals(
            MembershipAdmission.RejectRevoked,
            MembershipChainVerifier.verifyAdmission(masterKey, cap, binding(), listOf(revocation(epoch = 6L)), crypto::verifyEd25519),
        )
    }

    /**
     * F4: the authority key alone decides the verdict — the epoch is reserved and unread.
     *
     * ⚠⚠ This is the guard, not a restatement. It goes red the moment anyone reintroduces
     * `revocation.epoch >= capability.epoch`: the `0L` and `4L` cases below both sit *under* the
     * capability's epoch and would stop revoking. That comparison was inert in production (both
     * sides pinned to 0) but would become live the moment someone wired a real realm epoch in, and
     * a lagging counter would then evict a member permanently and silently — §7b.1 on the
     * membership path.
     */
    @Test
    fun theEpochDoesNotAffectTheVerdict() {
        val cap = capability(epoch = 5L)
        for (epoch in listOf(0L, 4L, 5L, 6L, Long.MAX_VALUE)) {
            assertTrue(
                MembershipChainVerifier.isRevoked(masterKey, cap, listOf(revocation(epoch = epoch)), crypto::verifyEd25519),
                "revocation at epoch $epoch stopped biting — the epoch comparison is back",
            )
        }
    }

    /**
     * The structural reason dropping the epoch is safe: `SharedRealmGrantManager.grant()` mints a
     * **fresh A26 per grant**, so re-sharing with someone you previously un-shared with produces an
     * authority key no prior revocation names. Revocation is permanent for a *key*, never for a
     * *person* — which is why F2′'s durable "never returns" ledger must **not** be mirrored here.
     */
    @Test
    fun aFreshAuthorityKeyIsAdmittedDespiteAPriorRevocation() {
        val freshAuthority = ByteArray(32) { (it + 77).toByte() }
        val staleRevocation = revocation(epoch = 0L, authority = memberAuthority)
        val reGranted = capability(epoch = 0L, authority = freshAuthority)

        assertFalse(
            MembershipChainVerifier.isRevoked(masterKey, reGranted, listOf(staleRevocation), crypto::verifyEd25519),
            "a re-grant's fresh authority key was caught by the previous member's revocation",
        )
        assertEquals(
            MembershipAdmission.Admit,
            MembershipChainVerifier.verifyAdmission(
                masterKey,
                reGranted,
                binding(signer = freshAuthority),
                listOf(staleRevocation),
                crypto::verifyEd25519,
            ),
        )
    }

    @Test
    fun revocationSignedByNonMaster_ignored() {
        val cap = capability(epoch = 5L)
        // A revocation not signed by the realm master cannot evict a member.
        assertFalse(MembershipChainVerifier.isRevoked(masterKey, cap, listOf(revocation(epoch = 5L, signer = wrongKey)), crypto::verifyEd25519))
    }

    @Test
    fun revocationForDifferentAuthority_ignored() {
        val cap = capability(epoch = 5L)
        assertFalse(MembershipChainVerifier.isRevoked(masterKey, cap, listOf(revocation(epoch = 5L, authority = wrongKey)), crypto::verifyEd25519))
    }
}
