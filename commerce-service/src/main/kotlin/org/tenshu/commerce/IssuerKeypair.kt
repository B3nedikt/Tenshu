package org.tenshu.commerce

import org.tenshu.core.common.security.Ed25519Backend

/**
 * The commerce issuer's A29 signing key. Loads the 32-byte seed from [CommerceConfig.issuerSeed]
 * and derives the public half via [Ed25519Backend.derivePublicKey]. The seed lives **only** in
 * this process; only the public half is pinned (in `EntitlementSignature.COMMERCE_PUBLIC_KEY`)
 * so on-device verify-on-merge can check service-minted records.
 *
 * v1 = env-supplied seed. KMS/HSM custody + key rotation are the deferred open item — kept behind
 * this one type so the swap is local.
 */
// TODO(A29): KMS + rotation — back the seed with a KMS/HSM and support a key-id'd rotation set
//  rather than a single env-supplied seed (commerce-service-phase1-plan.md §9 open item).
class IssuerKeypair(
    private val seed: ByteArray,
    private val ed25519: Ed25519Backend,
) {
    /** Public half of [seed], derived once at construction. */
    val publicKey: ByteArray = ed25519.derivePublicKey(seed)

    /** Sign [message] with the issuer seed. */
    fun sign(message: ByteArray): ByteArray = ed25519.sign(seed, message)
}
