package org.tenshu.storage.proxy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import org.tenshu.identity.EntitlementRecordCredential
import org.tenshu.identity.EntitlementSignature

/**
 * Holds the owner's currently-synced signed [EntitlementSignature.SignedEntitlement] record and
 * hands out the `X-Entitlement-Proof` **entitlement proof** (`signed-record.v1`) to present when no
 * bearer token is available (Track B3). Fed by the entitlement-storage reader (any local write /
 * sync-merge of the `Entitlement` row), read on the relay-write path.
 *
 * ⚠ **Lives in `:storage-service-proxy`, beside [initEntitlementStorageReader], deliberately.**
 * The reader is what feeds this provider (its `recordSink`), so the two halves of the
 * record→credential handoff share a module — and, unlike its previous home in
 * `:control-shared-proxy`, this one is reachable from the system-test harness. That
 * unreachability is why the presentation path had no end-to-end coverage and why an
 * entitlement the relay refused shipped to a device (`docs/tech-debt.md` → "Entitlement
 * enforcement is untested end-to-end").
 *
 * [proofHeader] gates on the record's **start** bound only. It used to gate the full
 * `[validFrom, validUntil]` window on the reasoning that "a doomed write is a 402, no point sending
 * it" — which was circular: the write was doomed *because* the record was withheld. Withholding also
 * made an expiry indistinguishable from having no record at all, since both produce the relay's
 * `entitlement_required` ("the client attached nothing"). The relay now floors an expired record to
 * the free tier, so presenting it is what turns a lapse into a downgrade instead of write-death.
 * Backed by a [MutableStateFlow] so [update] (proxy observer thread) and [proofHeader] (write path)
 * are safely concurrent without a lock.
 */
class EntitlementRecordCredentialProvider {

    private val current = MutableStateFlow<EntitlementSignature.SignedEntitlement?>(null)

    /**
     * Fired when a record becomes **usable when it was not** — the "credential acquired" edge. Wired
     * to `SyncScheduler.notifyEntitlementCredentialChanged()` so a session-less device re-drives its
     * stranded 402-backed-off writes promptly instead of waiting a backoff window out. Set once,
     * post-`initSyncService`, in the platform init hook.
     *
     * ⚠ Renamed from `onRecordFirstAvailable`: it now also covers expired→renewed, and a name saying
     * "first available" would have quietly mis-described the case that matters most (see [update]).
     */
    var onRecordUsable: (() -> Unit)? = null

    /**
     * The synced record changed (or cleared). Edge-triggers [onRecordUsable] when a record arrives
     * that is *better than what we had* — absent→present, or a **renewal** (a strictly later
     * `validUntil`).
     *
     * ⚠ The renewal half is not cosmetic. This used to fire on `previous == null` alone, so a device
     * holding an **expired** record — a non-null `current` — got no edge when the renewal finally
     * synced in, and its `402` backoff was left to time out on its own (up to 2 min) despite the fix
     * being in hand. That is precisely the state the 2026-08-04 incident left a device in
     * (`sync-device-catch-up.md` §1), so the recovery fetch would have been half-effective without
     * this: it can land the row, but only this makes the scheduler act on it.
     *
     * Comparing `validUntil` rather than firing on every save keeps it edge-triggered — an
     * idempotent re-save of the same record does not re-notify.
     */
    fun update(record: EntitlementSignature.SignedEntitlement?) {
        val previous = current.getAndUpdate { record }
        if (record != null && (previous == null || previous.validUntil < record.validUntil)) {
            onRecordUsable?.invoke()
        }
    }

    /**
     * The product id of the currently-synced record, or `null` when there is none.
     *
     * ⚠ Deliberately **not** window-gated at all — [proofHeader] now gates only the *start* bound. The one consumer asks "has the
     * tier this owner is entitled to changed since my bearer token was minted?", and an
     * out-of-window record still answers that: a record that is merely stale is precisely the
     * evidence that something moved. Gating it would make an expiring record look like *no* tier
     * change and suppress the refresh exactly when it is most likely warranted.
     */
    val currentTier: String? get() = current.value?.productId

    /**
     * The `signed-record.v1` `X-Entitlement-Proof` value to present when no bearer token is available,
     * or `null` when there is no record or its window has not opened yet at [nowMs].
     *
     * ⚠⚠ **An expired record is still presented.** Withholding it was silently equivalent to holding
     * no entitlement at all: the relay answers `402 entitlement_required` — whose message is
     * *"the client attached nothing"* — so an expiry was indistinguishable in the logs from a device
     * that never had a record, and the owner went write-dead with metadata still syncing. The relay
     * floors an expired record to the free tier rather than rejecting it, so presenting it is what
     * turns a lapse into a downgrade. Sending it can only ever *gain* authority; deciding the tier is
     * the relay's job, not ours.
     *
     * ⚠ A **not-yet-valid** record is still withheld — its window has not opened, and unlike expiry
     * that is not a state any owner ends up in by simply waiting.
     */
    fun proofHeader(nowMs: Long): String? {
        val record = current.value ?: return null
        if (nowMs < record.validFrom) return null
        return EntitlementRecordCredential.encode(record)
    }
}
