package org.tenshu.commerce

import org.tenshu.core.common.entitlement.StorageTiers

/**
 * The commerce plane's view of the product catalogue — ids and their storage quotas
 * (`commerce-accounts-and-sessions.md` §2.1: Free 5 GiB / Basic 50 GiB / Premium 2 TiB).
 *
 * ⚠ This used to redeclare the constants, on the grounds that a JVM Ktor module must not depend on
 * the KMP `:storage-service-proxy` where the "canonical map" lived. The premise was right and the
 * conclusion wrong: `:core-common` is already on this module's classpath, so [StorageTiers] is
 * reachable with no new module edge — and a lockstep-by-comment arrangement between the *issuer*
 * of an entitlement and its *enforcer* is exactly the kind that drifts silently.
 *
 * Kept as a named object because commerce genuinely owns *which* products it sells; only the
 * byte figures moved.
 */
object ProductCatalog {
    const val FREE = StorageTiers.FREE
    const val BASIC = StorageTiers.BASIC
    const val PREMIUM = StorageTiers.PREMIUM

    /** Quota for [productId]; [fallback] for an unknown id (e.g. a redeem-only or future tier). */
    fun quotaBytes(productId: String, fallback: Long): Long =
        StorageTiers.quotaBytes(productId, fallback)
}
