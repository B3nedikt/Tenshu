package org.tenshu.sync.relay

import org.tenshu.core.common.security.AccountClosureClaims

/**
 * Turns a **verified** commerce account-closure record into the erasure of everything the relay holds
 * for that owner (`retention-quota-model.md` Face A / D4 — the trigger half of P3; since F12 the body
 * is [AccountErasure], not the content-only `reclaimOwner` it used to be).
 *
 * ⚠⚠ **This is the only thing in the system that can permanently destroy user content**, so every
 * decision it makes is a refusal until proven otherwise. Four independent brakes stand between a
 * request and a delete, and each one alone is sufficient to stop it:
 *
 *  1. **The flag.** [RelayConfig.accountClosureReclaim] defaults `false`. Production opened it on
 *     2026-10-24 by maintainer decision, ahead of ⚖️ counsel's review of the retention text (owed).
 *  2. **The signature.** The caller must present a record signed by the commerce issuer key the
 *     relay already trusts. Verification happens *before* this class is reached.
 *  3. **Freshness.** The record is short-lived; commerce stops re-issuing when an account reopens,
 *     so a stale record cannot authorise anything. Also enforced before this class.
 *  4. **Grace.** Measured from commerce's recorded closure time, never from delivery — a delayed or
 *     retried record cannot shorten the window.
 *
 * ⚠ P3's "unless an open account still holds the realm" is commerce's decision, made before a record
 * is minted (`closedAccountsWithOwners`). The relay erases exactly the `ownerId` it is handed.
 *
 * ⚠ Deliberately *not* idempotency-tracked: erasing an already-erased owner is a no-op that reports
 * zeros ([AccountErasure] is safe to repeat, and finishes a previous run's byte phase), so a retried
 * delivery needs no dedupe state. Adding one would be state the relay has to keep correct for no gain.
 */
class AccountClosureReclaimer(
    private val erasure: AccountErasure,
    private val config: RelayConfig,
) {

    /** What [reclaimIfDue] decided — every outcome is reportable, none is silent. */
    sealed interface Outcome {
        /** The flag is off (the default). Nothing was read or written. */
        data object Disabled : Outcome

        /** Valid closure, but still inside the grace window. Nothing was deleted. */
        data class WithinGrace(val remainingMs: Long) : Outcome

        /** Past grace: everything the relay held for the owner was erased (bar the `erased_realm` tombstone). */
        data class Reclaimed(val result: AccountErasure.Report) : Outcome
    }

    /**
     * Erase [claims]'s owner if the closure is past the configured grace window.
     *
     * [nowMs] is injected rather than read from the clock so the grace boundary is testable and so
     * a caller can pin one instant across a batch.
     */
    fun reclaimIfDue(claims: AccountClosureClaims, nowMs: Long): Outcome {
        if (!config.accountClosureReclaim) return Outcome.Disabled

        val elapsedMs = nowMs - claims.closedAtSec * 1_000
        // ⚠ `>=` not `>`: with a zero grace window (a deliberate operator choice, e.g. for an
        // erasure request) a closure must be actionable immediately rather than never.
        if (elapsedMs < config.accountClosureGraceMs) {
            return Outcome.WithinGrace(remainingMs = config.accountClosureGraceMs - elapsedMs)
        }
        return Outcome.Reclaimed(erasure.eraseOwner(claims.ownerId))
    }
}
