package app.tenshu.control.ui.screens.services

import app.tenshu.control.data.PublisherAccount

/**
 * A flat, bridge-friendly projection of the Services status for surfaces that can't
 * consume the Compose screen — first consumer is Control-on-iOS's read-only
 * `ServicesView` (`docs/proposals/ios-services-status-and-purchase.md` S1). Scalars
 * only: SKIE renders this cleanly to Swift, and the keys keep the sealed/enum types
 * out of the ObjC surface (the same flattening discipline as `syncDevices(state:)`).
 *
 * Read-only by design — S1 must not grow purchase affordances (App Store rules gate
 * those; proposal §3).
 */
data class ServicesStatusSnapshot(
    /**
     * "none" | "free" | "basic" | "premium" — see [servicesStatusSnapshot].
     * "none" is the unfunded floor: no entitlement has been read yet, which is NOT the
     * same as the signed-up Free tier. It also covers the moment just after signing in,
     * before the entitlement syncs back.
     *
     * ⚠ "rei" was removed in CE-4: it was a redeemed account's *identity*, never a plan,
     * and the tier is now read from the entitlement's quota like every other. A redeemed
     * account therefore reports the tier it actually holds.
     */
    val planKey: String,
    /**
     * Where the plan came from: "localAccount" (this device holds the commerce session),
     * "syncedRealm" (no local account; storage active via the realm's synced entitlement —
     * the 2nd-device case), or "none" (no account, no funded storage).
     */
    val sourceKey: String,
    val usedBytes: Long,
    val limitBytes: Long,
    /** The signed-in account email, when the local session identifies via email. */
    val accountEmail: String?,
) {
    companion object {
        val EMPTY = ServicesStatusSnapshot(
            planKey = "none",
            sourceKey = "none",
            usedBytes = 0,
            limitBytes = 0,
            accountEmail = null,
        )
    }
}

/**
 * Derives the snapshot from the same two signals `ServicesViewModel` binds: the
 * device-local commerce [account] and the locally-derived [status] row.
 *
 * ⚠⚠ **Figures come from [status] on both paths.** This function used to read
 * `account.storageUsedBytes` / `storageLimitBytes` when a local account existed — the same
 * hardcoded literals that made the Compose card wrong (`commerce-end-to-end-dry-run.md`
 * §1.1 / D5), which means Control-on-iOS's read-only `ServicesView` carried the identical
 * bug and is fixed by the same change.
 *
 * [sourceKey] still distinguishes a local account from a synced-only device, because that
 * distinction is real and is about **actions** (D11) — it is simply not about the number.
 *
 * The plan tier is limit-derived via [planTierForLimit]: the synced entitlement carries no
 * explicit tier, so the quota is the truth. ⚠ A redeemed account therefore reports its real
 * tier (`basic`) rather than `rei` — "Rei" was never a plan, it is how the account was
 * identified, and [accountEmail] / the identity row is where that belongs.
 */
fun servicesStatusSnapshot(
    account: PublisherAccount?,
    status: StorageStatusModel?,
): ServicesStatusSnapshot {
    val storage = fundedStorage(status)
    if (storage == null && account == null) return ServicesStatusSnapshot.EMPTY
    return ServicesStatusSnapshot(
        planKey = storage?.plan?.asKey() ?: "none",
        sourceKey = if (account != null) "localAccount" else "syncedRealm",
        usedBytes = storage?.usedBytes ?: 0L,
        limitBytes = storage?.limitBytes ?: 0L,
        accountEmail =
            (account?.identityMethod as? PublisherAccount.IdentityMethod.Email)?.address,
    )
}

private fun SyncedPlanTier.asKey(): String = when (this) {
    SyncedPlanTier.Free -> "free"
    SyncedPlanTier.Basic -> "basic"
    SyncedPlanTier.Premium -> "premium"
}
