package org.tenshu.commerce

import org.tenshu.identity.EntitlementSignature

/**
 * The domain core: turns an `(ownerId, productId, now)` into a signed top-tier entitlement.
 *
 * The signed bytes come from [EntitlementSignature.message] **verbatim** — the same function the
 * on-device verify-on-merge path uses — so a service-minted record is accepted unchanged by the
 * existing verifier (the whole point of Phase 1: lift minting off the in-process `tenshu.dev`
 * mock without touching the §10.1 convergence/gating path).
 *
 * Stateless itself — the mint *authority* (which account may mint, at what tier) lives in the
 * [org.tenshu.commerce.store.CommerceStore]; this just signs once that gate has passed.
 */
class EntitlementMinter(
    private val config: CommerceConfig,
    private val issuerKeypair: IssuerKeypair,
) {

    /** The configured top-tier product id, minted when a request omits `productId`. */
    val topTierProductId: String get() = config.topTierProductId

    /**
     * Mint a signed entitlement for [ownerId] / [productId], valid from [nowMs] for the
     * configured TTL. Returns the six signed fields decoupled from any `Data` representation.
     */
    fun mint(
        ownerId: String,
        productId: String,
        nowMs: Long,
    ): EntitlementSignature.SignedEntitlement {
        val validFrom = nowMs
        val validUntil = nowMs + config.entitlementTtlHours * MS_PER_HOUR
        val bytes = EntitlementSignature.message(
            ownerId = ownerId,
            publisherId = config.issuerId,
            productId = productId,
            validFrom = validFrom,
            validUntil = validUntil,
        )
        return EntitlementSignature.SignedEntitlement(
            ownerId = ownerId,
            publisherId = config.issuerId,
            productId = productId,
            validFrom = validFrom,
            validUntil = validUntil,
            signature = issuerKeypair.sign(bytes),
        )
    }

    private companion object {
        const val MS_PER_HOUR = 60L * 60L * 1_000L
    }
}
