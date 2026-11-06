package org.tenshu.sync.common

/**
 * Verifies an N-link device-admission capability chain against the realm's
 * master public key (`docs/proposals/delegated-device-admission-authority.md`
 * §4.4) — the generalisation of the shipped 2-link [MembershipChainVerifier]
 * to arbitrary depth, over the same [Ed25519SignatureVerifier] seam so client
 * and relay verify byte-identical messages with their own crypto holder.
 *
 * The chain is ordered **leaf → root**: `chain[0]` is the cert of the device
 * exercising the authority (the *admitting* device when a leaf delegation is
 * checked), each `chain[i]` is signed by `chain[i+1]`'s A33 key, and the last
 * cert is signed by the realm master key (A1). An owner-minted cert therefore
 * verifies as a depth-1 chain; joiner-minted certs accrue depth (§4.3).
 *
 * Stateless — the master key, revocation list, depth bound, and [verify]
 * function are supplied per call, exactly like [MembershipChainVerifier].
 */
object AdmissionChainVerifier {

    /**
     * Default depth bound (proposal §7 Q2): caps verification cost and the
     * revocation-subtree blast radius. Deliberately generous — an owner
     * re-root (P4) flattens live chains back to depth 1, so real chains only
     * approach this if a realm grows device-by-device with no A1-holder ever
     * present.
     */
    const val MAX_ADMIT_DEPTH = 16

    /**
     * The chain is well-formed for [ownerId]'s realm: within [maxDepth], every
     * cert names that realm, each link is signed by its parent's A33 key, the
     * root is signed by [masterPublicKey], no link is revoked, and no link names a
     * device the owner has **removed**.
     *
     * ⚠⚠ [isRemovedDevice] is the key-independent strip (S1, 2026-10-28). F5b made every
     * revocation name a key, and the revoker learns keys from synced rows a device can write
     * about itself — so a malicious device can blank its own `admissionPublicKey` and become
     * unstrippable by key. Its removal still lands in the relay's F2′ ledger, which a removed id
     * never leaves, so refusing any chain *through* a removed device kills every "sleeper" it
     * admitted without needing its key. The relay passes `knownDevices::wasRemoved`; a client
     * without the ledger passes nothing.
     */
    fun verifyChain(
        masterPublicKey: ByteArray,
        ownerId: String,
        chain: List<AdmissionCapabilityCert>,
        revocations: List<AdmissionRevocation>,
        verify: Ed25519SignatureVerifier,
        maxDepth: Int = MAX_ADMIT_DEPTH,
        isRemovedDevice: (deviceId: String) -> Boolean = { false },
    ): AdmissionChainVerdict = when {
        chain.isEmpty() -> AdmissionChainVerdict.RejectEmptyChain
        chain.size > maxDepth -> AdmissionChainVerdict.RejectTooDeep
        chain.any { it.ownerId != ownerId } -> AdmissionChainVerdict.RejectWrongOwner
        !chain.withIndex().all { (i, cert) ->
            val parentKey = chain.getOrNull(i + 1)?.deviceA33PublicKey ?: masterPublicKey
            verify.verify(parentKey, cert.signedMessage(), cert.signature)
        } -> AdmissionChainVerdict.RejectBadCert

        chain.any { isRevoked(masterPublicKey, it, revocations, verify) } ->
            AdmissionChainVerdict.RejectRevoked

        chain.any { isRemovedDevice(it.deviceId) } -> AdmissionChainVerdict.RejectRemovedLink

        else -> AdmissionChainVerdict.Admit
    }

