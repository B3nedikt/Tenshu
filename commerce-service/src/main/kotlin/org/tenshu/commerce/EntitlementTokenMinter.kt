package org.tenshu.commerce

import org.tenshu.core.common.security.EntitlementTokenClaims
import org.tenshu.core.common.security.encodeEntitlementToken

/**
 * Mints the bearer **entitlement token** the relay verifies (`commerce-billing-plane.md` §4 / D2)
 * — distinct from [EntitlementMinter], which mints the synced `Entitlement` *record*. Both are
 * signed by the same A29 issuer key; the relay holds only the public half.
 *
 * Short-lived by design (TTL from [CommerceConfig.tokenTtlHours], 72 h default): revocation is
 * mostly "stop reissuing", so no relay-side revocation list on the hot path. Stateless itself —
 * the mint authority lives in the [org.tenshu.commerce.store.CommerceStore].
 */
class EntitlementTokenMinter(
    private val config: CommerceConfig,
    private val issuerKeypair: IssuerKeypair,
) {
    /** The configured top tier. */
    val topTier: String get() = config.topTierProductId

    /** Quota (bytes) the top-tier token asserts. */
    val topTierQuotaBytes: Long get() = config.topTierQuotaBytes

    /**
     * Mint a token for [tier] / [quotaBytes], valid from [nowMs] for the configured TTL. [ownerId]
     * binds the token to a relay owner (minimal variant); pass `null` for the blinding-ready
     * unbound form (the relay's owner check simply has nothing to compare, and skips it).
     */
    fun mint(
        ownerId: String?,
        tier: String,
        quotaBytes: Long,
        nowMs: Long,
    ): String {
        val issuedAtSec = nowMs / 1000
        val expiresAtSec = issuedAtSec + config.tokenTtlHours * SECONDS_PER_HOUR
        val claims = EntitlementTokenClaims(
            ownerId = ownerId,
            tier = tier,
            quotaBytes = quotaBytes,
            issuedAtSec = issuedAtSec,
            expiresAtSec = expiresAtSec,
            keyId = config.signingKeyId,
        )
        return encodeEntitlementToken(claims) { message -> issuerKeypair.sign(message) }
    }

    private companion object {
        const val SECONDS_PER_HOUR = 60L * 60L
    }
}
