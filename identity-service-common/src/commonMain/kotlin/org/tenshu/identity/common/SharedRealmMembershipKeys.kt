package org.tenshu.identity.common

import org.tenshu.core.core.TContext
import org.tenshu.core.security.SyncCryptoManager
import org.tenshu.identity.SharedRealmMembership

/**
 * The bridge between the [SharedRealmMembership] schema (SR-2) and the
 * identity-key wrap primitive (SR-1, `SyncCryptoManager.wrapKeyForIdentity` /
 * `unwrapKeyForIdentity`). It is the *consumer* that gives the membership schema
 * a producer (wrap-on-create) and a reader (unwrap-on-read), and the point where
 * the §2.3 invariant lives: a shared realm's master key (A12) reaches a member
 * **only** as ciphertext sealed to that member's A13 public key, and is recovered
 * **only** with that member's A13 private key.
 *
 * Pure crypto over the schema — no DB, no sync, no relay (those compose on top in
 * later slices). The caller supplies the realm-identity X25519 sub-key (A13) from
 * `SyncCryptoManager.deriveRealmEncryptionKeyPair`.
 */
object SharedRealmMembershipKeys {

    /**
     * Build a [SharedRealmMembership] for [sharedRealmId] by sealing the shared
     * realm's master key (A12) [sharedRealmMasterKey] to the recipient member's
     * A13 public key [recipientEncPublicKey]. The returned row carries only the
     * wrapped key — safe to persist + sync (House Rule #1).
     */
    fun create(
        context: TContext,
        crypto: SyncCryptoManager,
        sharedRealmId: String,
        sharedRealmMasterKey: ByteArray,
        recipientEncPublicKey: ByteArray,
        role: String,
    ): SharedRealmMembership = SharedRealmMembership.create(
        context = context,
        sharedRealmId = sharedRealmId,
        wrappedMasterKey = crypto.wrapKeyForIdentity(sharedRealmMasterKey, recipientEncPublicKey),
        role = role,
    )

    /**
     * Recover the shared realm's master key (A12) from [membership] using *this*
     * member's A13 private key [myEncPrivateKey]. Throws (AEAD authentication
     * failure) if the membership was sealed to a different member — recovery is
     * cryptographically bound to the recipient, not merely access-controlled.
     */
    fun resolveMasterKey(
        crypto: SyncCryptoManager,
        membership: SharedRealmMembership,
        myEncPrivateKey: ByteArray,
    ): ByteArray = crypto.unwrapKeyForIdentity(membership.wrappedMasterKey.get(), myEncPrivateKey)
}
