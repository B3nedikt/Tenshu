package org.tenshu.core.common.security

/**
 * Offline verifier for an [EntitlementTokenClaims] credential — holds the issuer's public key(s)
 * and validates signature + expiry against them, returning the tier/quota. This is the relay-side
 * "one more credential" check (`commerce-billing-plane.md` D2): no callback to commerce on the hot
 * path, verifier-only, stateless.
 *
 * **Rotation-ready.** [publicKeysByKeyId] maps a token's `keyId` to the issuer public key that
 * signed it; the `null` key is the issuer's default (unkeyed) key. During an A29 signing-key
 * rollover the relay holds current + previous keys here.
 *
 * **Blinding-ready (D3).** This verifier never inspects [EntitlementTokenClaims.ownerId]. The
 * minimal-variant owner-binding check ("does the token's `ownerId` match the request's?") is a
 * deliberate, separate caller step — keeping it out of the verifier means the strong (blinded)
 * variant, whose tokens carry no `ownerId`, verifies through the identical path.
 */
class EntitlementTokenVerifier(
    private val publicKeysByKeyId: Map<String?, ByteArray>,
    private val ed25519: Ed25519Backend,
    private val nowSec: () -> Long,
) {
    sealed class Result {
        /** Signature + expiry valid; [claims] carries the tier/quota the relay would enforce. */
        data class Valid(val claims: EntitlementTokenClaims) : Result()

        /** Verification failed; [failure] is the typed reason (never echoed to clients verbatim). */
        data class Invalid(val failure: EntitlementTokenFailure) : Result()
    }

    fun verify(token: String): Result {
        var unknownKeyId: String? = null
        var sawUnknownKey = false
        return try {
            val claims = decodeEntitlementToken(token, nowSec()) { keyId, message, signature ->
                val key = publicKeysByKeyId[keyId]
                if (key == null) {
                    sawUnknownKey = true
                    unknownKeyId = keyId
                    false
                } else {
                    ed25519.verify(key, message, signature)
                }
            }
            Result.Valid(claims)
        } catch (failure: EntitlementTokenFailure) {
            // A signature failure that was actually "no configured key for this keyId" is reported
            // as UnknownKey so an operator can tell a rotation/config gap from a real forgery.
            if (failure is EntitlementTokenFailure.SignatureMismatch && sawUnknownKey) {
                Result.Invalid(EntitlementTokenFailure.UnknownKey(unknownKeyId))
            } else {
                Result.Invalid(failure)
            }
        }
    }
}
