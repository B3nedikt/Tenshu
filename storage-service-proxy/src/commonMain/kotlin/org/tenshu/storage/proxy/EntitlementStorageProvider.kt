package org.tenshu.storage.proxy

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.tenshu.core.common.entitlement.StorageTiers
import org.tenshu.identity.Entitlement

/**
 * Product catalogue (storage-entitlement-sync.md slice 3): maps a Publisher
 * product id to its storage quota. The generic `productId → quota` half — the
 * single source of truth shared by every reader. The commerce-side
 * `plan → productId` mapping stays with the writer in `:control-shared`
 * (it depends on `PublisherAccount.Plan`).
 *
 * Relocated out of `:control-shared` in web-storage-status.md Bucket 2 so both
 * Control and the web SDK (`:tenshu-sdk-js`) derive figures from the synced
 * `Entitlement` through the same code.
 */
object EntitlementProducts {
    const val FREE = StorageTiers.FREE
    const val BASIC = StorageTiers.BASIC
    const val PREMIUM = StorageTiers.PREMIUM

    const val FREE_LIMIT_BYTES: Long = StorageTiers.FREE_BYTES
    const val BASIC_LIMIT_BYTES: Long = StorageTiers.BASIC_BYTES
    const val PREMIUM_LIMIT_BYTES: Long = StorageTiers.PREMIUM_BYTES

    /**
     * ⚠ Falls back to `0`, unlike the relay's [StorageTiers.FREE_BYTES] floor. This is a *display*
     * surface: showing nothing for a tier we cannot interpret is honest, whereas inventing a Free
     * allowance would put a number on screen that no plane actually agreed to.
     */
    fun quotaBytes(productId: String): Long = StorageTiers.quotaBytes(productId, fallback = 0L)
}

/**
 * How far into the future a [Entitlement.validFrom] may sit and still count as valid.
 *
 * ⚠⚠ **Not a nicety — without it a purchase is guaranteed to derive as "no storage".** The record
 * is minted server-side and stamped with the *issuer's* clock, then written locally, and the save
 * fires the derivation immediately. So the client evaluates `now` against a `validFrom` produced
 * milliseconds earlier on a different machine: any positive issuer skew puts the record in the
 * future at exactly the moment it arrives. Observed 2026-08-08 — commerce ran 1.7 s ahead of the
 * desktop, and both paid-tier redemptions derived `hasStorage = false` on a perfectly good record.
 *
 * ⚠ Leeway is applied at the **start** bound only, never at [Entitlement.validUntil]: being early
 * is a clock artefact on a record that is about to be valid regardless, while being late is
 * expiry, and extending that would be granting storage nobody is entitled to. The client is not
 * the enforcement authority either way — the relay re-checks the window on every write — so
 * accepting a few seconds early cannot manufacture a grant, it only stops the UI lying about one.
 */
private const val VALID_FROM_SKEW_LEEWAY_MS = 60_000L

/**
 * Pure mapper: a synced [Entitlement] (or its absence) → [StorageFigures].
 *
 * `hasStorage` iff the entitlement's window has **opened** — `now >= validFrom` (carrying
 * [VALID_FROM_SKEW_LEEWAY_MS] of tolerance for issuer clock skew). Within the window the limit is
 * derived from its `productId`; **past `validUntil` it is the free floor**, matching the relay,
 * which grants an expired record Free rather than refusing it. [usedBytes]
 * is the relay-reported per-owner fill level (`retention-quota-model.md` §5 / D5, P1),
 * threaded in by [initEntitlementStorageReader] via a [org.tenshu.sync.proxy.RelayUsageClient]-shaped
 * seam; it defaults to `0L` (the historical mock, and the value shown when there is no
 * entitlement to fill against). A **not-yet-valid** entitlement confers no storage; an **expired**
 * one confers the free tier. Pure so the truth table is unit-tested without a proxy.
 */
fun entitlementToFigures(
    entitlement: Entitlement?,
    nowMs: Long,
    usedBytes: Long = 0L,
    // `entitlement-trust-anchor-distribution.md` P1b: the limit the RELAY reports for this owner,
    // when it reported one. Preferred over the record-derived tier because the relay is the
    // authority that actually enforces it — the record can be superseded (upgrade, closure,
    // revocation) without the device noticing, and then the two disagree with the relay right.
    // ⚠ `null` = the relay did not say (offline, no credential presented, or a credential it could
    // not verify) and the record's tier stands. P1c removes that fallback in favour of an explicit
    // "unknown"; until then this is deliberately a hybrid so today's UX does not regress.
    relayLimitBytes: Long? = null,
): StorageFigures {
    if (entitlement == null) {
        return StorageFigures(hasStorage = false, usedBytes = 0L, limitBytes = 0L)
    }
    // ⚠⚠ Started and expired are separate questions, because the relay answers them separately. A
    // record whose window never opened is not evidence of anything; an EXPIRED one floors to Free
    // there (`RelayEntitlementVerifier`), so reporting "no storage" for it made this the one place
    // still saying the owner may not write when the enforcement authority had already said they may.
    // The visible cost was Control → Services showing nothing at all for a lapsed free grant, and
    // `RealmStorageState.NoPlan` — documented as "shut: nothing pushes, from any app, ever".
    val started = nowMs >= entitlement.validFrom.get() - VALID_FROM_SKEW_LEEWAY_MS
    val expired = nowMs > entitlement.validUntil.get()
    return StorageFigures(
        hasStorage = started,
        // Only surface a fill level against a real limit — usage without one (limit 0) would render
        // a nonsensical "N / 0" gauge. An expired record HAS a limit now, so its usage shows.
        usedBytes = if (started) usedBytes else 0L,
        limitBytes = when {
            !started -> 0L
            // ⚠ Free, and deliberately NOT [relayLimitBytes] here. Past `validUntil` the relay grants
            // the free floor whatever the record's own tier said, so a remembered higher figure would
            // promise headroom the next write is refused for. The stale direction that matters is the
            // generous one.
            expired -> EntitlementProducts.FREE_LIMIT_BYTES
            else -> relayLimitBytes ?: EntitlementProducts.quotaBytes(entitlement.productId.get())
        },
    )
}

/**
 * Real [StorageProvider] (storage-entitlement-sync.md slice 3): derives figures
 * from the **synced** [Entitlement] rather than the device-local mock account, so
 * a 2nd device that pulls the owner's entitlement reflects the subscription.
 *
 * A passive holder — the proxy-side reader installed by [initEntitlementStorageReader]
 * pushes figures in via [update] whenever an entitlement is saved (locally written
 * by the mock Publisher, or arriving via sync-merge). `initStorageService` already
 * collects [observe], so the rest of the pipeline (StorageStatus row → IPC stream →
 * Drive banner / Tier-2 gate) is unchanged.
 */
class EntitlementStorageProvider : StorageProvider {

    private val figures = MutableStateFlow(
        StorageFigures(hasStorage = false, usedBytes = 0L, limitBytes = 0L),
    )

    override fun observe(): Flow<StorageFigures> = figures.asStateFlow()

    fun update(next: StorageFigures) {
        figures.value = next
    }
}
