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

    /**
     * ⚠⚠ §7j.10.2, inverting `revocationNamingAnotherKey_doesNotRevoke_whateverItsEpoch`: the
     * APPROVER's refusal is id-scoped. A revocation naming device-a under ANOTHER key still refuses
     * device-a — a revocation is minted only for a removed device, so "a re-mint clears it" is the
     * escape a removed device needs. [AdmissionChainVerifier.isRevoked] itself stays key-exact.
     */
    @Test
    fun revocationNamingAnotherKeyOfTheApprover_stillRefuses() {
        val chain = listOf(cert("device-a", keyA, signer = masterKey, epoch = 5L))
        assertFalse(
            AdmissionChainVerifier.isRevoked(masterKey, chain[0], listOf(revocation("device-a", epoch = 99L, key = wrongKey)), crypto::verifyEd25519),
            "the key-exact predicate is unchanged",
        )
        assertEquals(
            AdmissionChainVerdict.RejectRevoked,
            verify(chain, revocations = listOf(revocation("device-a", epoch = 99L, key = wrongKey))),
        )
    }

    /**
     * ⚠⚠ §7j.10.2, the P4 path in the removal window: D (device-a) was removed and its key K_A
     * revoked; a second A1 holder that has not applied D's tombstone mints `[D(K')]` for a freshly
     * announced K'. Depth 1, no repeat, K' unrevoked — refused all the same, because the approver's
     * id is revoked.
     */
    @Test
    fun aFreshKeyForARevokedApprover_isStillRefused() {
        val freshKey = ByteArray(32) { (it + 220).toByte() }
        val reMinted = listOf(cert("device-a", freshKey, signer = masterKey, epoch = 6L))
        assertEquals(
            AdmissionChainVerdict.RejectRevoked,
            AdmissionChainVerifier.verifyAdmission(
                masterKey, ownerId, reMinted,
                "y".encodeToByteArray(), crypto.signEd25519(freshKey, "y".encodeToByteArray()),
                listOf(revocation("device-a", epoch = 5L)), crypto::verifyEd25519,
            ),
        )
        assertEquals(
            AdmissionChainVerdict.Admit,
            verify(reMinted, revocations = listOf(revocation("device-b", epoch = 5L))),
            "another device's revocation does not touch it",
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

    /**
     * ⚠⚠ §7j.10 (2026-11-11), inverting the per-link subtree property: revoking A — C's root-side
     * ancestor — no longer invalidates C's chain. A device is a member (it can admit) or removed
     * (it can do nothing); C was not removed, so C still admits.
     */
    @Test
    fun revokedIntermediateLink_doesNotRefuseTheApprover() {
        assertEquals(
            AdmissionChainVerdict.Admit,
            verify(depth3Chain(), revocations = listOf(revocation("device-a", epoch = 0L))),
        )
    }

    /** §7j.10 keeps the approver's OWN key revocable: C's key revoked ⇒ C cannot admit. */
    @Test
    fun revokedApproverKey_refuses() {
        assertEquals(
            AdmissionChainVerdict.RejectRevoked,
            verify(depth3Chain(), revocations = listOf(revocation("device-c", epoch = 0L))),
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
                listOf(revocation("device-c", epoch = 9L)), crypto::verifyEd25519,
            ),
        )
    }

    /**
     * ⚠⚠ §7j (2026-11-11): a chain through a removed ANCESTOR is admitted while its approver is
     * current. The S1 rule this replaces refused it, and on a realm with no A1 holder that locked
     * admission for good: every device the removed one had admitted could no longer approve. A
     * person confirming the SAS on a current member is the user's okay; sleepers are handled by the
     * removal UI listing admittees, not here.
     */
    @Test
    fun aChainThroughARemovedAncestor_isAdmitted_whenTheApproverIsCurrent() {
        assertEquals(
            AdmissionChainVerdict.Admit,
            AdmissionChainVerifier.verifyChain(
                masterKey, ownerId, depth3Chain(), emptyList(), crypto::verifyEd25519,
                isRemovedDevice = { it == "device-b" },
            ),
            "C's chain runs through removed B — C is current, so C may still admit",
        )
        assertEquals(
            AdmissionChainVerdict.Admit,
            AdmissionChainVerifier.verifyAdmission(
                masterKey, ownerId, depth3Chain(),
                "y".encodeToByteArray(), crypto.signEd25519(keyC, "y".encodeToByteArray()),
                emptyList(), crypto::verifyEd25519,
                isRemovedDevice = { it == "device-b" || it == "device-a" },
            ),
            "every ancestor removed — the approver is still current",
        )
    }

    /** §7j rule 2: the approver — the leaf that signed — must not be a removed device. */
    @Test
    fun aRemovedApprover_isRefused_evenWithNoRevocation() {
        assertEquals(
            AdmissionChainVerdict.RejectRemovedApprover,
            AdmissionChainVerifier.verifyAdmission(
                masterKey, ownerId, depth3Chain(),
                "y".encodeToByteArray(), crypto.signEd25519(keyC, "y".encodeToByteArray()),
                emptyList(), crypto::verifyEd25519,
                isRemovedDevice = { it == "device-c" },
            ),
            "a removed LEAF cannot admit",
        )
        assertEquals(
            AdmissionChainVerdict.RejectRemovedApprover,
            AdmissionChainVerifier.verifyChain(
                masterKey, ownerId, depth1Chain(), emptyList(), crypto::verifyEd25519,
                isRemovedDevice = { it == "device-a" },
            ),
            "a removed depth-1 approver cannot admit either",
        )
        assertEquals(
            AdmissionChainVerdict.Admit,
            AdmissionChainVerifier.verifyChain(
                masterKey, ownerId, depth3Chain(), emptyList(), crypto::verifyEd25519,
                isRemovedDevice = { it == "device-unrelated" },
            ),
        )
    }

    /**
     * ⚠⚠ §7j.10 (2026-11-11), inverting `aRevokedAncestor_stillRefuses_evenThoughARemovedOneDoesNot`:
     * B — C's admitter — was removed AND key-revoked (what an A1 holder's removal does). C is a
     * current member whose own key is valid, so C still admits. Refusing it left a kept device a
     * member that could not admit — the third state the maintainer ruled out.
     */
    @Test
    fun aRevokedAncestor_doesNotRefuse_whenTheApproversOwnKeyIsValid() {
        assertEquals(
            AdmissionChainVerdict.Admit,
            AdmissionChainVerifier.verifyChain(
                masterKey, ownerId, depth3Chain(), listOf(revocation("device-b", epoch = 1L)), crypto::verifyEd25519,
                isRemovedDevice = { it == "device-b" },
            ),
        )
        assertEquals(
            AdmissionChainVerdict.Admit,
            AdmissionChainVerifier.verifyAdmission(
                masterKey, ownerId, depth3Chain(),
                "y".encodeToByteArray(), crypto.signEd25519(keyC, "y".encodeToByteArray()),
                listOf(revocation("device-b", epoch = 1L), revocation("device-a", epoch = 1L)),
                crypto::verifyEd25519,
                isRemovedDevice = { it == "device-b" || it == "device-a" },
            ),
            "every ancestor removed and revoked — the approver is still a member",
        )
        assertEquals(
            AdmissionChainVerdict.RejectRevoked,
            AdmissionChainVerifier.verifyChain(
                masterKey, ownerId, depth3Chain(),
                listOf(revocation("device-b", epoch = 1L), revocation("device-c", epoch = 1L)),
                crypto::verifyEd25519,
                isRemovedDevice = { it == "device-b" },
            ),
            "the approver's own key revoked still refuses",
        )
    }

    private val keyReKey = ByteArray(32) { (it + 175).toByte() }   // the fresh key a self-re-key mints

    /** `[C(K'), C(K_C), B, A]` — C re-keying itself under its own (revoked) key K_C. */
    private fun selfReKeyChain() = listOf(cert("device-c", keyReKey, signer = keyC)) + depth3Chain()

    /**
     * ⚠⚠ §7j.10 fix round: C's own key K_C is revoked, C mints `C(K')` under K_C and presents
     * `[C(K'), C(K_C), …]`. K' is unrevoked and K_C is no longer `chain[0]` — but it names the
     * APPROVER's device id, so the revocation still applies. Refused as revoked (the specific
     * verdict pins this guard; the repeated-id guard alone would answer `RejectRepeatedDevice`).
     */
    @Test
    fun aSelfReKeyThroughTheApproversOwnRevokedKey_isRefusedAsRevoked() {
        val revoked = listOf(revocation("device-c", epoch = 1L))
        assertEquals(
            AdmissionChainVerdict.RejectRevoked,
            AdmissionChainVerifier.verifyChain(masterKey, ownerId, selfReKeyChain(), revoked, crypto::verifyEd25519),
        )
        assertEquals(
            AdmissionChainVerdict.RejectRevoked,
            AdmissionChainVerifier.verifyAdmission(
                masterKey, ownerId, selfReKeyChain(),
                "y".encodeToByteArray(), crypto.signEd25519(keyReKey, "y".encodeToByteArray()),
                revoked, crypto::verifyEd25519,
            ),
        )
    }

    /** §7j.10 fix round: a device id on two links is refused whatever the revocation list says. */
    @Test
    fun aChainThatNamesADeviceTwice_isRefused_evenWithNoRevocation() {
        assertEquals(
            AdmissionChainVerdict.RejectRepeatedDevice,
            verify(selfReKeyChain()),
            "the approver repeated at the leaf",
        )
        val keyB2 = ByteArray(32) { (it + 190).toByte() }
        val repeatedInTheMiddle = listOf(
            cert("device-c", keyC, signer = keyB2),
            cert("device-b", keyB2, signer = keyB),
            cert("device-b", keyB, signer = keyA),
            cert("device-a", keyA, signer = masterKey),
        )
        assertEquals(AdmissionChainVerdict.RejectRepeatedDevice, verify(repeatedInTheMiddle), "an ancestor repeated")
    }
}
