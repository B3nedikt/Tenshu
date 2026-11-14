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
     * Default depth bound (proposal §7 Q2): caps verification cost.
     * Deliberately generous — an owner
     * re-root (P4) flattens live chains back to depth 1, so real chains only
     * approach this if a realm grows device-by-device with no A1-holder ever
     * present.
     */
    const val MAX_ADMIT_DEPTH = 16

    /**
     * The chain is well-formed for [ownerId]'s realm: within [maxDepth], every
     * cert names that realm, each link is signed by its parent's A33 key, the
     * root is signed by [masterPublicKey], no device id appears twice, and the **approver**
     * — `chain[0]`, the device exercising the authority — is neither revoked (any revocation
     * naming its id, §7j.10.2) nor a device the owner has **removed**.
     *
     * ⚠⚠ Both the revocation list and [isRemovedDevice] are consulted for `chain[0]` ONLY,
     * never for an ancestor link (`admission-revocation-names-the-capability.md` §7j, §7j.10).
     * A device is either a member — it can admit — or removed — it can do nothing; there is no
     * "member that cannot admit" state. §7j retired S1's removed-ancestor rule, which locked a
     * realm with no A1 holder out of admission for good; §7j.10 retired the matching
     * revoked-ancestor rule, which did the same thing in a realm that HAS an A1 holder (removing
     * a device revokes its key, and every chain through it then failed). The threat is a
     * **silent** rejoin; an approval a person confirms by SAS on a current member is the user's
     * okay. Sleepers are handled where the user can see them: removing a device lists the
     * devices it admitted, each removable by id. A removed *new* device is refused by the
     * caller (F2′), a removed or revoked *approver* here, and the relay additionally binds
     * `chain[0]` to the authenticated, registered poster (§7j.9). The relay passes
     * `knownDevices::wasRemoved`; a client without the ledger passes nothing.
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

        // §7j.10: the APPROVER only — never another device's link (that is the revoked-ancestor
        // lock-out, pinned by `AdmissionChainVerifierTest.aRevokedAncestor_doesNotRefuse_whenTheApproversOwnKeyIsValid`).
        // ⚠⚠ ID-scoped for the approver, not key-scoped (§7j.10.2): any master-signed revocation
        // naming the approver's device id refuses, whatever key it names. Under §7j.10 a revocation
        // is only ever minted for a REMOVED device, so a revoked id is a removed id whose tombstone
        // has not landed yet — and a key-scoped check let it escape under a fresh key: a self-re-key
        // `[D(K'), D(K_D), …]` (§7j.10.1, pinned by
        // `AdmissionChainVerifierTest.aSelfReKeyThroughTheApproversOwnRevokedKey_isRefusedAsRevoked`),
        // or a depth-1 `[D(K')]` an A1 holder that has not applied D's tombstone minted by P4 for an
        // announced K' (pinned by `AdmissionChainVerifierTest.aFreshKeyForARevokedApprover_isStillRefused`).
        isApproverRevoked(masterPublicKey, ownerId, chain.first().deviceId, revocations, verify) ->
            AdmissionChainVerdict.RejectRevoked

        // §7j.10 fix round: no honest chain names a device twice (`PairingAdmission.admit` prepends a
        // cert for a NEW, key-derived id that F2′ keeps out of the realm's past; a P4 grant is depth
        // 1), so a repeat is a forged re-key — refused whatever the revocation list says. Pinned by
        // `AdmissionChainVerifierTest.aChainThatNamesADeviceTwice_isRefused_evenWithNoRevocation`.
        chain.map { it.deviceId }.toSet().size != chain.size -> AdmissionChainVerdict.RejectRepeatedDevice

        // §7j: the approver only. Applying this to `chain.drop(1)` is the S1 lock-out — pinned by
        // `AdmissionChainVerifierTest.aChainThroughARemovedAncestor_isAdmitted_whenTheApproverIsCurrent`.
        isRemovedDevice(chain.first().deviceId) -> AdmissionChainVerdict.RejectRemovedApprover

        else -> AdmissionChainVerdict.Admit
    }

    /**
     * True if a validly-master-signed revocation names [approverDeviceId] at all — whatever key it
     * names (§7j.10.2). ⚠ A keyless revocation still counts for nothing (F5b: the relay refuses to
     * store one, and an empty key never named a capability). [isRevoked] stays the key-exact
     * predicate; this is the approver's.
     */
    fun isApproverRevoked(
        masterPublicKey: ByteArray,
        ownerId: String,
        approverDeviceId: String,
        revocations: List<AdmissionRevocation>,
        verify: Ed25519SignatureVerifier,
    ): Boolean =
        revocations.any { revocation ->
            revocation.ownerId == ownerId &&
                revocation.deviceId == approverDeviceId &&
                revocation.deviceA33PublicKey.isNotEmpty() &&
                verify.verify(masterPublicKey, revocation.signedMessage(), revocation.signature)
        }

    /**
     * True if some validly-master-signed revocation strips [cert]. A revocation signed by anything
     * other than the realm master is ignored — it cannot strip an admitter.
     *
     * ## One rule: the revocation names the capability key (F1′, F5b)
     *
     * A revocation strips a cert iff both name the same device **and the same A33 key**, matched
     * exactly. No epoch comparison, so a **re-mint clears the revocation for free** — the re-mint
     * issues a new key that the old revocation does not name (`admission-revocation-names-the-capability.md`
     * §7b.2). ⚠⚠ [verifyChain] no longer uses it: since §7j.10.2 the approver's refusal is id-scoped
     * ([isApproverRevoked]) — a revocation is minted only for a removed device, so "a re-mint clears it"
     * would be exactly the escape a removed device needs.
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

    /**
     * A master-signed revocation names the approver's (`chain[0]`'s) device id — any key (§7j.10.2).
     * ⚠ Not another device's (§7j.10).
     */
    RejectRevoked,

    /**
     * The approver (`chain[0]`) is a device the owner removed (the relay's F2′ ledger) —
     * key-independent. ⚠ An *ancestor* link's removal is not a refusal (§7j).
     */
    RejectRemovedApprover,

    /**
     * Some device id appears on more than one link — a self-re-key (§7j.10 fix round). No honest
     * client builds one.
     */
    RejectRepeatedDevice,

    /** Chain valid, but the new-device delegation isn't signed by the admitting device's A33 key. */
    RejectBadDelegation,
}
