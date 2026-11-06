package org.tenshu.sync.common

import org.tenshu.core.security.BootstrapSyncCryptoManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies the N-link device-admission capability chain
 * (`docs/proposals/delegated-device-admission-authority.md` §4.4).
 *
 * Runs against [BootstrapSyncCryptoManager] — the stub keeps `publicKey ==
 * privateKey`, so each "keypair" here is a single distinct seed used as both
 * halves; what's exercised is the chain *logic* (link order, root binding,
 * depth bound, revocation epochs, who signed the leaf delegation). Real
 * Ed25519 rides the same seam the GR system tests exercise.
 */
class AdmissionChainVerifierTest {

    private val crypto = BootstrapSyncCryptoManager()

    private val masterKey = ByteArray(32) { (it + 1).toByte() }   // realm master (A1)
    private val keyA = ByteArray(32) { (it + 50).toByte() }       // device A's A33
    private val keyB = ByteArray(32) { (it + 100).toByte() }      // device B's A33
    private val keyC = ByteArray(32) { (it + 150).toByte() }      // device C's A33
    private val wrongKey = ByteArray(32) { (it + 200).toByte() }
    private val ownerId = "owner-realm-1"

    private fun cert(
        deviceId: String,
        subjectKey: ByteArray,
        signer: ByteArray,
        epoch: Long = 0L,
        owner: String = ownerId,
    ): AdmissionCapabilityCert {
        val sig = crypto.signEd25519(
            signer,
            AdmissionCapabilityCert.message(owner, subjectKey, deviceId, epoch),
        )
        return AdmissionCapabilityCert(owner, subjectKey, deviceId, epoch, sig)
    }

    /** A's cert is minted directly by the owner — the depth-1 chain. */
    private fun depth1Chain() = listOf(cert("device-a", keyA, signer = masterKey))

    /** Owner admits A, A admits B, B admits C — C's chain, leaf → root. */
    private fun depth3Chain() = listOf(
        cert("device-c", keyC, signer = keyB),
        cert("device-b", keyB, signer = keyA),
        cert("device-a", keyA, signer = masterKey),
    )

    private val keysByDevice = mapOf("device-a" to keyA, "device-b" to keyB, "device-c" to keyC)

    /** Names [deviceId]'s A33 key unless [key] says otherwise (F1′; required since F5b). */
    private fun revocation(
        deviceId: String,
        epoch: Long,
        signer: ByteArray = masterKey,
        key: ByteArray = keysByDevice.getValue(deviceId),
    ): AdmissionRevocation {
        val sig = crypto.signEd25519(
            signer,
            AdmissionRevocation.message(ownerId, deviceId, epoch, revokedAt = 3_000L, deviceA33PublicKey = key),
        )
        return AdmissionRevocation(ownerId, deviceId, epoch, revokedAt = 3_000L, signature = sig, deviceA33PublicKey = key)
    }

    private fun verify(
        chain: List<AdmissionCapabilityCert>,
        revocations: List<AdmissionRevocation> = emptyList(),
        maxDepth: Int = AdmissionChainVerifier.MAX_ADMIT_DEPTH,
    ) = AdmissionChainVerifier.verifyChain(
        masterKey, ownerId, chain, revocations, crypto::verifyEd25519, maxDepth,
    )

    @Test
    fun depth1OwnerMintedChain_admits() {
        assertEquals(AdmissionChainVerdict.Admit, verify(depth1Chain()))
    }

    @Test
    fun depth3Chain_admits() {
        assertEquals(AdmissionChainVerdict.Admit, verify(depth3Chain()))
    }

    @Test
    fun emptyChain_rejected() {
        assertEquals(AdmissionChainVerdict.RejectEmptyChain, verify(emptyList()))
    }

    @Test
    fun chainOverDepthBound_rejected() {
        assertEquals(AdmissionChainVerdict.RejectTooDeep, verify(depth3Chain(), maxDepth = 2))
    }

    @Test
    fun forgedIntermediateLink_rejected() {
        // B's cert claims to vouch for C but was signed by an unrelated key,
        // not by A's A33 — the chain breaks at that link.
        val chain = listOf(
            cert("device-c", keyC, signer = keyB),
            cert("device-b", keyB, signer = wrongKey),
            cert("device-a", keyA, signer = masterKey),
        )
        assertEquals(AdmissionChainVerdict.RejectBadCert, verify(chain))
    }

    @Test
    fun rootNotSignedByMaster_rejected() {
        val chain = listOf(cert("device-a", keyA, signer = wrongKey))
        assertEquals(AdmissionChainVerdict.RejectBadCert, verify(chain))
    }

    @Test
    fun certForDifferentRealm_rejected() {
        val chain = listOf(cert("device-a", keyA, signer = masterKey, owner = "other-realm"))
        assertEquals(AdmissionChainVerdict.RejectWrongOwner, verify(chain))
    }

