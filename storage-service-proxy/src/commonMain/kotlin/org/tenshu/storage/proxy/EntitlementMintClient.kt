package org.tenshu.storage.proxy

import org.tenshu.identity.EntitlementSignature

/**
 * Source of a **signed** [org.tenshu.identity.Entitlement] for the owner — the seam that lets
 * the entitlement writer obtain a record without holding any signing key on-device
 * (`publishing-commerce-reconciliation.md` §10.3 D5: minting moves off-device). The writer calls
 * [mint] when an account binds (Control) or on realm genesis (web) and writes the returned signed
 * fields into the realm; peers verify on merge against the publisher key
 * (`EntitlementSignature.publicKeysFor`).
 *
 * Returns `null` when no entitlement can be minted (not whitelisted, no email to gate on, service
 * unreachable). A `null` is a deliberate **no-op**, never a delete — matching the writer's
 * "a device that never subscribed must not delete another device's entitlement" rule.
 *
 * Relocated out of `:control-shared` in web-storage-status.md Bucket 3 so the apex
 * (`:tenshu-sdk-js`) can mint a default-free entitlement on web-genesis. The off-device
 * `CommerceEntitlementMintClient` (ktor / commerce-specific) stays in `:control-shared`.
 */
interface EntitlementMintClient {
    suspend fun mint(
        ownerId: String,
        email: String?,
        productId: String,
    ): EntitlementSignature.SignedEntitlement?
}

/**
 * Dev/test minter: signs the canonical message **in-process** with the committed dev key
 * (`EntitlementSignature.DEV_SEED`). Used by Control under `TENSHU_BILLING_MOCK` and by the web
 * apex (no commerce service stands behind `drive.tenshu.app` in v1). Insecure by design — the dev
 * key is public; a real deployment swaps in the off-device commerce minter.
 */
class DevSeedEntitlementMintClient(
    private val sign: (message: ByteArray) -> ByteArray,
    private val now: () -> Long,
    private val ttlMs: Long = THIRTY_DAYS_MS,
) : EntitlementMintClient {

    override suspend fun mint(
        ownerId: String,
        email: String?,
        productId: String,
    ): EntitlementSignature.SignedEntitlement {
        val validFrom = now()
        val validUntil = validFrom + ttlMs
        val message = EntitlementSignature.message(
            ownerId = ownerId,
            publisherId = EntitlementSignature.DEV_PUBLISHER_ID,
            productId = productId,
            validFrom = validFrom,
            validUntil = validUntil,
        )
        return EntitlementSignature.SignedEntitlement(
            ownerId = ownerId,
            publisherId = EntitlementSignature.DEV_PUBLISHER_ID,
            productId = productId,
            validFrom = validFrom,
            validUntil = validUntil,
            signature = sign(message),
        )
    }

    private companion object {
        /** v1 entitlement validity window — mirrors the prior in-process writer. */
        const val THIRTY_DAYS_MS: Long = 30L * 24 * 60 * 60 * 1000
    }
}
