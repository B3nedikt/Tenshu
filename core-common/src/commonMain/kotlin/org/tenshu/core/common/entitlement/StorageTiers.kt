package org.tenshu.core.common.entitlement

/**
 * The **one** answer to "how many bytes does this tier grant".
 *
 * ⚠⚠ This existed in three hand-maintained copies until 2026-08-09 — `EntitlementProducts`
 * (`:storage-service-proxy`, which called itself "the canonical map"), `ProductCatalog`
 * (`:commerce-service`), and `RelayEntitlementVerifier.quotaForProduct` (the relay) — each with
 * its own comment instructing the reader to keep it in lockstep with the others. They agreed only
 * because someone kept making them agree, and the planes they serve are the *issuer*, the
 * *enforcer* and the *display*: a drift would have meant a user metered at one number, billed for
 * a second and shown a third.
 *
 * ⚠ The stated reason for duplicating was that a JVM Ktor module must not depend on the KMP
 * storage module. True, and irrelevant to where the constants actually belong: all three already
 * depend on `:core-common`, so the shared home needed no new module edge at all.
 *
 * ⚠⚠ This table is **not** the only way a quota is decided, and unifying the two carriers on it
 * would be a regression. A signed `Entitlement` record can only name a `productId`, so the relay
 * maps it to bytes through here. A bearer **token** carries an explicit number instead, precisely
 * so commerce can grant an amount this table does not describe — `TokenRouting` mints an unknown
 * tier at the configured top-tier quota, which is what makes a redeem-only, promotional or bespoke
 * grant possible at all. Resolving a token's bytes from its `tier` would floor every one of those
 * to [FREE_BYTES].
 *
 * ⭐ So the guarantee this object provides is **agreement, not centralisation**: for a catalogue
 * tier, the number commerce mints into a token and the number the relay looks up for a record are
 * now the same by construction rather than by comment.
 */
object StorageTiers {
    const val FREE = "tenshu.drive.free"
    const val BASIC = "tenshu.drive.basic"
    const val PREMIUM = "tenshu.drive.premium"

    const val FREE_BYTES: Long = 5L * 1024 * 1024 * 1024 // 5 GiB
    const val BASIC_BYTES: Long = 50L * 1024 * 1024 * 1024 // 50 GiB
    const val PREMIUM_BYTES: Long = 2L * 1024 * 1024 * 1024 * 1024 // 2 TiB

    /**
     * Bytes granted by [productId], or [fallback] for an id this build does not recognise.
     *
     * ⚠ Callers choose the fallback because the right answer differs by plane. The relay passes
     * [FREE_BYTES]: a validly-signed credential naming a tier a not-yet-updated relay has never
     * heard of must be neither unlimited (a bypass) nor zero (a `507` wall for someone who paid).
     * A display surface may prefer `0` so it shows nothing rather than inventing a figure.
     */
    fun quotaBytes(productId: String, fallback: Long): Long = when (productId) {
        FREE -> FREE_BYTES
        BASIC -> BASIC_BYTES
        PREMIUM -> PREMIUM_BYTES
        else -> fallback
    }
}
