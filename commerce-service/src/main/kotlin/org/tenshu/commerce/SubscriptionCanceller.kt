package org.tenshu.commerce

import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import org.tenshu.commerce.play.GooglePlayClient
import org.tenshu.commerce.store.Account
import org.tenshu.commerce.store.CommerceStore
import org.tenshu.commerce.store.EntitlementSource
import org.tenshu.commerce.store.Rail

import org.tenshu.commerce.stripe.StripeClient

private val log = LoggerFactory.getLogger("SubscriptionCanceller")

/**
 * Stops a closed account from being billed (`commerce-end-to-end-dry-run.md` CE-5 follow-up).
 *
 * **Why this exists.** CE-5 made a closed account un-grantable: `/mint` and `/token` refuse it, and
 * the Stripe webhook refuses to entitle it. That is correct, and on its own it turns "closed" into
 * *"paying for nothing"* — the subscription keeps renewing against a service we now decline to
 * provide. Closing has to reach into the billing rail too.
 *
 * **Ordering is close-first, cancel-second, and the failure modes are asymmetric.** Cancelling
 * before closing means a crash in between leaves an *active* account whose subscription is gone:
 * the customer silently stops paying but keeps the service, and nothing would ever notice or retry.
 * Closing first means a crash leaves a closed account that is still billing — visible, wrong in the
 * expensive direction, and **self-healing**, because [sweepOnce] finds exactly that state. So the
 * durable intent lands first and the outward-facing call second.
 *
 * **No new state.** Cancellation is idempotent at the rail (an already-cancelled subscription is
 * simply not in the billing set), so the retry needs no "pending cancellation" flag, no queue and
 * no migration — re-running is free and reports 0.
 *
 * **The money question is settled and published**: `docs/policy/acceptable-use-and-limits.md` §5.2 —
 * cancelling stops future charges and does not refund the current period, and closing an account
 * cancels **immediately**, forfeiting the remainder. That is why this cancels outright rather than
 * at period end: a closed account cannot use the tier it would be keeping, so letting the
 * subscription run to term would only charge for something already switched off. ⚠ The forfeit is a
 * real user cost and the closure surface must say so *before* the action, not only in the terms.
 *
 * ⚠ Nothing here refunds. Refunds, where owed — notably an EU withdrawal request inside 14 days of a
 * new purchase, which policy §5.4 does **not** currently disapply — are issued by hand in the Stripe
 * dashboard.
 *
 * **Google Play the same way** (`commerce-store-link-out.md` D2): a Play subscription is cancelled
 * for good (no further renewals, nothing refunded — the Stripe rule above), with the same
 * close-first ordering and the same sweep. A renewal notification for a closed account books
 * nothing and cancels again (`GooglePlayRouting`). An Apple subscription cannot be stopped by us
 * at all (D6); that rail does not sell yet (P4).
 */
class SubscriptionCanceller(
    private val store: CommerceStore,
    private val stripe: StripeClient?,
    private val play: GooglePlayClient? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /**
     * Cancel everything still billing for [account]. Returns true when the rail confirmed (including
     * "there was nothing to cancel"), false when the call failed and a retry is owed.
     *
     * ⚠ Never throws: this runs immediately after a successful close, and an exception here must not
     * make the close look like it failed — the account **is** closed, and that is the fact the
     * operator asked for.
     */
    suspend fun cancelFor(account: Account): Boolean {
        // Both rails, each attempted even when the other fails: one outage must not keep the other
        // billing until the next sweep.
        val google = cancelGoogle(account)
        val stripeDone = cancelStripe(account)
        return google && stripeDone
    }

    /** The account's Google subscription, if it ever had one. True when nothing renews there. */
    private suspend fun cancelGoogle(account: Account): Boolean {
        val token = account.iapOriginalTxnId?.takeIf { it.isNotBlank() } ?: return true
        // The token field is shared with Apple's original transaction id (P4): only a token that
        // booked Google grants is Google's.
        val isGoogle = account.rail == Rail.IapGoogle ||
            store.listEntitlementHistory(account.id).any { it.source == EntitlementSource.IapGoogle }
        if (!isGoogle) return true
        val client = play ?: run {
            log.error("A closed account holds a Google subscription, but the Google rail is not configured")
            return false
        }
        val done = runCatching { client.cancelRenewals(token) }
            .onFailure { log.error("Google cancellation threw for a closed account", it) }
            .getOrDefault(false)
        if (!done) log.error("Could NOT cancel the Google subscription of a closed account — a sweep will retry")
        return done
    }

    private suspend fun cancelStripe(account: Account): Boolean {
        val customerId = account.stripeCustomerId?.takeIf { it.isNotBlank() } ?: return true
        val stripe = stripe ?: run {
            log.error("A closed account has a Stripe customer, but the Stripe rail is not configured")
            return false
        }
        val cancelled = runCatching { stripe.cancelActiveSubscriptions(customerId) }
            .onFailure { log.error("Subscription cancellation threw for closed account {}", shortId(account.id), it) }
            .getOrNull()

        return when {
            cancelled == null -> {
                // Loud: this is the state where a closed customer is still being charged. The sweep
                // will retry, but an operator should not have to infer that from silence.
                log.error(
                    "Could NOT cancel subscriptions for closed account {} — it may still be billed; a sweep will retry",
                    shortId(account.id),
                )
                false
            }
            cancelled > 0 -> {
                log.warn("Cancelled {} subscription(s) for closed account {}", cancelled, shortId(account.id))
                true
            }
            else -> true // Nothing was billing. The normal case on a re-run.
        }
    }

    /**
     * One pass over recently-closed accounts that still carry a Stripe customer. Returns how many
     * were reconciled and how many are still owed.
     *
     * [windowMs] bounds the set so the sweep does not grow forever as closed accounts accumulate:
     * cancellation normally succeeds at close time, and this is the tail that catches a Stripe
     * outage or a crash. An account still failing after the whole window is an operational problem
     * a retry loop cannot fix.
     */
    suspend fun sweepOnce(windowMs: Long): SweepReport {
        val closedSince = now() - windowMs
        val accounts = runCatching { store.closedAccountsWithBilling(closedSince) }
            .onFailure { log.error("Subscription-cancellation sweep could not read the store", it) }
            .getOrDefault(emptyList())

        var reconciled = 0
        var failed = 0
        for (account in accounts) {
            if (cancelFor(account)) reconciled++ else failed++
        }
        return SweepReport(examined = accounts.size, reconciled = reconciled, failed = failed)
    }

    /**
     * Run [sweepOnce] forever at [intervalMs].
     *
     * ⚠ No backoff and no dead-letter, matching the closure-delivery sweep: the store is the durable
     * truth, so a failed pass costs nothing and the next one simply tries again.
     */
    suspend fun runForever(
        intervalMs: Long,
        windowMs: Long,
        onSweep: (SweepReport) -> Unit = {},
    ): Nothing {
        while (true) {
            onSweep(sweepOnce(windowMs))
            delay(intervalMs)
        }
    }

    data class SweepReport(val examined: Int, val reconciled: Int, val failed: Int)
}