    /**
     * True if some validly-master-signed revocation strips [cert]. A revocation signed by anything
     * other than the realm master is ignored — it cannot strip an admitter.
     *
     * ## One rule: the revocation names the capability key (F1′, F5b)
     *
     * A revocation strips a cert iff both name the same device **and the same A33 key**, matched
     * exactly. No epoch comparison, so a **re-mint clears the revocation for free** — the re-mint
     * issues a new key that the old revocation does not name. That keeps Q3 conservative subtree
     * revocation recoverable (`admission-revocation-names-the-capability.md` §7b.2).
     *
     * ⚠⚠ F5b retired the epoch fallback (§7g): a revocation always carries a key now
     * ([AdmissionRevocation.deviceA33PublicKey] is required), so there is no "either side lacks a
     * key" case left to fall back on. A cert with an **empty** key matches nothing — and could not
     * have signed anything either, since an empty key verifies no signature. Do not reintroduce an
     * epoch branch here: §7g.2 measured that both ways of handling a keyless revocation are wrong,
     * one failing open and the other closed.
     */
    fun isRevoked(
        masterPublicKey: ByteArray,
        cert: AdmissionCapabilityCert,
        revocations: List<AdmissionRevocation>,
        verify: Ed25519SignatureVerifier,
    ): Boolean =
        revocations.any { revocation ->
            revocation.ownerId == cert.ownerId &&
                strips(revocation, cert) &&
                verify.verify(masterPublicKey, revocation.signedMessage(), revocation.signature)
        }

    // ⚠⚠ The `deviceId` conjunct is LOAD-BEARING, not redundant (S2). The revoker reads keys from
    // `Device` rows and `AdmissionKeyAnnouncement`s, which a device writes about itself — so a
    // malicious device can put a VICTIM's A33 key into its own row. Its revocation then names the
    // victim's key, and only the deviceId match stops it stripping the victim's cert. Pinned by
    // `KeyScopedRevocationTest.aRevocationNamingAnotherDevicesKeyDoesNotStripIt`.
    private fun strips(
        revocation: AdmissionRevocation,
        cert: AdmissionCapabilityCert,
    ): Boolean =
        revocation.deviceId == cert.deviceId &&
            revocation.deviceA33PublicKey.isNotEmpty() &&
            revocation.deviceA33PublicKey.contentEquals(cert.deviceA33PublicKey)

    /**
     * Full admission decision for a new-device delegation signed by a
     * *delegated* admitter: the admitter's [chain] must verify
     * ([verifyChain]) and [delegationSignature] over [delegationMessage]
     * (`MasterDelegationVerifier.pairingResponseMessage` bytes) must verify
     * against `chain[0]`'s A33 key — the admitting device's, per §4.4. The
     * relay registers the new device only on [AdmissionChainVerdict.Admit].
     *
     * An A1-holder's admission doesn't come through here — it signs the
     * delegation with the master key directly, verified as today.
     */
    fun verifyAdmission(
        masterPublicKey: ByteArray,
        ownerId: String,
        chain: List<AdmissionCapabilityCert>,
        delegationMessage: ByteArray,
        delegationSignature: ByteArray,
        revocations: List<AdmissionRevocation>,
        verify: Ed25519SignatureVerifier,
        maxDepth: Int = MAX_ADMIT_DEPTH,
        isRemovedDevice: (deviceId: String) -> Boolean = { false },
    ): AdmissionChainVerdict {
        val chainVerdict = verifyChain(masterPublicKey, ownerId, chain, revocations, verify, maxDepth, isRemovedDevice)
        if (chainVerdict != AdmissionChainVerdict.Admit) return chainVerdict
        val admitterKey = chain.first().deviceA33PublicKey
        return if (verify.verify(admitterKey, delegationMessage, delegationSignature)) {
            AdmissionChainVerdict.Admit
        } else {
            AdmissionChainVerdict.RejectBadDelegation
        }
    }
}

/** Outcome of [AdmissionChainVerifier] checks. */
enum class AdmissionChainVerdict {
    /** Chain (and delegation, where checked) valid and unrevoked. */
    Admit,

    /** No certs presented — nothing to root the authority in. */
    RejectEmptyChain,

    /** Chain longer than the depth bound — reject unverified. */
    RejectTooDeep,

    /** Some cert names a different realm's ownerId. */
    RejectWrongOwner,

    /** Some link's signature doesn't verify against its parent (incl. a root not signed by A1). */
    RejectBadCert,

    /** A master-signed revocation supersedes a link in the chain. */
    RejectRevoked,

    /** A link names a device the owner removed (the relay's F2′ ledger) — key-independent (S1). */
    RejectRemovedLink,

    /** Chain valid, but the new-device delegation isn't signed by the admitting device's A33 key. */
    RejectBadDelegation,
}
