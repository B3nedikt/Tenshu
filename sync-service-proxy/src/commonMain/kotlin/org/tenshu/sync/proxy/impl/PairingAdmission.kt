package org.tenshu.sync.proxy.impl

import org.tenshu.core.GenericError
import org.tenshu.core.TenshuException
import org.tenshu.core.core.TContext
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.sync.common.AdmissionCapabilityCert
import org.tenshu.sync.common.SyncManager
import org.tenshu.sync.common.deviceIdMatchesKey
import org.tenshu.sync.common.encodeAdmissionCertChain
import org.tenshu.sync.common.pairingDelegationMessage
import org.tenshu.sync.proxy.PairingKeyBlob

/**
 * The device-admission core shared by both pairing directions
 * (`delegated-device-admission-authority.md` §4.3): given a shared ECDH secret and the new
 * device's Ed25519 signing key + id, produce (1) the encrypted key blob the new device
 * installs, (2) a master/A33 delegation over the new device's signing key the relay verifies
 * to register it, and (3) the newly-minted admission capability so the new device itself
 * becomes an admitter. Extracted from `ApprovePairingRequestImplementation` so the offer flow
 * (`ApprovePairingOfferImplementation`) reuses this security-critical logic verbatim rather
 * than duplicating it — the join-flow tests (`ApprovePairingRequestImplementationTest`,
 * `DelegatedAdmissionApprovalTest`) cover it for both callers.
 */