    @Test
    fun revocationNamingTheKey_revokes_whateverItsEpoch() {
        // F5b: the epoch is unread — a revocation below the mint epoch strips just the same.
        val chain = listOf(cert("device-a", keyA, signer = masterKey, epoch = 5L))
        assertTrue(
            AdmissionChainVerifier.isRevoked(masterKey, chain[0], listOf(revocation("device-a", epoch = 5L)), crypto::verifyEd25519),
        )
        assertEquals(
            AdmissionChainVerdict.RejectRevoked,
            verify(chain, revocations = listOf(revocation("device-a", epoch = 4L))),
        )
    }

    @Test
    fun revocationNamingAnotherKey_doesNotRevoke_whateverItsEpoch() {
        val chain = listOf(cert("device-a", keyA, signer = masterKey, epoch = 5L))
        assertEquals(
            AdmissionChainVerdict.Admit,
            verify(chain, revocations = listOf(revocation("device-a", epoch = 99L, key = wrongKey))),
        )
    }

    @Test
    fun revocationSignedByNonMaster_ignored() {
        val chain = depth1Chain()
        assertFalse(
            AdmissionChainVerifier.isRevoked(masterKey, chain[0], listOf(revocation("device-a", epoch = 5L, signer = wrongKey)), crypto::verifyEd25519),
        )
        assertEquals(AdmissionChainVerdict.Admit, verify(chain, revocations = listOf(revocation("device-a", epoch = 5L, signer = wrongKey))))
    }

    @Test
    fun revokedIntermediateLink_rejectsWholeSubtreeChain() {
        // Revoking A invalidates C's chain even though C's own cert is fine —
        // the §5 subtree property falls out of per-link revocation checks.
        assertEquals(
            AdmissionChainVerdict.RejectRevoked,
            verify(depth3Chain(), revocations = listOf(revocation("device-a", epoch = 0L))),
        )
    }

    @Test
    fun delegationSignedByAdmitter_admits() {
        // C (leaf of the chain) admits new device Y: the delegation must be
        // signed by C's A33 — chain[0]'s subject.
        val delegation = "y-pub-key-bytes".encodeToByteArray()
        assertEquals(
            AdmissionChainVerdict.Admit,
            AdmissionChainVerifier.verifyAdmission(
                masterKey, ownerId, depth3Chain(),
                delegation, crypto.signEd25519(keyC, delegation),
                emptyList(), crypto::verifyEd25519,
            ),
        )
    }

    @Test
    fun delegationSignedByWrongKey_rejected() {
        val delegation = "y-pub-key-bytes".encodeToByteArray()
        // Signed by another link's key (B) — only the presenting admitter's
        // A33 (chain[0]) may sign the delegation.
        assertEquals(
            AdmissionChainVerdict.RejectBadDelegation,
            AdmissionChainVerifier.verifyAdmission(
                masterKey, ownerId, depth3Chain(),
                delegation, crypto.signEd25519(keyB, delegation),
                emptyList(), crypto::verifyEd25519,
            ),
        )
    }

    @Test
    fun verifyAdmission_propagatesChainRejection() {
        val delegation = "y-pub-key-bytes".encodeToByteArray()
        assertEquals(
            AdmissionChainVerdict.RejectRevoked,
            AdmissionChainVerifier.verifyAdmission(
                masterKey, ownerId, depth3Chain(),
                delegation, crypto.signEd25519(keyC, delegation),
                listOf(revocation("device-b", epoch = 9L)), crypto::verifyEd25519,
            ),
        )
    }

    /**
     * ⚠⚠ S1: a chain THROUGH a removed device is refused whatever its keys. F5b made revocations
     * key-scoped, and a device can blank its own `admissionPublicKey` so the revoker has no key to
     * name — the F2′ removal ledger is the key-independent strip for the sleepers it admitted.
     */
    @Test
    fun aChainThroughARemovedDevice_isRefused_evenWithNoRevocation() {
        assertEquals(
            AdmissionChainVerdict.RejectRemovedLink,
            AdmissionChainVerifier.verifyChain(
                masterKey, ownerId, depth3Chain(), emptyList(), crypto::verifyEd25519,
                isRemovedDevice = { it == "device-b" },
            ),
            "C's chain runs through removed B — no key, no revocation, still refused",
        )
        assertEquals(
            AdmissionChainVerdict.RejectRemovedLink,
            AdmissionChainVerifier.verifyAdmission(
                masterKey, ownerId, depth3Chain(),
                "y".encodeToByteArray(), crypto.signEd25519(keyC, "y".encodeToByteArray()),
                emptyList(), crypto::verifyEd25519,
                isRemovedDevice = { it == "device-c" },
            ),
            "a removed LEAF cannot admit either",
        )
        assertEquals(
            AdmissionChainVerdict.Admit,
            AdmissionChainVerifier.verifyChain(
                masterKey, ownerId, depth3Chain(), emptyList(), crypto::verifyEd25519,
                isRemovedDevice = { it == "device-unrelated" },
            ),
        )
    }
}
