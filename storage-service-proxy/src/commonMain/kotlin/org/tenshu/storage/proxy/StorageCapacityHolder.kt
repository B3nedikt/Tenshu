package org.tenshu.storage.proxy

import org.tenshu.core.TenshuException
import org.tenshu.core.common.proxy.ExecutionContext
import org.tenshu.storage.InsufficientStorageError
import org.tenshu.storage.NoStorageError

/**
 * Proxy-side latch carrying the latest [StorageFigures], populated by
 * [initStorageService] on every [StorageProvider] emission. Mirrors
 * `SyncBlockEmitterHolder`: a plain mutable slot read by the storage-consuming
 * op impls for Tier-2 enforcement (`docs/proposals/storage-capacity-gating.md`
 * §2). Single proxy thread; no synchronisation needed.
 */
class StorageCapacityHolder {
    var figures: StorageFigures? = null

    /**
     * The **relay** has refused a content write with `507` (over quota) — set from the sync
     * scheduler's storage backoff, cleared when a write next succeeds.
     *
     * ⚠⚠ Deliberately *not* a [StorageFigures] field: those come from the `StorageProvider` (the
     * entitlement and the relay's usage endpoint, re-fetched on a 60 s backstop). This
     * is the relay's answer to the one question the figures cannot always get right — *does the
     * next write fit* — and it exists because the arithmetic has two blind spots:
     *
     *  - a realm with a sliver of room left (**836 bytes** free on a 5 GiB tier, say) satisfies
     *    `used + required <= limit` for a small file while the relay refuses every 2 MiB chunk, and
     *  - bytes spent by **another of the owner's devices** are invisible until the next pull, so a
     *    device can be confidently wrong about how much room it has.
     *
     * [ensureWithinQuota] reads it so a manual upload is refused *at the moment the user asks for
     * it*, with the typed error that renders a real dialog, rather than being accepted, journalled
     * and then silently held.
     */
    var relayRejected: Boolean = false

    /**
     * Plaintext bytes already **journalled for upload but not yet on the relay** — the content
     * journal's own figure (`SyncObserver.deferredContentFigures().bytes`), pushed here each
     * scheduler tick.
     *
     * ⚠⚠ Why the pre-flight is wrong without it: [StorageFigures.usedBytes] is what the relay
     * *holds*, so a queue waiting to go up is invisible to it. With 40 MB free and 31.8 MB already
     * queued, `used + required` waves a new 30 MB file straight through — it is accepted,
     * journalled, and then never lands, because the realm cannot hold the queue it is joining. The
     * honest question is `used + queued + required`.
     *
     * ⚠ Refreshed per tick, so it lags a burst of uploads. That under-counts, which is the safe
     * direction — the same optimistic-on-unknown posture the rest of this gate takes: allow now and
     * refuse later beats refusing an upload that would have fit.
     *
     * ⚠ Plaintext sizes, while the relay meters ciphertext (~28 bytes of AEAD overhead per 2 MiB
     * chunk). A projection, not an audit, and the error is again on the permissive side.
     */
    var queuedContentBytes: Long = 0L
}

/**
 * Tier-2 authoritative check for actions that **inherently require the relay**
 * (sharing — Mode-A links, Mode-B folder shares): throws a typed
 * [TError][org.tenshu.core.TError] when the action can't proceed. No subscription
 * ⇒ [NoStorageError]; over quota ⇒ [InsufficientStorageError]. A null holder or
 * as-yet-unknown figures is a **no-op** (test harnesses / pre-derivation) — Tier-2
 * enforces only once the figures are known, matching Tier-1's optimistic-on-unknown.
 * The limit is hard (`used + required > limit`), per the §7 decision.
 *
 * Upload does **not** use this — see [ensureWithinQuota] (local-first reframe,
 * ADR 0019): a file is stored locally and synced opportunistically, so a missing
 * subscription must not block it.
 */
fun StorageCapacityHolder?.ensureCapacity(
    executionContext: ExecutionContext,
    requiredBytes: Long,
) {
    val figures = this?.figures ?: return
    if (!figures.hasStorage) {
        throw TenshuException(NoStorageError.create(executionContext.context))
    }
    if (figures.usedBytes + requiredBytes > figures.limitBytes) {
        throw TenshuException(
            InsufficientStorageError.create(
                context = executionContext.context,
                requiredBytes = requiredBytes,
                availableBytes = (figures.limitBytes - figures.usedBytes).coerceAtLeast(0L),
            ),
        )
    }
}

/**
 * Local-first Tier-2 check for **upload** (ADR 0019, local-first reframe). Upload
 * is a *local* operation — the bytes are written to the device's DB and synced to
 * the relay opportunistically — so **no subscription is not a failure**: with
 * `hasStorage == false` (or unknown figures) this is a no-op and the upload
 * proceeds local-only (the personal-realm sync gate keeps its block queued until a
 * subscription binds). Only a *subscribed* user who would exceed their paid quota
 * is blocked, with [InsufficientStorageError] — that ceiling is real because the
 * relay won't accept the bytes.
 *
 * Contrast [ensureCapacity], which additionally hard-blocks no-subscription with
 * [NoStorageError] for actions (sharing) that can't work without the relay at all.
 */
fun StorageCapacityHolder?.ensureWithinQuota(
    executionContext: ExecutionContext,
    requiredBytes: Long,
    // Queued bytes this write replaces rather than adds to: an overwrite of a file whose previous
    // version is still journalled, where the journal keeps only the newer intent. ⚠ Clamped to the
    // queue figure, which is refreshed per tick and may not include that entry yet.
    releasedQueuedBytes: Long = 0L,
) {
    val figures = this?.figures ?: return
    if (!figures.hasStorage) return // local-first: store now, sync when subscribed

    // ⚠⚠ The relay's own refusal, checked alongside the arithmetic below and not instead of it:
    // it catches the two cases the figures get wrong (a sliver too small for a chunk; another
    // device having spent the room since the last pull), while the arithmetic still catches an
    // over-large file on a realm the relay has not been asked about yet. See
    // [StorageCapacityHolder.relayRejected].
    //
    // ⚠⚠ The arithmetic counts [StorageCapacityHolder.queuedContentBytes] as spent. Those bytes are
    // not in `usedBytes` — the relay does not hold them yet — so without them a file is measured
    // against room the queue in front of it has already claimed, and joins a queue that can never
    // drain.
    val queued = (this.queuedContentBytes - releasedQueuedBytes.coerceAtLeast(0L)).coerceAtLeast(0L)
    val available = availableAfterQueue(figures, queued)
    if (this.relayRejected || requiredBytes > available) {
        throw TenshuException(
            InsufficientStorageError.create(
                context = executionContext.context,
                requiredBytes = requiredBytes,
                // ⚠ Net of the queue here too: reporting raw headroom would tell the user they have
                // room for the file this very call refused.
                availableBytes = available,
            ),
        )
    }
}

/**
 * Room for a *new* upload: the limit, less what the relay holds, less what is already queued to
 * reach it. Never negative.
 *
 * ⚠ Shared by [ensureWithinQuota] and the Tier-3 `CheckCapacity` op so the single-file refusal and
 * the folder-upload pre-flight cannot answer the same question differently.
 */
internal fun availableAfterQueue(figures: StorageFigures, queuedBytes: Long): Long =
    (figures.limitBytes - figures.usedBytes - queuedBytes).coerceAtLeast(0L)