internal class PairingAdmission(
    private val syncManager: SyncManager,
    private val cryptoManager: SyncCryptoManager,
) {
    /**
     * @property encryptedMasterKey the pairing key blob (`PairingKeyBlob.pack`) sealed under
     *   [sharedSecret] — the join-flow `encryptedMasterKey` / offer-flow `payload`.
     * @property masterDelegation Ed25519 signature over `signingKey || deviceId || epoch`,
     *   by A1 (owner) or the admitter's A33 (delegated).
     * @property presentedChain the A33 admitter's capability chain bytes when the delegation
     *   was A33-signed; `null` for an A1-signed (owner-shape) admission.
     * @property epoch the realm epoch stamped into the delegation + the new device's row.
     * @property admittedByDeviceId lineage stamp for the new device's `Device` entity —
     *   this device's id when it admitted via A33, `""` for an A1-rooted admission.
     */
    class Admitted(
        val encryptedMasterKey: ByteArray,
        val masterDelegation: ByteArray,
        val presentedChain: ByteArray?,
        val epoch: Long,
        val admittedByDeviceId: String,
        /**
         * The resolved master keypair when this device admitted via A1 (owner / phrase-holder),
         * `null` when it admitted via an A33 delegation. The join flow uses it for its
         * relay-forgot-the-realm self-heal (only an A1-holder can re-bootstrap genesis).
         */
        val masterKeyPair: SyncCryptoManager.KeyPair?,
        /**
         * Public half of the A33 capability this admission **minted for the new device**
         * (F0 / A′, `admission-revocation-names-the-capability.md` §7b.3).
         *
         * ⭐ The admitter generates that keypair here, so the public half is free at exactly the
         * moment it is guaranteed to exist. Every other source is unreliable: R1 measured the
         * revoker failing to resolve the target's own `AdmissionKeyAnnouncement`
         * (`a33KeyResolvable=false`), which is what makes a key-scoped revocation impossible to
         * author later. Stamped onto the new device's `Device` row by the caller.
         */
        val admissionPublicKey: ByteArray,
    ) {
        /**
         * This material without [masterKeyPair] — what may outlive the call (the join flow caches
         * it per pin, `PairingContext.admissionFor`). Everything left is sealed or public.
         */
        fun withoutMasterKeyPair(): Admitted = Admitted(
            encryptedMasterKey = encryptedMasterKey,
            masterDelegation = masterDelegation,
            presentedChain = presentedChain,
            epoch = epoch,
            admittedByDeviceId = admittedByDeviceId,
            masterKeyPair = null,
            admissionPublicKey = admissionPublicKey,
        )
    }

    /** In-memory A1, else phrase-derived A1, else `null` (§9 fallback's first two rungs). */
    fun resolveMasterKeyPair(): SyncCryptoManager.KeyPair? =
        syncManager.currentMasterKeyPair
            ?: syncManager.getRecoveryPhrase()?.let { cryptoManager.deriveMasterKeyPair(it) }

    /**
     * Build the admission material for [newDeviceSigningPublicKey]/[newDeviceId] using
     * [sharedSecret] to seal the blob. Throws [TenshuException] (the P0 guard) if this device
     * holds no admission authority (neither the master key nor an A33 capability) — a pre-P3
     * joiner structurally cannot admit.
     */
    fun admit(
        context: TContext,
        sharedSecret: ByteArray,
        newDeviceSigningPublicKey: ByteArray,
        newDeviceId: String,
    ): Admitted {
        val ownerId = syncManager.currentOwnerId ?: throw TenshuException(
            GenericError.create(context, syncManager.pairingAuthorityFailure())
        )
        val keys = syncManager.getDerivedKeys() ?: throw TenshuException(
            GenericError.create(context, "SyncManager not initialized with keys")
        )

        // Admission authority (§4.3 / §9 fallback): in-memory A1 → phrase-derived A1 →
        // this device's delegated A33 capability. None of the three ⇒ can't admit (P0 guard).
        val masterKeyPair = resolveMasterKeyPair()
        val ownAdmissionKeyPair = syncManager.getAdmissionKeyPair()
        val ownAdmissionChain = syncManager.getAdmissionCertChain()
        if (masterKeyPair == null && (ownAdmissionKeyPair == null || ownAdmissionChain.isEmpty())) {
            throw TenshuException(
                GenericError.create(
                    context,
                    "This device can't add new devices — it wasn't set up with your recovery " +
                        "phrase. Add new devices from a device that has the recovery phrase, or " +
                        "set up account recovery on this device first.",
                )
            )
        }

        // ⚠⚠ Workstream B: the id is a function of the key, and nothing here signs over a pair that
        // is not. Both values reach this device from outside — the joiner's `PairingRequest` via the
        // relay, the offerer's QR — and the A1/A33 signature below would otherwise vouch for whatever
        // id they named. The relay refuses the pair too; this keeps the admitter's key off it.
        if (!deviceIdMatchesKey(newDeviceId, newDeviceSigningPublicKey)) {
            throw TenshuException(
                GenericError.create(
                    context,
                    "The new device's id does not match its signing key, so it can't be added. " +
                        "Update Tenshu on both devices and start pairing again.",
                )
            )
        }
        val epoch = syncManager.currentRealmEpoch
        val delegationMessage = pairingDelegationMessage(newDeviceSigningPublicKey, newDeviceId, epoch)
        val delegationSigner = masterKeyPair?.privateKey ?: ownAdmissionKeyPair!!.privateKey
        val masterDelegation = cryptoManager.signEd25519(delegationSigner, delegationMessage)
        val presentedChain: ByteArray? =
            if (masterKeyPair == null) encodeAdmissionCertChain(ownAdmissionChain) else null

        // Mint the new device its own A33 capability (CHAINED — every admitted device
        // becomes an admitter). A1-holder mints a depth-1 chain; a delegated admitter
        // prepends to its own chain.
        val childAdmissionKeyPair = cryptoManager.generateAdmissionKeyPair()
        val childCertMessage = AdmissionCapabilityCert.message(
            ownerId = ownerId,
            deviceA33PublicKey = childAdmissionKeyPair.publicKey,
            deviceId = newDeviceId,
            mintedAtEpoch = epoch,
        )
        val childCert = AdmissionCapabilityCert(
            ownerId = ownerId,
            deviceA33PublicKey = childAdmissionKeyPair.publicKey,
            deviceId = newDeviceId,
            mintedAtEpoch = epoch,
            signature = cryptoManager.signEd25519(
                privateKey = masterKeyPair?.privateKey ?: ownAdmissionKeyPair!!.privateKey,
                message = childCertMessage,
            ),
        )
        val childChain = listOf(childCert) + if (masterKeyPair == null) ownAdmissionChain else emptyList()

        val blob = PairingKeyBlob.pack(
            keys,
            syncManager.getRealmEncryptionKeyPair(),
            childAdmissionKeyPair,
            childChain,
        )
        return Admitted(
            encryptedMasterKey = cryptoManager.encrypt(blob, sharedSecret),
            masterDelegation = masterDelegation,
            presentedChain = presentedChain,
            epoch = epoch,
            admissionPublicKey = childAdmissionKeyPair.publicKey,
            admittedByDeviceId = if (masterKeyPair == null) syncManager.currentDeviceId else "",
            masterKeyPair = masterKeyPair,
        )
    }
}
