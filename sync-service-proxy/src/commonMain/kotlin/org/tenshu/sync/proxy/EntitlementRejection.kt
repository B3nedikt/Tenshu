package org.tenshu.sync.proxy

import org.tenshu.core.TenshuException
import org.tenshu.core.messageOrNull

/**
 * Stable marker embedded in the [org.tenshu.core.GenericError] a relay write raises on an
 * HTTP `402 Payment Required` (entitlement enforcement, `second-device-entitlement-enforcement.md`).
 * Both the producer ([RestSyncRelay.pushBlock] / [RestContentBlobStore.putChunk]) and the consumer
 * ([SyncScheduler] via [isEntitlementRejection]) live in `:sync-service-proxy`, so this string
 * contract never crosses a module boundary — it lets the scheduler distinguish a *doomed*
 * entitlement rejection (back off) from a transient network failure (retry next tick) without a
 * new wire-registered error type.
 */
internal const val ENTITLEMENT_REJECTION_MARKER = "entitlement-required-402"

/**
 * True if [this] (or anything in its cause chain) is a relay `402` entitlement rejection — i.e.
 * carries [ENTITLEMENT_REJECTION_MARKER]. Robust to the throwable being either a raw
 * [TenshuException] wrapping the typed error or a re-wrapped exception whose `message` forwards it.
 */
internal fun Throwable.isEntitlementRejection(): Boolean {
    var t: Throwable? = this
    while (t != null) {
        if (t is TenshuException && t.error.messageOrNull()?.contains(ENTITLEMENT_REJECTION_MARKER) == true) {
            return true
        }
        if (t.message?.contains(ENTITLEMENT_REJECTION_MARKER) == true) return true
        t = t.cause
    }
    return false
}

/**
 * Stable marker embedded in the [org.tenshu.core.GenericError] a content `PUT` raises on an
 * HTTP `507 Insufficient Storage` — the relay's `over_quota` refusal
 * (`RelayRouting`'s `ContentRouting`, `commerce-billing-plane.md` §8 Phase 2b).
 *
 * ## Why this exists as a sibling of [ENTITLEMENT_REJECTION_MARKER] rather than folded into it
 *
 * Both are *doomed* writes — no amount of retrying at tick cadence fixes either — but they clear
 * by different events and say different things to the user. A `402` clears when this device
 * obtains a write credential; a `507` clears only when the **owner** frees bytes or raises their
 * tier, possibly from another device entirely. Sharing one backoff instance would let a cleared
 * credential also un-block a still-full realm.
 *
 * ⚠⚠ **A `507` was previously indistinguishable from a transient network failure.** Only `402` and
 * `401` were tagged, so an over-quota device retried its whole content journal every tick, forever.
 * Measured on a real phone 2026-09-06: **606 journalled emits, ~30 rejected `PUT`s/second sustained
 * for 80+ minutes**, Control pinned at 106% CPU, and the resulting 176,000 log lines evicted every
 * other Tenshu line from logcat — so the storm also destroyed the evidence for anything else wrong
 * with the device. This is the **third** instance of the retry-a-doomed-write storm documented on
 * [RejectionBackoff]; see its KDoc for the `402` and `401` cases.
 */
internal const val STORAGE_REJECTION_MARKER = "storage-full-507"

/**
 * True if [this] (or anything in its cause chain) is a relay `507` over-quota rejection — i.e.
 * carries [STORAGE_REJECTION_MARKER]. Same cause-chain walk as [isEntitlementRejection], for the
 * same reason: the throwable reaches the scheduler either raw or re-wrapped.
 */
internal fun Throwable.isStorageRejection(): Boolean {
    var t: Throwable? = this
    while (t != null) {
        if (t is TenshuException && t.error.messageOrNull()?.contains(STORAGE_REJECTION_MARKER) == true) {
            return true
        }
        if (t.message?.contains(STORAGE_REJECTION_MARKER) == true) return true
        t = t.cause
    }
    return false
}
