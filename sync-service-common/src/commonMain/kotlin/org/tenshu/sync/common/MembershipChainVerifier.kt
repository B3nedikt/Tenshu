package org.tenshu.sync.common

/**
 * Verifies an Ed25519 signature — the minimal seam [MembershipChainVerifier]
 * needs so both sides verify the chain with byte-identical messages but their
 * own crypto holder:
 *
 * - the **client** (orchestration) passes `syncCryptoManager::verifyEd25519`,
 * - the **relay** passes its `Ed25519Backend::verify`.
 *
 * Both have the identical `(publicKey, message, signature) -> Boolean` shape, so
 * each is a SAM-convertible method reference; the verifier never depends on a
 * whole `SyncCryptoManager` (unavailable relay-side) or on `Ed25519Backend` (a
 * core-common type the client layer would rather not reach for).
 */
fun interface Ed25519SignatureVerifier {
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean
}

/**
 * Verifies the Q30 membership capability chain
 * (`docs/proposals/shared-realm-membership-grant.md` §4, D1).
 *
 * The relay (GR-2) and any client checking a grant before acting on it run the
 * same two-link verification against the `realm → masterPublicKey` binding the
 * relay already stores from realm genesis:
 *
 * 1. the [MembershipCapability] is signed by the realm master key, and
 * 2. the [MemberDeviceBinding] is signed by the **member authority** key the
 *    capability vouches for, for the same realm,
 * 3. and no valid [MembershipRevocation] supersedes the capability.
 *
 * Stateless — all inputs (master key, the revocation list, the [verify]
 * function) are supplied by the caller, exactly as `MasterDelegationVerifier`
 * takes the master key + backend per call.
 */
object MembershipChainVerifier {

    /** The capability is validly signed by [masterPublicKey] (the realm's A1). */
    fun verifyCapability(
        masterPublicKey: ByteArray,
        capability: MembershipCapability,
        verify: Ed25519SignatureVerifier,
    ): Boolean =
        verify.verify(masterPublicKey, capability.signedMessage(), capability.signature)

    /**
     * The device binding is validly signed by the member authority the
     * [capability] vouches for, and is for the same realm. Does **not** check
     * the capability itself — compose via [verifyAdmission].
     */
    fun verifyDeviceBinding(
        capability: MembershipCapability,
        binding: MemberDeviceBinding,
        verify: Ed25519SignatureVerifier,
    ): Boolean =
        binding.realmId == capability.realmId &&
            verify.verify(
                capability.memberAuthorityPublicKey,
                binding.signedMessage(),
                binding.signature,
            )

    /**
     * True if some validly-master-signed revocation for the same realm **names this capability's
     * member-authority key**. A revocation signed by anything other than the realm master is
     * ignored — it cannot evict a member.
     *
     * ## Key-scoped, not epoch-ordered (F4 — the membership mirror of F1′)
     *
     * The predicate is an exact match on [MembershipCapability.memberAuthorityPublicKey]. It does
     * **not** compare epochs, and the reason it can afford not to is structural: `grant()` mints a
     * **fresh A26 per grant**, so re-sharing with the same person produces a key no prior
     * revocation names. Re-admission therefore needs no epoch to outrun a revocation, which is the
     * only job the comparison ever had.
     *
     * ⚠⚠ The removed `revocation.epoch >= capability.epoch` conjunct was **inert, not protective**:
     * both sides are pinned to `SharedRealmGrantManager.CAP_EPOCH`/`REVOKE_EPOCH` = 0, so it
     * evaluated `0 >= 0` — always true — on every real call. Leaving an always-true conjunct beside
     * a load-bearing key match is a trap in two directions: it reads as a second line of defence
     * that is not there, and it invites someone to "finish" it by wiring in a live realm epoch,
     * which is precisely how §7b.1 turned a lost counter into a permanent 403 on the admission
     * path. Pinned by `MembershipChainVerifierTest.theEpochDoesNotAffectTheVerdict`.
     *
     * ⚠ Like [AdmissionChainVerifier.isRevoked] since F5b, there is no epoch **fallback** branch:
     * [MembershipRevocation] has always carried a non-null `memberAuthorityPublicKey`.
     */
    fun isRevoked(
        masterPublicKey: ByteArray,
        capability: MembershipCapability,
        revocations: List<MembershipRevocation>,
        verify: Ed25519SignatureVerifier,
    ): Boolean =
        revocations.any { revocation ->
            revocation.realmId == capability.realmId &&
                revocation.memberAuthorityPublicKey.contentEquals(capability.memberAuthorityPublicKey) &&
                verify.verify(masterPublicKey, revocation.signedMessage(), revocation.signature)
        }

    /**
     * Full admission decision for one device presenting [capability] + [binding]
     * against [revocations]. The relay registers the device only on [Admit].
     */
    fun verifyAdmission(
        masterPublicKey: ByteArray,
        capability: MembershipCapability,
        binding: MemberDeviceBinding,
        revocations: List<MembershipRevocation>,
        verify: Ed25519SignatureVerifier,
    ): MembershipAdmission = when {
        !verifyCapability(masterPublicKey, capability, verify) -> MembershipAdmission.RejectBadCapability
        !verifyDeviceBinding(capability, binding, verify) -> MembershipAdmission.RejectBadBinding
        isRevoked(masterPublicKey, capability, revocations, verify) -> MembershipAdmission.RejectRevoked
        else -> MembershipAdmission.Admit
    }
}

/** Outcome of [MembershipChainVerifier.verifyAdmission]. */
enum class MembershipAdmission {
    /** Chain valid and not revoked — register the device. */
    Admit,

    /** Capability not signed by the realm master — reject. */
    RejectBadCapability,

    /** Device binding not signed by the vouched member authority (or wrong realm) — reject. */
    RejectBadBinding,

    /** A master-signed revocation supersedes the capability — reject. */
    RejectRevoked,
}
